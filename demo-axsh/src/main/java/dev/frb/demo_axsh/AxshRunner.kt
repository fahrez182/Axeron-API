package dev.frb.demo_axsh

import com.topjohnwu.superuser.Shell
import frb.axeron.axsh.Axsh
import java.io.File
import kotlin.concurrent.thread

/** One command's run: what it printed on each stream, how it exited and how long it took. */
data class AxshResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val millis: Long,
    /** Set when axsh itself couldn't be started (not the command failing). */
    val error: String? = null,
) {
    val ok get() = error == null && exitCode == 0
    val out get() = stdout.trim()
}

/** How a command reaches axsh. */
enum class Runner(val label: String, val description: String) {
    PROCESS(
        "axsh -c per command",
        "ProcessBuilder runs libaxsh.so for every command: one new axsh session each time."
    ),
    LIBSU(
        "libsu cached shell",
        "libsu's main shell, started on libaxsh.so once and kept: later commands skip the session setup."
    ),
    LIBSU_DIRECT(
        "libsu, no exec",
        "libsu on Axsh.newProcess(): the shell is started from this process over binder, no " +
                "libaxsh.so run, so no useLegacyPackaging. Needs direct connection (the broker)."
    ),
}

object AxshRunner {

    @Volatile
    var runner = Runner.PROCESS

    fun run(command: String, stdin: String? = null): AxshResult = when (runner) {
        Runner.PROCESS -> runProcess(command, stdin)
        Runner.LIBSU -> runLibsu(command, stdin) { Shell.cmd(it) }
        Runner.LIBSU_DIRECT -> runLibsu(command, stdin) { directShell().newJob().add(it) }
    }

    /** Built on our own Process, so not libsu's main shell (that one needs a path to exec). */
    @Volatile
    private var direct: Shell? = null

    @Synchronized
    private fun directShell(): Shell =
        direct?.takeIf { it.isAlive } ?: Shell.Builder.create().build(Axsh.newProcess()).also { direct = it }

    /**
     * libaxsh.so straight from [ProcessBuilder] rather than through libsu, so stdout and stderr
     * stay apart and the exit code is the shell's own: both are part of what is being tested.
     */
    fun runProcess(command: String, stdin: String? = null, axsh: File? = Application.axsh): AxshResult {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        if (axsh == null) return AxshResult(-1, "", "", 0, "libaxsh.so not found")

        return try {
            val process = ProcessBuilder(axsh.absolutePath, "-c", command).start()
            var stderr = ""
            val errReader = thread(name = "axsh-stderr") {
                stderr = process.errorStream.bufferedReader().readText()
            }
            process.outputStream.use { input -> stdin?.let { input.write(it.toByteArray()) } }
            val stdout = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            errReader.join()
            AxshResult(code, stdout, stderr, elapsed())
        } catch (e: Exception) {
            AxshResult(-1, "", "", elapsed(), e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Through a cached libsu shell, made into a job by [job]: libsu's main shell (Application
     * points its builder at libaxsh.so) or the one on [Axsh.newProcess]. The first call pays for
     * starting it; the rest are a write to an already open shell.
     *
     * Each command runs in a subshell, so `exit` or `cd` can't end or change the cached shell, and
     * its stdin is [stdin] as a here-document: the shell's own stdin is libsu's command stream.
     */
    private fun runLibsu(command: String, stdin: String?, job: (String) -> Shell.Job): AxshResult {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000

        val script = if (stdin == null) {
            "( $command ) </dev/null"
        } else {
            "( $command ) <<'__AXSH_STDIN__'\n${stdin.removeSuffix("\n")}\n__AXSH_STDIN__"
        }
        return try {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val result = job(script).to(out, err).exec()
            AxshResult(result.code, out.joinToString("\n"), err.joinToString("\n"), elapsed())
        } catch (e: Exception) {
            AxshResult(-1, "", "", elapsed(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** The current runner's cached shell is alive. */
    val cachedShellAlive: Boolean
        get() = when (runner) {
            Runner.LIBSU_DIRECT -> direct?.isAlive == true
            else -> Shell.getCachedShell()?.isAlive == true
        }

    /** Drops the current runner's cached shell; its next command starts a new one. */
    fun closeCachedShell() {
        runCatching {
            when (runner) {
                Runner.LIBSU_DIRECT -> direct?.close()
                else -> Shell.getCachedShell()?.close()
            }
        }
    }
}
