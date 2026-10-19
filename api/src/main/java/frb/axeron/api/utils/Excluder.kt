package frb.axeron.api.utils

import java.util.Locale

data class Excluder(val mode: Mode, val excludedExt: Set<String>?, val caseSensitive: Boolean) {

    enum class Mode {
        START,
        END,
        EQUALS,
        CONTAINS,
        MATCHES
    }

    companion object {
        @JvmStatic
        fun shouldSmartExclude(relativePath: String, excluders: List<Excluder>?): Boolean {
            if (excluders.isNullOrEmpty()) return false

            for (excluder in excluders) {
                val excludedExt = excluder.excludedExt
                if (excludedExt.isNullOrEmpty()) continue

                // Pilih nama sesuai mode caseSensitive (default locale, as String.toLowerCase() did)
                val compareName =
                    if (excluder.caseSensitive) relativePath else relativePath.lowercase(Locale.getDefault())

                for (ext in excludedExt) {
                    val compareExt = if (excluder.caseSensitive) ext else ext.lowercase(Locale.getDefault())

                    val matched = when (excluder.mode) {
                        Mode.START -> compareName.startsWith(compareExt)
                        Mode.END -> compareName.endsWith(compareExt)
                        Mode.EQUALS -> compareName == compareExt
                        Mode.CONTAINS -> compareName.contains(compareExt)
                        Mode.MATCHES -> compareName.matches(Regex(compareExt))
                    }
                    if (matched) return true
                }
            }

            return false
        }
    }
}
