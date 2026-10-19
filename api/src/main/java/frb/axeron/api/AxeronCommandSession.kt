package frb.axeron.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import frb.axeron.shell.Axsh
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Runs one command at a time like a terminal does. A command is done when its shell exits, even
 * if it left jobs running in the background (`cmd &`): those keep their output coming in,
 * and keep running while later commands run, unless background processing is restricted.
 */
class AxeronCommandSession {

    private val mainHandler = Handler(Looper.getMainLooper())
    var env: String? = null
    var export: String? = null
    private var resultListener: ResultListener? = null
    private var processListener: ProcessListener? = null

    @Volatile
    private var restrictBackground = true

    /** The latest command; earlier ones may still have background jobs reading out. */
    private var current: Run? = null

    fun setResultListener(resultListener: ResultListener?) {
        this.resultListener = resultListener
    }

    fun setProcessListener(processListener: ProcessListener?) {
        this.processListener = processListener
    }

    /**
     * True: what a command leaves running in the background is killed when its shell exits.
     * False: it keeps running, its output still shown, and its pid is reported.
     */
    fun setRestrictBackground(restrict: Boolean) {
        restrictBackground = restrict
    }

    /** Starts [input] as a new command, or writes it to the running one's stdin. */
    @Synchronized
    fun runCommand(input: String, isCompatModeEnabled: Boolean) {
        val run = current
        if (run != null && run.isRunning()) {
            Log.d("CmdOut", "write: $input")
            try {
                run.write(input)
            } catch (e: IOException) {
                errorListener("command: " + e.message)
            }
        } else {
            Log.d("CmdOut", "newProcess: $input")
            startNewProcess(input, isCompatModeEnabled)
        }
    }

    /** Stops the running command: TERM to its whole session, background jobs included. */
    @Synchronized
    fun killSession() {
        val run = current
        if (run != null && run.isRunning()) killGroup(run.pid)
    }

    /** As [killSession]. */
    fun destroy() {
        killSession()
    }

    private fun startNewProcess(command: String, isCompatModeEnabled: Boolean) {
        val env = Axeron.getEnvironment()
        val process = try {
            Axsh.exec(sessionCmd(command, isCompatModeEnabled), env?.env, null)
        } catch (e: Exception) {
            errorListener("Failed to start process: " + e.message)
            return
        }
        current = Run(command, process).also { it.start() }
    }

    private fun errorListener(error: String) {
        if (resultListener != null) mainHandler.post { resultListener?.onError(error) }
    }

    /**
     * One command: its shell and its output. The readers live as long as anything holds the
     * output open, background jobs included, so a later command never cuts them off.
     */
    private inner class Run(val command: String, val process: Process) {
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        val stdout = BufferedReader(InputStreamReader(process.inputStream))
        val stderr = BufferedReader(InputStreamReader(process.errorStream))

        /** Guarded by this Run: stdout read before stderr delivered the pid. */
        val early = StringBuilder()
        val lastOutput = AtomicReference("")

        /** Counted down by the EXIT trap's line, or stderr's end when there is none. */
        val bgMarkSeen = CountDownLatch(1)

        @Volatile
        var pid = -1

        @Volatile
        var backgroundPid = -1

        /** The pid line was seen and onProcessCreated posted. Guarded by this Run. */
        var started = false

        /** The shell exited: no more input, finished reported. */
        @Volatile
        var shellDone = false

        fun isRunning(): Boolean = !shellDone

        @Throws(IOException::class)
        fun write(input: String) {
            processListener?.onProcessRunning(input)
            writer.write(input)
            writer.newLine()
            writer.flush()
            Log.d("CommandSession", "Input dikirim: $input")
        }

        fun start() {
            val out = Thread(::readStdout, "SessionOutThread")
            val err = Thread(::readStderr, "SessionErrThread")
            out.start()
            err.start()
            Thread({ awaitShell(out, err) }, "SessionWaitThread").start()
        }

        private fun readStdout() {
            try {
                val buffer = CharArray(1024 * 8)
                while (true) {
                    val read = stdout.read(buffer)
                    if (read == -1) break
                    val part = String(buffer, 0, read)
                    synchronized(this) {
                        // Held until onProcessCreated, so it isn't lost and lands after it.
                        if (!started) early.append(part) else output(part)
                    }
                }
            } catch (e: IOException) {
                errorListener("stdout: " + e.message)
            } finally {
                close(stdout)
            }
        }

        private fun readStderr() {
            try {
                val buffer = CharArray(1024 * 8)
                while (true) {
                    val read = stderr.read(buffer)
                    if (read == -1) break
                    var part = String(buffer, 0, read)
                    Log.d("CmdOut", "error: $part")

                    var waitingForPid = false
                    synchronized(this) {
                        // The wrapper echoes its pid to stderr first; cut exactly that line and
                        // keep everything else byte-for-byte (newlines included).
                        if (!started) {
                            val m = PID_LINE.matcher(part)
                            if (!m.find()) {
                                waitingForPid = true
                            } else {
                                pid = m.group(1)!!.toInt()
                                Log.d("CmdOut", "pid: $pid")
                                part = part.substring(0, m.start()) + part.substring(m.end())
                                started = true
                                processListener?.let { listener ->
                                    val p = pid
                                    mainHandler.post { listener.onProcessCreated(p, command) }
                                }
                                if (early.isNotEmpty()) {
                                    output(early.toString())
                                    early.setLength(0)
                                }
                            }
                        }
                    }
                    if (waitingForPid) continue

                    val bg = BG_LINE.matcher(part)
                    if (bg.find()) {
                        val group = bg.group(1)!!
                        if (group.isNotEmpty()) backgroundPid = group.toInt()
                        part = part.substring(0, bg.start()) + part.substring(bg.end())
                        bgMarkSeen.countDown()
                    }
                    if (part.isNotEmpty() && resultListener != null) {
                        val error = part
                        mainHandler.post { resultListener?.onError(error) }
                    }
                }
            } catch (e: IOException) {
                errorListener("stderr: " + e.message)
            } finally {
                bgMarkSeen.countDown()
                close(stderr)
            }
        }

        private fun awaitShell(out: Thread, err: Thread) {
            val code = try {
                // The command's shell: its exit code is the command's.
                val exit = process.waitFor()
                bgMarkSeen.await(BG_MARK_WAIT_MS, TimeUnit.MILLISECONDS)
                out.join(BG_MARK_WAIT_MS)
                exit
            } catch (e: InterruptedException) {
                -1
            }
            Log.d("CommandSession", "Process selesai, exitCode = $code")

            shellDone = true
            close(writer)

            // Output still held open with the shell gone: a background job is still running.
            val leftRunning = out.isAlive || err.isAlive
            if (leftRunning && restrictBackground) {
                killGroup(pid)
            } else if (leftRunning && backgroundPid > 0) {
                processListener?.let { listener ->
                    val pids = intArrayOf(backgroundPid)
                    mainHandler.post { listener.onProcessBackground(pids) }
                }
            }

            val wasStarted: Boolean
            synchronized(this) {
                wasStarted = started
                // Never got a pid line: still show what it printed, and whatever comes later.
                started = true
                if (early.isNotEmpty()) {
                    output(early.toString())
                    early.setLength(0)
                }
            }
            if (wasStarted) {
                processListener?.let { listener ->
                    val last = lastOutput.get()
                    mainHandler.post { listener.onProcessFinished(code, last) }
                }
            }
        }

        private fun output(part: String) {
            if (resultListener == null) return
            lastOutput.set(part)
            mainHandler.post { resultListener?.output(part) }
        }
    }

