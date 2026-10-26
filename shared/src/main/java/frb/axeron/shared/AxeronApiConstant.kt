@file:Suppress("ClassName")

package frb.axeron.shared

/**
 * Constants shared by the manager, the server and apps. Every value is a `const val`, so Java
 * still sees static final compile-time constants (usable in `switch` and static imports).
 */
object AxeronApiConstant {
    object server {

        const val BINDER_DESCRIPTOR = "frb.axeron.server.IAxeronService"
        const val SHIZUKU_BINDER_DESCRIPTOR = "moe.shizuku.server.IShizukuService"
        const val VERSION_NAME = BuildConfig.VERSION_NAME
        const val VERSION_CODE: Long = BuildConfig.VERSION_CODE.toLong()
        const val TYPE_DEFAULT_ENV = -1
        const val TYPE_ENV = 0
        const val TYPE_NEW_ENV = 1

        /**
         * Path lists of the default env (TYPE_DEFAULT_ENV) that the user's env may only add to:
         * its value keeps `$NAME` for the default, which is put in front when it is missing.
         * Every other default variable is the server's or the system's, and the user's env
         * can't change it.
         */
        @JvmField
        val APPEND_ONLY_ENV = setOf("PATH", "LD_LIBRARY_PATH")
    }

    object folder {
        const val ROOT = "/"
        const val TMP = "data/local/tmp/"
        const val ADB = "data/adb/"

        /** Old base of the root world, before it moved to [ADB]; only migration reads it. */
        const val ROOT_DE = "data/user_de/0/android/"
        const val SHELL_DE = "data/user_de/0/com.android.shell/"
        const val PARENT = "axeron/"
        const val PLUGIN = "plugins/"
        const val PLUGIN_UPDATE = "plugins_update/"
        const val CACHE = "cache/"
        const val LOG = "logs/"
        const val BINARY = "bin/"
        const val EXTERNAL_BINARY = "xbin/"
        const val ZIP = "zip/"

        const val PARENT_PLUGIN = PARENT + PLUGIN
        const val PARENT_CACHE = PARENT + CACHE
        const val PARENT_LOG = PARENT + LOG
        const val PARENT_BINARY = PARENT + BINARY
        const val PARENT_EXTERNAL_BINARY = PARENT + EXTERNAL_BINARY
        const val PARENT_ZIP = PARENT + ZIP
        const val PARENT_PLUGIN_UPDATE = PARENT + PLUGIN_UPDATE

        /**
         * Flag file in [PARENT]: when present, every plugin's processes are stopped once
         * the server dies. Off (absent) by default, so plugins keep running without the server.
         */
        const val STOP_PLUGINS_WITH_SERVER = "ax_stop_plugins_with_server"
    }


    internal object permission {

        object ops {
            const val OP_COARSE_LOCATION = 0
            const val OP_FINE_LOCATION = 1
            const val OP_GPS = 2
            const val OP_VIBRATE = 3
            const val OP_CAMERA = 26
            const val OP_RECORD_AUDIO = 27
            const val OP_SYSTEM_ALERT_WINDOW = 24
            const val OP_ACCESS_NOTIFICATION_POLICY = 25
            const val OP_WAKE_LOCK = 40
            const val OP_GET_USAGE_STATS = 43
            const val OP_ACTIVATE_VPN = 47
            const val OP_REQUEST_INSTALL_PACKAGES = 63
            const val OP_MANAGE_EXTERNAL_STORAGE = 92
            const val OP_ACCESS_MEDIA_LOCATION = 87
            const val OP_ACCESS_NOTIFICATIONS = 88
            const val OP_BODY_SENSORS = 56
            const val OP_READ_CONTACTS = 4
            const val OP_WRITE_CONTACTS = 5
            const val OP_READ_CALL_LOG = 6
            const val OP_WRITE_CALL_LOG = 7
            const val OP_READ_SMS = 14
            const val OP_RECEIVE_SMS = 16
            const val OP_SEND_SMS = 20
            const val OP_RECEIVE_MMS = 18
            const val OP_READ_EXTERNAL_STORAGE = 59
            const val OP_WRITE_EXTERNAL_STORAGE = 60
            const val OP_WRITE_SETTINGS = 23
            const val OP_RUN_ANY_IN_BACKGROUND = 70

        }
    }
}
