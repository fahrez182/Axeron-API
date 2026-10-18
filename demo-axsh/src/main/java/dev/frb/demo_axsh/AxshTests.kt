package dev.frb.demo_axsh

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import frb.axeron.shell.ShellService
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** What a test found: [passed] null for a value with no right answer (shown as INFO). */
data class Outcome(val passed: Boolean?, val summary: String, val detail: String = "")

class AxshTest(
    val group: String,
    val title: String,
    val description: String,
    val run: () -> Outcome,
)

private fun pass(summary: String, detail: String = "") = Outcome(true, summary, detail)
private fun fail(summary: String, detail: String = "") = Outcome(false, summary, detail)
private fun info(summary: String, detail: String = "") = Outcome(null, summary, detail)

/** Everything axsh printed, for the expanded card. */
private fun AxshResult.dump() = buildString {
    error?.let { appendLine("error: $it") }
    appendLine("exit=$exitCode  ${millis}ms")
    if (stdout.isNotBlank()) appendLine("--- stdout\n${stdout.trimEnd()}")
    if (stderr.isNotBlank()) appendLine("--- stderr\n${stderr.trimEnd()}")
}.trimEnd()

/** A failed run says why: axsh's own error, else what the shell wrote to stderr. */
private fun AxshResult.why() = error ?: stderr.trim().lines().lastOrNull()?.takeIf { it.isNotBlank() }
?: "exit $exitCode"

private fun sh(command: String, stdin: String? = null) = AxshRunner.run(command, stdin)

private const val CONNECTION = "Connection"
private const val SHELL = "Shell"
private const val PRIVILEGES = "Privileges"
private const val TEST_FUNCTION = "Test Function plugin"
private const val ROOT_SERVICE = "libsu RootService"
private const val SHELL_SERVICE = "ShellService (POC)"

// The plugin's folder, as its scripts see it ($MODDIR).
private const val PLUGIN = "\$AXERONDIR/plugins/test_function"

