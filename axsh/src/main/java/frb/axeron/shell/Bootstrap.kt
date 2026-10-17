package frb.axeron.shell

import android.content.Intent
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import rikka.hidden.compat.ActivityManagerApis
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * `app_process` entry for apps that run axsh without a broker of their own (Termux executing
 * the manager's libaxsh.so, for instance). The native client starts
 *
 *     app_process -Djava.class.path=<lib dir>/libaxshboot.so /system/bin frb.axeron.shell.Bootstrap <fd> <libaxsh.so> <spawn time>
 *
 * with one end of a socketpair as `<fd>`. The classpath is this class's own R8-shrunk dex (see
 * bootstrapDex in build.gradle.kts), or the whole base.apk for APKs without it, which starts
 * slower. Running as the caller's uid, this process asks the
 * manager for the server binder and serves that session over the fd, so the client sees the
 * same protocol as with an in-app [Broker].
 *
 * When the user turned on [KeepAlive] in the manager, the app keeps a broker on
 * `@axsh.<uid>` so the next axsh runs skip app_process and the broadcast:
 *
 * - **The native daemon** (`libaxsh.so --axsh-daemon`), when the server offers a channel
 *   (IAxeronService.openAxshChannel): this process hands it the channel and exits. The daemon
 *   only passes each client's socket to the server, which serves it; no JVM stays around.
 * - **Otherwise this process** stays as the broker, with the binder cached.
 *
 * Either leaves after the idle time chosen in the manager, or once AxManager was updated, so the
 * next run starts a bootstrap with the new code.
 */
object Bootstrap {

    private const val TAG = "AxshBootstrap"

    /** How often a kept bootstrap checks whether to leave. */
    private const val CHECK_MS = 30_000L

    /** Mirrors DAEMON_FLAG in axsh_protocol.h. */
    private const val DAEMON_FLAG = "--axsh-daemon"

    private enum class Daemon { STARTED, NOT_PERMITTED, UNSUPPORTED }

    @JvmStatic
    fun main(args: Array<String>) {
        val fdNumber = args.getOrNull(0)?.toIntOrNull()
        if (fdNumber == null) {
            System.err.println("axsh: bootstrap needs a socket fd")
            exitProcess(2)
        }
        // The libaxsh.so that started us, for the daemon; older clients don't pass it.
        val axsh = args.getOrNull(1)
        // When it did (uptimeMillis's clock): the cost of app_process up to here.
        args.getOrNull(2)?.toLongOrNull()?.let {
            Log.i(TAG, "JVM up ${SystemClock.uptimeMillis() - it} ms after spawn")
        }

        val socket = try {
            connectedSocket(ParcelFileDescriptor.adoptFd(fdNumber).fileDescriptor)
        } catch (e: Throwable) {
            System.err.println("axsh: bootstrap can't use fd $fdNumber: $e")
            exitProcess(2)
        }

        // Set up the broker as soon as the manager says to keep one, so a second terminal tab
        // started while this first session runs is served by it too.
        val keptMinutes = intArrayOf(KeepAlive.OFF)
        val daemon = AtomicReference<Daemon?>(null)
        val listening = AtomicBoolean(false)
        val starter = AtomicReference<Thread?>(null)
        Broker.serveOne(socket, ::broadcast) { minutes ->
            if (minutes == KeepAlive.OFF || keptMinutes[0] != KeepAlive.OFF) return@serveOne
            keptMinutes[0] = minutes
            // Not on this binder thread: the channel is a call to the server, which needs the
            // binder this reply is still delivering.
            starter.set(thread(name = "axsh-daemon-start") {
                val result = startDaemon(axsh, minutes)
                daemon.set(result)
                if (result == Daemon.UNSUPPORTED) listening.set(Broker.listen())
            })
        }
        starter.get()?.join()

        when (daemon.get()) {
            null -> exitProcess(0) // keep-alive off
            Daemon.STARTED -> exitProcess(0)
            // Asked before the user allowed this app in this very session: try once more.
            Daemon.NOT_PERMITTED -> when (startDaemon(axsh, keptMinutes[0])) {
                Daemon.STARTED, Daemon.NOT_PERMITTED -> exitProcess(0)
                Daemon.UNSUPPORTED -> if (!Broker.listen()) exitProcess(0)
            }
            Daemon.UNSUPPORTED -> if (!listening.get()) exitProcess(0) // the app has its own broker
        }
        // Without the daemon this JVM is the broker, tens of MB: no limit is for the daemon only.
        stay(if (keptMinutes[0] == KeepAlive.NEVER) KeepAlive.SMART else keptMinutes[0])
    }

