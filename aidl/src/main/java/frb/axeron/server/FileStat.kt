package frb.axeron.server

import android.os.Parcel
import android.os.Parcelable

/** Parcel layout kept as the Java class wrote it: three strings, four flag bytes, two longs, a string. */
class FileStat() : Parcelable {
    @JvmField var path: String? = null
    @JvmField var name: String? = null
    @JvmField var parent: String? = null
    @JvmField var exists: Boolean = false
    @JvmField var isFile: Boolean = false
    @JvmField var isDirectory: Boolean = false
    @JvmField var isHidden: Boolean = false
    @JvmField var length: Long = 0
    @JvmField var lastModified: Long = 0
    @JvmField var canonicalPath: String? = null // "" jika gagal resolve

    private constructor(parcel: Parcel) : this() {
        path = parcel.readString()
        name = parcel.readString()
        parent = parcel.readString()
        exists = parcel.readByte().toInt() != 0
        isFile = parcel.readByte().toInt() != 0
        isDirectory = parcel.readByte().toInt() != 0
        isHidden = parcel.readByte().toInt() != 0
        length = parcel.readLong()
        lastModified = parcel.readLong()
        canonicalPath = parcel.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(path)
        dest.writeString(name)
        dest.writeString(parent)
        dest.writeByte(if (exists) 1 else 0)
        dest.writeByte(if (isFile) 1 else 0)
        dest.writeByte(if (isDirectory) 1 else 0)
        dest.writeByte(if (isHidden) 1 else 0)
        dest.writeLong(length)
        dest.writeLong(lastModified)
        dest.writeString(canonicalPath)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<FileStat> {
            override fun createFromParcel(parcel: Parcel): FileStat = FileStat(parcel)

            override fun newArray(size: Int): Array<FileStat?> = arrayOfNulls(size)
        }
    }
}
