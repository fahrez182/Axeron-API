package frb.axeron.shell.host;

import android.annotation.SuppressLint;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Server side of one remote shell, forked on the client's fds. With a PTY it copies between the
 * PTY and the client's pipes; without one the pipes are the shell's stdio and nothing is copied.
 * Natives live in libaxsh_host.so ({@link #loadLibrary}). Started from rish's RishHost.
 */
public class PtyHost {

    private static final String TAG = "PtyHost";

    private static String libraryPath;

    /** Where libaxsh_host.so is; null to look it up by name. Set before {@link #loadLibrary}. */
    public static void setLibraryPath(String path) {
        libraryPath = path;
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public static void loadLibrary() {
        if (libraryPath == null) {
            System.loadLibrary("axsh_host");
        } else {
            System.load(libraryPath + "/libaxsh_host.so");
        }
    }

    /** tty flag: stdin, stdout and stderr all on the PTY. */
    public static final byte TTY_ALL = (1) | (1 << 1) | (1 << 2);

    // libcore/ojluni/src/main/java/java/lang/ProcessImpl.java
    private final String[] args;
    private final String[] env;
    private final String dir;
    private final byte tty;
    private final int stdin;
    private final int stdout;
    private final int stderr;
    /** Gets the exit code once the shell is gone (IAxeronService.newAxshProcess); -1 for none. */
    private final int status;
    private int pid;
    private int ptmx;
    private volatile int exitCode = Integer.MAX_VALUE;
    private Thread waiter;
    public PtyHost(
            String[] args, String[] env, String dir,
            byte tty,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr,
            ParcelFileDescriptor status) {

        this.args = args;
        this.env = env;
        this.dir = dir;
        this.tty = tty;
        this.stdin = detachFd(stdin);
        this.stdout = detachFd(stdout);
        this.stderr = detachFd(stderr);
        // Detached like the others: a caller in this process (AxshServer) closing its object
        // afterwards must not close ours.
        this.status = detachFd(status);
    }

    private static byte[] createCBytesForStringArray(String[] array) {
        if (array == null) {
            return null;
        }

        byte[][] bytes = new byte[array.length][];
        int count = bytes.length; // For added NUL bytes
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = array[i].getBytes();
            count += bytes[i].length;
        }
        byte[] block = new byte[count];
        int i = 0;
        for (byte[] arg : bytes) {
            System.arraycopy(arg, 0, block, i, arg.length);
            i += arg.length + 1;
            // No need to write NUL bytes explicitly
        }
        return block;
    }

    private static byte[] createCBytesForString(String s) {
        if (s == null) {
            return null;
        }

        byte[] bytes = s.getBytes();
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0,
                result, 0,
                bytes.length);
        result[result.length - 1] = (byte) 0;
        return result;
    }

    private static int detachFd(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return -1;
        }
        return pfd.detachFd();
    }

    private static native int[] start(
            byte[] argBlock, int argc,
            byte[] envBlock, int envc,
            byte[] dirBlock,
            byte tty, int stdin, int stdout, int stderr);

    private static native void setWindowSize(int ptmx, long size);

    private static native int waitFor(int pid);

    /**
     * Fork and execute, start transfer threads.
     */
    public void start() {
        Log.d(TAG, "start");


        byte[] argBlock = createCBytesForStringArray(args);
        byte[] envBlock = createCBytesForStringArray(env);
        byte[] dirBlock = createCBytesForString(dir);

        int[] result = start(
                argBlock, args.length,
                envBlock, env != null ? env.length : -1,
                dirBlock,
                tty, stdin, stdout, stderr);

        pid = result[0];
        ptmx = result[1];

        waiter = new Thread(() -> {
            exitCode = waitFor(pid);
            reportExit(exitCode);
        }, "pty-host-wait-" + pid);
        waiter.start();
    }

    /** The exit code to {@link #status}, int32 little-endian, then EOF. */
    private void reportExit(int code) {
        if (status < 0) return;
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array();
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(status))) {
            out.write(bytes);
        } catch (IOException e) {
            // The caller is gone (EPIPE): nobody is left to tell.
            Log.d(TAG, "exit code of " + pid + " not delivered: " + e.getMessage());
        }
    }

    public int getPid() {
        return pid;
    }

    /** The exit code, or Integer.MAX_VALUE while the shell still runs. */
    public int getExitCode() {
        return exitCode;
    }

    /** Blocks until the shell exits, then returns its exit code. */
    public int waitForExit() throws InterruptedException {
        waiter.join();
        return exitCode;
    }

    /** Kills the shell's whole session (it called setsid, so its pid is the group id). */
    public void destroy() {
        if (pid <= 0) return;
        try {
            Os.kill(-pid, OsConstants.SIGKILL);
        } catch (ErrnoException e) {
            Log.d(TAG, "kill " + pid + ": " + e.getMessage());
        }
    }

    /** No-op without a PTY (ptmx is -1 then). */
    public void setWindowSize(long size) {
        Log.d(TAG, "setWindowSize");

        if (ptmx >= 0) setWindowSize(ptmx, size);
    }
}
