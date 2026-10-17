package frb.axeron.axsh

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import frb.axeron.server.IAxeronService
import frb.axeron.server.IAxshProcess
import frb.axeron.shared.AxeronApiConstant
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * axsh for an app that has the axsh module and runs [AxshBroker]: a shell on AxManager's server
 * as a [java.lang.Process], started in this process. Nothing is exec'd, so `libaxsh.so` needn't be
 * extracted (no `useLegacyPackaging`), and there is no socket or handshake: one binder call.
 *
 * For libsu, wrap it: `Shell.Builder.create().build(Axsh.newProcess())`.
 */
object Axsh {

    /** What libaxsh runs too (REMOTE_SHELL in main.cpp). */
    private const val SHELL = "/system/bin/sh"

    /**
     * Starts `sh` with [args] (`sh` semantics: none for a shell reading stdin, `-c cmd`, …) on the
     * server, on pipes this process keeps: stdin, stdout and stderr apart, the shell's exit code.
     *
     * Blocks: call it off the main thread. With AxManager's permission on Ask, it waits on the
     * dialog (up to 15 s). [AxshBroker.start] must have run, as it holds or fetches the binder.
     *
     * @throws SecurityException when this app isn't allowed in AxManager
     * @throws IOException when the server is out of reach or the shell can't start
     */
    @JvmStatic
    fun newProcess(vararg args: String): Process {
        val service = AxshBroker.service() ?: throw IOException("AxManager server is not reachable")
        val argv = arrayOf(SHELL, *args)

        // [0] read end, [1] write end. The server gets stdin's read end and the others' write ends.
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val stderr = ParcelFileDescriptor.createPipe()
        val remote = try {
            try {
                start(service, argv, stdin[0], stdout[1], stderr[1])
            } catch (_: SecurityException) {
                if (!AxshBroker.awaitPermission(service)) throw SecurityException(AxshSessions.DENIED_MESSAGE)
                start(service, argv, stdin[0], stdout[1], stderr[1])
            }
        } catch (e: Throwable) {
            listOf(stdin[1], stdout[0], stderr[0]).forEach { it.closeQuietly() }
            if (e is RemoteException) throw IOException("AxManager server failed", e)
            throw e
        } finally {
            // The server has its own copies now; ours would hide the shell's EOF from the reader.
            listOf(stdin[0], stdout[1], stderr[1]).forEach { it.closeQuietly() }
        }
        return RemoteShell(remote, stdin[1], stdout[0], stderr[0])
    }

    private fun start(
        service: IAxeronService,
        argv: Array<String>,
        stdin: ParcelFileDescriptor,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor
    ): IAxshProcess {
        val env = service.getEnvironment(AxeronApiConstant.server.TYPE_ENV)?.env
        return service.newAxshProcess(argv, env, null, stdin, stdout, stderr, false, 0)
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    /** The shell's ends of the pipes, and its exit code once [IAxshProcess.waitFor] returns. */
    private class RemoteShell(
        private val remote: IAxshProcess,
        stdin: ParcelFileDescriptor,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
    ) : Process() {

        private val input: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(stdin)
        private val output: InputStream = ParcelFileDescriptor.AutoCloseInputStream(stdout)
        private val error: InputStream = ParcelFileDescriptor.AutoCloseInputStream(stderr)

        private val exited = CountDownLatch(1)

        @Volatile
        private var code = -1

        init {
            // exitValue() must answer without blocking (libsu asks it to see if the shell lives).
            thread(name = "axsh-wait", isDaemon = true) {
                code = try {
                    remote.waitFor()
                } catch (_: RemoteException) {
                    -1 // the server died, and the shell with it
                }
                exited.countDown()
            }
        }

        override fun getOutputStream() = input

        override fun getInputStream() = output

        override fun getErrorStream() = error

        override fun waitFor(): Int {
            exited.await()
            return code
        }

        override fun exitValue(): Int {
            if (exited.count > 0) throw IllegalThreadStateException("axsh shell still running")
            return code
        }

        override fun destroy() {
            try {
                remote.destroy()
            } catch (_: RemoteException) {
            }
        }
    }
}
