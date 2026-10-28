package dev.frb.demo_axsh

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.topjohnwu.superuser.Shell
import frb.axeron.shell.AxBroker
import frb.axeron.shell.AxShellService
import java.io.File
import kotlin.system.exitProcess

/**
 * Two ways an app can run axsh, picked by [directConnection] (prefs key `axsh_direct_connection`):
 *
 * - **true**: the axsh dependency. Our own libaxsh.so, served by [AxBroker] listening in this
 *   process: the binder is fetched once and every shell after that is a socket connect away.
 * - **false**: no broker. The manager's libaxsh.so, as Termux runs it: each shell starts a
 *   bootstrap in app_process that asks the manager for the binder, then exits.
 *
 * Read once at start: a listening broker can't reliably be torn down (close doesn't always wake
 * accept), so changing it restarts the app.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        directConnection = prefs(this).getBoolean(KEY_DIRECT_CONNECTION, DEFAULT_DIRECT_CONNECTION)

        if (directConnection) AxBroker.start(this)
        axsh = if (directConnection) ownAxsh() else managerAxsh()
        Log.i(TAG, "direct=$directConnection axsh=${axsh?.absolutePath} (exists=${axsh?.exists()})")

        val path = axsh?.absolutePath ?: return
        // No broker here, so AxShellService has no Axsh.exec to launch with: it runs the manager's
        // libaxsh.so itself, as a no-broker app does.
        if (!directConnection) AxShellService.setLauncher { argv ->
            val command = argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            ProcessBuilder(path, "-c", "exec $command").start()
        }
        Shell.enableLegacyStderrRedirection = true
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setCommands(path))
    }

    private fun ownAxsh() = File(applicationInfo.nativeLibraryDir, "libaxsh.so")

    // Found by the API, not by a package name. Its path changes on every manager update, so it is
    // resolved on each start rather than stored.
    private fun managerAxsh(): File? {
        val manager = AxBroker.findManager(this)
        if (manager == null) {
            Log.e(TAG, "no AxManager found (not installed, or in stealth mode)")
            return null
        }
        return File(manager.nativeLibraryDir, "libaxsh.so")
    }

    companion object {
        private const val TAG = "Axsh"

        private const val KEY_DIRECT_CONNECTION = "axsh_direct_connection"
        const val DEFAULT_DIRECT_CONNECTION = true

        lateinit var app: App
            private set

        /** The mode this process started in. */
        var directConnection = DEFAULT_DIRECT_CONNECTION
            private set

        /** The libaxsh.so this mode runs; null when it can't be found (manager not installed). */
        var axsh: File? = null
            private set

        private fun prefs(context: Context) = context.getSharedPreferences("demo", MODE_PRIVATE)

        /** Saves the new mode and restarts the app into it. */
        fun switchDirectConnection(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_DIRECT_CONNECTION, enabled).commit()
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                context.startActivity(Intent.makeRestartActivityTask(it.component))
            }
            exitProcess(0)
        }
    }
}
