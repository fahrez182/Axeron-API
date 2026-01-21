package frb.axeron.server

interface AxeronInterface {
    fun enableShizukuService(enable: Boolean)
    fun getServerInfo(): ServerInfo?
}