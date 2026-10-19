package frb.axeron.shared

/**
 * App-ops that only MIUI / HyperOS has, past the AOSP range. `cmd appops` takes them by
 * number and prints them as `MIUIOP(<number>)`.
 *
 * A static table: the ROM gives these ops no names (`AppOpsManager.opToName` knows
 * none), and OEMs number their own ops however they like, so the same code can mean something
 * else on another vendor's ROM or another ROM version. Use these only after checking the ROM is
 * MIUI, and treat any name derived from them as a hint that can mislead, not as the truth.
 */
object MiuiAppOps {

    /** "Autostart": others may start the app when it isn't running (broadcasts, services). */
    const val AUTO_START = 10008

    /** "Home screen shortcuts": pin requests are dropped silently while denied. */
    const val INSTALL_SHORTCUT = 10017

    /** "Open new windows while running in the background": activity starts from the background. */
    const val BACKGROUND_START_ACTIVITY = 10021

    /** What the manager needs for itself, allowed by the server on MIUI. */
    @JvmField
    val MANAGER_OPS = intArrayOf(AUTO_START, INSTALL_SHORTCUT, BACKGROUND_START_ACTIVITY)

    /** The system property only MIUI and HyperOS set. */
    const val VERSION_PROPERTY = "ro.miui.ui.version.name"
}
