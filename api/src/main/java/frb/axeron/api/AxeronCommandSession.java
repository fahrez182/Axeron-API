package frb.axeron.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import frb.axeron.shell.Axsh;
import frb.axeron.server.Environment;

/**
 * Runs one command at a time like a terminal does. A command is done when its shell exits, even
 * if it left jobs running in the background ({@code cmd &}): those keep their output coming in,
 * and keep running while later commands run, unless background processing is restricted.
 */
public class AxeronCommandSession {
    private static final Pattern PID_LINE = Pattern.compile("(?m)^[ \\t]*(\\d+)[ \\t]*(?:\\r?\\n|$)");
    /** Written to stderr by the command shell's EXIT trap, followed by its {@code $!}. */
    private static final String BG_MARK = "__axeron_bg__=";
    private static final Pattern BG_LINE = Pattern.compile("(?m)^" + BG_MARK + "(\\d*)[ \\t]*(?:\\r?\\n|$)");
    /** How long to wait for the EXIT trap's line once the shell is gone. */
    private static final long BG_MARK_WAIT_MS = 500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String injectEnv;
    private String injectExport;
    private ResultListener resultListener;
    private ProcessListener processListener;
    private volatile boolean restrictBackground = true;
    /** The latest command; earlier ones may still have background jobs reading out. */
    private Run current;

    public static String[] getQuickCmd(
            String cmd,
            boolean useBusybox,
            boolean withPid
    ) {
        return quickCmd(cmd, useBusybox, withPid, true);
    }

    /**
     * The session's own command: no {@code setsid}, as the axsh host already starts the shell in
     * its own session. A second setsid would fork and exit at once, leaving the server waiting on
     * it instead of the command: its pid not the command's, its exit code always 0.
     * The EXIT trap reports the last background job's pid ({@code $!}); it goes before the command
     * on the same line, so line numbers in the shell's errors stay the user's.
     */
    private static String[] sessionCmd(String cmd, boolean useBusybox) {
        String traced = "trap 'echo \"" + BG_MARK + "$!\" 1>&2' EXIT; " + cmd;
        return quickCmd(traced, useBusybox, true, false);
    }

    private static String[] quickCmd(
            String cmd,
            boolean useBusybox,
            boolean withPid,
            boolean setsid
    ) {
        String execCmd;

        if (withPid) {
            execCmd =
                    "export PARENT_PID=$$; " +
                            "echo $PARENT_PID 1>&2; " +
                            "exec -a \"QuickShell\" /system/bin/sh -c \"$0\"";
        } else {
            execCmd =
                    "exec -a \"QuickShell\" /system/bin/sh -c \"$0\"";
        }

        List<String> argv = new ArrayList<>();
        if (useBusybox) {
            argv.add(AxeronPluginService.INSTANCE.getBUSYBOX());
            if (setsid) argv.add("setsid");
            argv.add("sh");
        } else {
            if (setsid) argv.add("setsid");
            argv.add("/system/bin/sh");
        }
        argv.add("-c");
        argv.add(execCmd);
        argv.add(cmd);
        return argv.toArray(new String[0]);
    }

    public String getEnv() {
        return injectEnv;
    }

    public void setEnv(String env) {
        injectEnv = env;
    }

    public String getExport() {
        return injectExport;
    }

    public void setExport(String export) {
        this.injectExport = export;
    }

    public void setResultListener(ResultListener resultListener) {
        this.resultListener = resultListener;
    }

    public void setProcessListener(ProcessListener processListener) {
        this.processListener = processListener;
    }

    /**
     * True: what a command leaves running in the background is killed when its shell exits.
     * False: it keeps running, its output still shown, and its pid is reported.
     */
    public void setRestrictBackground(boolean restrict) {
        restrictBackground = restrict;
    }

    /** Starts [input] as a new command, or writes it to the running one's stdin. */
    public synchronized void runCommand(String input, boolean isCompatModeEnabled) {
        Run run = current;
        if (run != null && run.isRunning()) {
            Log.d("CmdOut", "write: " + input);
            try {
                run.write(input);
            } catch (IOException e) {
                errorListener("command: " + e.getMessage());
            }
        } else {
            Log.d("CmdOut", "newProcess: " + input);
            startNewProcess(input, isCompatModeEnabled);
        }
    }

    /** Stops the running command: TERM to its whole session, background jobs included. */
    public synchronized void killSession() {
        Run run = current;
        if (run != null && run.isRunning()) killGroup(run.pid);
    }

    /** As {@link #killSession()}. */
    public void destroy() {
        killSession();
    }

    private void startNewProcess(String command, boolean isCompatModeEnabled) {
        Environment env = Axeron.getEnvironment();
        Process process;
        try {
            process = Axsh.exec(
                    sessionCmd(command, isCompatModeEnabled),
                    env != null ? env.getEnv() : null,
                    null);
        } catch (Exception e) {
            errorListener("Failed to start process: " + e.getMessage());
            return;
        }
        current = new Run(command, process);
        current.start();
    }

    /** TERM to the process group [pgid] (a command's session: the axsh host's setsid). */
    private static void killGroup(int pgid) {
        // Never -1: that would be every process this uid may signal.
        if (pgid <= 1) return;
        new Thread(() -> {
            try {
                Process kill = Axsh.exec(new String[]{"kill", "-TERM", "-" + pgid});
                kill.getOutputStream().close();
                kill.waitFor();
                kill.getInputStream().close();
                kill.getErrorStream().close();
            } catch (Exception e) {
                Log.w("CmdOut", "kill " + pgid + ": " + e.getMessage());
            }
        }, "SessionKill").start();
    }

