package frb.axeron.api

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.server.Environment
import frb.axeron.server.IAxeronService
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.AxeronApiConstant.server.TYPE_ENV
import frb.axeron.shared.ShizukuApiConstant.BINDER_TRANSACTION_transact
import frb.axeron.shared.ShizukuApiConstant.BIND_APPLICATION_PERMISSION_GRANTED
import frb.axeron.shared.ShizukuApiConstant.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import frb.axeron.shared.ShizukuApiConstant.REQUEST_PERMISSION_REPLY_ALLOWED
import frb.axeron.shell.Axsh
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import java.io.IOException
import java.io.InputStream

/**
 * Client side of the AxManager server: holds its binder, tells listeners when it arrives or
 * dies, and wraps its calls. Every member is [JvmStatic], so Java callers and apps built
 * against the former Java class keep calling `Axeron.pingBinder()` as before.
 */
object Axeron {
    private val RECEIVED_LISTENERS = ArrayList<ListenerHolder<OnBinderReceivedListener>>()
    private val DEAD_LISTENERS = ArrayList<ListenerHolder<OnBinderDeadListener>>()
    private val PERMISSION_LISTENERS = ArrayList<ListenerHolder<OnRequestPermissionResultListener>>()

    private const val TAG = "AxeronApplication"
    private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    private var binder: IBinder? = null
    private var service: IAxeronService? = null
    private var axeronInfo: AxeronInfo? = null
    private var binderReady = false

    private var permissionGranted = false
    private var shouldShowRequestPermissionRationale = false

    private val SHIZUKU_APPLICATION = object : IShizukuApplication.Stub() {

        override fun bindApplication(data: Bundle) {
            permissionGranted = data.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false)
            shouldShowRequestPermissionRationale =
                data.getBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)

            scheduleBinderReceivedListeners()

