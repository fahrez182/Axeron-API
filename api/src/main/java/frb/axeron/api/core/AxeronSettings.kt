package frb.axeron.api.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemProperties
import androidx.annotation.IntDef
import frb.axeron.api.utils.EmptySharedPreferencesImpl
import java.util.Locale

object AxeronSettings {

    const val NAME = "settings"
    const val APP_THEME_ID = "app_theme_id"

    const val TCP_MODE = "tcp_mode"
    const val TCP_PORT = "tcp_port"
    const val LAUNCH_MODE = "mode"
    const val ENABLE_DYNAMIC_COLOR = "enable_dynamic_color"
    const val ENABLE_DEVELOPER_OPTIONS = "enable_developer_options"
    const val ENABLE_WEB_DEBUGGING = "enable_web_debugging"
    const val CUSTOM_PRIMARY_COLOR = "custom_primary_color"
    const val ENABLE_IGNITE_RELOG = "enable_ignite_relog"
    const val LANGUAGE = "language"
    const val ENABLE_START_ON_BOOT = "enable_start_on_boot"
    const val PREFER_SYSTEM_BINARIES = "prefer_system_binaries"
    const val WAKE_LOCK = "wake_lock"

    private var sPreferences: SharedPreferences? = null

    /** Throws before [initialize]; the Java class returned null, and every caller then failed. */
    @JvmStatic
    fun getPreferences(): SharedPreferences =
        checkNotNull(sPreferences) { "AxeronSettings.initialize() hasn't been called" }

    private fun getSettingsStorageContext(context: Context): Context {
        val storageContext = context.createDeviceProtectedStorageContext()

        return object : ContextWrapper(storageContext) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return try {
                    super.getSharedPreferences(name, mode)
                } catch (e: IllegalStateException) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    EmptySharedPreferencesImpl()
                }
            }
        }
    }

    @JvmStatic
    fun initialize(context: Context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    @JvmStatic
    @LaunchMethod
    fun getLastLaunchMode(): Int = getPreferences().getInt(LAUNCH_MODE, LaunchMethod.UNKNOWN)

    @JvmStatic
    fun setLastLaunchMode(@LaunchMethod method: Int) {
        getPreferences().edit().putInt(LAUNCH_MODE, method).apply()
    }

    @JvmStatic
    fun getTcpMode(): Boolean = getPreferences().getBoolean(TCP_MODE, true)

    @JvmStatic
    fun setTcpMode(enable: Boolean) {
        getPreferences().edit().putBoolean(TCP_MODE, enable).apply()
    }

    @JvmStatic
    fun getTcpPort(): Int {
        return try {
            var port = SystemProperties.getInt("service.adb.tcp.port", -1)
            if (port <= 0) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
            if (port <= 0) port = 5555
            getPreferences().getInt(TCP_PORT, port)
        } catch (e: NumberFormatException) {
            5555
        }
    }

    @JvmStatic
    fun setTcpPort(port: Int?) {
        if (port != null) {
            getPreferences().edit().putInt(TCP_PORT, port).apply()
        } else {
            getPreferences().edit().remove(TCP_PORT).apply()
        }
    }

    // IGNITE RELOG

    @JvmStatic
    fun getEnableIgniteRelog(): Boolean = getPreferences().getBoolean(ENABLE_IGNITE_RELOG, false)

    @JvmStatic
    fun setEnableIgniteRelog(enable: Boolean) {
        getPreferences().edit().putBoolean(ENABLE_IGNITE_RELOG, enable).apply()
    }


    // DEVELOPER MODE

    @JvmStatic
    fun getEnableDeveloperOptions(): Boolean = getPreferences().getBoolean(ENABLE_DEVELOPER_OPTIONS, false)

    @JvmStatic
    fun setEnableDeveloperOptions(enable: Boolean) {
        getPreferences().edit().putBoolean(ENABLE_DEVELOPER_OPTIONS, enable).apply()
    }

    // WEB DEBUGGING

    @JvmStatic
    fun getEnableWebDebugging(): Boolean = getPreferences().getBoolean(ENABLE_WEB_DEBUGGING, false)

    @JvmStatic
    fun setEnableWebDebugging(enable: Boolean) {
        getPreferences().edit().putBoolean(ENABLE_WEB_DEBUGGING, enable).apply()
    }

    // APP THEME

    @JvmStatic
    fun getAppThemeId(): Int = getPreferences().getInt(APP_THEME_ID, 0)

    @JvmStatic
    fun setAppThemeId(id: Int) {
        getPreferences().edit().putInt(APP_THEME_ID, id).apply()
    }

    // DYNAMIC COLOR

    @JvmStatic
    fun getEnableDynamicColor(): Boolean = getPreferences().getBoolean(ENABLE_DYNAMIC_COLOR, false)

    @JvmStatic
    fun setEnableDynamicColor(enable: Boolean) {
        getPreferences().edit().putBoolean(ENABLE_DYNAMIC_COLOR, enable).apply()
    }

    // ON BOOT

    @JvmStatic
    fun getStartOnBoot(): Boolean = getPreferences().getBoolean(ENABLE_START_ON_BOOT, true)

    @JvmStatic
    fun setStartOnBoot(method: Boolean) {
        getPreferences().edit().putBoolean(ENABLE_START_ON_BOOT, method).apply()
    }

    //PRIMARY COLOR

    @JvmStatic
    fun getCustomPrimaryColor(): String? = getPreferences().getString(CUSTOM_PRIMARY_COLOR, null)

    @JvmStatic
    fun setPrimaryColor(hex: String?) {
        getPreferences().edit().putString(CUSTOM_PRIMARY_COLOR, hex).apply()
    }

    @JvmStatic
    fun removePrimaryColor() {
        getPreferences().edit().remove(CUSTOM_PRIMARY_COLOR).apply()
    }

    // PATH PRIORITY

    @JvmStatic
    fun getPreferSystemBinaries(): Boolean = getPreferences().getBoolean(PREFER_SYSTEM_BINARIES, false)

    @JvmStatic
    fun setPreferSystemBinaries(prefer: Boolean) {
        getPreferences().edit().putBoolean(PREFER_SYSTEM_BINARIES, prefer).apply()
    }

    // WAKELOCK: the server holds it from start; the manager re-applies this on connect, so
    // "off" survives restarts. Defaults to on, the server's behaviour before it was a switch.

    @JvmStatic
    fun getWakeLock(): Boolean = getPreferences().getBoolean(WAKE_LOCK, true)

    @JvmStatic
    fun setWakeLock(enabled: Boolean) {
        getPreferences().edit().putBoolean(WAKE_LOCK, enabled).apply()
    }

    /**
     * The language picked in the app (saved below Android 13, which keeps it itself), or null
     * for the system language. The picker saves "system" in lower case, and older versions
     * "zh_CN", which BCP 47 alone parses to an empty locale.
     */
    @JvmStatic
    fun getLocale(): Locale? {
        val tag = getPreferences().getString(LANGUAGE, null)
        if (tag.isNullOrEmpty() || tag.equals("system", ignoreCase = true)) {
            return null
        }
        return Locale.forLanguageTag(tag.replace('_', '-'))
    }

    @IntDef(
        LaunchMethod.UNKNOWN,
        LaunchMethod.ROOT,
        LaunchMethod.ADB,
        LaunchMethod.ROOT_AS_SHELL,
    )
    @Retention(AnnotationRetention.SOURCE)
    annotation class LaunchMethod {
        companion object {
            const val UNKNOWN = -1
            const val ROOT = 0
            const val ADB = 1
            const val ROOT_AS_SHELL = 2
        }
    }
}
