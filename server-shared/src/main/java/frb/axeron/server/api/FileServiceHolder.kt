package frb.axeron.server.api

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import frb.axeron.server.FileStat
import frb.axeron.server.IFileService
import frb.axeron.server.IOutputStreamCallback
import frb.axeron.server.util.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

class FileServiceHolder : IFileService.Stub() {

    // ---------- Implementasi IAxFile ----------

    override fun mkdirs(dirPath: String): Boolean = f(dirPath).mkdirs()

    override fun createNewFile(path: String): Boolean {
        val newFile = File(path)
        if (newFile.exists() && newFile.isFile) return true
        val parent = File(requireNotNull(newFile.parent))
        if (!parent.exists()) {
            if (!parent.mkdirs()) return false
        }

        return try {
            val fos = FileOutputStream(path, false)
            Os.fsync(fos.fd)
            fos.close()
            true
        } catch (e: IOException) {
            false
        } catch (e: ErrnoException) {
            false
        }
    }

    override fun delete(path: String): Boolean = f(path).delete()

    override fun exists(path: String): Boolean = f(path).exists()

    override fun stat(path: String): FileStat = toStat(f(path))

    override fun getCanonicalPath(path: String): String {
        return try {
            f(path).canonicalPath
        } catch (e: IOException) {
            ""
        }
    }

    override fun list(
        dirPath: String,
        filter: Int,
        sortBy: Int,
        ascending: Boolean,
        offset: Int,
        limit: Int
    ): MutableList<FileStat> {
        val out = ArrayList<FileStat>()
        val dir = f(dirPath)
        if (!dir.exists() || !dir.isDirectory) return out

        val children = dir.listFiles() ?: return out

        val filtered = ArrayList<File>(children.size)
        for (c in children) {
            when (filter) {
                IFileService.FILTER_FILES -> if (c.isFile) filtered.add(c)
                IFileService.FILTER_DIRECTORIES -> if (c.isDirectory) filtered.add(c)
                else -> filtered.add(c)
            }
        }

        filtered.sortWith(comparator(sortBy, ascending))

        val n = filtered.size
        val start = maxOf(0, minOf(offset, n))
        val end = if (limit <= 0) n else minOf(start + limit, n)

        for (i in start until end) out.add(toStat(filtered[i]))
        return out
    }

    override fun setLastModified(path: String, newLastModified: Long): Boolean =
        f(path).setLastModified(newLastModified)

    override fun chmod(path: String, mode: Int): Boolean {
        return try {
            Os.chmod(path, mode)
            true
        } catch (e: ErrnoException) {
            false
        }
    }

    override fun chown(path: String, uid: Int, gid: Int): Boolean {
        return try {
            Os.chown(path, uid, gid)
            true
        } catch (e: ErrnoException) {
            false
        }
    }

