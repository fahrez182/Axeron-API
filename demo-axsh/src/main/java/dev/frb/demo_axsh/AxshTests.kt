package dev.frb.demo_axsh

import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.topjohnwu.superuser.ipc.RootService
import frb.axeron.shell.AxShellService
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

private const val COLD_START = "Cold start"
private const val CONNECTION = "Connection"
private const val SHELL = "Shell"
private const val PRIVILEGES = "Privileges"
private const val TEST_FUNCTION = "Test Function plugin"
private const val ROOT_SERVICE = "libsu RootService"
private const val SHELL_SERVICE = "AxShellService"
private const val SHELL_SERVICE_DAEMON = "AxShellService daemon"

/** One line under each section's title. */
val sectionInfo: Map<String, String> = mapOf(
    COLD_START to "Getting the server binder, the first thing an app does. The sticky is the new " +
            "path, the manager relay the old one. Force-stop AxManager before running the relay " +
            "to see what waking it costs.",
    CONNECTION to "How this app reaches AxManager's server through axsh.",
    SHELL to "Exit code, streams, stdin and env of an axsh shell.",
    PRIVILEGES to "What the shell can do and the app can't. uid 2000: ADB mode, 0: root.",
    TEST_FUNCTION to "Needs the test_function plugin installed in AxManager.",
    ROOT_SERVICE to "libsu's own RootService, to compare. Root mode only.",
    SHELL_SERVICE to "RootService's API on axsh, in ADB mode too. UserService1 and 2 share one process.",
    SHELL_SERVICE_DAEMON to "Bound with CATEGORY_DAEMON_MODE: a process that outlives the app.",
)

// The plugin's folder, as its scripts see it ($MODDIR).
private const val PLUGIN = "\$AXERONDIR/plugins/test_function"

