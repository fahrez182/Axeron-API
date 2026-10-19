package frb.axeron.api

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.text.TextUtils
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@SuppressLint("PrivateApi")
object SystemServiceHelper {

    private val SYSTEM_SERVICE_CACHE = HashMap<String, IBinder?>()
    private val TRANSACT_CODE_CACHE = HashMap<String, Int>()

    private val getService: Method? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
    } catch (e: ClassNotFoundException) {
        Log.w("SystemServiceHelper", Log.getStackTraceString(e))
        null
    } catch (e: NoSuchMethodException) {
        Log.w("SystemServiceHelper", Log.getStackTraceString(e))
        null
    }

    /**
     * Returns a reference to a service with the given name.
     *
     * @param name the name of the service to get such as "package" for android.content.pm.IPackageManager
     * @return a reference to the service, or `null` if the service doesn't exist
     */
    @JvmStatic
    fun getSystemService(name: String): IBinder? {
        var binder = SYSTEM_SERVICE_CACHE[name]
        if (binder == null) {
            try {
                binder = getService!!.invoke(null, name) as IBinder?
            } catch (e: IllegalAccessException) {
                Log.w("SystemServiceHelper", Log.getStackTraceString(e))
            } catch (e: InvocationTargetException) {
                Log.w("SystemServiceHelper", Log.getStackTraceString(e))
            }
            SYSTEM_SERVICE_CACHE[name] = binder
        }
        return binder
    }

    /**
     * Returns transaction code from given class and method name.
     *
     * @param className  class name such as "android.content.pm.IPackageManager$Stub"
     * @param methodName method name such as "getInstalledPackages"
     * @return transaction code, or `null` if the class or the method doesn't exist
     */
    @Deprecated("Use AxeronBinderWrapper instead")
    @JvmStatic
    fun getTransactionCode(className: String, methodName: String): Int? {
        val fieldName = "TRANSACTION_$methodName"
        val key = "$className.$fieldName"

        TRANSACT_CODE_CACHE[key]?.let { return it }

        try {
            val cls = Class.forName(className)
            var declaredField: Field? = null
            try {
                declaredField = cls.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                for (f in cls.declaredFields) {
                    if (f.type != Int::class.javaPrimitiveType) continue

                    val name = f.name
                    if (name.startsWith(fieldName + "_") &&
                        TextUtils.isDigitsOnly(name.substring(fieldName.length + 1))
                    ) {
                        declaredField = f
                        break
                    }
                }
            }
            if (declaredField == null) {
                return null
            }

            declaredField.isAccessible = true
            val value = declaredField.getInt(cls)

            TRANSACT_CODE_CACHE[key] = value
            return value
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Obtain a new data parcel for [Axeron.transactRemote].
     *
     * @param serviceName   system service name
     * @param interfaceName class name for reflection
     * @param methodName    method name for reflection
     * @return data parcel
     * @throws NullPointerException can't get system service or transaction code
     */
    @Deprecated("Use AxeronBinderWrapper instead")
    @JvmStatic
    fun obtainParcel(serviceName: String, interfaceName: String, methodName: String): Parcel {
        @Suppress("DEPRECATION")
        return obtainParcel(serviceName, interfaceName, "$interfaceName\$Stub", methodName)
    }

    /**
     * Obtain a new data parcel for [Axeron.transactRemote].
     *
     * @param serviceName   system service name
     * @param interfaceName interface name
     * @param className     class name for reflection
     * @param methodName    method name for reflection
     * @return data parcel
     * @throws NullPointerException can't get system service or transaction code
     */
    @Deprecated("Use AxeronBinderWrapper instead")
    @JvmStatic
    fun obtainParcel(serviceName: String, interfaceName: String, className: String, methodName: String): Parcel {
        throw UnsupportedOperationException("Direct use of Axeron#transactRemote is no longer supported, please use AxeronBinderWrapper")
    }
}
