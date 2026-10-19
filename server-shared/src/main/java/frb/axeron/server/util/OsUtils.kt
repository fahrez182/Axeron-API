package frb.axeron.server.util

import android.os.SELinux
import android.system.Os

object OsUtils {

    private val UID = Os.getuid()
    private val PID = Os.getpid()
    private val SELINUX_CONTEXT: String? = try {
        SELinux.getContext()
    } catch (tr: Throwable) {
        null
    }

    @JvmStatic
    fun getUid(): Int = UID

    @JvmStatic
    fun getPid(): Int = PID

    @JvmStatic
    fun getSELinuxContext(): String? = SELINUX_CONTEXT
}
