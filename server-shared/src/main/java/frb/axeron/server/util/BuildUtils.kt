package frb.axeron.server.util

import android.os.Build

/**
 * TODO: Replace it with [BuildUtils].
 */
object BuildUtils {

    private val SDK = Build.VERSION.SDK_INT

    private val PREVIEW_SDK = if (SDK >= 23) Build.VERSION.PREVIEW_SDK_INT else 0

    @JvmStatic
    fun atLeast31(): Boolean = SDK >= 31 || SDK == 30 && PREVIEW_SDK > 0

    @JvmStatic
    fun atLeast30(): Boolean = SDK >= 30

    @JvmStatic
    fun atLeast29(): Boolean = SDK >= 29

    @JvmStatic
    fun atLeast28(): Boolean = SDK >= 28

    @JvmStatic
    fun atLeast26(): Boolean = SDK >= 26

    @JvmStatic
    fun atLeast24(): Boolean = SDK >= 24

    @JvmStatic
    fun atLeast23(): Boolean = SDK >= 23
}
