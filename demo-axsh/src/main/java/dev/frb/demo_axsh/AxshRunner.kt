package dev.frb.demo_axsh

import com.topjohnwu.superuser.Shell
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
}

object AxshRunner {

    @Volatile
    var runner = Runner.PROCESS

    fun run(command: String, stdin: String? = null): AxshResult = when (runner) {
        Runner.PROCESS -> runProcess(command, stdin)
        Runner.LIBSU -> runLibsu(command, stdin)
    }

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
     * Through libsu's cached main shell (Application points its builder at libaxsh.so). The first
     * call pays for starting it; the rest are a write to an already open shell.
     *
     * Each command runs in a subshell, so `exit` or `cd` can't end or change the cached shell, and
     * its stdin is [stdin] as a here-document: the shell's own stdin is libsu's command stream.
     */
    fun runLibsu(command: String, stdin: String? = null): AxshResult {
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
            val result = Shell.cmd(script).to(out, err).exec()
            AxshResult(result.code, out.joinToString("\n"), err.joinToString("\n"), elapsed())
        } catch (e: Exception) {
            AxshResult(-1, "", "", elapsed(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** The cached shell, if libsu has started one. */
    val cachedShellAlive: Boolean
        get() = Shell.getCachedShell()?.isAlive == true

    /** Drops libsu's cached shell; the next libsu command starts a new one. */
    fun closeCachedShell() {
        runCatching { Shell.getCachedShell()?.close() }
    }
}
