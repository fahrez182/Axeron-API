package frb.axeron.axsh.host

import android.os.ParcelFileDescriptor
import frb.axeron.server.IAxshProcess

/**
 * Server side of [IAxshProcess]: one shell forked on the caller's fds by [PtyHost]. Each session
 * is its own object, so one caller (a broker serving several clients) can run many at once.
 */
class AxshProcess private constructor(private val host: PtyHost) : IAxshProcess.Stub() {

    companion object {
        /**
         * Starts [cmd] (full argv). Without [pty], [stdin]/[stdout]/[stderr] become its stdio
         * directly ([stderr] null: stdout); with it, they're copied to and from a new PTY and
         * [stderr] is unused.
         */
        @JvmStatic
        fun start(
            cmd: Array<String>,
            env: Array<String>?,
            dir: String?,
            stdin: ParcelFileDescriptor,
            stdout: ParcelFileDescriptor,
            stderr: ParcelFileDescriptor?,
            pty: Boolean,
            size: Long
        ): AxshProcess {
            require(cmd.isNotEmpty()) { "empty command" }
            val host = if (pty) {
                stderr?.close()
                PtyHost(cmd, env, dir, PtyHost.TTY_ALL, stdin, stdout, null)
            } else {
                PtyHost(cmd, env, dir, 0, stdin, stdout, stderr)
            }
            host.start()
            if (pty && size != 0L) host.setWindowSize(size)
            return AxshProcess(host)
        }
    }

    override fun setWindowSize(size: Long) = host.setWindowSize(size)

    override fun waitFor(): Int = try {
        host.waitForExit()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        -1
    }

    override fun destroy() = host.destroy()
}
