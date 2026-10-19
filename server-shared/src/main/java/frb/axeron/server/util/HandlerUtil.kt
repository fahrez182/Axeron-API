package frb.axeron.server.util

import android.os.Handler

object HandlerUtil {

    private var mainHandler: Handler? = null

    @JvmStatic
    fun getMainHandler(): Handler =
        checkNotNull(mainHandler) { "Please call setMainHandler first" }

    @JvmStatic
    fun setMainHandler(mainHandler: Handler) {
        this.mainHandler = mainHandler
    }
}
