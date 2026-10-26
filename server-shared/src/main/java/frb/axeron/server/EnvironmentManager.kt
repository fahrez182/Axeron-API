package frb.axeron.server

import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The user's env, kept in axeron_env.json. Plain blocking file I/O under one lock: it ran in
 * runBlocking + withContext(IO) before, called from binder threads, and a failed write there
 * left nothing behind but the old value. A write that fails now throws, with the file's state
 * unchanged on disk: written to a temporary file first, then renamed over the old one.
 */
class EnvironmentManager(val isRoot: Boolean = false) {

    private val fileName = "axeron_env.json"
    private val file: File
        get() = File(
            PathHelper.getWorkingPath(isRoot, AxeronApiConstant.folder.PARENT),
            fileName
        ).absoluteFile
    private val lock = Any()
    private var data: HashMap<String, String> = hashMapOf()

    init {
        synchronized(lock) { load() }
    }

    /** Memuat file JSON ke HashMap */
    private fun load() {
        val file = file
        if (!file.exists()) return
        val jsonText = file.readText()
        if (jsonText.isEmpty()) return
        val jsonObject = JSONObject(jsonText)
        data = jsonObject.keys().asSequence()
            .associateWith { jsonObject.getString(it) }
            .toMap(HashMap())
    }

    /** Writes [map] to the file; [data] takes it only once it is on disk. */
    private fun save(map: HashMap<String, String>) {
        val file = file
        val tmp = File(file.parentFile, "$fileName.tmp")
        tmp.writeText(JSONObject(map as Map<*, *>).toString(4))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("can't replace $file")
        }
        data = map
    }

    /** PUT - Tambah atau update satu key */
    fun put(key: String, value: String) = synchronized(lock) {
        save(HashMap(data).apply { put(key, value) })
    }

    /** PUT ALL - Tambahkan atau update banyak key-value */
    fun putAll(map: Map<String, String>) = synchronized(lock) {
        save(HashMap(data).apply { putAll(map) })
    }

    fun replaceAll(map: Map<String, String>) = synchronized(lock) {
        save(HashMap(map))
    }

    /** READ - Ambil value berdasarkan key */
    fun read(key: String): String? = synchronized(lock) { data[key] }

    /** DELETE - Hapus key */
    fun delete(key: String) = synchronized(lock) {
        save(HashMap(data).apply { remove(key) })
    }

    /** GET ALL - Ambil semua data */
    fun getAll(): Map<String, String> = synchronized(lock) {
        load()
        data.toMap()
    }

    fun putBlocking(key: String, value: String) = put(key, value)

    fun putAllBlocking(map: MutableMap<String, String>) = putAll(map)

    fun replaceAllBlocking(map: MutableMap<String, String>) = replaceAll(map)
}