private val mainTests: List<AxshTest> = listOf(
    // ---- connection ----
    AxshTest(
        CONNECTION, "Reach the server",
        "Runs `id`. The first run may show AxManager's permission dialog."
    ) {
        val r = sh("id")
        if (r.ok) pass(r.out, r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(
        CONNECTION, "Latency",
        "5× `true`. Compare direct connection on and off."
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
        "4 commands at once. Per command: 4 shells. libsu: 1 shared shell."
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
        "`cd` and variables don't carry over to the next command."
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
    AxshTest(SHELL, "Exit code", "`exit 42` comes back as 42.") {
        val r = sh("exit 42")
        if (r.error == null && r.exitCode == 42) pass("42") else fail(r.why(), r.dump())
    },
    AxshTest(SHELL, "stdout and stderr apart", "stdout and stderr arrive on their own pipes.") {
        val r = sh("echo out; echo err >&2")
        if (r.stdout.trim() == "out" && r.stderr.trim() == "err") pass("out | err", r.dump())
        else fail("streams mixed or missing", r.dump())
    },
    AxshTest(SHELL, "stdin", "Input reaches the shell, then EOF.") {
        val r = sh("read l; echo \"got:\$l\"; cat | wc -c", "hello axsh\nrest\n")
        val lines = r.out.lines()
        if (lines.firstOrNull() == "got:hello axsh" && lines.getOrNull(1)?.trim() == "5") pass(r.out, r.dump())
        else fail("unexpected: ${r.out}", r.dump())
    },
    AxshTest(SHELL, "Large output", "100 000 lines, none lost.") {
        val r = sh("seq 1 100000")
        val lines = r.stdout.lines().filter { it.isNotEmpty() }
        val kb = r.stdout.length / 1024
        if (r.ok && lines.size == 100_000 && lines.last() == "100000") pass("${kb} KB in ${r.millis}ms")
        else fail("got ${lines.size} lines (${r.why()})")
    },
    AxshTest(SHELL, "Server environment", "The shell has the server's env (AXERON*, PATH).") {
        val r = sh("env | grep -E '^(AXERON|TMPDIR|PATH)' | sort")
        val ok = r.ok && r.stdout.contains("AXERONDIR=") && r.stdout.contains("PATH=")
        if (ok) pass("${r.out.lines().size} variables", r.dump()) else fail(r.why(), r.dump())
    },

    // ---- privileges ----
    AxshTest(PRIVILEGES, "Identity", "The shell's uid and SELinux context.") {
        val r = sh("echo \"\$(id -u) \$(tr -d '\\0' < /proc/self/attr/current)\"")
        val uid = r.out.substringBefore(' ')
        when {
            !r.ok -> fail(r.why(), r.dump())
            uid == "0" || uid == "2000" -> pass(r.out, r.dump())
            else -> fail("runs as uid $uid, not the server", r.dump())
        }
    },
    AxshTest(PRIVILEGES, "Package manager", "`pm list packages`.") {
        val r = sh("pm list packages | wc -l")
        val count = r.out.toIntOrNull() ?: 0
        if (r.ok && count > 0) pass("$count packages") else fail(r.why(), r.dump())
    },
    AxshTest(PRIVILEGES, "Settings", "`settings get global adb_enabled`.") {
        val r = sh("settings get global adb_enabled")
        if (r.ok) info("adb_enabled = ${r.out}", r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(PRIVILEGES, "dumpsys", "`dumpsys battery`.") {
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
    AxshTest(TEST_FUNCTION, "axtest on PATH", "axtest is linked into \$AXERONXBIN.") {
        val r = sh("command -v axtest; echo \"\$AXERONXBIN\"")
        val (path, xbin) = r.out.lines().let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        when {
            !r.ok || r.out.lines().size < 2 -> fail("axtest not found (re-ignite after installing)", r.dump())
            path == "$xbin/axtest" -> pass(path, r.dump())
            else -> fail("found at $path, expected $xbin/axtest", r.dump())
        }
    },
    AxshTest(TEST_FUNCTION, "axtest ping", "Calls axtest, expects pong.") {
        val r = sh("axtest ping")
        if (r.ok && r.out.startsWith("pong")) pass(r.out, r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(TEST_FUNCTION, "axtest env", "Variables axtest sees when an app calls it.") {
        val r = sh("axtest env")
        if (r.ok && r.out.isNotEmpty()) pass("${r.out.lines().size} variables", r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(
        TEST_FUNCTION, "Not marked as the plugin",
        "AXERON_PLUGIN is unset in an axsh shell."
    ) {
        val r = sh("axtest marker")
        when {
            !r.ok -> fail(r.why(), r.dump())
            r.out == "AXERON_PLUGIN=<none>" -> pass(r.out, r.dump())
            else -> fail("marked: ${r.out}", r.dump())
        }
    },
    AxshTest(TEST_FUNCTION, "system.prop", "debug.axeron.test_function = loaded.") {
        val r = sh("getprop debug.axeron.test_function")
        if (r.ok && r.out == "loaded") pass(r.out) else fail("'${r.out}' (${r.why()})", r.dump())
    },
    AxshTest(TEST_FUNCTION, "Plugin processes", "Plugin processes alive now.") {
        val r = sh("axtest procs")
        val lines = r.out.lines().filter { it.isNotBlank() }
        if (r.ok) info("${lines.size} marked processes", r.dump()) else fail(r.why(), r.dump())
    },
    AxshTest(TEST_FUNCTION, "Plugin reports", "[FAIL] lines in the plugin's reports/.") {
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
        "Binds RootUserService through libsu, shows its uid. Skipped in ADB mode."
    ) {
        // RootService.bind's own gate (libsu 6.0.0): without it the bind just never connects.
        if (!com.topjohnwu.superuser.Shell.getShell().isRoot) {
            return@AxshTest info("skipped by libsu: main shell isn't root (ADB mode)")
        }
        val connection = UserServiceConnection()
        val intent = Intent(App.app, RootUserService::class.java)
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

    // ---- AxShellService ----
    AxshTest(
        SHELL_SERVICE, "Bind",
        "Binds UserService1, shows its uid and SELinux context."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(App.app, UserService1::class.java)
        mainHandler.post { AxShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val uid = service.uid
            val pid = service.pid
            val seLinux = service.seLinuxContext
            pass("uid $uid · $seLinux", "uid=$uid\npid=$pid\nselinux=$seLinux")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { AxShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Runtime.exec inside the service",
        "`id`, run by Runtime.exec inside UserService1."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(App.app, UserService1::class.java)
        mainHandler.post { AxShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val output = service.exec("id")
            pass(output, output)
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { AxShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Two services at once",
        "Binds UserService1 and UserService2, shows both pids."
    ) {
        val connection1 = UserServiceConnection()
        val connection2 = UserServiceConnection()
        val intent1 = Intent(App.app, UserService1::class.java)
        val intent2 = Intent(App.app, UserService2::class.java)
        mainHandler.post { AxShellService.bind(intent1, connection1) }
        mainHandler.post { AxShellService.bind(intent2, connection2) }
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
            mainHandler.post { AxShellService.unbind(connection1) }
            mainHandler.post { AxShellService.unbind(connection2) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Static shared between services",
        "Each service adds 1 to the same static counter."
    ) {
        val connection1 = UserServiceConnection()
        val connection2 = UserServiceConnection()
        val intent1 = Intent(App.app, UserService1::class.java)
        val intent2 = Intent(App.app, UserService2::class.java)
        mainHandler.post { AxShellService.bind(intent1, connection1) }
        mainHandler.post { AxShellService.bind(intent2, connection2) }
        try {
            val service1 = connection1.waitForService()
            val service2 = connection2.waitForService()
            val after1 = service1.incrementSharedCounter()
            val after2 = service2.incrementSharedCounter()
            pass(
                "UserService1 → $after1, UserService2 → $after2",
                "UserService1 pid=${service1.pid}: counter=$after1\nUserService2 pid=${service2.pid}: counter=$after2"
            )
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { AxShellService.unbind(connection1) }
            mainHandler.post { AxShellService.unbind(connection2) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "Clipboard from the service's Context",
        "A clipboard call from the service's Context; shows its op package."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(App.app, UserService1::class.java)
        mainHandler.post { AxShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val opPackage = service.opPackageName
            val hasClip = service.hasPrimaryClip()
            pass("$opPackage · hasPrimaryClip=$hasClip", "opPackageName=$opPackage\nhasPrimaryClip=$hasClip")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { AxShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE, "getApplicationContext()",
        "What getApplicationContext() returns inside the service."
    ) {
        val connection = UserServiceConnection()
        val intent = Intent(App.app, UserService1::class.java)
        mainHandler.post { AxShellService.bind(intent, connection) }
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
            mainHandler.post { AxShellService.unbind(connection) }
        }
    },
)

private val mainHandler = Handler(Looper.getMainLooper())

private val daemonTests: List<AxshTest> = listOf(
    AxshTest(
        SHELL_SERVICE_DAEMON, "Bind, unbind, bind again",
        "Pid before and after an unbind."
    ) {
        val intent = Intent(App.app, DaemonService::class.java)
        intent.addCategory(AxShellService.CATEGORY_DAEMON_MODE)

        val connection1 = UserServiceConnection()
        mainHandler.post { AxShellService.bind(intent, connection1) }
        val firstPid: Int
        try {
            firstPid = connection1.waitForService().pid
        } catch (e: Exception) {
            return@AxshTest fail("first bind: $e")
        }
        mainHandler.post { AxShellService.unbind(connection1) }

        Thread.sleep(2000)

        val connection2 = UserServiceConnection()
        mainHandler.post { AxShellService.bind(intent, connection2) }
        try {
            val secondPid = connection2.waitForService().pid
            pass("pid $firstPid, then pid $secondPid", "first bind: pid $firstPid\nsecond bind: pid $secondPid")
        } catch (e: Exception) {
            fail("second bind: $e", "first bind: pid $firstPid")
        } finally {
            mainHandler.post { AxShellService.unbind(connection2) }
        }
    },
    AxshTest(
        SHELL_SERVICE_DAEMON, "Survives closing the app",
        "Shows the pid. Run, close the app from Recents, reopen, run again."
    ) {
        val intent = Intent(App.app, DaemonService::class.java)
        intent.addCategory(AxShellService.CATEGORY_DAEMON_MODE)
        val connection = UserServiceConnection()
        mainHandler.post { AxShellService.bind(intent, connection) }
        try {
            val service = connection.waitForService()
            val pid = service.pid
            val uid = service.uid
            pass("pid $pid · uid $uid", "pid=$pid\nuid=$uid")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { AxShellService.unbind(connection) }
        }
    },
    AxshTest(
        SHELL_SERVICE_DAEMON, "Stop it",
        "stop(), then bind again: shows the new pid."
    ) {
        val intent = Intent(App.app, DaemonService::class.java)
        intent.addCategory(AxShellService.CATEGORY_DAEMON_MODE)
        mainHandler.post { AxShellService.stop(intent) }

        Thread.sleep(2000)

        val connection = UserServiceConnection()
        mainHandler.post { AxShellService.bind(intent, connection) }
        try {
            val pid = connection.waitForService().pid
            pass("pid after stop: $pid", "pid after stop: $pid")
        } catch (e: Exception) {
            fail(e.toString())
        } finally {
            mainHandler.post { AxShellService.unbind(connection) }
        }
    },
)

private val coldStartTests: List<AxshTest> = listOf(
    AxshTest(
        COLD_START, "First Axsh.exec vs next",
        "Axsh.exec(true) three times: the first one pulls the sticky and verifies the server " +
                "(attach + callback); the next ones reuse it. Needs AxBroker.start. Runs first."
    ) {
        if (!App.directConnection) return@AxshTest info("not run: needs the direct connection (AxBroker.start)")
        val millis = mutableListOf<Double>()
        repeat(3) {
            val start = System.nanoTime()
            val exit = frb.axeron.shell.Axsh.exec(arrayOf("true")).waitFor()
            millis += (System.nanoTime() - start) / 1_000_000.0
            if (exit != 0) fail("exit=$exit", "Axsh.exec(true) exit=$exit")
        }
        info(
            "first %.2fms, next %s, first-minus-next %.2fms".format(
                millis[0], millis.drop(1).joinToString(" ") { "%.2f".format(it) },
                millis[0] - millis[1]
            ),
            "ms each: $millis"
        )
    },
    AxshTest(
        COLD_START, "Exec cost: PATH, absolute, local",
        "Four runs each of: Axsh.exec(true) through PATH, Axsh.exec(/system/bin/true), and " +
                "Runtime.exec(/system/bin/true) in this app's own process, no server involved."
    ) {
        if (!App.directConnection) return@AxshTest info("not run: needs the direct connection (AxBroker.start)")
        fun run(block: () -> java.lang.Process): List<Double> = List(4) {
            val start = System.nanoTime()
            block().waitFor()
            (System.nanoTime() - start) / 1_000_000.0
        }
        val path = run { frb.axeron.shell.Axsh.exec(arrayOf("true")) }
        val absolute = run { frb.axeron.shell.Axsh.exec(arrayOf("/system/bin/true")) }
        val local = run { Runtime.getRuntime().exec(arrayOf("/system/bin/true")) }
        info(
            "path %.1f, absolute %.1f, local %.1f (ms, last of 4)".format(path.last(), absolute.last(), local.last()),
            "path: $path\nabsolute: $absolute\nlocal: $local"
        )
    },
    AxshTest(
        COLD_START, "Sticky (new path)",
        "registerReceiver(null, SERVER_BINDER): the first call, then 5 more. Needs the server running."
    ) {
        val filter = IntentFilter("AxRuntime.SERVER_BINDER")
        val millis = mutableListOf<Double>()
        var binder: android.os.IBinder? = null
        repeat(6) {
            val start = System.nanoTime()
            val sticky = App.app.registerReceiver(null, filter)
            millis += (System.nanoTime() - start) / 1_000_000.0
            binder = sticky?.getBundleExtra("data")?.getBinder("binder")
        }
        val ping = binder?.pingBinder()
        info(
            "first %.2fms, next %s, ping=%s".format(
                millis[0], millis.drop(1).joinToString(" ") { "%.2f".format(it) }, ping
            ),
            "binder: $binder\nms each: $millis"
        )
    },
    AxshTest(
        COLD_START, "Manager relay (old path)",
        "Broadcast REQUEST_BINDER to AxManager and wait for its reply (5s). With AxManager " +
                "force-stopped this includes waking it."
    ) {
        val latch = CountDownLatch(1)
        val start = System.nanoTime()
        var replyMillis = -1.0
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                if (latch.count > 0) {
                    replyMillis = (System.nanoTime() - start) / 1_000_000.0
                    latch.countDown()
                }
                return true
            }
        }
        val intent = Intent("AxRuntime.REQUEST_BINDER")
            .setPackage(App.MANAGER_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("data", Bundle().apply {
                putBinder("binder", receiver)
                putInt("uid", Process.myUid())
            })
        App.app.sendBroadcast(intent)
        val answered = latch.await(5, TimeUnit.SECONDS)
        if (answered) info("reply after %.1fms".format(replyMillis), "reply after $replyMillis ms")
        else fail("no reply in 5000ms", "AxManager didn't answer: not running, or blocked from starting")
    },
)

// Declared last: top-level vals initialize in file order.
val axshTests: List<AxshTest> = coldStartTests + mainTests + daemonTests
