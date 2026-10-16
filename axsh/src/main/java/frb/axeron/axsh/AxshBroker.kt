package frb.axeron.axsh

import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import frb.axeron.server.IAxeronService
import frb.axeron.shared.AxeronApiConstant
import moe.shizuku.server.IRemoteProcess
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * In-app half of `axsh` for when the server's own socket is out of reach.
 *
 * SELinux lets an app connect to its own sockets, but not to one owned by the server's domain
 * (`shell` in ADB mode). This broker listens on `@axsh.<uid>`, starts the remote shell over
 * binder ([IAxeronService.newProcess], which the server checks against this app's uid), and hands
 * the process's stdin/stdout/stderr pipes to the native client over SCM_RIGHTS. From then on the
 * client talks to the pipes directly; the broker only waits for the exit code.
 *
 * The binder comes from the manager the same way axerish gets it: an `AxRuntime.REQUEST_BINDER`
 * broadcast answered by `ShellBinderRequestHandler`.
 *
 * Wire format: see `src/main/cpp/axsh_protocol.h` (broker section).
 */
object AxshBroker {

    private const val TAG = "AxshBroker"

    // Mirrors ServerConstants.REQUEST_BINDER_AXRUNTIME and the manager's package.
    private const val ACTION_REQUEST_BINDER = "AxRuntime.REQUEST_BINDER"
    private const val MANAGER_PACKAGE = "frb.axeron.manager"
    private const val BINDER_TIMEOUT_SECONDS = 5L

    private const val PERMISSION_REQUEST_CODE = 0x4158
    private const val PERMISSION_REQUEST_INTERVAL_MS = 10_000L

    private const val VERSION = 1

    private const val STATUS_OK = 0
    private const val STATUS_DENIED = 1
    private const val STATUS_ERROR = 2

    private const val FRAME_EXIT = 0x02

    private const val MAX_ARGS = 4096
    private const val MAX_STRING = 1 shl 20

    private var server: LocalServerSocket? = null

    /** How the binder request reaches the manager: Context in an app, ActivityManager in app_process. */
    @Volatile
    private var sendRequest: ((Intent) -> Unit)? = null

    @Volatile
    private var service: IAxeronService? = null

    /** Abstract socket name the native client looks for; per-uid so apps never share a broker. */
    fun socketName(uid: Int = Process.myUid()) = "axsh.$uid"

    /** Set when the host already holds the server binder; then nothing is asked of the manager. */
    @Volatile
    private var binderSource: (() -> IBinder?)? = null

    /**
     * @param binder where the host keeps the server binder, if it has one (the manager does);
     *   null to ask the manager for it with an `AxRuntime.REQUEST_BINDER` broadcast. The manager
     *   must not use the broadcast: its own receiver would wait behind a main thread that may be
     *   blocked on the very shell being started.
     */
    @Synchronized
    fun start(context: Context, binder: (() -> IBinder?)? = null) {
        if (server != null) return
        val appContext = context.applicationContext
        sendRequest = { appContext.sendBroadcast(it) }
        binderSource = binder
        val listener = try {
            LocalServerSocket(socketName())
        } catch (e: IOException) {
            Log.e(TAG, "can't bind @${socketName()}", e)
            return
        }
        server = listener
        thread(name = "axsh-broker", isDaemon = true) { acceptLoop(listener) }
        Log.i(TAG, "listening on @${socketName()}")
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    private fun acceptLoop(listener: LocalServerSocket) {
        while (true) {
            val client = try {
                listener.accept()
            } catch (e: IOException) {
                if (server === listener) Log.w(TAG, "accept failed", e)
                break
            }
            thread(name = "axsh-broker-session", isDaemon = true) { serve(client) }
        }
    }

    /**
     * Serves one already-connected client on the calling thread, for [AxshBootstrap]: no
     * listener, and the binder request goes out through [sender].
     */
    internal fun serveOne(socket: LocalSocket, sender: (Intent) -> Unit) {
        sendRequest = sender
        serve(socket)
    }

    private fun serve(socket: LocalSocket) {
        try {
            socket.use { serveSession(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "session failed", e)
        }
    }

    private fun serveSession(socket: LocalSocket) {
        val input = socket.inputStream
        val output = socket.outputStream

        // The broker spends this app's AxManager grant, so only this app's own processes may use it.
        val peerUid = try {
            socket.peerCredentials.uid
        } catch (_: IOException) {
            -1
        }
        if (peerUid != Process.myUid()) {
            Log.w(TAG, "rejected uid=$peerUid")
            writeStatus(output, STATUS_DENIED, "Not this app's process")
            return
        }

        Log.i(TAG, "client connected (uid=$peerUid)")
        val argv = readRequest(input)
        if (argv == null) {
            writeStatus(output, STATUS_ERROR, "Malformed request")
            return
        }

        val axeron = obtainService()
        if (axeron == null) {
            writeStatus(output, STATUS_ERROR, "AxManager server is not running")
            return
        }

        // newProcess is the authority: checkSelfPermission() only knows granted apps, not the
        // manager, which newProcess lets through.
        val process = try {
            val env = axeron.getEnvironment(AxeronApiConstant.server.TYPE_ENV)?.env
            axeron.newProcess(argv.toTypedArray(), env, null)
        } catch (e: SecurityException) {
            requestPermission(axeron)
            writeStatus(output, STATUS_DENIED, "AxManager permission required: allow this app in AxManager, then run again")
            return
        } catch (e: Exception) {
            Log.w(TAG, "newProcess failed", e)
            writeStatus(output, STATUS_ERROR, "Can't start remote shell")
            return
        }
        if (process == null) {
            writeStatus(output, STATUS_ERROR, "Can't start remote shell")
            return
        }

        if (!sendPipes(socket, output, process)) return
        Log.i(TAG, "session started: ${argv.joinToString(" ")}")

        // The client keeps the socket open until it has the exit code: EOF before that means it
        // died, and the remote shell must not outlive it.
        thread(name = "axsh-broker-watch", isDaemon = true) {
            try {
                while (input.read() >= 0) Unit
            } catch (_: IOException) {
            }
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }

        val code = try {
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "waitFor failed", e)
            -1
        }
        val frame = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(FRAME_EXIT.toByte())
        frame.putInt(code)
        output.write(frame.array())
        output.flush()
    }

    private fun sendPipes(socket: LocalSocket, output: OutputStream, process: IRemoteProcess): Boolean {
        val pipes = ArrayList<ParcelFileDescriptor>(3)
        try {
            for (pipe in listOf(process.outputStream, process.inputStream, process.errorStream)) {
                if (pipe == null) {
                    writeStatus(output, STATUS_ERROR, "Remote shell has no stdio")
                    process.destroy()
                    return false
                }
                pipes.add(pipe)
            }
            socket.setFileDescriptorsForSend(pipes.map { it.fileDescriptor }.toTypedArray<FileDescriptor>())
            writeStatus(output, STATUS_OK, null)
            socket.setFileDescriptorsForSend(null)
            return true
        } finally {
            // The client holds its own copies now; ours must go, or the remote shell never sees
            // EOF on stdin when the client closes it.
            pipes.forEach {
                try {
                    it.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    // ---- binder ----

    private var lastPermissionRequest = 0L

    /**
     * The server resolves the request by our binder uid and opens the manager's confirmation
     * dialog itself, so no client registration is needed. Throttled: clients that arrive together
     * (libsu retrying, several commands) must not stack dialogs.
     */
    @Synchronized
    private fun requestPermission(axeron: IAxeronService) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPermissionRequest < PERMISSION_REQUEST_INTERVAL_MS) return
        lastPermissionRequest = now
        try {
            axeron.requestPermission(PERMISSION_REQUEST_CODE)
            Log.i(TAG, "permission requested")
        } catch (e: Exception) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    private fun obtainService(): IAxeronService? {
        binderSource?.let { source ->
            return source()?.takeIf { it.pingBinder() }?.let { IAxeronService.Stub.asInterface(it) }
        }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        synchronized(this) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            val binder = requestBinder() ?: return null
            return IAxeronService.Stub.asInterface(binder).also { service = it }
        }
    }

    /** Asks the manager for the server binder; the reply arrives as transaction 1 on [receiver]. */
    private fun requestBinder(): IBinder? {
        val latch = CountDownLatch(1)
        var received: IBinder? = null
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                received = data.readStrongBinder()
                latch.countDown()
                return true
            }
        }

        val intent = Intent(ACTION_REQUEST_BINDER)
            .setPackage(MANAGER_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", Bundle().apply { putBinder("binder", receiver) })
        try {
            val sender = sendRequest ?: return null
            sender(intent)
        } catch (e: Exception) {
            Log.w(TAG, "binder request failed", e)
            return null
        }

        if (!latch.await(BINDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "no binder from $MANAGER_PACKAGE")
            return null
        }
        val binder = received
        if (binder == null || !binder.pingBinder()) {
            Log.w(TAG, "manager replied without a live binder")
            return null
        }
        return binder
    }

    // ---- wire helpers (same encoding as the server's AxshServer) ----

    private fun readRequest(input: InputStream): List<String>? {
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

        // Environment is ignored, as on the server: the remote shell gets the Axeron environment.
        val envc = readU32(input) ?: return null
        if (envc < 0 || envc > MAX_ARGS) return null
        repeat(envc) { readString(input) ?: return null }

        return argv
    }

    private fun writeStatus(output: OutputStream, status: Int, message: String?) {
        if (status != STATUS_OK) Log.w(TAG, "refused session: status=$status, $message")
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