    override fun fsync(pfd: ParcelFileDescriptor): Boolean {
        return try {
            pfd.use {
                try {
                    Os.fsync(it.fileDescriptor)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: IOException) {
            false
        }
    }

    override fun inputStreamPfd(path: String, outputStreamPfd: ParcelFileDescriptor): Boolean {
        val fis = try {
            FileInputStream(path)
        } catch (e: FileNotFoundException) {
            try {
                outputStreamPfd.close()
            } catch (ignored: IOException) {
            }
            return false
        }

        Thread {
            try {
                fis.use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(outputStreamPfd).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val len = input.read(buffer)
                            if (len == -1) break
                            output.write(buffer, 0, len)
                        }
                    }
                }
            } catch (e: IOException) {
                // EPIPE here just means the reader closed early.
                LOGGER.w("inputStreamPfd: $path", e)
            }
        }.start()
        return true
    }

    override fun outputStreamPfd(
        path: String,
        inputStreamPfd: ParcelFileDescriptor?,
        callback: IOutputStreamCallback?,
        append: Boolean
    ) {
        Thread {
            try {
                FileInputStream(inputStreamPfd!!.fileDescriptor).use { fis ->
                    FileOutputStream(path, append).use { fos ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val len = fis.read(buffer)
                            if (len == -1) break
                            fos.write(buffer, 0, len)
                        }
                        fos.flush()
                        Os.fsync(fos.fd)
                    }
                }

                callback?.onComplete()
            } catch (e: IOException) {
                reportError(callback, IOutputStreamCallback.IO_EXCEPTION, e)
            } catch (e: ErrnoException) {
                reportError(callback, IOutputStreamCallback.ERRNO_EXCEPTION, e)
            } catch (e: RemoteException) {
                reportError(callback, IOutputStreamCallback.FILE_NOT_FOUND, e)
            } finally {
                try {
                    inputStreamPfd?.close()
                } catch (ignored: IOException) {
                }
            }
        }.start()
    }

    private fun reportError(callback: IOutputStreamCallback?, code: Int, e: Exception) {
        try {
            callback?.onError(code, e.message)
        } catch (ignored: RemoteException) {
        }
    }

    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        try {
            FileInputStream(dir).use { Os.fsync(it.fd) }
        } catch (ignored: Throwable) {
        }
    }

    @Throws(IOException::class, ErrnoException::class)
    private fun copyFileDurable(src: File, dst: File) {
        val buf = ByteArray(64 * 1024)

        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    output.write(buf, 0, r)
                }
                output.flush() // flush userspace buffer
                Os.fsync(output.fd) // fsync FILE tujuan
            }
        }

        // Optional: samakan timestamp (metadata)
        dst.setLastModified(src.lastModified())
    }

    override fun renameTo(src: String, dst: String, overwrite: Boolean): Boolean {
        val srcFile = f(src)
        val dstFile = f(dst)
        if (!srcFile.exists() || !srcFile.isFile) return false

        val dstParent = dstFile.parentFile
        if (dstParent != null && !dstParent.exists()) {
            if (!dstParent.mkdirs()) return false
        }

        // Handle overwrite
        if (dstFile.exists()) {
            if (!overwrite) return false
            if (!dstFile.delete()) return false
            fsyncDir(dstParent)
        }

        // 1) TRY ATOMIC RENAME (same filesystem)
        if (srcFile.renameTo(dstFile)) {
            // rename is atomic; fsync directory to persist metadata
            fsyncDir(dstParent)
            return true
        }

        // 2) FALLBACK: CROSS-FILESYSTEM MOVE
        // copy -> fsync(file) -> fsync(dst dir) -> delete src -> fsync(src dir)
        try {
            copyFileDurable(srcFile, dstFile)
        } catch (e: ErrnoException) {
            return false
        } catch (e: IOException) {
            return false
        }
        fsyncDir(dstParent)

        if (!srcFile.delete()) return false
        fsyncDir(srcFile.parentFile)

        return true
    }

    private companion object {
        const val TAG = "FileService"
        val LOGGER = Logger(TAG)

        fun f(path: String): File = File(path)

        // ---------- Helpers ----------

        fun toStat(file: File): FileStat {
            val s = FileStat()
            s.path = file.absolutePath
            s.name = file.name
            s.parent = file.parentFile?.absolutePath ?: ""
            s.exists = file.exists()
            s.isFile = s.exists && file.isFile
            s.isDirectory = s.exists && file.isDirectory
            s.isHidden = file.isHidden
            if (s.exists) {
                s.length = file.length()
                s.lastModified = file.lastModified()
            } else {
                s.length = 0L
                s.lastModified = 0L
            }
            s.canonicalPath = try {
                file.canonicalPath
            } catch (e: IOException) {
                ""
            }
            return s
        }

        fun comparator(sortBy: Int, asc: Boolean): Comparator<File> {
            val c: Comparator<File> = when (sortBy) {
                IFileService.SORT_LAST_MOD -> compareBy { it.lastModified() }
                IFileService.SORT_LENGTH -> compareBy { if (it.isFile) it.length() else -1L }
                else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
            return if (asc) c else c.reversed()
        }
    }
}
