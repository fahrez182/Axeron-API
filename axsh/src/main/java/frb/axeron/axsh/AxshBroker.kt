package frb.axeron.axsh

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import frb.axeron.server.IAxeronService
import frb.axeron.shared.ShizukuApiConstant
import moe.shizuku.server.IShizukuApplication
import rikka.hidden.compat.PackageManagerApis
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-app half of `axsh` for when the server's own socket is out of reach.
 *
 * SELinux lets an app connect to its own sockets, but not to one owned by the server's domain
 * (`shell` in ADB mode). This broker listens on `@axsh.<uid>` and serves [AxshSessions] there,
 * reaching the server over binder, which checks it against this app's uid.
 *
 * The binder comes from the host when it holds one (the manager), otherwise from the manager the
 * same way axerish got it: an `AxRuntime.REQUEST_BINDER` broadcast answered by
 * `ShellBinderRequestHandler`.
 */
object AxshBroker : AxshSessions.Host {

    override val tag = "AxshBroker"

    // Mirrors ServerConstants.REQUEST_BINDER_AXRUNTIME and the manager's package.
    private const val ACTION_REQUEST_BINDER = "AxRuntime.REQUEST_BINDER"
    private const val MANAGER_PACKAGE = "frb.axeron.manager"
    private const val BINDER_TIMEOUT_SECONDS = 5L

    private const val PERMISSION_REQUEST_CODE = 0x4158

    private val sessions = AxshSessions(this)

    /** How the binder request reaches the manager: Context in an app, ActivityManager in app_process. */
    @Volatile
    private var sendRequest: ((Intent) -> Unit)? = null

    /** Set when the host already holds the server binder; then nothing is asked of the manager. */
    @Volatile
    private var binderSource: (() -> IBinder?)? = null

    @Volatile
    private var service: IAxeronService? = null

    /** Abstract socket name the native client looks for; per-uid so apps never share a broker. */
    fun socketName(uid: Int = Process.myUid()) = "axsh.$uid"

    /**
     * @param binder where the host keeps the server binder, if it has one (the manager does);
     *   null to ask the manager for it with an `AxRuntime.REQUEST_BINDER` broadcast. The manager
     *   must not use the broadcast: its own receiver would wait behind a main thread that may be
     *   blocked on the very shell being started.
     */
    @Synchronized
    fun start(context: Context, binder: (() -> IBinder?)? = null) {
        // From Application.attachBaseContext, applicationContext is still null (the app isn't
        // registered yet); the Application itself is the context to keep then.
        val appContext = context.applicationContext ?: context
        sendRequest = { appContext.sendBroadcast(it) }
        packageName = context.packageName
        binderSource = binder
        sessions.listen(socketName())
    }

    fun stop() = sessions.stop()

    /**
     * Serves one already-connected client on the calling thread, for [AxshBootstrap]: no
     * listener, and the binder request goes out through [sender].
     */
    internal fun serveOne(socket: LocalSocket, sender: (Intent) -> Unit) {
        sendRequest = sender
        sessions.serve(socket)
    }

    // ---- AxshSessions.Host ----

    // The broker spends this app's AxManager grant, so only this app's own processes may use it.
    override fun refuse(uid: Int, pid: Int): String? =
        if (uid == Process.myUid()) null else "Not this app's process"

    override fun service(): IAxeronService? {
        binderSource?.let { source ->
            return source()?.takeIf { it.pingBinder() }?.let { IAxeronService.Stub.asInterface(it) }
        }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        synchronized(this) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            val binder = requestBinder() ?: return null
            return IAxeronService.Stub.asInterface(binder).also { service = it }
        }
    }

    // ---- permission ----

    /** One dialog in flight; every session refused meanwhile waits on the same answer. */
    private class PermissionWait {
        val latch = CountDownLatch(1)

        @Volatile
        var allowed = false
    }

    private val permissionLock = Any()
    private var permissionWait: PermissionWait? = null

    @Volatile
    private var attachedTo: IBinder? = null

    /** Our package, for attachApplication: set by [start] in an app, looked up by uid otherwise. */
    @Volatile
    private var packageName: String? = null

    /**
     * Receives the dialog's answer. The server only reports it to an attached client whose pid
     * asked, so the broker attaches itself before asking: an event, nothing is polled.
     */
    private val application = object : IShizukuApplication.Stub() {
        override fun bindApplication(data: Bundle?) {}

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
            if (requestCode != PERMISSION_REQUEST_CODE) return
            answerPermission(data?.getBoolean(ShizukuApiConstant.REQUEST_PERMISSION_REPLY_ALLOWED) == true)
        }

        override fun showPermissionConfirmation(
            requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int
        ) {
        }
    }

    override fun awaitPermission(service: IAxeronService): Boolean {
        var ask = false
        val wait = synchronized(permissionLock) {
            permissionWait ?: PermissionWait().also {
                permissionWait = it
                ask = true
            }
        }
        if (ask) {
            try {
                attach(service)
                service.requestPermission(PERMISSION_REQUEST_CODE)
                Log.i(tag, "permission requested, waiting up to ${AxshSessions.PERMISSION_WAIT_MS}ms")
            } catch (e: Exception) {
                Log.w(tag, "requestPermission failed", e)
                answerPermission(false)
            }
        }

        val answered = wait.latch.await(AxshSessions.PERMISSION_WAIT_MS, TimeUnit.MILLISECONDS)
        if (!answered) {
            // Unanswered (dismissed or left open): the next client asks again.
            synchronized(permissionLock) {
                if (permissionWait === wait) permissionWait = null
            }
            Log.w(tag, "no answer to the permission request")
        }
        return answered && wait.allowed
    }

    private fun answerPermission(allowed: Boolean) {
        val wait = synchronized(permissionLock) {
            permissionWait.also { permissionWait = null }
        } ?: return
        wait.allowed = allowed
        wait.latch.countDown()
    }

    /** Once per server binder: the record lives until this process (or the server) dies. */
    private fun attach(service: IAxeronService) {
        val binder = service.asBinder()
        if (attachedTo === binder) return
        val name = packageName
            ?: PackageManagerApis.getPackagesForUidNoThrow(Process.myUid()).firstOrNull()
            ?: throw IllegalStateException("no package for uid ${Process.myUid()}")
        val args = Bundle().apply {
            putString(ShizukuApiConstant.ATTACH_APPLICATION_PACKAGE_NAME, name)
            putInt(ShizukuApiConstant.ATTACH_APPLICATION_API_VERSION, -1)
        }
        service.attachApplication(application, args)
        attachedTo = binder
    }

    // ---- binder ----

    /** Asks the manager for the server binder; the reply arrives as transaction 1 on [receiver]. */
    private fun requestBinder(): IBinder? {
        val latch = CountDownLatch(1)
        var received: IBinder? = null
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                received = data.readStrongBinder()
                latch.countDown()
                return true
            }
        }

        val intent = Intent(ACTION_REQUEST_BINDER)
            .setPackage(MANAGER_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", Bundle().apply { putBinder("binder", receiver) })
        try {
            val sender = sendRequest ?: return null
            sender(intent)
        } catch (e: Exception) {
            Log.w(tag, "binder request failed", e)
            return null
        }

        if (!latch.await(BINDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(tag, "no binder from $MANAGER_PACKAGE")
            return null
        }
        val binder = received
        if (binder == null || !binder.pingBinder()) {
            Log.w(tag, "manager replied without a live binder")
            return null
        }
        return binder
    }
}
