package frb.axeron.shell

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.LocalSocket
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import frb.axeron.server.IAxeronService
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.ShizukuApiConstant
import moe.shizuku.server.IShizukuApplication
import rikka.hidden.compat.PackageManagerApis
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-app half of `axsh` for when the server's own socket is out of reach.
 *
 * SELinux lets an app connect to its own sockets, but not to one owned by the server's domain
 * (`shell` in ADB mode). This broker listens on `@axsh.<uid>` and serves [Sessions] there,
 * reaching the server over binder, which checks it against this app's uid.
 *
 * The binder comes from the host when it holds one (the manager), otherwise it is pulled from the
 * server's `AxRuntime.SERVER_BINDER` sticky broadcast (an app with a Context), or, failing that
 * and for an app_process client that can't read a sticky ([Bootstrap]), asked of the manager with
 * an `AxRuntime.REQUEST_BINDER` broadcast.
 */
object AxBroker : Sessions.Host {

    @InternalAxeronApi
    override val tag = "AxshBroker"

    // Mirrors AxeronService.SERVER_BINDER_ACTION: the server's sticky binder, read in-app.
    private const val ACTION_SERVER_BINDER = "AxRuntime.SERVER_BINDER"

    // Mirrors ServerConstants.REQUEST_BINDER_AXRUNTIME: the relay for an app_process client
    // ([Bootstrap]), which has no app process in AMS and so can't read a sticky.
    private const val ACTION_REQUEST_BINDER = "AxRuntime.REQUEST_BINDER"
    private const val BINDER_TIMEOUT_MS = 5_000L

    // How long a binder has to call back as the server; a real one does within a few ms.
    private const val VERIFY_TIMEOUT_MS = 2_000L

    private const val PERMISSION_REQUEST_CODE = 0x4158

    private val sessions = Sessions(this)

    /** How the binder request reaches the manager from app_process ([Bootstrap]). */
    @Volatile
    private var sendRequest: ((Intent) -> Unit)? = null

    /**
     * The manager to ask for the binder from app_process: the package whose libaxsh this
     * process was started from, which [Bootstrap] works out. Null for an app, which reads the
     * server's sticky instead and names no manager.
     */
    @Volatile
    private var relayPackage: String? = null

    /** Where an app reads the server's sticky binder from; null in app_process. */
    @Volatile
    private var stickyContext: Context? = null

    /** Set when the host already holds the server binder; then nothing is asked of the manager. */
    @Volatile
    private var binderSource: (() -> IBinder?)? = null

    @Volatile
    private var service: IAxeronService? = null

    /** Abstract socket name the native client looks for; per-uid so apps never share a broker. */
    @InternalAxeronApi
    fun socketName(uid: Int = Process.myUid()) = "axsh.$uid"

    /**
     * @param binder where the host keeps the server binder, if it has one (the manager does);
     *   null to read it from the server's `AxRuntime.SERVER_BINDER` sticky broadcast.
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, binder: (() -> IBinder?)? = null) {
        // On the object, as @Synchronized was before @JvmStatic (which would lock the class):
        // the same monitor service() takes.
        synchronized(this) {
            // From Application.attachBaseContext, applicationContext is still null (the app isn't
            // registered yet); the Application itself is the context to keep then.
            val appContext = context.applicationContext ?: context
            stickyContext = appContext
            packageName = context.packageName
            binderSource = binder
            sessions.listen(socketName())
        }
    }

    @JvmStatic
    fun stop() = sessions.stop()

    /**
     * Serves one already-connected client on the calling thread, for [Bootstrap]: no
     * listener, and the binder request goes to [manager] through [sender]. [onKeepAlive] gets
     * the manager's [KeepAlive] setting as soon as its binder reply arrives.
     */
    internal fun serveOne(
        socket: LocalSocket, manager: String?, sender: (Intent) -> Unit, onKeepAlive: (Int) -> Unit = {}
    ) {
        relayPackage = manager
        sendRequest = sender
        keepAliveListener = onKeepAlive
        sessions.serve(socket)
    }

    /** For [Bootstrap] staying on: listen on this app's broker socket. False when taken. */
    internal fun listen(): Boolean = sessions.listen(socketName())

