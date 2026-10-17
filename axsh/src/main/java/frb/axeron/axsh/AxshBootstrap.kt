package frb.axeron.axsh

import android.content.Intent
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import rikka.hidden.compat.ActivityManagerApis
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * `app_process` entry for apps that run axsh without a broker of their own (Termux executing
 * the manager's libaxsh.so, for instance). The native client starts
 *
 *     app_process -Djava.class.path=<manager base.apk> /system/bin frb.axeron.axsh.AxshBootstrap <fd>
 *
 * with one end of a socketpair as `<fd>`. Running as the caller's uid, this process asks the
 * manager for the server binder and serves that session over the fd, so the client sees the
 * same protocol as with an in-app [AxshBroker].
 *
 * When the user turned on [AxshKeepAlive] in the manager, it then stays on as the app's broker
 * (`@axsh.<uid>`) with the binder cached: the next axsh runs connect straight to it, with no new
 * app_process and no broadcast. It leaves after the idle time chosen there, or once AxManager
 * was updated (its APK gone), so the next run starts a bootstrap with the new code.
 */
object AxshBootstrap {

    private const val TAG = "AxshBootstrap"

    /** How often a kept bootstrap checks whether to leave. */
    private const val CHECK_MS = 30_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val fdNumber = args.getOrNull(0)?.toIntOrNull()
        if (fdNumber == null) {
            System.err.println("axsh: bootstrap needs a socket fd")
            exitProcess(2)
        }

        val socket = try {
            connectedSocket(ParcelFileDescriptor.adoptFd(fdNumber).fileDescriptor)
        } catch (e: Throwable) {
            System.err.println("axsh: bootstrap can't use fd $fdNumber: $e")
            exitProcess(2)
        }

        // Listen as soon as the manager says to stay, so a second terminal tab started while
        // this first session runs is served here too.
        val keptMinutes = intArrayOf(AxshKeepAlive.OFF)
        val listening = AtomicBoolean(false)
        AxshBroker.serveOne(socket, ::broadcast) { minutes ->
            if (minutes == AxshKeepAlive.OFF || !listening.compareAndSet(false, true)) return@serveOne
            keptMinutes[0] = minutes
            if (!AxshBroker.listen()) listening.set(false) // the app runs a broker of its own
        }

        if (!listening.get()) exitProcess(0)
        stay(keptMinutes[0])
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
        val apk = System.getProperty("java.class.path")?.substringBefore(':')
        val idleLimit = if (minutes == AxshKeepAlive.NEVER) Long.MAX_VALUE else minutes * 60_000L
        Log.i(TAG, "kept as broker, idle limit ${if (minutes == AxshKeepAlive.NEVER) "never" else "$minutes min"}")

        while (true) {
            Thread.sleep(CHECK_MS)
            if (apk != null && !File(apk).exists()) {
                // AxManager was updated: let the next run start the new code, finish what runs.
                Log.i(TAG, "$apk is gone, leaving")
                AxshBroker.stop()
                while (AxshBroker.activeSessions > 0) Thread.sleep(CHECK_MS)
                exitProcess(0)
            }
            if (AxshBroker.activeSessions == 0 && AxshBroker.idleMillis >= idleLimit) {
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
