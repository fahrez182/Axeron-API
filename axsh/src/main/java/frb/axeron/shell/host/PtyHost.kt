package frb.axeron.shell.host

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Server side of one remote shell, forked on the client's fds. With a PTY it copies between the
 * PTY and the client's pipes; without one the pipes are the shell's stdio and nothing is copied.
 * Natives live in libaxsh_host.so ([loadLibrary]), registered on this class by name
 * (RegisterNatives in pty_host.cpp), so they stay static methods of `PtyHost` itself.
 * Started from rish's RishHost.
 */
class PtyHost(
    // libcore/ojluni/src/main/java/java/lang/ProcessImpl.java
    private val args: Array<String>,
    private val env: Array<String>?,
    private val dir: String?,
    private val tty: Byte,
    stdin: ParcelFileDescriptor?,
    stdout: ParcelFileDescriptor?,
    stderr: ParcelFileDescriptor?,
    status: ParcelFileDescriptor?
) {
    private val stdin = detachFd(stdin)
    private val stdout = detachFd(stdout)
    private val stderr = detachFd(stderr)

    /** Gets the exit code once the shell is gone (IAxeronService.newAxshProcess); -1 for none. */
    // Detached like the others: a caller in this process (AxshServer) closing its object
    // afterwards must not close ours.
    private val status = detachFd(status)

    var pid = 0
        private set
    private var ptmx = 0

    /** The exit code, or Integer.MAX_VALUE while the shell still runs. */
    @Volatile
    var exitCode = Int.MAX_VALUE
        private set
    private var waiter: Thread? = null

    /**
     * Fork and execute, start transfer threads.
     */
    fun start() {
        Log.d(TAG, "start")


        val argBlock = createCBytesForStringArray(args)
        val envBlock = createCBytesForStringArray(env)
        val dirBlock = createCBytesForString(dir)

        val result = start(
            argBlock, args.size,
            envBlock, env?.size ?: -1,
            dirBlock,
            tty, stdin, stdout, stderr
        )

        pid = result[0]
        ptmx = result[1]

        waiter = Thread({
            exitCode = waitFor(pid)
            reportExit(exitCode)
        }, "pty-host-wait-$pid").also { it.start() }
    }

    /** The exit code to [status], int32 little-endian, then EOF. */
    private fun reportExit(code: Int) {
        if (status < 0) return
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(status)).use {
                it.write(bytes)
            }
        } catch (e: IOException) {
            // The caller is gone (EPIPE): nobody is left to tell.
            Log.d(TAG, "exit code of " + pid + " not delivered: " + e.message)
        }
    }

    /** Blocks until the shell exits, then returns its exit code. */
    @Throws(InterruptedException::class)
    fun waitForExit(): Int {
        waiter!!.join()
        return exitCode
    }

    /** Kills the shell's whole session (it called setsid, so its pid is the group id). */
    fun destroy() {
        if (pid <= 0) return
        try {
            Os.kill(-pid, OsConstants.SIGKILL)
        } catch (e: ErrnoException) {
            Log.d(TAG, "kill " + pid + ": " + e.message)
        }
    }

    /** No-op without a PTY (ptmx is -1 then). */
    fun setWindowSize(size: Long) {
        Log.d(TAG, "setWindowSize")

        if (ptmx >= 0) setWindowSize(ptmx, size)
    }

    companion object {

        private const val TAG = "PtyHost"

        private var libraryPath: String? = null

        /** tty flag: stdin, stdout and stderr all on the PTY. */
        const val TTY_ALL: Byte = ((1) or (1 shl 1) or (1 shl 2)).toByte()

        /** Where libaxsh_host.so is; null to look it up by name. Set before [loadLibrary]. */
        @JvmStatic
        fun setLibraryPath(path: String?) {
            libraryPath = path
        }

        @JvmStatic
        @SuppressLint("UnsafeDynamicallyLoadedCode")
        fun loadLibrary() {
            val path = libraryPath
            if (path == null) {
                System.loadLibrary("axsh_host")
            } else {
                System.load("$path/libaxsh_host.so")
            }
        }

        private fun createCBytesForStringArray(array: Array<String>?): ByteArray? {
            if (array == null) {
                return null
            }

            val bytes = Array(array.size) { array[it].toByteArray() }
            var count = bytes.size // For added NUL bytes
            for (b in bytes) count += b.size
            val block = ByteArray(count)
            var i = 0
            for (arg in bytes) {
                arg.copyInto(block, i)
                i += arg.size + 1
                // No need to write NUL bytes explicitly
            }
            return block
        }

        private fun createCBytesForString(s: String?): ByteArray? {
            if (s == null) {
                return null
            }

            val bytes = s.toByteArray()
            val result = ByteArray(bytes.size + 1)
            bytes.copyInto(result)
            result[result.size - 1] = 0
            return result
        }

        private fun detachFd(pfd: ParcelFileDescriptor?): Int = pfd?.detachFd() ?: -1

        // Static natives on PtyHost (@JvmStatic): the names and signatures RegisterNatives binds.

        @JvmStatic
        private external fun start(
            argBlock: ByteArray?, argc: Int,
            envBlock: ByteArray?, envc: Int,
            dirBlock: ByteArray?,
            tty: Byte, stdin: Int, stdout: Int, stderr: Int
        ): IntArray

        @JvmStatic
        private external fun setWindowSize(ptmx: Int, size: Long)

        @JvmStatic
        private external fun waitFor(pid: Int): Int
    }
}
