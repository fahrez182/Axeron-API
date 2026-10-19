package frb.axeron.server

import frb.axeron.server.util.Logger

abstract class ConfigPackageEntry {

    abstract val isAllowed: Boolean

    abstract val isDenied: Boolean

    companion object {
        @JvmField
        protected val LOGGER = Logger("ConfigPackageEntry")
    }
}
