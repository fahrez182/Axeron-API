package frb.axeron.axsh

import android.content.Context

/**
 * Per app (uid): whether a shell-mode bootstrap leaves a broker behind for that app after its
 * first session, and for how long without sessions. Kept by the manager, which answers each
 * bootstrap's binder request with the setting of the uid it says it runs as.
 *
 * The broker is axsh's native daemon (a few MB), so it is on while used ([SMART]) unless the
 * user picked otherwise for that app.
 *
 * The uid in the request isn't checked, and needs no checking: the setting only shapes the
 * requester's own helper, in its own process; claiming another uid gains no access.
 */
object AxshKeepAlive {

    /** Not kept: one session, then the bootstrap exits. */
    const val OFF = 0

    /** Kept while used: leaves after this many minutes without a command (the default). */
    const val SMART = 5

    /** Kept until the server stops or AxManager is updated. */
    const val NEVER = -1

    const val DEFAULT = SMART

    /** The choices offered. */
    val CHOICES = intArrayOf(OFF, SMART, NEVER)

    /** Bundle key of the requester's uid in the `AxRuntime.REQUEST_BINDER` data. */
    const val EXTRA_UID = "uid"

    private const val PREFS = "axsh"
    private const val KEY_MINUTES = "keep_alive_minutes_"

    // Older managers offered 5 to 60 minutes: any of those reads as SMART now.
    fun get(context: Context, uid: Int): Int {
        if (uid < 0) return OFF
        val minutes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MINUTES + uid, DEFAULT)
        return if (minutes > 0) SMART else minutes
    }

    fun set(context: Context, uid: Int, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (minutes == DEFAULT) prefs.remove(KEY_MINUTES + uid) else prefs.putInt(KEY_MINUTES + uid, minutes)
        prefs.apply()
    }
}
