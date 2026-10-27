package frb.axeron.shell

/**
 * Per app (uid): whether a shell-mode bootstrap leaves a broker behind for that app after its
 * first session, and for how long without commands. The broker is axsh's native daemon (a few
 * MB), so it is on while used ([SMART]) unless the user picked otherwise for that app.
 *
 * Kept by the server with the app's permission, as two bits of its flags ([MASK]; neither set is
 * [SMART]), so it lasts with them and changing it there ends the app's running daemon. The
 * manager reads and writes it with getFlagsForUid / updateFlagsForUid and answers each
 * bootstrap's binder request with the setting of the uid it says it runs as.
 *
 * The uid in the request isn't checked, and needs no checking: the setting only shapes the
 * requester's own helper, in its own process; claiming another uid gains no access.
 */
@InternalAxeronApi
object KeepAlive {

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

    // Next to ConfigManager.FLAG_ALLOWED (1 << 1) and FLAG_DENIED (1 << 2).
    const val FLAG_OFF = 1 shl 3
    const val FLAG_NO_LIMIT = 1 shl 4
    const val MASK = FLAG_OFF or FLAG_NO_LIMIT

    /** The setting kept in an app's server flags. */
    fun fromFlags(flags: Int): Int = when {
        flags and FLAG_OFF != 0 -> OFF
        flags and FLAG_NO_LIMIT != 0 -> NEVER
        else -> SMART
    }

    /** The flags (within [MASK]) that keep [setting]. */
    fun toFlags(setting: Int): Int = when (setting) {
        OFF -> FLAG_OFF
        NEVER -> FLAG_NO_LIMIT
        else -> 0
    }
}
