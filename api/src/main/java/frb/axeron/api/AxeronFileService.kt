package frb.axeron.api

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
import android.util.ArraySet
import android.util.Log
import frb.axeron.api.utils.Excluder
import frb.axeron.server.FileStat
import frb.axeron.server.IFileService
import frb.axeron.server.IOutputStreamCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class AxeronFileService : Parcelable {

    private var fileService: IFileService?

    constructor(fileService: IFileService) {
        this.fileService = fileService
        try {
            fileService.asBinder().linkToDeath(IBinder.DeathRecipient {
                this.fileService = null
                Log.v(TAG, "AxeronFileService is dead")

                CACHE.remove(this@AxeronFileService)
            }, 0)
        } catch (e: RemoteException) {
            Log.e(TAG, "linkToDeath", e)
        }

        // The reference to the binder object must be hold
        CACHE.add(this)
    }

    protected constructor(parcel: Parcel) {
        fileService = IFileService.Stub.asInterface(parcel.readStrongBinder())
    }

    private fun getFS(): IFileService {
        fileService?.let { return it }
        return try {
            Axeron.requireService().fileService.also { fileService = it }
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    protected open fun getDynamicBufferSize(fileSize: Long): Int = maxOf(fileSize * 2 / 100, 4096L).toInt()

    @Throws(IOException::class)
    protected open fun dynamicWriteFile(os: OutputStream, input: InputStream, size: Long) {
        val buffer = ByteArray(getDynamicBufferSize(size))
        while (true) {
            val length = input.read(buffer)
            if (length <= 0) break
            os.write(buffer, 0, length)
        }
    }

    fun getStreamSession(destination: String, ensureParents: Boolean, append: Boolean): StreamSession? {
        return try {
            StreamSession(getFS(), destination, ensureParents, append)
        } catch (e: IOException) {
            null
        } catch (e: RemoteException) {
            null
        }
    }

    @Throws(RemoteException::class, FileNotFoundException::class)
    fun setFileInputStream(source: String): FileInputStream {
        // Read through a pipe we own instead of the server's fd of the file: SELinux would
        // reject that fd on the binder hop for files apps can't read (e.g. /data/adb).
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            throw FileNotFoundException(e.message)
        }
        val opened = try {
            getFS().inputStreamPfd(source, pipe[1])
        } finally {
            // The server got its own dup; ours must go or the reader never sees EOF.
            closeQuietly(pipe[1])
        }
        if (!opened) {
            closeQuietly(pipe[0])
            throw FileNotFoundException(source)
        }
        return ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
    }

    fun exists(path: String): Boolean = try {
        getFS().exists(path)
    } catch (e: RemoteException) {
        false
    }

    fun mkdirs(path: String): Boolean = try {
        getFS().mkdirs(path)
    } catch (e: RemoteException) {
        false
    }

    fun fsync(pfd: ParcelFileDescriptor): Boolean = try {
        getFS().fsync(pfd)
    } catch (e: RemoteException) {
        false
    }

    fun delete(pathFrom: String): Boolean = delete(pathFrom, true)

    fun delete(pathFrom: String, removeParent: Boolean): Boolean =
        smartDelete(pathFrom, null, removeParent) != null

    fun smartDelete(pathFrom: String, excluders: List<Excluder>?): List<FileStat>? =
        smartDelete(pathFrom, excluders, true)

    fun smartDelete(pathFrom: String, excluders: List<Excluder>?, removeParent: Boolean): List<FileStat>? {
        Log.d("smartDelete", "pathFrom: $pathFrom")
        val checkedFolders = HashSet<String?>()
        return smartDeleteRecursive(pathFrom, pathFrom, excluders, checkedFolders, removeParent)
    }

    private fun smartDeleteRecursive(
        rootPath: String,
        folderPath: String,
        excluders: List<Excluder>?,
        checkedFolders: MutableSet<String?>,
        removeParent: Boolean
    ): List<FileStat>? {
        val tag = "smartDelete"
        val deleted = ArrayList<FileStat>()

        try {
            val pathStat = getFS().stat(folderPath)

            if (!pathStat.exists) return null
            // Jika file/folder masuk daftar exclude, jangan dihapus
            checkedFolders.add(pathStat.parent)

            if (Excluder.shouldSmartExclude(pathStat.path!!, excluders)) {
                Log.d(tag, "Skipping deleted: " + pathStat.path)
                return deleted
            }

            if (pathStat.isDirectory) {
                val children = getDirectories(pathStat.path!!)

                for (childStat in children) {
                    val childFile = File(childStat.path!!)

                    val result = smartDeleteRecursive(
                        rootPath,
                        childFile.absolutePath,
                        excluders,
                        checkedFolders,
                        removeParent
                    ) ?: return null
                    deleted.addAll(result)
                }

                if (!removeParent && rootPath == folderPath) return deleted

                if (checkedFolders.contains(folderPath)) {
                    getFS().delete(folderPath)
                    return deleted
                }

                // Hapus folder setelah isinya dihapus
                if (!getFS().delete(folderPath)) {
                    Log.e(tag, "Gagal menghapus folder: $folderPath")
                    return null
                }

                deleted.add(pathStat)
                Log.d(tag, "Folder deleted: $folderPath")
            } else {
                if (!getFS().delete(folderPath)) {
                    Log.e(tag, "Gagal menghapus file: $folderPath")
                    return null
                }

                deleted.add(pathStat)
                Log.d(tag, "File deleted: $folderPath")
            }

        } catch (e: RemoteException) {
            Log.e(tag, "Error deleting: " + e.message, e)
            return null
        }

        return deleted
    }

    fun getDirectories(folderPath: String): List<FileStat> = try {
        getFS().list(folderPath, IFileService.FILTER_ALL, IFileService.SORT_NAME, true, 0, -1)
    } catch (e: RemoteException) {
        ArrayList()
    }

    fun copy(pathFrom: String, destination: String): Boolean =
        smartCopy(pathFrom, destination, null) != null

    fun smartCopy(pathFrom: String, destination: String, excluders: List<Excluder>?): List<FileStat>? =
        smartCopy(pathFrom, destination, excluders, false)

    fun smartCopy(
        pathFrom: String,
        destination: String,
        excluders: List<Excluder>?,
        sanitize: Boolean
    ): List<FileStat>? {
        val sanitizedPath: MutableSet<String>? = if (sanitize) HashSet() else null
        val result = smartCopyRecursive(pathFrom, pathFrom, destination, destination, excluders, sanitizedPath)
        if (sanitizedPath != null) {
            Log.d("smartDelete", "SanitizedCopy: $sanitizedPath")
            smartDelete(
                destination,
                arrayListOf(Excluder(Excluder.Mode.START, sanitizedPath, false)),
                false
            )
            sanitizedPath.clear()
        }
        return result
    }

    private fun smartCopyRecursive(
        rootPath: String,
        folderPath: String,
        rootDestination: String,
        destination: String,
        excluders: List<Excluder>?,
        sanitizedPath: MutableSet<String>?
    ): List<FileStat>? {
        val copied = ArrayList<FileStat>()

        try {
            val pathStat = getFS().stat(folderPath)
            val destStat = getFS().stat(destination)
            if (!pathStat.exists) return null
            val dest = File(destStat.path!!)
            val relativePath = getRelativePath(rootPath, pathStat.path!!)

            // Skip jika folder masuk daftar exclude
            if (Excluder.shouldSmartExclude(relativePath, excluders)) {
                // Jika file/folder sebelumnya sudah ada di destination dan sekarang excluded, hapus
                if (pathStat.isFile) {
                    getFS().delete(destStat.path)
                    Log.i(TAG, "File dihapus karena excluded")
                } else if (pathStat.isDirectory) {
                    if (delete(destStat.path!!)) {
                        Log.i(TAG, "Folder dihapus karena excluded")
                    } else {
                        Log.e(TAG, "Gagal menghapus folder karena excluded")
                    }
                } else {
                    Log.d(TAG, "Skipping excluded: " + getRelativePath(rootPath, pathStat.path!!))
                }

                return copied
            }

            if (pathStat.isDirectory) {
                if (!getFS().mkdirs(destination)) {
                    Log.e(TAG, "Gagal membuat directory: $destination")
                    return null
                }

                val children = getDirectories(folderPath)
                for (childStat in children) {
                    val childFile = File(childStat.path!!)
                    val result = smartCopyRecursive(
                        rootPath, // tetap selalusama
                        childFile.absolutePath,
                        rootDestination,
                        File(dest, childFile.name).absolutePath,
                        excluders,
                        sanitizedPath
                    ) ?: return null
                    copied.addAll(result)
                }
            } else {
                if (sanitizedPath != null) {
                    if (rootDestination != destStat.path) sanitizedPath.add(destStat.path!!)
                }

                if (destStat.isFile &&
                    pathStat.length == destStat.length &&
                    pathStat.lastModified == destStat.lastModified
                ) {
                    Log.d(TAG, "Skipping identical file: " + pathStat.path)
                    return copied
                }

                Log.i(TAG, "Copying from: " + pathStat.path + " to " + destStat.path)

                if (destStat.isFile && !getFS().delete(destStat.path)) {
                    Log.e(TAG, "Gagal menghapus file tujuan sebelum overwrite: " + destStat.path)
                    return null
                }

                setFileInputStream(pathStat.path!!).use { input ->
                    val session = getStreamSession(destStat.path!!, true, false)
                        ?: throw IOException("Can't open a stream to " + destStat.path)
                    session.outputStream.use { output ->
                        dynamicWriteFile(output, input, input.channel.size())

                        getFS().setLastModified(destination, pathStat.lastModified)
                        copied.add(destStat)
                        Log.d(TAG, "File copied successfully: $folderPath")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error copying folder: " + e.message, e)
            return null
        } catch (e: RemoteException) {
            Log.e(TAG, "Error copying folder: " + e.message, e)
            return null
        }

        return copied
    }

    @Throws(RemoteException::class)
    fun createNewFile(absolutePath: String): Boolean = getFS().createNewFile(absolutePath)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(getFS().asBinder())
    }

    override fun describeContents(): Int = 0

    /**
     * Writes to [destination] through a pipe that a server thread drains into the file.
     * [close] sends EOF and blocks until the server reports the file is fully written
     * and synced, so the file can be chmod-ed or executed right after.
     */
    class StreamSession @Throws(IOException::class, RemoteException::class) constructor(
        fileService: IFileService,
        private val destination: String,
        ensureParents: Boolean,
        append: Boolean
    ) : AutoCloseable {

        private val inputFd: ParcelFileDescriptor
        private val outputFd: ParcelFileDescriptor

        private val done = CountDownLatch(1)
        private val closed = AtomicBoolean(false)

        /** True once the server confirmed the whole stream was written and synced. */
        @Volatile
        var isSuccessful = false
            private set

        init {
            val file = fileService.stat(destination)
            if (ensureParents) {
                val p: FileStat? = fileService.stat(file.parent)
                if (p != null && !p.exists) {
                    fileService.mkdirs(p.path)
                }
            }

            val pipe = ParcelFileDescriptor.createPipe()
            inputFd = pipe[0]
            outputFd = pipe[1]
            val callback = object : IOutputStreamCallback.Stub() {
                override fun onComplete() {
                    isSuccessful = true
                    done.countDown()
                    Log.d("StreamSession", "Stream finished safely: $destination")
                }

                override fun onError(errCode: Int, message: String?) {
                    done.countDown()
                    Log.e("StreamSession", "Stream error ($errCode): $destination: $message")
                }
            }
            try {
                fileService.outputStreamPfd(destination, inputFd, callback, append)
            } catch (e: RemoteException) {
                closeQuietly(inputFd)
                closeQuietly(outputFd)
                throw e
            }
        }

        /** Closing the returned stream closes the session too, so it also waits for the server. */
        val outputStream: FileOutputStream
            get() = object : FileOutputStream(outputFd.fileDescriptor) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        this@StreamSession.close()
                    }
                }
            }

        val inputStream: FileInputStream
            get() = FileInputStream(inputFd.fileDescriptor)

        /**
         * Closes the write end (EOF for the server) and waits for the server to finish writing.
         * Must not run on the main thread. Safe to call more than once.
         */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return

            closeQuietly(outputFd)
            closeQuietly(inputFd)

            try {
                if (!done.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.e("StreamSession", "Stream not completed in ${COMPLETION_TIMEOUT_SECONDS}s: $destination")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private companion object {
            const val COMPLETION_TIMEOUT_SECONDS = 30L
        }
    }

    companion object {
        private const val TAG = "AxeronFileService"
        private val CACHE: MutableSet<AxeronFileService> = Collections.synchronizedSet(ArraySet())

        @JvmField
        val CREATOR = object : Parcelable.Creator<AxeronFileService> {
            override fun createFromParcel(parcel: Parcel): AxeronFileService = AxeronFileService(parcel)

            override fun newArray(size: Int): Array<AxeronFileService?> = arrayOfNulls(size)
        }

        @JvmStatic
        fun getRelativePath(rootPath: String, fullPath: String): String =
            File(rootPath).toURI().relativize(File(fullPath).toURI()).path

        private fun closeQuietly(pfd: ParcelFileDescriptor) {
            try {
                pfd.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
