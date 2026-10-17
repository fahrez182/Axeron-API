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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import frb.axeron.shell.Axsh;
import frb.axeron.server.Environment;

public class AxeronCommandSession {
    private static final Pattern PID_LINE = Pattern.compile("(?m)^[ \\t]*(\\d+)[ \\t]*(?:\\r?\\n|$)");
    private final Object startLock = new Object();
    private final StringBuilder earlyOutput = new StringBuilder();
    private final AtomicBoolean isProcessRunning = new AtomicBoolean(false);
    private final AtomicInteger pid = new AtomicInteger(-1);
    private final AtomicInteger exitCode = new AtomicInteger(-1);
    Handler mainHandler = new Handler(Looper.getMainLooper());
    Handler outputHandler = new Handler(Looper.getMainLooper());
    Thread outThread, errThread, waitThread;
    Handler finishHandler = new Handler(Looper.getMainLooper());
    private String injectEnv;
    private String injectExport;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader bufferedReader;
    private BufferedReader bufferedError;
    private final AtomicReference<String> lastOutput = new AtomicReference<>("");
    private ResultListener resultListener;
    private ProcessListener processListener;

    public static String[] getQuickCmd(
            String cmd,
            boolean useBusybox,
            boolean withPid
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

        if (useBusybox) {
            return new String[]{
                    AxeronPluginService.INSTANCE.getBUSYBOX(),
                    "setsid",
                    "sh",
                    "-c",
                    execCmd,
                    cmd
            };
        } else {
            return new String[]{
                    "setsid",
                    "/system/bin/sh",
                    "-c",
                    execCmd,
                    cmd
            };
        }
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

    public synchronized void runCommand(String input, boolean isCompatModeEnabled) {
        try {
            if (isProcessRunning.get()) {
                Log.d("CmdOut", "write: " + input);
                writeToProcess(input);
            } else {
                Log.d("CmdOut", "newProcess: " + input);
                startNewProcess(input, isCompatModeEnabled);
            }
        } catch (IOException e) {
            errorListener("command: " + e.getMessage());
        }
    }

    public void killSession() {
        // The command's own session (getQuickCmd's setsid), which destroy() alone wouldn't reach.
        final int target = pid.get();
        new Thread(() -> {
            try {
                Process kill = Axsh.exec(new String[]{"kill", "-TERM", "-" + target});
                kill.getOutputStream().close();
                kill.waitFor();
                kill.getInputStream().close();
                kill.getErrorStream().close();
            } catch (Exception e) {
                Log.w("CmdOut", "kill " + target + ": " + e.getMessage());
            }
        }, "SessionKill").start();
        destroy();
    }

    private void startNewProcess(String command, boolean isCompatModeEnabled) {
        destroy(); // bersihkan jika ada
        exitCode.set(0);

        Environment env = Axeron.getEnvironment();
        try {
            process = Axsh.exec(
                    getQuickCmd(command, isCompatModeEnabled, true),
                    env != null ? env.getEnv() : null,
                    null);
        } catch (Exception e) {
            process = null;
            errorListener("Failed to start process: " + e.getMessage());
            return;
        }
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        bufferedError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        synchronized (startLock) {
            earlyOutput.setLength(0);
        }

        outThread = new Thread(() -> {
            try {
                char[] buffer = new char[1024 * 8];
                int bytesRead;

                while ((bytesRead = bufferedReader.read(buffer)) != -1) {
                    String part = new String(buffer, 0, bytesRead);

                    synchronized (startLock) {
                        // stdout can be read before stderr delivered the pid; hold it so it
                        // isn't lost and still lands after onProcessCreated.
                        if (!isProcessRunning.get()) {
                            earlyOutput.append(part);
                            continue;
                        }
                        if (resultListener != null) {
                            lastOutput.set(part);
                            outputHandler.post(() -> resultListener.output(part));
                        }
                    }
                }
            } catch (IOException | NullPointerException e) {
                errorListener("stdout: " + e.getMessage());
            }
        }, "SessionOutThread");

        errThread = new Thread(() -> {
            try {
                char[] buffer = new char[1024 * 8];
                int bytesRead;

                while ((bytesRead = bufferedError.read(buffer)) != -1) {
                    String finalLine = new String(buffer, 0, bytesRead);
                    Log.d("CmdOut", "error: " + finalLine);
                    Log.d("CmdOut", "isProcess: " + isProcessRunning.get());

                    // The wrapper echoes its pid to stderr first; cut exactly that line and
                    // keep everything else byte-for-byte (newlines included).
                    if (!isProcessRunning.get()) {
                        Matcher m = PID_LINE.matcher(finalLine);
                        if (m.find()) {
                            pid.set(Integer.parseInt(m.group(1)));
                            Log.d("CmdOut", "pid: " + pid.get());
                            String remaining = finalLine.substring(0, m.start()) + finalLine.substring(m.end());
                            synchronized (startLock) {
                                isProcessRunning.set(true);
                                if (processListener != null) {
                                    mainHandler.post(() -> processListener.onProcessCreated(pid.get(), command));
                                }
                                if (earlyOutput.length() > 0 && resultListener != null) {
                                    String early = earlyOutput.toString();
                                    earlyOutput.setLength(0);
                                    lastOutput.set(early);
                                    outputHandler.post(() -> resultListener.output(early));
                                }
                            }
                            if (!remaining.isEmpty() && resultListener != null) {
                                mainHandler.post(() -> resultListener.onError(remaining));
                            }
                            continue;
                        }
                    }

                    if (isProcessRunning.get() && resultListener != null) {
                        mainHandler.post(() -> resultListener.onError(finalLine));
                    }

                }
            } catch (IOException | NullPointerException e) {
                errorListener("stderr: " + e.getMessage());
            }
        }, "SessionErrThread");

        waitThread = new Thread(() -> {
            try {
                if (outThread != null) outThread.join();
                if (errThread != null) errThread.join();

                int code = process.waitFor();
                exitCode.set(code);

                Log.d("CommandSession", "Process selesai, exitCode = " + exitCode);
            } catch (InterruptedException | NullPointerException ignored) {
            } finally {
                destroy();
            }

        }, "SessionWaitThread");

        outThread.start();
        errThread.start();
        waitThread.start();
    }

    private void errorListener(String error) {
        if (resultListener != null)
            mainHandler.post(() -> resultListener.onError(error));
    }

    private void writeToProcess(String input) throws IOException {
        if (writer != null) {
            if (processListener != null) processListener.onProcessRunning(input);
            writer.write(input);
            writer.newLine();
            writer.flush();
            Log.d("CommandSession", "Input dikirim: " + input);
        }
    }

    public synchronized void destroy() {
        try {
            // 1. Pastikan proses dihentikan dulu (sinyal soft kill)
            if (process != null) process.destroy();

            // 2. Tunggu stream selesai dibaca (dari waitThread → pakai .join)
            //    -> jangan close reader dulu, tunggu dari luar

            // 3. Tutup semua stream SETELAH thread selesai
            if (writer != null) writer.close();
            if (bufferedReader != null) bufferedReader.close();
            if (bufferedError != null) bufferedError.close();

            // 4. Optional: kalau masih ada thread aktif, interrupt (fallback)
            if (outThread != null && outThread.isAlive()) outThread.interrupt();
            if (errThread != null && errThread.isAlive()) errThread.interrupt();
            if (waitThread != null && waitThread.isAlive()) waitThread.interrupt();

        } catch (IOException e) {
            errorListener("destroy: " + e.getMessage());
        }

        if (isProcessRunning.get() && processListener != null) {
            finishHandler.post(() -> processListener.onProcessFinished(exitCode.get(), lastOutput.get()));
        }

        // 6. Cleanup referensi
        isProcessRunning.set(false);
        writer = null;
        bufferedReader = null;
        bufferedError = null;
        process = null;
        outThread = null;
        errThread = null;
        waitThread = null;
    }

    public interface ResultListener {
        void output(CharSequence output);

        void onError(CharSequence error);
    }

    public interface ProcessListener {
        void onProcessCreated(int pid, @NonNull String command);

        void onProcessRunning(@NonNull String input);

        void onProcessFinished(int exitCode, @NonNull String lastOutput);
    }

}