    /**
     * Starts `libaxsh.so --axsh-daemon <minutes>` with a server channel as its stdin: of our fds,
     * ProcessBuilder only passes 0-2 on.
     */
    private fun startDaemon(axsh: String?, minutes: Int): Daemon {
        if (axsh == null || !File(axsh).canExecute()) return Daemon.UNSUPPORTED
        val channel = try {
            Broker.openChannel()
        } catch (_: SecurityException) {
            return Daemon.NOT_PERMITTED
        } catch (e: Exception) {
            Log.w(TAG, "openAxshChannel failed", e)
            null
        } ?: return Daemon.UNSUPPORTED

        return try {
            channel.use {
                Os.dup2(it.fileDescriptor, 0)
                val process = try {
                    ProcessBuilder(axsh, DAEMON_FLAG, minutes.toString())
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(File("/dev/null"))
                        .redirectError(File("/dev/null"))
                        .start()
                } finally {
                    // Our fd 0 must not keep the channel open: the server sees the daemon go by
                    // its EOF.
                    val devNull = Os.open("/dev/null", OsConstants.O_RDONLY, 0)
                    Os.dup2(devNull, 0)
                    Os.close(devNull)
                }
                Log.i(TAG, "native daemon started ($process), idle limit $minutes")
            }
            Daemon.STARTED
        } catch (e: Exception) {
            Log.w(TAG, "can't start the native daemon", e)
            Daemon.UNSUPPORTED
        }
    }

    // Not the (Intent, userId) overload: it goes sticky for intents without a component, which
    // needs BROADCAST_STICKY, and only the server's shell/root uid has that.
    private fun broadcast(intent: Intent) {
        ActivityManagerApis.broadcastIntent(
            intent, null, null, 0, null, null, null, -1, null,
            false, false, Os.getuid() / 100000
        )
    }

    /** Serves as the app's broker until idle for [minutes] (or never), or until stale. */
    private fun stay(minutes: Int) {
        // Done with the terminal that started us: its stderr would keep the tty open for nothing.
        dropStderr()
        // libaxshboot.so or base.apk: either goes with the old APK's directory on an update.
        val apk = System.getProperty("java.class.path")?.substringBefore(':')
        val idleLimit = if (minutes == KeepAlive.NEVER) Long.MAX_VALUE else minutes * 60_000L
        Log.i(TAG, "kept as broker, idle limit ${if (minutes == KeepAlive.NEVER) "never" else "$minutes min"}")

        while (true) {
            Thread.sleep(CHECK_MS)
            if (apk != null && !File(apk).exists()) {
                // AxManager was updated: let the next run start the new code, finish what runs.
                Log.i(TAG, "$apk is gone, leaving")
                Broker.stop()
                while (Broker.activeSessions > 0) Thread.sleep(CHECK_MS)
                exitProcess(0)
            }
            if (Broker.activeSessions == 0 && Broker.idleMillis >= idleLimit) {
                Log.i(TAG, "idle for $minutes min, leaving")
                exitProcess(0)
            }
        }
    }

    private fun dropStderr() {
        try {
            val devNull = Os.open("/dev/null", OsConstants.O_WRONLY, 0)
            Os.dup2(devNull, 2)
            Os.close(devNull)
        } catch (e: Exception) {
            Log.w(TAG, "can't drop stderr", e)
        }
    }

    // LocalSocket(FileDescriptor) is hidden, but app_process has no hidden API enforcement.
    private fun connectedSocket(fd: FileDescriptor): LocalSocket =
        LocalSocket::class.java.getDeclaredConstructor(FileDescriptor::class.java)
            .apply { isAccessible = true }
            .newInstance(fd)
}
