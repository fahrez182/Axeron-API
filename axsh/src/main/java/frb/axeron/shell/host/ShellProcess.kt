package frb.axeron.shell.host

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import frb.axeron.server.IAxshProcess
import frb.axeron.shell.InternalAxeronApi

/**
 * Server side of [IAxshProcess]: one shell forked on the caller's fds by [PtyHost]. Each session
 * is its own object, so one caller (a broker serving several clients) can run many at once.
 */
@InternalAxeronApi
class ShellProcess private constructor(private val host: PtyHost) : IAxshProcess.Stub() {

    companion object {
        /**
         * Starts [cmd] (full argv). Without [pty], [stdin]/[stdout]/[stderr] become its stdio
         * directly ([stderr] null: stdout); with it, they're copied to and from a new PTY and
         * [stderr] is unused. [status], if given, gets the exit code (see
         * IAxeronService.newAxshProcess), so the caller needn't block in [waitFor].
         */
        @JvmStatic
        @JvmOverloads
        fun start(
            cmd: Array<String>,
            env: Array<String>?,
            dir: String?,
            stdin: ParcelFileDescriptor,
            stdout: ParcelFileDescriptor,
            stderr: ParcelFileDescriptor?,
            pty: Boolean,
            size: Long,
            status: ParcelFileDescriptor?,
            /** The caller's app record: when it dies, the shell goes too (as newProcess does). */
            owner: IBinder? = null
        ): ShellProcess {
            require(cmd.isNotEmpty()) { "empty command" }
            val host = if (pty) {
                stderr?.close()
                PtyHost(cmd, env, dir, PtyHost.TTY_ALL, stdin, stdout, null, status)
            } else {
                PtyHost(cmd, env, dir, 0, stdin, stdout, stderr, status)
            }
            host.start()
            if (pty && size != 0L) host.setWindowSize(size)
            owner?.let { killWith(it, host) }
            return ShellProcess(host)
        }

        private fun killWith(owner: IBinder, host: PtyHost) {
            try {
                owner.linkToDeath({
                    // Still running: an exited shell's pid may belong to someone else by now.
                    if (host.exitCode == Int.MAX_VALUE) {
                        host.destroy()
                        Log.i("ShellProcess", "destroyed pid ${host.pid}: its owner died")
                    }
                }, 0)
            } catch (e: RemoteException) {
                // Already dead.
                host.destroy()
            }
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
