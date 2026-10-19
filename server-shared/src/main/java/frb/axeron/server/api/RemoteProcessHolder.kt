package frb.axeron.server.api

import android.os.IBinder
import android.os.ParcelFileDescriptor
import frb.axeron.server.util.Logger
import frb.axeron.server.util.ParcelFileDescriptorUtil
import moe.shizuku.server.IRemoteProcess
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteProcessHolder(private val process: Process, token: IBinder?) : IRemoteProcess.Stub() {

    private var input: ParcelFileDescriptor? = null
    private var output: ParcelFileDescriptor? = null

    init {
        if (token != null) {
            try {
                val deathRecipient = IBinder.DeathRecipient {
                    try {
                        if (alive()) {
                            destroy()
                            LOGGER.i("destroy process because the owner is dead")
                        }
                    } catch (e: Throwable) {
                        LOGGER.w(e, "failed to destroy process")
                    }
                }
                token.linkToDeath(deathRecipient, 0)
            } catch (e: Throwable) {
                LOGGER.w(e, "linkToDeath")
            }
        }
    }

    override fun getOutputStream(): ParcelFileDescriptor {
        output?.let { return it }
        return try {
            ParcelFileDescriptorUtil.pipeTo(process.outputStream).also { output = it }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun getInputStream(): ParcelFileDescriptor {
        input?.let { return it }
        return try {
            ParcelFileDescriptorUtil.pipeFrom(process.inputStream).also { input = it }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun getErrorStream(): ParcelFileDescriptor {
        return try {
            ParcelFileDescriptorUtil.pipeFrom(process.errorStream)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun waitFor(): Int {
        return try {
            process.waitFor()
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        }
    }

    override fun exitValue(): Int = process.exitValue()

    override fun destroy() {
        process.destroy()
    }

    override fun alive(): Boolean {
        return try {
            exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    override fun waitForTimeout(timeout: Long, unitName: String): Boolean {
        val unit = TimeUnit.valueOf(unitName)
        val startTime = System.nanoTime()
        var rem = unit.toNanos(timeout)

        do {
            try {
                exitValue()
                return true
            } catch (ex: IllegalThreadStateException) {
                if (rem > 0) {
                    try {
                        Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100))
                    } catch (e: InterruptedException) {
                        throw IllegalStateException()
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime)
        } while (rem > 0)
        return false
    }

    private companion object {
        val LOGGER = Logger("RemoteProcessHolder")
    }
}
