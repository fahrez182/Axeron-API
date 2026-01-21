package frb.axeron.aidl

import frb.axeron.shared.ServerInfo

interface AxeronInterface {
    fun enableShizukuService(enable: Boolean)
    fun getServerInfo(): ServerInfo?
}