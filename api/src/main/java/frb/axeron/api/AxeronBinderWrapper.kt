package frb.axeron.api

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import frb.axeron.server.IAxeronService
import java.io.FileDescriptor

class AxeronBinderWrapper(private val original: IBinder) : IBinder {

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val newData = Parcel.obtain()
        try {
            newData.writeInterfaceToken(IAxeronService.DESCRIPTOR)
            newData.writeStrongBinder(original)
            newData.writeInt(code)
            newData.writeInt(flags)
            newData.appendFrom(data, 0, data.dataSize())
            Axeron.transactRemote(newData, reply, 0)
        } finally {
            newData.recycle()
        }
        return true
    }

    override fun getInterfaceDescriptor(): String? = original.interfaceDescriptor

    override fun pingBinder(): Boolean = original.pingBinder()

    override fun isBinderAlive(): Boolean = original.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        original.dump(fd, args)
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        original.dumpAsync(fd, args)
    }

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        original.linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        original.unlinkToDeath(recipient, flags)
}
