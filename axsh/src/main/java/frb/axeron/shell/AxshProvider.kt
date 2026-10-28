package frb.axeron.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import frb.axeron.shared.AxeronApiConstant

/**
 * axsh's one door into an app that uses it (`<package>` + PROVIDER_AUTHORITY_SUFFIX), for the
 * server's uids alone: the manifest asks INTERACT_ACROSS_USERS_FULL, which root and shell hold
 * and an app doesn't, and every call checks the uid again.
 *
 * - [AxeronApiConstant.server.METHOD_SEND_CAPABILITY]: the server hands this app its capability
 *   once AxManager allows it. Nothing arrives before that.
 * - [ShellServiceClient.METHOD_ATTACH]: a service process hands in its control binder, with a
 *   token one of our binds is waiting on.
 *
 * Starting, it keeps the app's Context for AxShellService.bind: providers start before the
 * Application.
 */
@InternalAxeronApi
class AxshProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != 2000) { // root, shell
            Log.w("AxshProvider", "$method from uid $uid refused")
            return null
        }
        return when (method) {
            AxeronApiConstant.server.METHOD_SEND_CAPABILITY -> {
                val capability = extras.getBinder(AxeronApiConstant.server.EXTRA_CAPABILITY) ?: return null
                AxBroker.onCapability(capability)
                Bundle()
            }
            ShellServiceClient.METHOD_ATTACH -> {
                val token = extras.getString(ShellServiceClient.EXTRA_TOKEN) ?: return null
                val control = extras.getBinder(ShellServiceClient.EXTRA_BINDER) ?: return null
                val appToken = ShellServiceClient.attach(token, control) ?: return null
                Bundle().apply { putBinder(ShellServiceClient.EXTRA_BINDER, appToken) }
            }
            else -> null
        }
    }

    override fun onCreate(): Boolean {
        ShellServiceClient.init(context!!)
        return true
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
