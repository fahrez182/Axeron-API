package frb.axeron.api.core

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import frb.axeron.api.Axeron
import java.io.File
import kotlin.system.exitProcess


open class Engine: Application() {

    companion object {
        @JvmStatic
        lateinit var application: Engine
            private set
    }

    private fun saveCrashLog(t: Throwable) {
        try {
            val logFile = File(externalCacheDir, "crash.log")
            logFile.appendText(
                "\n=== Crash at ${System.currentTimeMillis()} ===\n" +
                        Log.getStackTraceString(t) + "\n"
            )
        } catch (e: Exception) {
            // gagal nulis log
            Log.e("Engine", "Failed to save crash log", e)
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // log error
            Log.e("Engine", "Uncaught exception in thread ${thread.name}", throwable)
            Log.i("Engine", "Force stop AxeronService")
            if (Axeron.pingBinder()) {
                Axeron.destroy()
            }
            // contoh: simpan ke file log
            saveCrashLog(throwable)

            Process.killProcess(Process.myPid())
            exitProcess(1) // exit code bebas
        }
    }
}