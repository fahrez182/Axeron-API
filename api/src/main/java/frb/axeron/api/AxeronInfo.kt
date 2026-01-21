package frb.axeron.api

import android.os.Parcelable
import frb.axeron.server.ServerInfo
import frb.axeron.shared.AxeronApiConstant
import kotlinx.parcelize.Parcelize

@Parcelize
data class AxeronInfo(
    val serverInfo: ServerInfo = ServerInfo()
) : Parcelable {

    fun getActualVersion(): Long {
        return serverInfo.getActualVersion()
    }

    fun isRunning(): Boolean {
        return Axeron.pingBinder() && AxeronApiConstant.server.getActualVersion() <= getActualVersion()
    }

    fun isNeedUpdate(): Boolean {
        return AxeronApiConstant.server.getActualVersion() > getActualVersion() && Axeron.pingBinder()
    }

    fun isNeedExtraStep(): Boolean {
        return isRunning() && !serverInfo.permission
    }

}