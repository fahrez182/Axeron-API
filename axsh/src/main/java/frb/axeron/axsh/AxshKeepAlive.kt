package frb.axeron.axsh

import android.content.Context

/**
 * Per app (uid): whether a shell-mode bootstrap stays alive as that app's broker after its first
 * session, and for how long without sessions. Kept by the manager, which answers each
 * bootstrap's binder request with the setting of the uid it says it runs as. Off unless the user
 * turned it on for that app, since the waiting helper costs memory there.
 *
 * The uid in the request isn't checked, and needs no checking: the setting only shapes the
 * requester's own helper, in its own process; claiming another uid gains no access.
 */
object AxshKeepAlive {

    /** Not kept: one session, then the bootstrap exits (the default). */
    const val OFF = 0

    /** Kept until the app's processes go or AxManager is updated. */
    const val NEVER = -1

    /** The choices offered, in minutes, [OFF] and [NEVER] included. */
    val CHOICES = intArrayOf(OFF, 5, 15, 30, 60, NEVER)

    /** Bundle key of the requester's uid in the `AxRuntime.REQUEST_BINDER` data. */
    const val EXTRA_UID = "uid"

    private const val PREFS = "axsh"
    private const val KEY_MINUTES = "keep_alive_minutes_"

    fun get(context: Context, uid: Int): Int =
        if (uid < 0) OFF
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MINUTES + uid, OFF)

    fun set(context: Context, uid: Int, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (minutes == OFF) prefs.remove(KEY_MINUTES + uid) else prefs.putInt(KEY_MINUTES + uid, minutes)
        prefs.apply()
    }
}
