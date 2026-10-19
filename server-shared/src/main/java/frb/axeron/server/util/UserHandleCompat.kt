package frb.axeron.server.util

object UserHandleCompat {

    const val PER_USER_RANGE = 100000

    @JvmStatic
    fun getUserId(uid: Int): Int = uid / PER_USER_RANGE

    @JvmStatic
    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE
}
