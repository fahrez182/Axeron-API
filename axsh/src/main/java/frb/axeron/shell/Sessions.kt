package frb.axeron.shell

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import frb.axeron.server.IAxeronService
import frb.axeron.server.IAxshProcess
import frb.axeron.shared.AxeronApiConstant
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The axsh session protocol, for every place that serves it: the server's own socket
 * (AxshServer), an app's [Broker] and [Bootstrap]. They differ only in [Host].
 *
 * The client always brings its own pipes (SCM_RIGHTS on the handshake's first byte) and the
 * shell is started on them with [IAxeronService.newAxshProcess]: without a PTY they are its
 * stdio as they are, so no byte passes through here or through the server. The socket then only
 * carries FRAME_WINCH in and one FRAME_EXIT out. Wire format: `src/main/cpp/axsh_protocol.h`.
 */
class Sessions(private val host: Host) {

    interface Host {
        /** Log tag. */
        val tag: String

        /**
         * Null if the peer may run shells here, otherwise why not, which the client is told.
         * A host that knows how may ask the user for permission on the way.
         */
        fun refuse(uid: Int, pid: Int): String?

        /** The server to start shells on; null when it is out of reach. */
        fun service(): IAxeronService?

        /**
         * The server refused the shell ([SecurityException]). Ask the user for permission if this
         * host can, and block until they answer: true when granted, so the shell is tried again
         * in the same session. Give up after [PERMISSION_WAIT_MS].
         */
        fun awaitPermission(service: IAxeronService): Boolean = false
    }

    companion object {
        private const val VERSION = 1

        private const val STATUS_OK = 0
        private const val STATUS_DENIED = 1
        private const val STATUS_ERROR = 2

        private const val FLAG_PTY = 1

        private const val FRAME_EXIT = 0x02
        private const val FRAME_WINCH = 0x03

        private const val PIPE_FDS = 3
        private const val PTY_FDS = 2

        // Mirrors PTY_ENV in axsh_protocol.h; "LC_" is a prefix.
        private val PTY_ENV = listOf("TERM", "COLORTERM", "LANG")
        private const val PTY_ENV_PREFIX = "LC_"

        private const val MAX_ARGS = 4096
        private const val MAX_STRING = 1 shl 20

        const val DENIED_MESSAGE = "AxManager permission required: allow this app in AxManager"

        /** How long a session waits on the permission dialog before giving up. */
        const val PERMISSION_WAIT_MS = 15_000L

        /** [base] with the terminal's own entries from [client] (PTY_ENV) laid over it. */
        internal fun ptyEnvironment(base: Array<String>?, client: List<String>): Array<String> {
            val env = LinkedHashMap<String, String>()
            base?.forEach { entry -> env[entry.substringBefore('=')] = entry }
            for (entry in client) {
                val name = entry.substringBefore('=', missingDelimiterValue = "")
                if (name in PTY_ENV || name.startsWith(PTY_ENV_PREFIX)) env[name] = entry
            }
            return env.values.toTypedArray()
        }

        /** struct winsize packed as IAxshProcess.setWindowSize wants it, pixels left 0. */
        internal fun windowSize(rows: Int, cols: Int): Long =
            (rows.toLong() and 0xffff) or ((cols.toLong() and 0xffff) shl 16)

        /**
         * The exit code from [status], the read end of the pipe given to newAxshProcess; closes
         * it. EOF without the code: a server from before that parameter (it left the pipe unread)
         * or a dead one, so ask [remote], which holds one of the old server's binder threads as
         * before, or fails at once on a dead one.
         */
        internal fun awaitExit(status: ParcelFileDescriptor, remote: IAxshProcess): Int {
            val code = ByteArray(4)
            val delivered = try {
                ParcelFileDescriptor.AutoCloseInputStream(status).use { readFully(it, code) }
            } catch (_: IOException) {
                false
            }
            if (delivered) return ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).int
            return try {
                remote.waitFor()
            } catch (_: RemoteException) {
                -1 // the server died, and the shell with it
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }
    }

    private val tag get() = host.tag

    @Volatile
    private var listener: LocalServerSocket? = null

    /** Listens on the abstract socket [name], one thread per client. False if it can't bind. */
    @Synchronized
    fun listen(name: String): Boolean {
        if (listener != null) return true
        val socket = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(tag, "can't bind @$name", e)
            return false
        }
        listener = socket
        thread(name = "axsh-accept", isDaemon = true) { acceptLoop(socket) }
        Log.i(tag, "listening on @$name")
        return true
    }

    @Synchronized
    fun stop() {
        try {
            listener?.close()
        } catch (_: IOException) {
        }
        listener = null
    }

    private fun acceptLoop(socket: LocalServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (listener === socket) Log.w(tag, "accept failed", e)
                break
            }
            thread(name = "axsh-session", isDaemon = true) { serve(client) }
        }
    }

    /** Serves one connected client on the calling thread, until its shell is gone. */
    fun serve(socket: LocalSocket) {
        activeCount.incrementAndGet()
        try {
            socket.use { serveSession(it) }
        } catch (e: Throwable) {
            Log.w(tag, "session failed", e)
        } finally {
            lastSessionEnd = SystemClock.elapsedRealtime()
            activeCount.decrementAndGet()
        }
    }

    private val activeCount = AtomicInteger(0)

    @Volatile
    private var lastSessionEnd = SystemClock.elapsedRealtime()

    /** Sessions being served right now. */
    val active: Int get() = activeCount.get()

    /** How long no session has been served; 0 while one is. */
    val idleMillis: Long
        get() = if (active > 0) 0 else SystemClock.elapsedRealtime() - lastSessionEnd

    private fun serveSession(socket: LocalSocket) {
        val input = socket.inputStream
        val output = socket.outputStream

        val credentials = try {
            socket.peerCredentials
        } catch (_: IOException) {
            null
        }
        val uid = credentials?.uid ?: -1
        val pid = credentials?.pid ?: -1

        val request = readRequest(input)
        // The client's pipes arrive with the handshake's first byte.
        val received = socket.ancillaryFileDescriptors.orEmpty()
        try {
            if (request == null) {
                writeStatus(output, STATUS_ERROR, "Malformed request")
                return
            }
            val expected = if (request.pty) PTY_FDS else PIPE_FDS
            if (received.size != expected) {
                writeStatus(output, STATUS_ERROR, "Malformed request: expected $expected pipes")
                return
            }

            host.refuse(uid, pid)?.let { reason ->
                Log.w(tag, "refused uid=$uid pid=$pid: $reason")
                writeStatus(output, STATUS_DENIED, reason)
                return
            }

            val axeron = host.service()
            if (axeron == null) {
                writeStatus(output, STATUS_ERROR, "AxManager server is not running")
                return
            }

            // The server writes the shell's exit code into this pipe's write end.
            val status = ParcelFileDescriptor.createPipe()
            try {
                val process = start(output, axeron, request, received, status[1])
                // Before the relay: holding the client's stdout write end would keep it from ever
                // seeing EOF once the shell is done.
                closeAll(received)
                if (process == null) return
                writeStatus(output, STATUS_OK, null)
                Log.i(tag, "session started (uid=$uid, pty=${request.pty}): ${request.argv.joinToString(" ")}")
                relay(input, output, process, status[0])
            } finally {
                // When the relay never got it; closing twice is harmless.
                status[0].close()
            }
        } finally {
            // Early refusals; closing twice is harmless (Os.close invalidates the descriptor).
            closeAll(received)
        }
    }

    private fun start(
        output: OutputStream,
        axeron: IAxeronService,
        request: Request,
        received: Array<out FileDescriptor>,
        status: ParcelFileDescriptor,
    ): IAxshProcess? {
        val pipes = received.map { ParcelFileDescriptor.dup(it) }
        val launch = {
            val base = axeron.getEnvironment(AxeronApiConstant.server.TYPE_ENV)?.env
            val env = if (request.pty) ptyEnvironment(base, request.env) else base
            axeron.newAxshProcess(
                request.argv.toTypedArray(), env, null,
                pipes[0], pipes[1], pipes.getOrNull(2),
                request.pty, windowSize(request.rows, request.cols), status
            )
        }
        val started = try {
            try {
                launch()
            } catch (e: SecurityException) {
                // The client just waits on its status meanwhile; nothing is polled.
                if (!host.awaitPermission(axeron)) {
                    writeStatus(output, STATUS_DENIED, DENIED_MESSAGE)
                    return null
                }
                launch()
            }
        } catch (e: SecurityException) {
            writeStatus(output, STATUS_DENIED, DENIED_MESSAGE)
            return null
        } catch (e: Exception) {
            Log.w(tag, "remote shell failed to start", e)
            null
        } finally {
            // The server has its own copies now (or took ours: AxshServer's call is local).
            (pipes + status).forEach {
                try {
                    it.close()
                } catch (_: IOException) {
                }
            }
        }
        if (started == null) writeStatus(output, STATUS_ERROR, "Can't start remote shell")
        return started
    }

    /** Window changes in, the exit code out; EOF first means the client died. */
    private fun relay(
        input: InputStream,
        output: OutputStream,
        process: IAxshProcess,
        status: ParcelFileDescriptor,
    ) {
        val exited = AtomicBoolean(false)
        thread(name = "axsh-session-in", isDaemon = true) {
            try {
                while (true) {
                    if (input.read() != FRAME_WINCH) break
                    val size = ByteArray(4)
                    if (!readFully(input, size)) break
                    val buf = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN)
                    val rows = buf.short.toInt() and 0xffff
                    val cols = buf.short.toInt() and 0xffff
                    process.setWindowSize(windowSize(rows, cols))
                }
            } catch (_: Exception) {
            }
            // The shell must not outlive its client.
            if (!exited.get()) {
                try {
                    process.destroy()
                } catch (_: Exception) {
                }
            }
        }

        // Read here, not waited on over binder: a long session (libsu's cached shell) would hold
        // one of the server's few binder threads for as long as it lives.
        val code = awaitExit(status, process)
        exited.set(true)
        val frame = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(FRAME_EXIT.toByte())
        frame.putInt(code)
        output.write(frame.array())
        output.flush()
    }


    private fun closeAll(fds: Array<out FileDescriptor>) {
        for (fd in fds) {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
            }
        }
    }

    // ---- wire ----

    private class Request(
        val argv: List<String>,
        val flags: Int,
        /** The client's env; only a PTY session takes anything from it (PTY_ENV). */
        val env: List<String>,
        val rows: Int,
        val cols: Int,
    ) {
        val pty get() = flags and FLAG_PTY != 0
    }

    private fun readRequest(input: InputStream): Request? {
        val magic = ByteArray(4)
        if (!readFully(input, magic)) return null
        if (String(magic, Charsets.US_ASCII) != "AXSH") return null

        val version = input.read()
        val flags = input.read()
        if (version != VERSION || flags < 0) return null

        val argc = readU32(input) ?: return null
        if (argc <= 0 || argc > MAX_ARGS) return null
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(readString(input) ?: return null) }

        val envc = readU32(input) ?: return null
        if (envc < 0 || envc > MAX_ARGS) return null
        val env = ArrayList<String>(envc)
        repeat(envc) { env.add(readString(input) ?: return null) }

        var rows = 0
        var cols = 0
        if (flags and FLAG_PTY != 0) {
            val size = ByteArray(4)
            if (!readFully(input, size)) return null
            val buf = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN)
            rows = buf.short.toInt() and 0xffff
            cols = buf.short.toInt() and 0xffff
        }

        return Request(argv, flags, env, rows, cols)
    }

    private fun writeStatus(output: OutputStream, status: Int, message: String?) {
        if (status != STATUS_OK) Log.w(tag, "refused session: status=$status, $message")
        output.write(status)
        if (message != null) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            output.write(bytes)
        }
        output.flush()
    }

    private fun readU32(input: InputStream): Int? {
        val b = ByteArray(4)
        if (!readFully(input, b)) return null
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readString(input: InputStream): String? {
        val len = readU32(input) ?: return null
        if (len < 0 || len > MAX_STRING) return null
        if (len == 0) return ""
        val b = ByteArray(len)
        if (!readFully(input, b)) return null
        return String(b, Charsets.UTF_8)
    }
}