private val mainTests: List<AxshTest> = listOf(
    // ---- connection ----
    AxshTest(
        CONNECTION, "Reach the server",
        "axsh -c id. The first run may wait on AxManager's permission dialog (up to 15 s)."
    ) {
        val r = sh("id")
        if (r.ok) pass(r.out, r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(
        CONNECTION, "Latency",
        "Five runs of `true`. Per command, each is a session; compare direct connection on and off. " +
                "libsu: only a first run without a cached shell pays for one."
    ) {
        val runs = List(5) { sh("true") }
        val bad = runs.firstOrNull { !it.ok }
        val times = runs.map { it.millis }
        val detail = times.joinToString("  ") { "${it}ms" }
        when {
            bad != null -> fail(bad.why(), bad.dump())
            else -> info("first ${times.first()}ms · avg of rest ${times.drop(1).average().toLong()}ms", detail)
        }
    },
    AxshTest(
        CONNECTION, "Concurrent commands",
        "Four at once. Per command: four axsh sessions, four shells. libsu: one cached shell, so " +
                "they queue and share its pid."
    ) {
        val pool = Executors.newFixedThreadPool(4)
        val results = try {
            // $$ is the shell axsh started, also inside libsu's ( ) subshell.
            pool.invokeAll(List(4) { Callable { sh("echo \$\$") } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val detail = results.joinToString("\n") { "pid ${it.out}  ${it.millis}ms" }
        val bad = results.firstOrNull { !it.ok }
        val pids = results.map { it.out }.toSet()
        val expected = if (AxshRunner.runner == Runner.PROCESS) 4 else 1
        when {
            bad != null -> fail(bad.why(), detail)
            pids.size != expected -> fail("expected $expected shell(s), got ${pids.size}: $pids", detail)
            else -> pass("${pids.size} shell(s): ${pids.joinToString()}", detail)
        }
    },
    AxshTest(
        CONNECTION, "Shell state stays apart",
        "`cd` and a variable in one command must not leak into the next, in either runner."
    ) {
        sh("cd /system; LEAK=1")
        val r = sh("echo \"\$PWD \${LEAK:-unset}\"")
        when {
            !r.ok -> fail(r.why(), r.dump())
            r.out.startsWith("/system") || r.out.endsWith(" 1") -> fail("leaked: ${r.out}", r.dump())
            else -> pass(r.out, r.dump())
        }
    },

    // ---- shell ----
    AxshTest(SHELL, "Exit code", "`exit 42` must come back as 42.") {
        val r = sh("exit 42")
        if (r.error == null && r.exitCode == 42) pass("42") else fail(r.why(), r.dump())
    },
    AxshTest(SHELL, "stdout and stderr apart", "Each stream reaches its own pipe.") {
        val r = sh("echo out; echo err >&2")
        if (r.stdout.trim() == "out" && r.stderr.trim() == "err") pass("out | err", r.dump())
        else fail("streams mixed or missing", r.dump())
    },
    AxshTest(SHELL, "stdin", "Input written to axsh reaches the shell, then EOF.") {
        val r = sh("read l; echo \"got:\$l\"; cat | wc -c", "hello axsh\nrest\n")
        val lines = r.out.lines()
        if (lines.firstOrNull() == "got:hello axsh" && lines.getOrNull(1)?.trim() == "5") pass(r.out, r.dump())
        else fail("unexpected: ${r.out}", r.dump())
    },
    AxshTest(SHELL, "Large output", "100 000 lines (~600 KB) through the pipes, nothing lost.") {
        val r = sh("seq 1 100000")
        val lines = r.stdout.lines().filter { it.isNotEmpty() }
        val kb = r.stdout.length / 1024
        if (r.ok && lines.size == 100_000 && lines.last() == "100000") pass("${kb} KB in ${r.millis}ms")
        else fail("got ${lines.size} lines (${r.why()})")
    },
    AxshTest(SHELL, "Server environment", "The shell gets the server's env: AXERON* and its PATH.") {
        val r = sh("env | grep -E '^(AXERON|TMPDIR|PATH)' | sort")
        val ok = r.ok && r.stdout.contains("AXERONDIR=") && r.stdout.contains("PATH=")
        if (ok) pass("${r.out.lines().size} variables", r.dump()) else fail(r.why(), r.dump())
    },

    // ---- privileges ----
    AxshTest(PRIVILEGES, "Identity", "Who the shell runs as: shell (2000) in ADB mode, root (0) with root.") {
        val r = sh("echo \"\$(id -u) \$(tr -d '\\0' < /proc/self/attr/current)\"")
        val uid = r.out.substringBefore(' ')
        when {
            !r.ok -> fail(r.why(), r.dump())
            uid == "0" || uid == "2000" -> pass(r.out, r.dump())
            else -> fail("runs as uid $uid, not the server", r.dump())
        }
    },
    AxshTest(PRIVILEGES, "Package manager", "`pm list packages`, which an app can't do on its own.") {
        val r = sh("pm list packages | wc -l")
        val count = r.out.toIntOrNull() ?: 0
        if (r.ok && count > 0) pass("$count packages") else fail(r.why(), r.dump())
    },
    AxshTest(PRIVILEGES, "Settings", "Read a global setting through `settings`.") {
        val r = sh("settings get global adb_enabled")
        if (r.ok) info("adb_enabled = ${r.out}", r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(PRIVILEGES, "dumpsys", "A system service dump, battery here.") {
        val r = sh("dumpsys battery | grep -E 'level|status|AC powered|USB powered'")
        if (r.ok) info(r.out.lines().joinToString(" · ") { it.trim() }, r.dump()) else fail(r.why(), r.dump())
    },

    // ---- Test Function plugin ----
    AxshTest(TEST_FUNCTION, "Installed", "plugins/test_function exists and is enabled.") {
        val r = sh("cd \"$PLUGIN\" || exit 3; grep '^version=' module.prop; [ -e disable ] && echo disabled; true")
        when {
            r.error == null && r.exitCode == 3 -> fail("not installed", r.dump())
            !r.ok -> fail(r.why(), r.dump())
            r.out.contains("disabled") -> fail("installed but disabled", r.dump())
            else -> pass(r.out.lines().first().removePrefix("version="), r.dump())
        }
    },
    AxshTest(TEST_FUNCTION, "axtest on PATH", "The plugin's system/bin/axtest, linked into \$AXERONXBIN.") {
        val r = sh("command -v axtest; echo \"\$AXERONXBIN\"")
        val (path, xbin) = r.out.lines().let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        when {
            !r.ok || r.out.lines().size < 2 -> fail("axtest not found (re-ignite after installing)", r.dump())
            path == "$xbin/axtest" -> pass(path, r.dump())
            else -> fail("found at $path, expected $xbin/axtest", r.dump())
        }
    },
    AxshTest(TEST_FUNCTION, "axtest ping", "Call the plugin's binary from this app.") {
        val r = sh("axtest ping")
        if (r.ok && r.out.startsWith("pong")) pass(r.out, r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(TEST_FUNCTION, "axtest env", "The variables the plugin's binary sees when an app calls it.") {
        val r = sh("axtest env")
        if (r.ok && r.out.isNotEmpty()) pass("${r.out.lines().size} variables", r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(
        TEST_FUNCTION, "Not marked as the plugin",
        "An axsh shell is not a plugin process: AXERON_PLUGIN must be unset."
    ) {
        val r = sh("axtest marker")
        when {
            !r.ok -> fail(r.why(), r.dump())
            r.out == "AXERON_PLUGIN=<none>" -> pass(r.out, r.dump())
            else -> fail("marked: ${r.out}", r.dump())
        }
    },
    AxshTest(TEST_FUNCTION, "system.prop", "The plugin's debug.axeron.test_function=loaded.") {
        val r = sh("getprop debug.axeron.test_function")
        if (r.ok && r.out == "loaded") pass(r.out) else fail("'${r.out}' (${r.why()})", r.dump())
    },
    AxshTest(TEST_FUNCTION, "Plugin processes", "`axtest procs`: plugin processes alive now, with their plugin.") {
        val r = sh("axtest procs")
        val lines = r.out.lines().filter { it.isNotBlank() }
        if (r.ok) info("${lines.size} marked processes", r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(TEST_FUNCTION, "Plugin reports", "Every [FAIL] the plugin's own stages wrote to reports/.") {
        val r = sh(
            "cd \"$PLUGIN/reports\" 2>/dev/null || exit 3; ls *.txt | wc -l; grep -H '^\\[FAIL\\]' *.txt; true"
        )
        val lines = r.out.lines()
        val reports = lines.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        val fails = lines.drop(1).filter { it.isNotBlank() }
        when {
            r.error == null && r.exitCode == 3 -> fail("no reports yet (re-ignite)", r.dump())
            !r.ok -> fail(r.why(), r.dump())
            fails.isEmpty() -> pass("$reports reports, no FAIL", r.dump())
            else -> fail("${fails.size} FAIL in $reports reports", fails.joinToString("\n"))
        }
    },

    // ---- libsu RootService ----
    AxshTest(
        ROOT_SERVICE, "Bind through axsh",
        "libsu starts its RootService process from its main shell, here libaxsh.so (whatever the " +
                "runner). Root mode only: libsu skips the launch on a non-root shell, and in ADB mode " +
                "shell can't read its main.jar nor send the reply broadcast (BROADCAST_PACKAGE_REMOVED)."
    ) {
        // RootService.bind's own gate (libsu 6.0.0): without it the bind just never connects.
        if (!com.topjohnwu.superuser.Shell.getShell().isRoot) {
            return@AxshTest info("skipped by libsu: main shell isn't root (ADB mode)")
        }
        val connection = UserServiceConnection()
        val intent = Intent(Application.app, RootUserService::class.java)
        mainHandler.post { RootService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val uid = service.uid
            val pid = service.pid
            val seLinux = service.seLinuxContext
            pass("uid $uid · $seLinux", "uid=$uid\npid=$pid\nselinux=$seLinux")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { RootService.unbind(connection) }
        }
    },

    // ---- ShellService (POC) ----
    AxshTest(
        SHELL_SERVICE, "Bind like RootService",
        "Bind UserService1 and ask who it is. Expected: uid 2000 (ADB mode) or 0 (root mode)."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(Application.app, UserService1::class.java)
        mainHandler.post { ShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val uid = service.uid
            val pid = service.pid
            val seLinux = service.seLinuxContext
            pass("uid $uid · $seLinux", "uid=$uid\npid=$pid\nselinux=$seLinux")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Runtime.exec inside the service",
        "UserService1 runs `id` with Runtime.exec. The output is what the service's process sees."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(Application.app, UserService1::class.java)
        mainHandler.post { ShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val output = service.exec("id")
            pass(output, output)
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Two services at once",
        "Bind UserService1 and UserService2 together, then ask each for its pid and `id`. " +
                "Each service class has its own process."
    ) {
        val connection1 = UserServiceConnection()
        val connection2 = UserServiceConnection()
        val intent1 = Intent(Application.app, UserService1::class.java)
        val intent2 = Intent(Application.app, UserService2::class.java)
        mainHandler.post { ShellService.bind(intent1, connection1) }
        mainHandler.post { ShellService.bind(intent2, connection2) }
        try {
            val service1 = connection1.waitForService()
            val service2 = connection2.waitForService()
            val pid1 = service1.pid
            val pid2 = service2.pid
            val id1 = service1.exec("id")
            val id2 = service2.exec("id")
            pass(
                "pid $pid1 & pid $pid2",
                "UserService1  pid=$pid1\n$id1\n\nUserService2  pid=$pid2\n$id2"
            )
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection1) }
            mainHandler.post { ShellService.unbind(connection2) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Clipboard from the service's Context",
        "UserService1 calls ClipboardManager.hasPrimaryClip with its own Context. The clipboard " +
                "checks the Context's op package against the uid: it must be the uid's own package."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(Application.app, UserService1::class.java)
        mainHandler.post { ShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val opPackage = service.opPackageName
            val hasClip = service.hasPrimaryClip()
            pass("$opPackage · hasPrimaryClip=$hasClip", "opPackageName=$opPackage\nhasPrimaryClip=$hasClip")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "getApplicationContext()",
        "What UserService1's getApplicationContext() returns. Many libraries call it; \"null\" " +
                "means they would crash inside the service."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(Application.app, UserService1::class.java)
        mainHandler.post { ShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            // A Kotlin null here (not the string "null"): the service's process doesn't know
            // this AIDL method, so it runs an older base.apk.
            val appContext: String? = service.applicationContextName
            if (appContext == null) {
                fail("no answer: the service runs an older base.apk")
            } else if (appContext == "null") {
                fail("getApplicationContext() returned null")
            } else {
                pass(appContext)
            }
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection) }
        }
    },
)

private val mainHandler = Handler(Looper.getMainLooper())

private const val SHELL_SERVICE_DAEMON = "ShellService daemon"

private val daemonTests: List<AxshTest> = listOf(
    AxshTest(
        SHELL_SERVICE_DAEMON, "Bind, unbind, bind again",
        "DaemonService's onUnbind returns true. Bind, read its pid, unbind, wait 2 s, bind again, " +
                "read its pid. The same pid twice: the process stayed on between the binds."
    ) {
        val intent = Intent(Application.app, DaemonService::class.java)

        val connection1 = UserServiceConnection()
        mainHandler.post { ShellService.bind(intent, connection1) }
        val firstPid: Int
        try {
            firstPid = connection1.waitForService().pid
        } catch (e: Exception) {
            return@AxshTest fail("first bind: $e")
        }
        mainHandler.post { ShellService.unbind(connection1) }

        Thread.sleep(2000)

        val connection2 = UserServiceConnection()
        mainHandler.post { ShellService.bind(intent, connection2) }
        try {
            val secondPid = connection2.waitForService().pid
            pass("pid $firstPid, then pid $secondPid", "first bind: pid $firstPid\nsecond bind: pid $secondPid")
        } catch (e: Exception) {
            fail("second bind: $e", "first bind: pid $firstPid")
        } finally {
            mainHandler.post { ShellService.unbind(connection2) }
        }
    },
    AxshTest(
        SHELL_SERVICE_DAEMON, "Pid now (run, close the app, run again)",
        "Bind DaemonService and show its pid. Run it, close this app from Recents, open it and run " +
                "it again: the same pid means the daemon outlived the app."
    ) {
        val intent = Intent(Application.app, DaemonService::class.java)
        val connection = UserServiceConnection()
        mainHandler.post { ShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val pid = service.pid
            val uid = service.uid
            pass("pid $pid · uid $uid", "pid=$pid\nuid=$uid")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE_DAEMON, "Stop it",
        "ShellService.stop on DaemonService (not bound here), wait 2 s, then bind: a new pid means " +
                "the old daemon is gone. Run it last: it leaves a fresh daemon behind."
    ) {
        val intent = Intent(Application.app, DaemonService::class.java)
        mainHandler.post { ShellService.stop(intent) }

        Thread.sleep(2000)

        val connection = UserServiceConnection()
        mainHandler.post { ShellService.bind(intent, connection) }
        try {
            val pid = connection.waitForService().pid
            pass("pid after stop: $pid", "pid after stop: $pid")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { ShellService.unbind(connection) }
        }
    },
)

// Declared last: top-level vals initialize in file order.
val axshTests: List<AxshTest> = mainTests + daemonTests
