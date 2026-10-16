package frb.axeron.shared;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;

import java.io.File;

public class PathHelper {
    public static File getPath(String folderName) {
        return new File(Environment.getExternalStorageDirectory(), folderName);
    }

    @Deprecated
    public static File getWorkingPath(String folderName) {
        if (folderName == null) return new File(AxeronApiConstant.folder.SHELL_DE);
        return new File(AxeronApiConstant.folder.SHELL_DE, folderName);
    }

    /**
     * Root and shell each keep their own world. Root's lives in /data/adb, where root solutions
     * (Magisk, KernelSU, APatch) keep their data; it used to be {@link AxeronApiConstant.folder#ROOT_DE}.
     */
    public static File getWorkingPath(boolean rootMode, String folderName) {
        if (rootMode) {
            if (folderName == null) return new File(AxeronApiConstant.folder.ADB);
            return new File(AxeronApiConstant.folder.ADB, folderName);
        }
        if (folderName == null) return new File(AxeronApiConstant.folder.SHELL_DE);
        return new File(AxeronApiConstant.folder.SHELL_DE, folderName);
    }

    public static File getTmpPath(String folderName) {
        return new File(AxeronApiConstant.folder.TMP, folderName);
    }

    public static String getRelativePath(String rootPath, String fullPath) {
        return new File(rootPath).toURI().relativize(new File(fullPath).toURI()).getPath();
    }

    @Nullable
    public static String getFileNameFromUri(Context context, Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst()) {
                    return cursor.getString(nameIndex);
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }


}