            if (isFirstInit(false) || AxeronSettings.getEnableIgniteRelog()) {
                Log.d(TAG, "igniteService")
                AxeronPluginService.igniteService()
            }
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle) {
            val allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false)
            scheduleRequestPermissionResultListener(
                requestCode,
                if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            )
        }

        override fun showPermissionConfirmation(
            requestUid: Int,
            requestPid: Int,
            requestPackageName: String?,
            requestCode: Int
        ) {
            // non-app
        }
    }

    private val DEATH_RECIPIENT = IBinder.DeathRecipient {
        binderReady = false
        onBinderReceived(null, null)
    }

    /**
     * A server call with the usual failures (no binder yet, server gone, refused) logged under
     * [name] and answered with [fallback].
     */
    private inline fun <T> remote(name: String, fallback: T, block: IAxeronService.() -> T): T =
        try {
            requireService().block()
        } catch (e: RemoteException) {
            Log.e(TAG, name, e)
            fallback
        } catch (e: IllegalStateException) {
            Log.e(TAG, name, e)
            fallback
        } catch (e: SecurityException) {
            Log.e(TAG, name, e)
            fallback
        }

    /** On [handler], else on the main thread: right away when already on it. */
    private fun dispatch(handler: Handler?, action: Runnable) {
        when {
            handler != null -> handler.post(action)
            Looper.myLooper() == Looper.getMainLooper() -> action.run()
            else -> MAIN_HANDLER.post(action)
        }
    }

    @JvmStatic
    fun checkSelfPermission(): Int {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED
        permissionGranted = remote("checkSelfPermission", false) { checkSelfPermission() }
        return if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    @JvmStatic
    fun shouldShowRequestPermissionRationale(): Boolean {
        if (permissionGranted) return false
        if (shouldShowRequestPermissionRationale) return true
        shouldShowRequestPermissionRationale = remote("shouldShowRequestPermissionRationale", false) {
            shouldShowRequestPermissionRationale()
        }
        return shouldShowRequestPermissionRationale
    }

    // The add* functions are overloads, not a default handler: with handler last, a trailing
    // lambda (`addBinderReceivedListener { … }`) would be taken for the handler.

    @JvmStatic
    fun addRequestPermissionResultListener(listener: OnRequestPermissionResultListener) {
        addRequestPermissionResultListener(listener, null)
    }

    @JvmStatic
    fun addRequestPermissionResultListener(listener: OnRequestPermissionResultListener, handler: Handler?) {
        synchronized(RECEIVED_LISTENERS) {
            PERMISSION_LISTENERS.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    fun removeRequestPermissionResultListener(listener: OnRequestPermissionResultListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return PERMISSION_LISTENERS.removeIf { it.listener === listener }
        }
    }

    private fun scheduleRequestPermissionResultListener(requestCode: Int, result: Int) {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in PERMISSION_LISTENERS) {
                dispatch(holder.handler) { holder.listener.onRequestPermissionResult(requestCode, result) }
            }
        }
    }

    @JvmStatic
    fun requestPermission(requestCode: Int) {
        remote("requestPermission", Unit) { requestPermission(requestCode) }
    }

    private fun scheduleBinderDeadListeners() {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in DEAD_LISTENERS) {
                dispatch(holder.handler, holder.listener::onBinderDead)
            }
        }
    }

    @JvmStatic
    fun addBinderDeadListener(listener: OnBinderDeadListener) {
        addBinderDeadListener(listener, null)
    }

    @JvmStatic
    fun addBinderDeadListener(listener: OnBinderDeadListener, handler: Handler?) {
        synchronized(RECEIVED_LISTENERS) {
            DEAD_LISTENERS.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    fun removeBinderDeadListener(listener: OnBinderDeadListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return DEAD_LISTENERS.removeIf { it.listener === listener }
        }
    }

    private fun scheduleBinderReceivedListeners() {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in RECEIVED_LISTENERS) {
                dispatch(holder.handler, holder.listener::onBinderReceived)
            }
        }
        binderReady = true
    }

    @JvmStatic
    fun addBinderReceivedListener(listener: OnBinderReceivedListener) {
        addListener(listener, false, null)
    }

    @JvmStatic
    fun addBinderReceivedListener(listener: OnBinderReceivedListener, handler: Handler?) {
        addListener(listener, false, handler)
    }

    @JvmStatic
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) {
        addListener(listener, true, null)
    }

    @JvmStatic
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener, handler: Handler?) {
        addListener(listener, true, handler)
    }

    private fun addListener(listener: OnBinderReceivedListener, sticky: Boolean, handler: Handler?) {
        if (sticky && binderReady) {
            dispatch(handler, listener::onBinderReceived)
        }
        synchronized(RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return RECEIVED_LISTENERS.removeIf { it.listener === listener }
        }
    }

    @JvmStatic
    fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
        if (binder === newBinder) {
            // Server re-pushed the same binder: its state changed (e.g. runtime permission),
            // so drop the cached info and let listeners re-read it.
            if (newBinder != null) {
                axeronInfo = null
                scheduleBinderReceivedListeners()
            }
            return
        }

        if (newBinder == null) {
            binder = null
            service = null
            axeronInfo = null

            scheduleBinderDeadListeners()
        } else {
            if (pingBinder()) {
                binder?.unlinkToDeath(DEATH_RECIPIENT, 0)
            }
            binder = newBinder
            service = IAxeronService.Stub.asInterface(newBinder)
            axeronInfo = null

            try {
                newBinder.linkToDeath(DEATH_RECIPIENT, 0)
            } catch (e: Throwable) {
                Log.i(TAG, "attachApplication")
            }

            try {
                attachApplication(newBinder, packageName)
                Log.i("ShizukuApplication", "attachApplication")
            } catch (e: Throwable) {
                Log.w("ShizukuApplication", Log.getStackTraceString(e))
                // Fallback: trigger listeners even if attach failed so apps aren't stuck
                scheduleBinderReceivedListeners()
            }
        }
    }

    @Throws(RemoteException::class)
    private fun attachApplication(binder: IBinder, packageName: String?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IAxeronService.DESCRIPTOR)
            data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder())
            data.writeString(packageName)
            binder.transact(14, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getShizukuService(): IShizukuService? {
        val service = service ?: return null
        return try {
            service.shizukuService
        } catch (e: RemoteException) {
            Log.e(TAG, "getShizukuService", e)
            null
        }
    }

    @JvmStatic
    fun enableShizukuService(enable: Boolean) {
        remote("enableShizukuService", Unit) { enableShizukuService(enable) }
    }

    @JvmStatic
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    fun isFirstInit(markAsFirstInit: Boolean): Boolean =
        remote("isFirstInit", false) { isFirstInit(markAsFirstInit) }

    @JvmStatic
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    fun requireService(): IAxeronService =
        service ?: throw IllegalStateException("binder haven't been received")

    @JvmStatic
    fun pingBinder(): Boolean = binder?.pingBinder() == true

    @JvmStatic
    fun getBinder(): IBinder? = binder

    @JvmStatic
    fun newFileService(): AxeronFileService? =
        remote("newFileService", null) { AxeronFileService(fileService) }

    /**
     * Runs the server's starter again, in its own session: restarts the server, or replaces one
     * that needs updating. Returns at once; the work goes on in the background. When the starter
     * can't be run (a server from before axsh has no way to), the server is stopped instead, for
     * the user to start again.
     */
    @JvmStatic
    fun relaunchServer() {
        val argv = AxeronCommandSession.getQuickCmd(Starter.internalCommand, true, false)
        Thread({
            val process = try {
                Axsh.exec(argv)
            } catch (e: IOException) {
                Log.e(TAG, "relaunchServer, stopping the server", e)
                destroy()
                return@Thread
            } catch (e: RuntimeException) {
                Log.e(TAG, "relaunchServer, stopping the server", e)
                destroy()
                return@Thread
            }
            // Drained, not closed: the starter writes as it goes, and a closed pipe would end it.
            drain(process.inputStream)
            drain(process.errorStream)
        }, "AxeronRelaunch").start()
    }

    private fun drain(stream: InputStream) {
        Thread({
            val buffer = ByteArray(4096)
            try {
                stream.use {
                    while (it.read(buffer) != -1) {
                        // discard
                    }
                }
            } catch (ignored: IOException) {
            }
        }, "AxeronRelaunchDrain").start()
    }

    @JvmStatic
    fun destroy() {
        if (binder != null) {
            try {
                requireService().exit()
            } catch (ignored: RemoteException) {
                // A server that doesn't take us for the manager (it outlived a reinstall) refuses
                // exit. Let go of it all the same: only the starter, run as root or over ADB, can
                // kill it, and it does on the next start.
            } catch (ignored: SecurityException) {
            }
            binder = null
            service = null
            axeronInfo = null
            scheduleBinderDeadListeners()
        }
    }

    @JvmStatic
    fun getAxeronInfo(): AxeronInfo {
        axeronInfo?.let { return it }
        return try {
            AxeronInfo(requireService().serverInfo).also { axeronInfo = it }
        } catch (e: Exception) {
            AxeronInfo()
        }
    }

    /** Throws [IllegalStateException] before the binder is received, as it always did. */
    @JvmStatic
    fun getPackages(flags: Int): List<PackageInfo> = try {
        requireService().getPackages(flags).list
    } catch (ignored: RemoteException) {
        ArrayList()
    }

    /** Throws [IllegalStateException] before the binder is received, as it always did. */
    @JvmStatic
    fun getPlugins(): List<PluginInfo> = try {
        requireService().plugins.list
    } catch (ignored: RemoteException) {
        ArrayList()
    }

    @JvmStatic
    fun getPluginById(id: String): PluginInfo? = try {
        requireService().getPluginById(id)
    } catch (e: RemoteException) {
        null
    }

    @JvmStatic
    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        remote("transactRemote", Unit) { asBinder().transact(BINDER_TRANSACTION_transact, data, reply, flags) }
    }

    @JvmStatic
    fun getEnvironment(): Environment? = getEnvironment(TYPE_ENV)

    @JvmStatic
    fun getEnvironment(envType: Int): Environment? = try {
        requireService().getEnvironment(envType)
    } catch (e: Exception) {
        null
    }

    @JvmStatic
    fun setNewEnvironment(env: Environment) {
        remote("setNewEnvironment", Unit) { setNewEnvironment(env) }
    }

    @JvmStatic
    fun setPreferSystemBinaries(prefer: Boolean) {
        remote("setPreferSystemBinaries", Unit) { setPreferSystemBinaries(prefer) }
    }

    /**
     * Holds (or releases) a partial wakelock in the server, keeping the CPU awake for long
     * running work while the screen is off. Lasts until released or the server stops.
     */
    @JvmStatic
    fun setWakeLock(enabled: Boolean) {
        remote("setWakeLock", Unit) { setWakeLock(enabled) }
    }

    /** False as well when the server can't be reached. */
    @JvmStatic
    fun isWakeLockHeld(): Boolean = remote("isWakeLockHeld", false) { isWakeLockHeld() }

    @JvmStatic
    fun getFlagsForUids(uids: IntArray, mask: Int): IntArray =
        remote("getFlagsForUids", IntArray(uids.size)) { getFlagsForUids(uids, mask) }

    @JvmStatic
    fun checkRemotePermission(permission: String): Int {
        if (getAxeronInfo().isRoot()) return PackageManager.PERMISSION_GRANTED
        return remote("checkRemotePermission", PackageManager.PERMISSION_DENIED) { checkPermission(permission) }
    }

    /**
     * Whether the server refuses us as its manager: one left running across a reinstall of the
     * manager still holds the old uid. Its gated calls answer SecurityException then, and
     * checkPermission is gated on every server version.
     */
    @JvmStatic
    fun isRefusedByServer(): Boolean = try {
        requireService().checkPermission("android.permission.GRANT_RUNTIME_PERMISSIONS")
        false
    } catch (e: SecurityException) {
        true
    } catch (e: RemoteException) {
        false
    } catch (e: IllegalStateException) {
        false
    }

    @JvmStatic
    fun isUpdated(): Boolean =
        AxeronApiConstant.server.VERSION_CODE <= getAxeronInfo().serverInfo.versionCode

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    fun interface OnBinderDeadListener {
        fun onBinderDead()
    }

    @JvmStatic
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    fun attachUserService(binder: IBinder, options: Bundle) {
        remote("attachUserService", Unit) { attachUserService(binder, options) }
    }

    private class ListenerHolder<T>(val listener: T, val handler: Handler?)

    @JvmStatic
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle) {
        remote("dispatchPermissionConfirmationResult", Unit) {
            dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        }
    }

    @JvmStatic
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    fun getFlagsForUid(uid: Int, mask: Int): Int = remote("getFlagsForUid", 0) { getFlagsForUid(uid, mask) }

    @JvmStatic
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        remote("updateFlagsForUid", Unit) { updateFlagsForUid(uid, mask, value) }
    }

    fun interface OnRequestPermissionResultListener {
        fun onRequestPermissionResult(requestCode: Int, grantResult: Int)
    }
}
