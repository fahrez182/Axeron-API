package frb.axeron.server

import android.os.Parcel
import android.os.Parcelable
import java.util.Collections
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parcel layout (kept as the Java class wrote it, so servers of either version read it): the
 * entry count, then each key and value as strings. [isNewEnv] isn't parceled; a received
 * Environment always reads false.
 */
class Environment : Parcelable {

    val envMap: Map<String, String>
    val isNewEnv: Boolean

    constructor(envMap: Map<String, String>, newEnv: Boolean) {
        this.isNewEnv = newEnv
        this.envMap = Collections.unmodifiableMap(HashMap(envMap))
    }

    // Parcelable
    private constructor(parcel: Parcel) {
        val size = parcel.readInt()
        val map = HashMap<String, String>()
        repeat(size) {
            val k = parcel.readString()!!
            val v = parcel.readString()!!
            map[k] = v
        }
        this.envMap = Collections.unmodifiableMap(map)
        this.isNewEnv = false
    }

    /** `KEY=value` entries, for exec; null when there are none. */
    val env: Array<String>?
        get() {
            if (envMap.isEmpty()) return null
            return envMap.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(envMap.size)
        for ((key, value) in envMap) {
            dest.writeString(key)
            dest.writeString(value)
        }
    }

    override fun describeContents(): Int = 0

    // --------- Builder ----------
    class Builder(private val newEnv: Boolean) {
        private val systemEnvMap: MutableMap<String, String> = HashMap(System.getenv())
        private val envMap: MutableMap<String, String> = if (newEnv) HashMap() else systemEnvMap

        /**
         * Sets [key], with `$VAR` in [value] replaced from this env, then the system's. A `$VAR`
         * found in neither stays as it is: a `$` meant literally (a regex, a password) isn't
         * lost. A null value removes the key (the Java class stored it and then failed in
         * [build]).
         */
        fun put(key: String, value: String?): Builder {
            if (value == null) {
                envMap.remove(key)
                return this
            }
            val matcher = VARIABLE.matcher(value)
            val sb = StringBuffer()
            while (matcher.find()) {
                val name = matcher.group(1)
                val replacement = envMap[name] ?: systemEnvMap[name] ?: matcher.group()
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
            }
            matcher.appendTail(sb)
            envMap[key] = sb.toString()
            return this
        }

        /** Sets every entry of [map] as it is, without replacing `$VAR`: values already built. */
        fun putAllRaw(map: Map<String, String>): Builder {
            envMap.putAll(map)
            return this
        }

        fun putAll(envArray: Array<String>?): Builder {
            if (envArray != null) {
                for (s in envArray) {
                    val idx = s.indexOf('=')
                    if (idx > 0) {
                        val key = s.substring(0, idx)
                        val value = s.substring(idx + 1)
                        put(key, value) // gunakan put() biar support substitusi $KEY
                    }
                }
            }
            return this
        }

        /**
         * [put] for each entry. One that refers to another entry of [map] is set after it, so
         * `A=$B` takes the B of [map] whatever their order; in a cycle, the entry met first
         * takes the other's previous value. `$KEY` in KEY's own value is its previous value.
         */
        fun putAll(map: Map<String, String>): Builder {
            val pending = LinkedHashMap(map)
            fun putPending(key: String) {
                val value = pending.remove(key) ?: return
                val matcher = VARIABLE.matcher(value)
                while (matcher.find()) {
                    val name = matcher.group(1)
                    if (name != key) putPending(name)
                }
                put(key, value)
            }
            while (pending.isNotEmpty()) putPending(pending.keys.first())
            return this
        }

        fun remove(key: String): Builder {
            envMap.remove(key)
            return this
        }

        fun clear(): Builder {
            envMap.clear()
            return this
        }

        fun build(): Environment {
            if (envMap.isEmpty()) return Environment(systemEnvMap, newEnv)
            return Environment(envMap, newEnv)
        }
    }

    companion object {
        private val VARIABLE: Pattern = Pattern.compile("\\$([A-Za-z0-9_]+)")

        @JvmField
        val CREATOR = object : Parcelable.Creator<Environment> {
            override fun createFromParcel(parcel: Parcel): Environment = Environment(parcel)

            override fun newArray(size: Int): Array<Environment?> = arrayOfNulls(size)
        }
    }
}
