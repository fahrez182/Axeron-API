package frb.axeron.shell

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import frb.axeron.server.IAxeronService
import frb.axeron.server.IAxshProcess
import frb.axeron.shared.AxeronApiConstant
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * axsh for an app that has the axsh module and runs [Broker]: a shell on AxManager's server
 * as a [java.lang.Process], started in this process. Nothing is exec'd, so `libaxsh.so` needn't be
 * extracted (no `useLegacyPackaging`), and there is no socket or handshake: one binder call.
 *
 * For libsu, wrap it: `Shell.Builder.create().build(Axsh.newProcess())`. For a terminal emulator
 * in the app, [newTerminal]; for a program run as it is, [exec].
 */
object Axsh {

    /** What libaxsh runs too (REMOTE_SHELL in main.cpp). */
    private const val SHELL = "/system/bin/sh"

    /** A [newTerminal] shell: a [Process] whose PTY can be resized. */
    abstract class Terminal : Process() {
        /** The terminal's new size, in character cells; the shell gets SIGWINCH. */
        abstract fun setWindowSize(rows: Int, cols: Int)
    }

    /**
     * Starts `sh` with [args] (`sh` semantics: none for a shell reading stdin, `-c cmd`, …) on the
     * server, on pipes this process keeps: stdin, stdout and stderr apart, the shell's exit code.
     *
     * Blocks: call it off the main thread. With AxManager's permission on Ask, it waits on the
     * dialog (up to 15 s). [Broker.start] must have run, as it holds or fetches the binder.
     *
     * @throws SecurityException when this app isn't allowed in AxManager
     * @throws IOException when the server is out of reach or the shell can't start
     */
    @JvmStatic
    @Throws(IOException::class)
    fun newProcess(vararg args: String): Process =
        start(arrayOf(SHELL, *args), env = null, dir = null, pty = false, rows = 0, cols = 0, terminalEnv = emptyMap())

    /**
     * Runs [argv] as it is (no shell around it, argv[0] looked up in PATH), as
     * `Runtime.getRuntime().exec(argv, env, dir)` would on the server; otherwise as [newProcess].
     *
     * @param env the whole environment, `NAME=value` each; null for AxManager's (as in QuickShell)
     * @param dir the working directory; null for the server's
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun exec(argv: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        require(argv.isNotEmpty()) { "empty command" }
        return start(argv, env = env, dir = dir, pty = false, rows = 0, cols = 0, terminalEnv = emptyMap())
    }

    /**
     * Like [newProcess], with the shell on a PTY on the server: a prompt, line editing, job
     * control, full-screen programs (`vi`, `top`). Everything it prints comes on
     * [Process.getInputStream] (the PTY merges stderr in, [Process.getErrorStream] stays empty);
     * write keys as the terminal sends them, ^C included, to [Process.getOutputStream].
     *
     * The shell gets AxManager's environment with [env] laid over it, of which only what describes
     * the terminal is taken, as from axsh in a terminal app: TERM, COLORTERM, LANG and LC_*.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun newTerminal(
        rows: Int,
        cols: Int,
        args: Array<String> = emptyArray(),
        env: Map<String, String> = mapOf("TERM" to "xterm-256color"),
    ): Terminal = start(arrayOf(SHELL, *args), env = null, dir = null, pty = true, rows = rows, cols = cols, terminalEnv = env)

    private fun start(
        argv: Array<out String>,
        env: Array<String>?,
        dir: String?,
        pty: Boolean,
        rows: Int,
        cols: Int,
        terminalEnv: Map<String, String>,
    ): Terminal {
        val service = Broker.service() ?: throw IOException("AxManager server is not reachable")

        // [0] read end, [1] write end. The server gets stdin's read end and the others' write
        // ends. A PTY merges stderr into stdout, so it has no pipe of its own then.
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val stderr = if (pty) null else ParcelFileDescriptor.createPipe()
        val remoteEnds = listOfNotNull(stdin[0], stdout[1], stderr?.get(1))
        val ourEnds = listOfNotNull(stdin[1], stdout[0], stderr?.get(0))

        val launch = {
            val base = env ?: service.getEnvironment(AxeronApiConstant.server.TYPE_ENV)?.env
            val shellEnv =
                if (pty) Sessions.ptyEnvironment(base, terminalEnv.map { "${it.key}=${it.value}" }) else base
            service.newAxshProcess(
                argv, shellEnv, dir, stdin[0], stdout[1], stderr?.get(1),
                pty, if (pty) Sessions.windowSize(rows, cols) else 0
            )
                // A server from before axsh doesn't know the call, and answers with nothing.
                ?: throw IOException("AxManager server is too old for axsh")
        }
        val remote = try {
            try {
                launch()
            } catch (_: SecurityException) {
                if (!Broker.awaitPermission(service)) throw SecurityException(Sessions.DENIED_MESSAGE)
                launch()
            }
        } catch (e: Throwable) {
            ourEnds.forEach { it.closeQuietly() }
            if (e is RemoteException) throw IOException("AxManager server failed", e)
            throw e
        } finally {
            // The server has its own copies now; ours would hide the shell's EOF from the reader.
            remoteEnds.forEach { it.closeQuietly() }
        }
        return RemoteShell(remote, stdin[1], stdout[0], stderr?.get(0))
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
        stderr: ParcelFileDescriptor?,
    ) : Terminal() {

        private val input: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(stdin)
        private val output: InputStream = ParcelFileDescriptor.AutoCloseInputStream(stdout)
        private val error: InputStream =
            stderr?.let { ParcelFileDescriptor.AutoCloseInputStream(it) } ?: ByteArrayInputStream(ByteArray(0))

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

        override fun setWindowSize(rows: Int, cols: Int) {
            try {
                remote.setWindowSize(Sessions.windowSize(rows, cols))
            } catch (_: RemoteException) {
            }
        }
    }
}
