package dev.frb.demo_axsh

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.system.Os
import com.topjohnwu.superuser.ipc.RootService
import frb.axeron.shell.AxShellService
import java.io.File

// A static. Services in the same process see the same value.
object SharedCounter {
    var value = 0
}

// ============================================================================================
// 1. The service. Everything inside runs in the service's own process, as uid 2000 (ADB mode)
//    or uid 0 (root mode).
// ============================================================================================

class UserService1 : AxShellService() {

    override fun onBind(intent: Intent): IBinder {
        return object : IUserService.Stub() {

            override fun getUid(): Int {
                return Os.getuid()
            }

            override fun getPid(): Int {
                return Os.getpid()
            }

            override fun getSeLinuxContext(): String {
                return File("/proc/self/attr/current").readText().trim('\u0000', '\n')
            }

            override fun exec(command: String): String {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                return output.trim()
            }

            override fun getOpPackageName(): String {
                return this@UserService1.opPackageName
            }

            override fun hasPrimaryClip(): Boolean {
                val clipboard = getSystemService(ClipboardManager::class.java)
                return clipboard.hasPrimaryClip()
            }

            override fun getApplicationContextName(): String {
                return applicationContext?.toString() ?: "null"
            }

            override fun incrementSharedCounter(): Int {
                SharedCounter.value = SharedCounter.value + 1
                return SharedCounter.value
            }
        }
    }
}

// A second service, the very same code. It runs in the same process as UserService1.
class UserService2 : AxShellService() {

    override fun onBind(intent: Intent): IBinder {
        return object : IUserService.Stub() {

            override fun getUid(): Int {
                return Os.getuid()
            }

            override fun getPid(): Int {
                return Os.getpid()
            }

            override fun getSeLinuxContext(): String {
                return File("/proc/self/attr/current").readText().trim('\u0000', '\n')
            }

            override fun exec(command: String): String {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                return output.trim()
            }

            override fun getOpPackageName(): String {
                return this@UserService2.opPackageName
            }

            override fun hasPrimaryClip(): Boolean {
                val clipboard = getSystemService(ClipboardManager::class.java)
                return clipboard.hasPrimaryClip()
            }

            override fun getApplicationContextName(): String {
                return applicationContext?.toString() ?: "null"
            }

            override fun incrementSharedCounter(): Int {
                SharedCounter.value = SharedCounter.value + 1
                return SharedCounter.value
            }
        }
    }
}

// A daemon. Bound with an intent that has AxShellService.CATEGORY_DAEMON_MODE, it runs in the
// daemon process, which keeps running after this app is closed. onUnbind returns true, so the
// service stays there too, and the next bind reaches it again and calls onRebind.
class DaemonService : AxShellService() {

    override fun onUnbind(intent: Intent): Boolean {
        return true
    }

    override fun onRebind(intent: Intent) {
        android.util.Log.i("DaemonService", "onRebind in pid " + Os.getpid())
    }

    override fun onBind(intent: Intent): IBinder {
        return object : IUserService.Stub() {

            override fun getUid(): Int {
                return Os.getuid()
            }

            override fun getPid(): Int {
                return Os.getpid()
            }

            override fun getSeLinuxContext(): String {
                return File("/proc/self/attr/current").readText().trim('\u0000', '\n')
            }

            override fun exec(command: String): String {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                return output.trim()
            }

            override fun getOpPackageName(): String {
                return this@DaemonService.opPackageName
            }

            override fun hasPrimaryClip(): Boolean {
                val clipboard = getSystemService(ClipboardManager::class.java)
                return clipboard.hasPrimaryClip()
            }

            override fun getApplicationContextName(): String {
                return applicationContext?.toString() ?: "null"
            }

            override fun incrementSharedCounter(): Int {
                SharedCounter.value = SharedCounter.value + 1
                return SharedCounter.value
            }
        }
    }
}

// The same thing with libsu's RootService, to compare (root mode only).
class RootUserService : RootService() {

    override fun onBind(intent: Intent): IBinder {
        return object : IUserService.Stub() {

            override fun getUid(): Int {
                return Os.getuid()
            }

            override fun getPid(): Int {
                return Os.getpid()
            }

            override fun getSeLinuxContext(): String {
                return File("/proc/self/attr/current").readText().trim('\u0000', '\n')
            }

            override fun exec(command: String): String {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                return output.trim()
            }

            override fun getOpPackageName(): String {
                return this@RootUserService.opPackageName
            }

            override fun hasPrimaryClip(): Boolean {
                val clipboard = getSystemService(ClipboardManager::class.java)
                return clipboard.hasPrimaryClip()
            }

            override fun getApplicationContextName(): String {
                return applicationContext?.toString() ?: "null"
            }

            override fun incrementSharedCounter(): Int {
                SharedCounter.value = SharedCounter.value + 1
                return SharedCounter.value
            }
        }
    }
}

// ============================================================================================
// 2. The connection, in the app. A plain ServiceConnection: the binder arrives in
//    onServiceConnected, on the main thread.
// ============================================================================================

class UserServiceConnection : ServiceConnection {

    @Volatile
    var service: IUserService? = null

    @Volatile
    var error: String? = null

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = IUserService.Stub.asInterface(binder)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    override fun onBindingDied(name: ComponentName) {
        error = "binding died: " + AxShellService.lastFailure(name)
    }

    override fun onNullBinding(name: ComponentName) {
        error = "null binding: " + AxShellService.lastFailure(name)
    }

    /** For the tests only, which run off the main thread: waits until connected (or 20 s). */
    fun waitForService(): IUserService {
        repeat(200) {
            service?.let { return it }
            error?.let { throw IllegalStateException(it) }
            Thread.sleep(100)
        }
        throw IllegalStateException("not connected after 20 s")
    }
}
