package frb.axeron.server.util

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object ParcelFileDescriptorUtil {

    @JvmStatic
    @Throws(IOException::class)
    fun pipeFrom(inputStream: InputStream): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()

        TransferThread(inputStream, ParcelFileDescriptor.AutoCloseOutputStream(writeSide))
            .start()

        return readSide
    }

    @JvmStatic
    @Throws(IOException::class)
    fun pipeTo(outputStream: OutputStream): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()

        TransferThread(ParcelFileDescriptor.AutoCloseInputStream(readSide), outputStream)
            .start()

        return writeSide
    }

    class TransferThread(
        private val input: InputStream,
        private val output: OutputStream
    ) : Thread("ParcelFileDescriptor Transfer Thread") {

        init {
            isDaemon = true
        }

        override fun run() {
            val buf = ByteArray(8192)

            try {
                while (true) {
                    val len = input.read(buf)
                    if (len <= 0) break
                    output.write(buf, 0, len)
                    output.flush()
                }
            } catch (e: IOException) {
                Log.e("TransferThread", Log.getStackTraceString(e))
            } finally {
                try {
                    input.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                try {
                    output.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
