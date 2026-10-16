package frb.axeron.axsh

import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import rikka.hidden.compat.ActivityManagerApis
import java.io.FileDescriptor
import kotlin.system.exitProcess

/**
 * `app_process` entry for apps that run axsh without a broker of their own (Termux executing
 * the manager's libaxsh.so, for instance). The native client starts
 *
 *     app_process -Djava.class.path=<manager base.apk> /system/bin frb.axeron.axsh.AxshBootstrap <fd>
 *
 * with one end of a socketpair as `<fd>`. Running as the caller's uid, this process asks the
 * manager for the server binder and then serves exactly one broker session over that fd, so the
 * client sees the same protocol as with an in-app [AxshBroker].
 */
object AxshBootstrap {

    @JvmStatic
    fun main(args: Array<String>) {
        val fdNumber = args.getOrNull(0)?.toIntOrNull()
        if (fdNumber == null) {
            System.err.println("axsh: bootstrap needs a socket fd")
            exitProcess(2)
        }

        val socket = try {
            connectedSocket(ParcelFileDescriptor.adoptFd(fdNumber).fileDescriptor)
        } catch (e: Throwable) {
            System.err.println("axsh: bootstrap can't use fd $fdNumber: $e")
            exitProcess(2)
        }

        AxshBroker.serveOne(socket) { intent ->
            // Not the (Intent, userId) overload: it goes sticky for intents without a component,
            // which needs BROADCAST_STICKY, and only the server's shell/root uid has that.
            ActivityManagerApis.broadcastIntent(
                intent, null, null, 0, null, null, null, -1, null,
                false, false, Os.getuid() / 100000
            )
        }
        exitProcess(0)
    }

    // LocalSocket(FileDescriptor) is hidden, but app_process has no hidden API enforcement.
    private fun connectedSocket(fd: FileDescriptor): LocalSocket =
        LocalSocket::class.java.getDeclaredConstructor(FileDescriptor::class.java)
            .apply { isAccessible = true }
            .newInstance(fd)
}
