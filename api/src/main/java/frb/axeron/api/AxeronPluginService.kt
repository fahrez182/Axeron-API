package frb.axeron.api

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.annotations.SerializedName
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Engine.Companion.application
import frb.axeron.shell.Axsh
import frb.axeron.server.Environment
import frb.axeron.server.PluginInstaller
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture


object AxeronPluginService {
    const val TAG = "PluginService"

    val BUSYBOX: String
        get() = "${application.applicationInfo.nativeLibraryDir}/libbusybox.so"
    val RESETPROP: String
        get() = "${application.applicationInfo.nativeLibraryDir}/libresetprop.so"
    /** The plugin igniter's dex (the manager's :reignite module), run in app_process. */
    val IGNITER: String
        get() = "${application.applicationInfo.nativeLibraryDir}/libaxreignite.so"
    val BASEAPK: String
        get() = application.applicationInfo.sourceDir

    val ROOT_MODE
        get() = Axeron.getAxeronInfo().isRoot()

    val AXERONDIR: String
        get() = PathHelper.getWorkingPath(ROOT_MODE, AxeronApiConstant.folder.PARENT).absolutePath
    val AXERONBIN: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_BINARY
        ).absolutePath
    val PLUGINDIR: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_PLUGIN
        ).absolutePath
    val PLUGINUPDATEDIR: String
        get() = PathHelper.getWorkingPath(
            ROOT_MODE,
            AxeronApiConstant.folder.PARENT_PLUGIN_UPDATE
        ).absolutePath

    private val axFS: AxeronFileService?
        get() = Axeron.newFileService()

    /**
     * Env var the igniter looks for to find a plugin's processes (AXERON_PLUGIN=<id>): whatever
     * a plugin's own code starts must carry it, so disabling the plugin can stop it.
     */
    const val PLUGIN_MARKER = "AXERON_PLUGIN"

    /**
     * The server env, tagged with [pluginId] when a plugin's code runs in it. The env sent
     * replaces the process env as a whole, so with no base env to add to, nothing is tagged.
     */
    private fun environmentFor(pluginId: String?): Environment? {
        val base = Axeron.getEnvironment()
        if (pluginId.isNullOrEmpty() || base == null) return base
        return Environment(HashMap(base.envMap).apply { put(PLUGIN_MARKER, pluginId) }, base.isNewEnv)
    }

    fun getUid(context: Context, packageName: String): Int? =
        try {
            context.packageManager
                .getApplicationInfo(packageName, 0)
                .uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun checkManageExternalStorageUid(
        context: Context,
        uid: Int,
        packageName: String
    ): Int {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return appOps.unsafeCheckOpNoThrow(
            "android:manage_external_storage",
            uid,
            packageName
        )
    }

    fun allowManageExternalStorageUid(uid: Int): Int {
        return Axsh.exec(
            arrayOf(
                "sh",
                "-c",
                "cmd appops set --uid $uid MANAGE_EXTERNAL_STORAGE allow"
            )
        ).waitFor()
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    fun ensureManageExternalStorageAllowed(
        context: Context,
        packageNames: List<String> = listOf(
            "com.android.externalstorage",
            "com.android.providers.downloads",
            "com.google.android.storagemanager"
        ),
        onResult: (Boolean) -> Unit
    ) {
        Thread {
            var allAllowed = true

            packageNames.forEach { pkg ->
                val uid = getUid(context, pkg) ?: return@forEach

                val mode = checkManageExternalStorageUid(
                    context = context,
                    uid = uid,
                    packageName = pkg
                )

                if (mode != AppOpsManager.MODE_DEFAULT && mode != AppOpsManager.MODE_ALLOWED) {
                    val exitCode = allowManageExternalStorageUid(uid)
                    if (exitCode != 0) {
                        allAllowed = false
                        return@forEach
                    }

                    // re-check (wajib)
                    val recheck = checkManageExternalStorageUid(
                        context = context,
                        uid = uid,
                        packageName = pkg
                    )

                    if (recheck != AppOpsManager.MODE_ALLOWED) {
                        allAllowed = false
                    }
                }
            }

            // balik ke main thread
            Handler(Looper.getMainLooper()).post {
                onResult(allAllowed)
            }
        }.start()
    }


    data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
        constructor(result: ResultExec, showReboot: Boolean) : this(
            result.code,
            result.err,
            showReboot
        )

        constructor(result: ResultExec) : this(result, result.isSuccess())
    }

    suspend fun flashPlugin(
        installer: PluginInstaller,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit
    ): FlashResult {
        val fs = axFS ?: return FlashResult(-1, "Axeron service unavailable", false)
        val file =
            File(
                PathHelper.getWorkingPath(ROOT_MODE, AxeronApiConstant.folder.PARENT_ZIP),
                "module.zip"
            )

        val copied = withContext(Dispatchers.IO) {
            application.contentResolver.openInputStream(installer.uri)?.use { input ->
                copyStream(input, fs, file.absolutePath)
            } ?: false
        }
        if (!copied) {
            fs.delete(file.absolutePath)
            return FlashResult(-1, "Failed to copy module zip", false)
        }

        val cmd =
            "ZIPFILE=${file.absolutePath}; . functions.sh; install_plugin ${installer.autoEnable}; exit 0"
        val result = execWithIO(cmd, onStdout, onStderr, standAlone = true)

        Log.i(TAG, "install module ${installer.uri} result: $result")

        fs.delete(file.absolutePath)

        return FlashResult(result)
    }

    data class ResultExec(
        @SerializedName("errno")
        val code: Int,
        @SerializedName("stdout")
        val out: String = "",
        @SerializedName("stderr")
        val err: String = ""
    ) {
        fun isSuccess(): Boolean {
            return code == 0
        }
    }

    /**
     * Streams [stream] as text chunks. Chunks are raw stream output: they can split or join
     * lines, so callers must not treat one as a line. The reader keeps UTF-8 decoding state,
     * so a multibyte character split across reads stays intact.
     */
    private inline fun readChunks(stream: InputStream, onChunk: (String) -> Unit) {
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val buf = CharArray(4096)
        while (true) {
            val len = reader.read(buf)
            if (len < 0) break
            if (len > 0) onChunk(String(buf, 0, len))
        }
    }

    suspend fun execWithIO(
        cmd: String,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        useSetsid: Boolean = false,
        useBusybox: Boolean = true,
        standAlone: Boolean = false,
        hideStderr: Boolean = true,
        /** The plugin whose code this runs (action.sh, its WebUI), tagged with [PLUGIN_MARKER]. */
        pluginId: String? = null
    ): ResultExec = runCatching {

        Log.d(TAG, "execWithIO: $cmd")

        val process = Axsh.exec(
            if (useSetsid) arrayOf(BUSYBOX, "setsid", "sh")
            else arrayOf(BUSYBOX, "sh"),
            environmentFor(pluginId)?.env
        )

        process.outputStream.use { os ->
            val cmdLine = when {
                useBusybox && !standAlone -> "$BUSYBOX sh -c \"$cmd\"\n"
                useBusybox && standAlone -> "$BUSYBOX sh -o standalone -c \"$cmd\"\n"
                else -> "sh -c \"$cmd\"\n"
            }
            os.write(cmdLine.toByteArray())
            os.flush()
        }

        val builderOut = StringBuilder()
        val builderErr = StringBuilder()

        coroutineScope {

            val jobStdout = async(Dispatchers.IO) {
                readChunks(process.inputStream) { chunk ->
                    synchronized(builderOut) { builderOut.append(chunk) }
                    onStdout(chunk)
                }
            }

            val jobStderr = async(Dispatchers.IO) {
                readChunks(process.errorStream) { chunk ->
                    synchronized(builderErr) { builderErr.append(chunk) }
                    onStderr(chunk)
                }
            }

            // Tunggu keduanya selesai
            jobStdout.await()
            jobStderr.await()
        }

        val exit = process.waitFor()
        process.destroy()

        ResultExec(
            code = exit,
            out = builderOut.toString(),
            err = if (!hideStderr) builderErr.toString() else ""
        )
    }.getOrElse { e ->
        if (e is kotlinx.coroutines.CancellationException || e.toString()
                .contains("CancellationException")
        ) throw e
        ResultExec(-1, err = e.toString())
    }

    fun execWithIOFuture(
        cmd: String,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        useBusybox: Boolean = true,
        standAlone: Boolean = false,
        hideStderr: Boolean = true,
        /** The plugin whose code this runs (its WebUI), tagged with [PLUGIN_MARKER]. */
        pluginId: String? = null
    ): CompletableFuture<ResultExec> {

        val future = CompletableFuture<ResultExec>()

        CoroutineScope(Dispatchers.IO).launch {

            val result = runCatching {

                val process = Axsh.exec(arrayOf("sh"), environmentFor(pluginId)?.env)

                // KIRIM COMMAND
                process.outputStream.use { os ->
                    val cmdLine = when {
                        useBusybox && !standAlone -> "$BUSYBOX sh -c \"$cmd\"\n"
                        useBusybox && standAlone -> "$BUSYBOX sh -o standalone -c \"$cmd\"\n"
                        else -> "sh -c \"$cmd\"\n"
                    }
                    os.write(cmdLine.toByteArray())
                    os.flush()
                }

                val builderOut = StringBuilder()
                val builderErr = StringBuilder()

                supervisorScope {

                    val jobOut = async(Dispatchers.IO) {
                        readChunks(process.inputStream) { chunk ->
                            synchronized(builderOut) { builderOut.append(chunk) }
                            onStdout(chunk)
                        }
                    }

                    val jobErr = async(Dispatchers.IO) {
                        readChunks(process.errorStream) { chunk ->
                            synchronized(builderErr) { builderErr.append(chunk) }
                            onStderr(chunk)
                        }
                    }

                    jobOut.await()
                    jobErr.await()
                }

                val exit = process.waitFor()
                process.destroy()

                ResultExec(
                    code = exit,
                    out = builderOut.toString(),
                    err = if (!hideStderr) builderErr.toString() else ""
                )
            }

            future.complete(
                result.getOrElse { e ->
                    ResultExec(-1, err = e.toString())
                }
            )
        }

        return future
    }


    fun togglePlugin(dirId: String, enable: Boolean): Boolean {
        val fs = axFS ?: return false
        val path = "$PLUGINDIR/$dirId"
        val updatePath = "$PLUGINUPDATEDIR/$dirId"

        if (enable) {
            // hapus disable di plugin folder
            fs.delete("$path/disable")

            //buat file jika memang dari awal gak ada keduanya
            if (!fs.exists("$updatePath/update_disable") && !fs.exists("$updatePath/update_enable")) {
                return fs.createNewFile("$updatePath/update_enable")
            }

            // hapus update_disable jika ada
            fs.delete("$updatePath/update_disable")
            // buat update_enable kalau belum ada

        } else {
            fs.createNewFile("$path/disable")

            // kalau update_enable ada, hapus update_enable
            if (!fs.exists("$updatePath/update_enable") && !fs.exists("$updatePath/update_disable")) {
                return fs.createNewFile("$updatePath/update_disable")
            }

            fs.delete("$updatePath/update_enable")
        }

        // kalau semua file sudah sesuai kondisi, return true
        return true
    }


    fun uninstallPlugin(dirId: String): Boolean {
        val fs = axFS ?: return false
        val path = "$PLUGINDIR/$dirId"
        val updatePath = "$PLUGINUPDATEDIR/$dirId"

        return fs.createNewFile("$path/remove") && fs.createNewFile("$updatePath/update_remove")
    }

    fun restorePlugin(dirId: String): Boolean {
        val fs = axFS ?: return false
        val path = "$PLUGINDIR/$dirId"
        val updatePath = "$PLUGINUPDATEDIR/$dirId"

        return fs.delete("$path/remove") && fs.delete("$updatePath/update_remove")
    }

    //===================================
    // IGNITER
    //===================================

    suspend fun resetManagerNative(
        rootMode: Boolean,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): FlashResult = withContext(Dispatchers.IO) {

        fun out(s: String) = onStdout(s + "\n")
        fun err(s: String) = onStderr(s + "\n")

        val axeronDir = PathHelper.getWorkingPath(rootMode, AxeronApiConstant.folder.PARENT).absolutePath
        val pluginDir = PathHelper.getWorkingPath(
            rootMode,
            AxeronApiConstant.folder.PARENT_PLUGIN
        ).absolutePath

        out("Resetting AxManager")
        out("at $axeronDir")
        out("- Removing plugins")

        // 1) mark plugin remove
        val fs = axFS
        val pluginsDirs = fs?.getDirectories(pluginDir) ?: emptyList()
        if (pluginsDirs.isEmpty()) {
            out("- No plugins directory")
        } else {
            pluginsDirs.filter {
                it.isDirectory
            }.forEach { plugin ->
                out("- Mark to remove ${plugin.path}")
                runCatching {
                    fs?.createNewFile(File(plugin.path, "remove").absolutePath)
                }.onFailure {
                    err("!! failed touch ${plugin.path}/remove : ${it.message}")
                }
            }
        }

        // 2) jalankan igniter secara native (langsung panggil class)
        out("- Running igniter (native)")
        runCatching {
            igniteSuspendService(false, onStdout, onStderr)
        }.onFailure {
            err("!! Igniter crash: ${it.stackTraceToString()}")
            return@withContext FlashResult(-1, it.stackTraceToString(), false)
        }

        // 3) hapus folder: each mode has its own AXERONDIR, so this wipes only [rootMode]'s.
        out("- Removing AXERONDIR")
        val removed = execWithIO("rm -rf \"$axeronDir\"", hideStderr = false)
        if (!removed.isSuccess()) err("!! failed to remove $axeronDir: ${removed.err}")

        out("Complete")

        FlashResult(0, "", true)
    }


    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        fun isSuccess() = exitCode == 0
    }

    suspend fun execProcessSafe(
        cmd: Array<String>,
        env: Environment? = null,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): ExecResult = withContext(Dispatchers.IO) {

        val process = Axsh.exec(cmd, env?.env)

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        // Callbacks get stream text like execWithIO, so each line keeps its newline.
        val outJob = launch {
            process.inputStream.bufferedReader().useLines {
                it.forEach { line ->
                    onStdout(line + "\n")
                    stdout.appendLine(line)
                }
            }
        }

        val errJob = launch {
            process.errorStream.bufferedReader().useLines {
                it.forEach { line ->
                    onStderr(line + "\n")
                    stderr.appendLine(line)
                }
            }
        }

        val exitCode = process.waitFor()

        outJob.join()
        errJob.join()

        process.destroy()

        ExecResult(exitCode, stdout.toString(), stderr.toString())
    }

    @JvmStatic
    fun igniteService(): Boolean {
        return runBlocking(Dispatchers.IO) {
            igniteSuspendService()
        }
    }

    suspend fun igniteSuspendService(
        ensure: Boolean = true,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): Boolean =
        withContext(Dispatchers.IO) {

            val localVer = Axeron.getAxeronInfo().getVersionCode()
            val serverVer = AxeronApiConstant.server.VERSION_CODE

            if (serverVer > localVer) {
                Log.i(TAG, "Updating.. $localVer < $serverVer")
                return@withContext false
            }

            if (Axeron.isFirstInit(true)) {
                Log.i(TAG, "First Init: Removing old bin")
                removeScripts()
                removeLibrary()
            }

            if (ensure) {
                if (!ensureLibrary()) return@withContext false
                if (!ensureScripts()) return@withContext false
            }

            val cmd =
                "CLASSPATH=$IGNITER; app_process / frb.axeron.reignite.Igniter ${AxeronSettings.getEnableDeveloperOptions()}"

            Log.d(TAG, "Start Init Service")

            val result = execProcessSafe(
                arrayOf(BUSYBOX, "sh", "-c", cmd),
                Axeron.getEnvironment(),
                onStdout,
                onStderr
            )

            if (result.stdout.isNotBlank()) Log.i(TAG, "STDOUT:\n${result.stdout}")
            if (result.stderr.isNotBlank()) Log.e(TAG, "STDERR:\n${result.stderr}")

            result.isSuccess()
        }


    suspend fun removeScripts() = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext
        val binDir = AXERONBIN

        application.assets.list("scripts")?.forEach { filename ->
            val dstFile = File(binDir, filename)
            if (fs.exists(dstFile.absolutePath)) {
                if (!fs.delete(dstFile.absolutePath)) {
                    Log.e(TAG, "failed to remove ${dstFile.absolutePath}")
                } else {
                    Log.i(TAG, "removed ${dstFile.absolutePath}")
                }
            }
        }
    }

    suspend fun removeLibrary() = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext
        val dstBusybox = File(AXERONBIN, "busybox")

        if (fs.exists(dstBusybox.absolutePath)) {
            if (!fs.delete(dstBusybox.absolutePath)) {
                return@withContext
            }

            val cmd = "find $AXERONBIN -type l -delete"
            val result = execWithIO(cmd, useBusybox = false, hideStderr = false)

            if (!result.isSuccess()) {
                Log.e(TAG, "remove symlink failed: ${result.err}")
                return@withContext
            }

            Log.i(TAG, "symlink from busybox removed")
        }
        val dstResetprop = File(AXERONBIN, "resetprop")
        if (fs.exists(dstResetprop.absolutePath)) {
            fs.delete(dstResetprop.absolutePath)
        }
    }

    private suspend fun copyStream(
        inputStream: InputStream,
        fs: AxeronFileService,
        dstPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = fs.getStreamSession(dstPath, true, false) ?: return@withContext false
        session.use {
            val outputStream = it.outputStream
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { read -> bytesRead = read } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
        }
        // close() waited for the server, so this is the real outcome of the write
        session.isSuccessful
    }

    private suspend fun ensureScripts(): Boolean = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext false
        val binDir = AXERONBIN

        if (!fs.exists(binDir) && !fs.mkdirs(binDir)) return@withContext false

        val assetManager = application.assets
        assetManager.list("scripts")?.forEach { filename ->
            val dstPath = File(binDir, filename).absolutePath
            if (fs.exists(dstPath)) return@forEach

            runCatching {
                assetManager.open("scripts/$filename").use { inputStream ->
                    if (copyStream(inputStream, fs, dstPath)) {
                        execWithIO("chmod 755 \"$dstPath\"", useBusybox = false)
                        execWithIO("$BUSYBOX dos2unix \"$dstPath\"", useBusybox = true)
                        Log.i(TAG, "$filename (Script) fixed with dos2unix")
                    }
                }
            }.onFailure {
                Log.e(TAG, "Failed to copy scripts/$filename", it)
            }
        }
        return@withContext true
    }

    suspend fun ensureLibrary(): Boolean = withContext(Dispatchers.IO) {
        val fs = axFS ?: return@withContext false
        return@withContext try {
            if (!fs.exists(AXERONBIN) && !fs.mkdirs(AXERONBIN)) return@withContext false

            val dstBusyBox = File(AXERONBIN, "busybox").absolutePath
            val dstResetProp = File(AXERONBIN, "resetprop").absolutePath

            if (!fs.exists(dstBusyBox)) {
                java.io.FileInputStream(BUSYBOX).use { inputStream ->
                    if (copyStream(inputStream, fs, dstBusyBox)) {
                        execWithIO("chmod 755 \"$dstBusyBox\"", useBusybox = false)
                        execWithIO("\"$dstBusyBox\" --install -s \"$AXERONBIN\"", useBusybox = false)
                    }
                }
            }

            if (!fs.exists(dstResetProp)) {
                java.io.FileInputStream(RESETPROP).use { inputStream ->
                    if (copyStream(inputStream, fs, dstResetProp)) {
                        execWithIO("chmod 755 \"$dstResetProp\"", useBusybox = false)
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure library", e)
            false
        }
    }

}
