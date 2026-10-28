package frb.axeron.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.net.LocalSocket
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import frb.axeron.server.IAxeronService
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.ShizukuApiConstant
import moe.shizuku.server.IShizukuApplication
import rikka.hidden.compat.PackageManagerApis
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-app half of `axsh` for when the server's own socket is out of reach.
 *
 * SELinux lets an app connect to its own sockets, but not to one owned by the server's domain
 * (`shell` in ADB mode). This broker listens on `@axsh.<uid>` and serves [Sessions] there,
 * reaching the server over binder, which checks it against this app's uid.
 *
 * The binder comes from the host when it holds one (the manager). Otherwise an app has its
 * capability pushed in by the server once AxManager allows it ([AxshProvider]): nothing is
 * published for an app to find. An app_process client ([Bootstrap]), which has no provider, asks
 * the manager with an `AxRuntime.REQUEST_BINDER` broadcast.
 */
object AxBroker : Sessions.Host {

    @InternalAxeronApi
    override val tag = "AxshBroker"

    // Mirrors ServerConstants.REQUEST_BINDER_AXRUNTIME: the relay for an app_process client
    // ([Bootstrap]), which has no provider for the server to push to.
    private const val ACTION_REQUEST_BINDER = "AxRuntime.REQUEST_BINDER"
    private const val BINDER_TIMEOUT_MS = 5_000L

    // How long a cold app's first call waits for the server's push, which it can beat.
    private const val PUSH_WAIT_MS = 3_000L

    // Mirrors SERVER_NAME_FILE in axsh_protocol.h: where, in the host's $TMPDIR, the native
    // client finds the server's socket name.
    private const val SERVER_NAME_FILE = ".axsh-server"

    // How long a binder has to call back as the server; a real one does within a few ms.
    private const val VERIFY_TIMEOUT_MS = 2_000L

    private const val PERMISSION_REQUEST_CODE = 0x4158

    private val sessions = Sessions(this)

    /** How the binder request reaches the manager from app_process ([Bootstrap]). */
    @Volatile
    private var sendRequest: ((Intent) -> Unit)? = null

    /**
     * The manager to ask for the binder from app_process: the package whose libaxsh this
     * process was started from, which [Bootstrap] works out. Null for an app, which is pushed its
     * capability instead and names no manager.
     */
    @Volatile
    private var relayPackage: String? = null

    /** Started in an app ([start]), which the server pushes to; false in app_process. */
    @Volatile
    private var inApp = false

    /** Set when the host already holds the server binder; then nothing is asked of the manager. */
    @Volatile
    private var binderSource: (() -> IBinder?)? = null

    @Volatile
    private var service: IAxeronService? = null

    /** This app's own view of the server, pushed in by it once AxManager allows us (AxshProvider). */
    @Volatile
    private var capability: IBinder? = null

    /** Opened by the first capability, for a cold call waiting on it in [requestBinder]. */
    private val firstPush = CountDownLatch(1)

    /** A newer capability: the next [service] verifies and takes it over whatever it held. */
    internal fun onCapability(binder: IBinder) {
        // Before the lock: a call may hold it while it waits on this capability.
        capability = binder
        firstPush.countDown()
        synchronized(this) {
            service = null
        }
        Log.i(tag, "capability received")
    }

    /** Abstract socket name the native client looks for; per-uid so apps never share a broker. */
    @InternalAxeronApi
    fun socketName(uid: Int = Process.myUid()) = "axsh.$uid"

    /**
     * @param binder where the host keeps the server binder, if it has one (the manager does);
     *   null to use the capability the server pushes once AxManager allows this app.
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, binder: (() -> IBinder?)? = null) {
        // On the object, as @Synchronized was before @JvmStatic (which would lock the class):
        // the same monitor service() takes.
        synchronized(this) {
            inApp = true
            packageName = context.packageName
            binderSource = binder
            sessions.listen(socketName())
        }
    }

    @JvmStatic
    fun stop() = sessions.stop()

    /**
     * The installed AxManager, as the system resolves its request-access door: no package name
     * needed (axsh's manifest declares the query). Null when there is none, or in its stealth
     * mode, where it doesn't show itself to apps.
     */
    @JvmStatic
    fun findManager(context: Context): ApplicationInfo? =
        context.packageManager.resolveActivity(Intent(AxeronApiConstant.server.ACTION_REQUEST_ACCESS), 0)
            ?.activityInfo?.applicationInfo

    /**
     * Asks AxManager to allow this app: its dialog, answered to [activity]'s onActivityResult for
     * [requestCode] (RESULT_OK once allowed; the server then pushes the capability by itself).
     * False when nothing on the device takes the request.
     */
    @JvmStatic
    fun requestAccess(activity: Activity, requestCode: Int): Boolean = try {
        @Suppress("DEPRECATION")
        activity.startActivityForResult(Intent(AxeronApiConstant.server.ACTION_REQUEST_ACCESS), requestCode)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

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
        // to yet) requestBinder waits a moment for the push instead of failing the first call.
        binderSource?.let { source ->
            source()?.takeIf { it.pingBinder() }?.let { return IAxeronService.Stub.asInterface(it) }
        }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        synchronized(this) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            val binder = requestBinder() ?: return null
            val candidate = IAxeronService.Stub.asInterface(binder)
            if (!fromServer(candidate)) return null
            cacheSocketName(candidate)
            return candidate.also { service = it }
        }
    }

    /**
     * A pushed capability, or a manager's relay, is only as good as whoever could write it: a binder from
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
     * Leaves the server's socket name (new on every server start, told to allowed apps only)
     * where this uid's native client looks: the host's own $TMPDIR, private to it, which the
     * client and the bootstrap it spawns share (Termux sets one). Without one, or while this app
     * isn't allowed, nothing is left and the client keeps coming through the broker.
     */
    private fun cacheSocketName(service: IAxeronService) {
        val dir = System.getenv("TMPDIR")?.takeIf { it.isNotEmpty() } ?: return
        try {
            val name = service.axshSocketName ?: return
            val tmp = File(dir, "$SERVER_NAME_FILE.tmp")
            tmp.writeText(name)
            Os.chmod(tmp.path, "600".toInt(8))
            if (!tmp.renameTo(File(dir, SERVER_NAME_FILE))) tmp.delete()
        } catch (e: Exception) {
            // Not allowed yet, or a server from before the name was hidden.
            Log.i(tag, "no server socket name to leave: ${e.message ?: e}")
        }
    }

    /**
     * The server binder. In an app, the capability the server pushed: a cold process's first call
     * can beat that push, so it waits a moment, and the host's own binder (the manager's) may land
     * meanwhile. An app_process client, which has no provider, asks the manager it was started
     * from instead.
     */
    private fun requestBinder(): IBinder? {
        capability?.takeIf { it.pingBinder() }?.let {
            unavailable = null
            return it
        }
        if (!inApp) return requestBinderFromManager()

        firstPush.await(PUSH_WAIT_MS, TimeUnit.MILLISECONDS)
        val binder = capability?.takeIf { it.pingBinder() }
            ?: binderSource?.invoke()?.takeIf { it.pingBinder() }
        unavailable = if (binder != null) null
        else "no access to the server: this app isn't allowed in AxManager, or the server isn't running"
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
            .setComponent(ComponentName(manager, AxeronApiConstant.server.RELAY_RECEIVER))
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
            // Not running, couldn't be woken, or (stealth) not answering this app: worded as when
            // there is nobody to ask, since that is what the app is to make of it.
            unavailable = "no way to reach the server (no answer)"
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
