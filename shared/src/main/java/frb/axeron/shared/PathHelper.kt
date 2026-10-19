package frb.axeron.shared

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File

object PathHelper {
    @JvmStatic
    fun getPath(folderName: String): File {
        return File(Environment.getExternalStorageDirectory(), folderName)
    }

    @Deprecated("The working path depends on the mode", ReplaceWith("getWorkingPath(rootMode, folderName)"))
    @JvmStatic
    fun getWorkingPath(folderName: String?): File {
        if (folderName == null) return File(AxeronApiConstant.folder.SHELL_DE)
        return File(AxeronApiConstant.folder.SHELL_DE, folderName)
    }

    /**
     * Root and shell each keep their own world. Root's lives in /data/adb, where root solutions
     * (Magisk, KernelSU, APatch) keep their data; it used to be [AxeronApiConstant.folder.ROOT_DE].
     */
    @JvmStatic
    fun getWorkingPath(rootMode: Boolean, folderName: String?): File {
        if (rootMode) {
            if (folderName == null) return File(AxeronApiConstant.folder.ADB)
            return File(AxeronApiConstant.folder.ADB, folderName)
        }
        if (folderName == null) return File(AxeronApiConstant.folder.SHELL_DE)
        return File(AxeronApiConstant.folder.SHELL_DE, folderName)
    }

    @JvmStatic
    fun getTmpPath(folderName: String): File {
        return File(AxeronApiConstant.folder.TMP, folderName)
    }

    @JvmStatic
    fun getRelativePath(rootPath: String, fullPath: String): String {
        return File(rootPath).toURI().relativize(File(fullPath).toURI()).path
    }

    @JvmStatic
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }


}
