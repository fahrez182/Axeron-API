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

    fun initialize(packageName: String) {
        Log.i(TAG, "Initializing Axerish (shell+checksum) for $packageName")

        val baseDir = "${'/'}data/data/${packageName}/files/bin"
        axerish_path = File(baseDir, "axerish")
        dex_path = File(baseDir, "shell_axerish.dex")

        val cmd = $$"""
            set -e
    
            APK=$(pm path $$packageName | sed 's/package://')
            if [ -z "$APK" ]; then
                echo "base.apk not found"
                exit 1
            fi
    
            mkdir -p $$baseDir
    
            check_and_extract() {
                ASSET_NAME=$1
                OUT_FILE=$2
                MODE=$3
    
                TMP_HASH=$(unzip -p "$APK" "assets/$ASSET_NAME" | sha256sum | cut -d' ' -f1)
    
                if [ -f "$OUT_FILE" ]; then
                    CUR_HASH=$(sha256sum "$OUT_FILE" | cut -d' ' -f1)
                else
                    CUR_HASH=""
                fi
    
                if [ "$TMP_HASH" = "$CUR_HASH" ]; then
                    echo "$ASSET_NAME unchanged, skip"
                    return
                fi
    
                echo "Updating $ASSET_NAME"
                unzip -o "$APK" "assets/$ASSET_NAME" -d $$baseDir
                mv "$$baseDir/assets/$ASSET_NAME" "$OUT_FILE"
                chmod $MODE "$OUT_FILE"
            }
    
            check_and_extract axerish $${axerish_path.absolutePath} 755
            check_and_extract shell_axerish.dex $${dex_path.absolutePath} 400
    
            rmdir $$baseDir/assets 2>/dev/null || true
            echo "Axerish init done"
        """.trimIndent()

        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", cmd)
            )

            p.inputStream.bufferedReader().useLines {
                it.forEach { line -> Log.i(TAG, line) }
            }
            p.errorStream.bufferedReader().useLines {
                it.forEach { line -> Log.e(TAG, line) }
            }

            val code = p.waitFor()
            Log.i(TAG, "Axerish init exit code=$code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Axerish (shell)", e)
        }
    }

}

