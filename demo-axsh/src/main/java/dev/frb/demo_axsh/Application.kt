package dev.frb.demo_axsh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.topjohnwu.superuser.Shell
import frb.axeron.axsh.AxshBroker
import java.io.File
import kotlin.system.exitProcess

/**
 * Two ways an app can run axsh, picked by [directConnection] (prefs key `axsh_direct_connection`):
 *
 * - **true**: the axsh dependency. Our own libaxsh.so, served by [AxshBroker] listening in this
 *   process: the binder is fetched once and every shell after that is a socket connect away.
 * - **false**: no broker. The manager's libaxsh.so, as Termux runs it: each shell starts a
 *   bootstrap in app_process that asks the manager for the binder, then exits.
 *
 * Read once at start: a listening broker can't reliably be torn down (close doesn't always wake
 * accept), so changing it restarts the app.
 */
class Application : android.app.Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        directConnection = prefs(this).getBoolean(KEY_DIRECT_CONNECTION, DEFAULT_DIRECT_CONNECTION)

        if (directConnection) AxshBroker.start(this)
        axsh = if (directConnection) ownAxsh() else managerAxsh()
        Log.i(TAG, "direct=$directConnection axsh=${axsh?.absolutePath} (exists=${axsh?.exists()})")

        val path = axsh?.absolutePath ?: return
        Shell.enableLegacyStderrRedirection = true
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setCommands(path))
    }

    private fun ownAxsh() = File(applicationInfo.nativeLibraryDir, "libaxsh.so")

    // Its path changes on every manager update, so it is resolved on each start rather than stored.
    private fun managerAxsh(): File? = try {
        File(packageManager.getApplicationInfo(MANAGER_PACKAGE, 0).nativeLibraryDir, "libaxsh.so")
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e(TAG, "$MANAGER_PACKAGE is not installed")
        null
    }

    companion object {
        private const val TAG = "Axsh"
        const val MANAGER_PACKAGE = "frb.axeron.manager"

        private const val KEY_DIRECT_CONNECTION = "axsh_direct_connection"
        const val DEFAULT_DIRECT_CONNECTION = true

        lateinit var app: Application
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