    /** Sessions running now, and how long none have (0 while some are). */
    internal val activeSessions: Int get() = sessions.active

    internal val idleMillis: Long get() = sessions.idleMillis

    /**
     * A channel into the server's own axsh sessions (IAxeronService.openAxshChannel), for
     * [Bootstrap] to hand to the native daemon. Null when the server is out of reach or too
     * old to have one; throws [SecurityException] while this app isn't allowed yet.
     */
    internal fun openChannel(): ParcelFileDescriptor? = service()?.openAxshChannel()

    /** The manager's keep-alive setting, from its binder reply; for [Bootstrap]. */
    @Volatile
    private var keepAliveListener: ((Int) -> Unit)? = null

    // ---- Sessions.Host ----

    // The broker spends this app's AxManager grant, so only this app's own processes may use it.
    @InternalAxeronApi
    override fun refuse(uid: Int, pid: Int): String? =
        if (uid == Process.myUid()) null else "Not this app's process"

    /** Why the last [service] came back null, shown to the client in place of a bare error. */
    @Volatile
    private var unavailable: String? = null

    @InternalAxeronApi
    override fun unavailableReason(): String? = unavailable

    @InternalAxeronApi
    override fun service(): IAxeronService? {
        // The host's own binder first; while it has none (a cold process the server hasn't pushed
        // to yet) the sticky below answers instead of failing the first call.
        binderSource?.let { source ->
            source()?.takeIf { it.pingBinder() }?.let { return IAxeronService.Stub.asInterface(it) }
        }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        synchronized(this) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            val binder = requestBinder() ?: return null
            val candidate = IAxeronService.Stub.asInterface(binder)
            if (!fromServer(candidate)) return null
            return candidate.also { service = it }
        }
    }

    /**
     * A sticky, or a manager's relay, is only as good as whoever could write it: a binder from
     * there may not be the server's, and this one is handed the app's shells and their pipes.
     * What can't be forged is the uid of a call into this process, so the binder is attached and
     * must call [application] back from root or shell, the only uids a server runs as.
     */
    private fun fromServer(candidate: IAxeronService): Boolean {
        val handshake = Handshake()
        this.handshake = handshake
        try {
            attachedTo = null
            attach(candidate)
            if (!handshake.latch.await(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                unavailable = "the binder never called back, so it isn't known to be the server's"
                return false
            }
        } catch (e: Exception) {
            Log.w(tag, "can't attach to the binder", e)
            unavailable = "can't attach to the binder: ${e.message ?: e}"
            return false
        } finally {
            this.handshake = null
        }
        if (!handshake.fromServer) {
            unavailable = "the binder isn't the server's: it called back from an app, not root or shell"
            attachedTo = null
            return false
        }
        unavailable = null
        return true
    }

    /** The server's call back on [application] during [fromServer], and whose uid made it. */
    private class Handshake {
        val latch = CountDownLatch(1)

        @Volatile
        var fromServer = false
    }

    @Volatile
    private var handshake: Handshake? = null

    private fun callerIsServer(): Boolean {
        val uid = Binder.getCallingUid()
        return uid == Process.ROOT_UID || uid == Process.SHELL_UID
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
        override fun bindApplication(data: Bundle?) {
            // Called back from root or shell, and for an attach we made: a binder relaying ours to
            // the real server would be named here as the uid that attached.
            val clientUid = data?.getInt(AxeronApiConstant.server.BIND_APPLICATION_CLIENT_UID, -1) ?: -1
            val fromServer = callerIsServer() && (clientUid == -1 || clientUid == Process.myUid())
            if (!fromServer) {
                Log.w(tag, "bindApplication from uid ${Binder.getCallingUid()} for uid $clientUid, not the server's for us")
            }
            handshake?.let {
                it.fromServer = fromServer
                it.latch.countDown()
            }
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
            if (requestCode != PERMISSION_REQUEST_CODE || !callerIsServer()) return
            answerPermission(data?.getBoolean(ShizukuApiConstant.REQUEST_PERMISSION_REPLY_ALLOWED) == true)
        }

        override fun showPermissionConfirmation(
            requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int
        ) {
        }
    }

    @InternalAxeronApi
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
                Log.i(tag, "permission requested, waiting up to ${Sessions.PERMISSION_WAIT_MS}ms")
            } catch (e: Exception) {
                Log.w(tag, "requestPermission failed", e)
                answerPermission(false)
            }
        }

        val answered = wait.latch.await(Sessions.PERMISSION_WAIT_MS, TimeUnit.MILLISECONDS)
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

    /**
     * The server binder: pulled from its sticky when this is an app with a Context, which names
     * no manager. An app_process client (no Context, no app process in AMS) can't read one and
     * asks the manager it was started from instead.
     */
    private fun requestBinder(): IBinder? {
        stickyContext?.let { context ->
            val binder = readStickyBinder(context)
            if (binder != null) unavailable = null
            return binder
        }
        return requestBinderFromManager()
    }

    /**
     * Reads the server's binder from its sticky broadcast (AxeronService.publishBinder):
     * registerReceiver with a null receiver just returns the sticky intent system_server holds,
     * so nothing is delivered, nobody is woken and there is nothing to wait for. Needs a real app
     * process, so only [start]'s Context path; a stale sticky holds a dead binder, dropped here.
     */
    private fun readStickyBinder(context: Context): IBinder? {
        val sticky = try {
            context.registerReceiver(null, IntentFilter(ACTION_SERVER_BINDER))
        } catch (e: Exception) {
            unavailable = "can't read the server's sticky binder: ${e.message ?: e}"
            Log.w(tag, "sticky read failed", e)
            return null
        }
        val binder = sticky?.getBundleExtra("data")?.getBinder("binder")
        if (binder == null) {
            unavailable = "the server left no binder (not running, or too old to publish one)"
            return null
        }
        if (!binder.pingBinder()) {
            unavailable = "the server's binder is dead (server stopped)"
            return null
        }
        Log.i(tag, "binder from the server's sticky")
        return binder
    }

    /** Asks the manager for the server binder; the reply arrives as transaction 1 on [receiver]. */
    private fun requestBinderFromManager(): IBinder? {
        val manager = relayPackage ?: run {
            unavailable = "no way to reach the server (no broker started, or an AxManager install " +
                "path that doesn't name its package)"
            return null
        }
        val start = SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var received: IBinder? = null
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                if (latch.count == 0L) return true
                received = data.readStrongBinder()
                // Then the version code, the library dir and, from newer managers, the
                // keep-alive setting; an older reply simply ends early.
                var keepAlive = KeepAlive.OFF
                runCatching {
                    data.readLong()
                    data.readString()
                    if (data.dataAvail() >= 4) keepAlive = data.readInt()
                }
                keepAliveListener?.invoke(keepAlive)
                latch.countDown()
                return true
            }
        }

        val intent = Intent(ACTION_REQUEST_BINDER)
            .setPackage(manager)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // We're usually a background app: the background queue can hold this for seconds.
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("data", Bundle().apply {
                putBinder("binder", receiver)
                // Which app's keep-alive setting to answer with (see KeepAlive).
                putInt(KeepAlive.EXTRA_UID, Process.myUid())
            })
        try {
            val sender = sendRequest ?: run {
                unavailable = "no way to reach the server (no broker started)"
                return null
            }
            sender(intent)
            Log.i(tag, "binder asked of $manager")
        } catch (e: Exception) {
            unavailable = "binder request to the manager failed: ${e.message ?: e}"
            Log.w(tag, "binder request failed", e)
            return null
        }

        if (!latch.await(BINDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            unavailable = "the manager didn't answer (is AxManager running?)"
            Log.w(tag, "no binder from $manager")
            return null
        }
        val binder = received
        if (binder == null || !binder.pingBinder()) {
            unavailable = "the manager replied without a live binder"
            Log.w(tag, "manager replied without a live binder")
            return null
        }
        unavailable = null
        Log.i(tag, "binder from $manager in ${SystemClock.uptimeMillis() - start} ms")
        return binder
    }
}

/** The name up to 2.1.0; renamed so the public API all starts with Ax. */
@Deprecated("Renamed to AxBroker", ReplaceWith("AxBroker", "frb.axeron.shell.AxBroker"))
typealias Broker = AxBroker
