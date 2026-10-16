package dev.frb.demo_axsh

import android.content.pm.PackageManager
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlin.concurrent.thread

class Application : android.app.Application() {

    override fun onCreate() {
        super.onCreate()
        // No axsh dependency: exec the manager's own libaxsh.so. Its path changes on every manager
        // update, so it is resolved on each start rather than stored.
        val axsh = managerAxsh()
        Log.d("Axsh", "axsh path: ${axsh?.absolutePath} (exists=${axsh?.exists()})")
        if (axsh == null) return
        probe(axsh)
        Shell.enableLegacyStderrRedirection = true
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().run {
            setCommands(axsh.absolutePath)
        })
    }

    private fun managerAxsh(): File? = try {
        val info = packageManager.getApplicationInfo(MANAGER_PACKAGE, 0)
        File(info.nativeLibraryDir, "libaxsh.so")
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e("Axsh", "$MANAGER_PACKAGE is not installed")
        null
    }

    // libsu drops the shell's stderr, so run axsh once on its own to see why it fails, if it does.
    private fun probe(axsh: File) = thread(name = "axsh-probe") {
        try {
            val process = ProcessBuilder(axsh.absolutePath, "-c", "id")
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText().trim()
            Log.i("Axsh", "probe exit=${process.waitFor()} output=$output")
        } catch (e: Exception) {
            Log.e("Axsh", "probe failed", e)
        }
    }

    private companion object {
        const val MANAGER_PACKAGE = "frb.axeron.manager"
    }
}
