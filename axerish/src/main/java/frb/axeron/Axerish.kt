package frb.axeron

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private fun sha256(input: java.io.InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8 * 1024)
    var read: Int
    while (input.read(buffer).also { read = it } != -1) {
        digest.update(buffer, 0, read)
    }
    return digest.digest()
}

private fun isAssetIdentical(
    context: Context,
    assetName: String,
    file: File
): Boolean {
    if (!file.exists()) return false

    val assetHash = context.assets.open(assetName).use {
        sha256(it)
    }
    val fileHash = file.inputStream().use {
        sha256(it)
    }

    return assetHash.contentEquals(fileHash)
}


fun copyAssetIfDifferent(
    context: Context,
    assetName: String,
    outFile: File
): Boolean {
    if (isAssetIdentical(context, assetName, outFile)) {
        return false
    }

    if (outFile.exists()) {
        outFile.delete()
    }

    outFile.parentFile?.mkdirs()

    context.assets.open(assetName).use { input ->
        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
            Os.fsync(output.fd)
        }
    }

    return true
}


object Axerish {
    const val TAG = "Axerish"

    lateinit var axerish_path: File
    lateinit var dex_path: File

    fun initialize(context: Context) {
        Log.i(TAG, "Initializing Axerish")

        val filesDir = "${'/'}data/data/${context.packageName}/files"
        axerish_path = File(filesDir, "bin/axerish")
        dex_path = File(filesDir, "bin/shell_axerish.dex")

        // permission AFTER copy
        try {
            if (copyAssetIfDifferent(context, "axerish", axerish_path)) {
                Os.chmod(axerish_path.absolutePath, "755".toInt(8))
            } else {
                Log.i(TAG, "Axerish already exists")
            }
            if (copyAssetIfDifferent(context, "shell_axerish.dex", dex_path)) {
                Os.chmod(dex_path.absolutePath, "400".toInt(8))
            } else {
                Log.i(TAG, "Axerish dex already exists")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to Init Axerish", e)
        }
    }
}