    private void errorListener(String error) {
        if (resultListener != null)
            mainHandler.post(() -> resultListener.onError(error));
    }

    /**
     * One command: its shell and its output. The readers live as long as anything holds the
     * output open, background jobs included, so a later command never cuts them off.
     */
    private final class Run {
        final String command;
        final Process process;
        final BufferedWriter writer;
        final BufferedReader stdout;
        final BufferedReader stderr;
        /** Guarded by this Run: stdout read before stderr delivered the pid. */
        final StringBuilder early = new StringBuilder();
        final AtomicReference<String> lastOutput = new AtomicReference<>("");
        /** Counted down by the EXIT trap's line, or stderr's end when there is none. */
        final CountDownLatch bgMarkSeen = new CountDownLatch(1);
        volatile int pid = -1;
        volatile int backgroundPid = -1;
        /** The pid line was seen and onProcessCreated posted. Guarded by this Run. */
        boolean started;
        /** The shell exited: no more input, finished reported. */
        volatile boolean shellDone;

        Run(String command, Process process) {
            this.command = command;
            this.process = process;
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        }

        boolean isRunning() {
            return !shellDone;
        }

        void write(String input) throws IOException {
            if (processListener != null) processListener.onProcessRunning(input);
            writer.write(input);
            writer.newLine();
            writer.flush();
            Log.d("CommandSession", "Input dikirim: " + input);
        }

        void start() {
            Thread out = new Thread(this::readStdout, "SessionOutThread");
            Thread err = new Thread(this::readStderr, "SessionErrThread");
            out.start();
            err.start();
            new Thread(() -> awaitShell(out, err), "SessionWaitThread").start();
        }

        private void readStdout() {
            try {
                char[] buffer = new char[1024 * 8];
                int read;
                while ((read = stdout.read(buffer)) != -1) {
                    String part = new String(buffer, 0, read);
                    synchronized (this) {
                        // Held until onProcessCreated, so it isn't lost and lands after it.
                        if (!started) {
                            early.append(part);
                            continue;
                        }
                        output(part);
                    }
                }
            } catch (IOException e) {
                errorListener("stdout: " + e.getMessage());
            } finally {
                close(stdout);
            }
        }

        private void readStderr() {
            try {
                char[] buffer = new char[1024 * 8];
                int read;
                while ((read = stderr.read(buffer)) != -1) {
                    String part = new String(buffer, 0, read);
                    Log.d("CmdOut", "error: " + part);

                    synchronized (this) {
                        // The wrapper echoes its pid to stderr first; cut exactly that line and
                        // keep everything else byte-for-byte (newlines included).
                        if (!started) {
                            Matcher m = PID_LINE.matcher(part);
                            if (!m.find()) continue;
                            pid = Integer.parseInt(m.group(1));
                            Log.d("CmdOut", "pid: " + pid);
                            part = part.substring(0, m.start()) + part.substring(m.end());
                            started = true;
                            if (processListener != null) {
                                final int p = pid;
                                mainHandler.post(() -> processListener.onProcessCreated(p, command));
                            }
                            if (early.length() > 0) {
                                output(early.toString());
                                early.setLength(0);
                            }
                        }
                    }

                    Matcher bg = BG_LINE.matcher(part);
                    if (bg.find()) {
                        if (!bg.group(1).isEmpty()) backgroundPid = Integer.parseInt(bg.group(1));
                        part = part.substring(0, bg.start()) + part.substring(bg.end());
                        bgMarkSeen.countDown();
                    }
                    if (!part.isEmpty() && resultListener != null) {
                        final String error = part;
                        mainHandler.post(() -> resultListener.onError(error));
                    }
                }
            } catch (IOException e) {
                errorListener("stderr: " + e.getMessage());
            } finally {
                bgMarkSeen.countDown();
                close(stderr);
            }
        }

        private void awaitShell(Thread out, Thread err) {
            int code;
            try {
                // The command's shell: its exit code is the command's.
                code = process.waitFor();
                bgMarkSeen.await(BG_MARK_WAIT_MS, TimeUnit.MILLISECONDS);
                out.join(BG_MARK_WAIT_MS);
            } catch (InterruptedException e) {
                code = -1;
            }
            Log.d("CommandSession", "Process selesai, exitCode = " + code);

            shellDone = true;
            close(writer);

            // Output still held open with the shell gone: a background job is still running.
            boolean leftRunning = out.isAlive() || err.isAlive();
            if (leftRunning && restrictBackground) {
                killGroup(pid);
            } else if (leftRunning && backgroundPid > 0 && processListener != null) {
                final int[] pids = {backgroundPid};
                mainHandler.post(() -> processListener.onProcessBackground(pids));
            }

            final boolean wasStarted;
            synchronized (this) {
                wasStarted = started;
                // Never got a pid line: still show what it printed, and whatever comes later.
                started = true;
                if (early.length() > 0) {
                    output(early.toString());
                    early.setLength(0);
                }
            }
            if (wasStarted && processListener != null) {
                final int exit = code;
                final String last = lastOutput.get();
                mainHandler.post(() -> processListener.onProcessFinished(exit, last));
            }
        }

        private void output(String part) {
            if (resultListener == null) return;
            lastOutput.set(part);
            mainHandler.post(() -> resultListener.output(part));
        }
    }

    private static void close(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    public interface ResultListener {
        void output(CharSequence output);

        void onError(CharSequence error);
    }

    public interface ProcessListener {
        void onProcessCreated(int pid, @NonNull String command);

        void onProcessRunning(@NonNull String input);

        void onProcessFinished(int exitCode, @NonNull String lastOutput);

        /** The command's shell is done but left these processes running in the background. */
        default void onProcessBackground(@NonNull int[] pids) {
        }
    }

}