    interface ResultListener {
        fun output(output: CharSequence?)

        fun onError(error: CharSequence?)
    }

    interface ProcessListener {
        fun onProcessCreated(pid: Int, command: String)

        fun onProcessRunning(input: String)

        fun onProcessFinished(exitCode: Int, lastOutput: String)

        /** The command's shell is done but left these processes running in the background. */
        fun onProcessBackground(pids: IntArray) {
        }
    }

    companion object {
        private val PID_LINE: Pattern = Pattern.compile("(?m)^[ \\t]*(\\d+)[ \\t]*(?:\\r?\\n|$)")

        /** Written to stderr by the command shell's EXIT trap, followed by its `$!`. */
        private const val BG_MARK = "__axeron_bg__="
        private val BG_LINE: Pattern = Pattern.compile("(?m)^$BG_MARK(\\d*)[ \\t]*(?:\\r?\\n|$)")

        /** How long to wait for the EXIT trap's line once the shell is gone. */
        private const val BG_MARK_WAIT_MS = 500L

        @JvmStatic
        fun getQuickCmd(cmd: String, useBusybox: Boolean, withPid: Boolean): Array<String> =
            quickCmd(cmd, useBusybox, withPid, true)

        /**
         * The session's own command: no `setsid`, as the axsh host already starts the shell in
         * its own session. A second setsid would fork and exit at once, leaving the server waiting on
         * it instead of the command: its pid not the command's, its exit code always 0.
         * The EXIT trap reports the last background job's pid (`$!`); it goes before the command
         * on the same line, so line numbers in the shell's errors stay the user's.
         */
        private fun sessionCmd(cmd: String, useBusybox: Boolean): Array<String> {
            val traced = "trap 'echo \"$BG_MARK\$!\" 1>&2' EXIT; $cmd"
            return quickCmd(traced, useBusybox, true, false)
        }

        private fun quickCmd(cmd: String, useBusybox: Boolean, withPid: Boolean, setsid: Boolean): Array<String> {
            val execCmd = if (withPid) {
                "export PARENT_PID=\$\$; " +
                        "echo \$PARENT_PID 1>&2; " +
                        "exec -a \"QuickShell\" /system/bin/sh -c \"\$0\""
            } else {
                "exec -a \"QuickShell\" /system/bin/sh -c \"\$0\""
            }

            val argv = ArrayList<String>()
            if (useBusybox) {
                argv.add(AxeronPluginService.BUSYBOX)
                if (setsid) argv.add("setsid")
                argv.add("sh")
            } else {
                if (setsid) argv.add("setsid")
                argv.add("/system/bin/sh")
            }
            argv.add("-c")
            argv.add(execCmd)
            argv.add(cmd)
            return argv.toTypedArray()
        }

        /** TERM to the process group [pgid] (a command's session: the axsh host's setsid). */
        private fun killGroup(pgid: Int) {
            // Never -1: that would be every process this uid may signal.
            if (pgid <= 1) return
            Thread({
                try {
                    val kill = Axsh.exec(arrayOf("kill", "-TERM", "-$pgid"))
                    kill.outputStream.close()
                    kill.waitFor()
                    kill.inputStream.close()
                    kill.errorStream.close()
                } catch (e: Exception) {
                    Log.w("CmdOut", "kill " + pgid + ": " + e.message)
                }
            }, "SessionKill").start()
        }

        private fun close(closeable: Closeable) {
            try {
                closeable.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
