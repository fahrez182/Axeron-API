package frb.axeron.shell

import android.app.ActivityThread
import android.content.AttributionSource
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.ddm.DdmHandleAppName
import android.database.Cursor
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import rikka.hidden.compat.ActivityManagerApis
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * POC: libsu's RootService for AxManager, used the same way, in ADB mode as well as root.
 *
 * ```
 * class MyService : ShellService() {
 *     override fun onBind(intent: Intent): IBinder = object : IMyService.Stub() { … }
 * }
 * ShellService.bind(Intent(context, MyService::class.java), connection)   // main thread
 * ShellService.unbind(connection)
 * ```
 *
 * Each service component runs in its own `app_process`, started through axsh ([setLauncher]),
 * so as the server's uid (shell or root), with this app's base.apk as its classpath. Its
 * [onCreate], [onBind], [onUnbind] and [onDestroy] run on that process's main thread; its
 * Context is this package's (user 0 only in this POC). Connections share one [onBind] binder;
 * when the last one unbinds the service is destroyed and its process exits, unless [onUnbind]
 * keeps it as a daemon. If the process dies, connections get onServiceDisconnected; if it never
 * comes up, onBindingDied.
 *
 * Where RootService fails in ADB mode, this differs: it starts on any shell (libsu only from a
 * root one), its classpath is base.apk (not a jar in the app's private cache, which shell can't
 * read), and it hands its binder in through [ShellServiceProvider], which checks the caller's uid
 * (not a broadcast needing BROADCAST_PACKAGE_REMOVED).
 */
abstract class ShellService : ContextWrapper(null) {

    open fun onCreate() {}

    abstract fun onBind(intent: Intent): IBinder

    /**
     * The last connection is gone (unbound, or the app died). Return true to keep running as a
     * daemon, as RootService's: the process outlives the app, and the next bind, from this app
     * process or a later one, reaches it again through [onRebind]. False: [onDestroy], then exit.
     */
    open fun onUnbind(intent: Intent): Boolean = false

    /** A bind reached this service after [onUnbind] returned true. */
    open fun onRebind(intent: Intent) {}

    open fun onDestroy() {}

    /** Ends this service: its process exits and every connection gets onServiceDisconnected. */
    fun stopSelf() {
        ShellServiceRemote.destroy()
    }

    internal fun attach(base: Context) = attachBaseContext(base)

    /**
     * No Application runs in the service's process (making one would run the app's
     * Application.onCreate as shell), so the package context's is null: this service instead,
     * for the many libraries that call `context.applicationContext`.
     */
    override fun getApplicationContext(): Context = super.getApplicationContext() ?: this

    /** How a service's `app_process` command line is run as the server's uid. */
    fun interface Launcher {
        @Throws(IOException::class)
        fun launch(argv: Array<String>): java.lang.Process
    }

    companion object {
        @JvmStatic
        fun bind(intent: Intent, connection: ServiceConnection) =
            ShellServiceClient.bind(intent, ShellServiceClient.mainExecutor, connection)

        @JvmStatic
        fun bind(intent: Intent, executor: Executor, connection: ServiceConnection) =
            ShellServiceClient.bind(intent, executor, connection)

        @JvmStatic
        fun unbind(connection: ServiceConnection) = ShellServiceClient.unbind(connection)

        /**
         * Stops the service whatever its connections; they get onServiceDisconnected. Also a
         * daemon this app process isn't bound to (left by an earlier one).
         */
        @JvmStatic
        fun stop(intent: Intent) = ShellServiceClient.stop(intent)

        /**
         * Why [component]'s last bind gave onNullBinding (onBind's exception, from its process)
         * or onBindingDied (its process's last stderr lines); null if it didn't fail.
         */
        @JvmStatic
        fun lastFailure(component: ComponentName): String? = ShellServiceClient.failures[component]

        /**
         * Default: [Axsh.exec] through this app's Broker; while none runs, the manager's
         * `libaxsh.so -c`.
         */
        @JvmStatic
        fun setLauncher(launcher: Launcher) {
            ShellServiceClient.launcher = launcher
        }
    }
}

// ---- in the app ----

/** The app side: one [Record] per service component, touched on the main thread only. */
internal object ShellServiceClient {
    private const val TAG = "ShellService"

    const val AUTHORITY_SUFFIX = ".shellservice"
    const val METHOD_ATTACH = "attach"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BINDER = "binder"

    // Transactions on a service process's control binder (ShellServiceRemote.Control).
    const val TRANSACTION_BIND = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_UNBIND = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 2

    // What a service process is started for, on its stdin after the token.
    const val ACTION_BIND = "bind"
    const val ACTION_STOP = "stop"

    private val main = Handler(Looper.getMainLooper())
    val mainExecutor = Executor { main.post(it) }

    // Mirrors Broker's.
    private const val MANAGER_PACKAGE = "frb.axeron.manager"

    /**
     * For a debuggable app: JDWP in the service's process, as the server's ServiceStarter gives
     * Shizuku user services, so Studio's "Attach Debugger" lists it (named by ShellServiceRemote).
     */
    private val DEBUG_ARGS: Array<String> = when {
        Build.VERSION.SDK_INT >= 30 -> arrayOf(
            "-Xcompiler-option", "--debuggable",
            "-XjdwpProvider:adbconnection", "-XjdwpOptions:suspend=n,server=y",
        )
        Build.VERSION.SDK_INT >= 28 -> arrayOf(
            "-Xcompiler-option", "--debuggable",
            "-XjdwpProvider:internal", "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y",
        )
        else -> arrayOf(
            "-Xcompiler-option", "--debuggable",
            "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y",
        )
    }

    @Volatile
    var launcher = ShellService.Launcher(::defaultLaunch)

    /**
     * Broker has no "running" state of its own: without [Broker.start] it has no way to the binder,
     * and [Axsh.exec] says so at once with an IOException. Then as a no-broker app does, through
     * the manager's libaxsh.so (its bootstrap asks the manager for the binder itself).
     */
    private fun defaultLaunch(argv: Array<String>): java.lang.Process = try {
        Axsh.exec(argv)
    } catch (e: IOException) {
        val axsh = try {
            File(context.packageManager.getApplicationInfo(MANAGER_PACKAGE, 0).nativeLibraryDir, "libaxsh.so")
        } catch (_: PackageManager.NameNotFoundException) {
            throw e
        }
        Log.i(TAG, "Axsh.exec: ${e.message}; through $axsh")
        val command = argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        ProcessBuilder(axsh.path, "-c", "exec $command").start()
    }

    /** This app, kept by [ShellServiceProvider.onCreate]: providers start before the Application. */
    @Volatile
    private var appContext: Context? = null

    val context: Context
        get() = checkNotNull(appContext) { "ShellServiceProvider isn't running: is the axsh manifest merged?" }

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    private class Record(val component: ComponentName, val intent: Intent) {
        val token: String = UUID.randomUUID().toString()
        val connections = LinkedHashMap<ServiceConnection, Executor>()

        /** Held by the service process: it dies with this app. */
        val appToken = Binder()

        @Volatile
        var process: java.lang.Process? = null

        @Volatile
        var control: IBinder? = null

        var service: IBinder? = null

        /** What the process wrote to stderr lately, for when it never connects. */
        val stderr = ArrayDeque<String>()
    }

    private val records = HashMap<ComponentName, Record>()

    /** Why each component's last bind ended in onNullBinding / onBindingDied. */
    val failures = ConcurrentHashMap<ComponentName, String>()

    /** Records waiting for their process to hand in its control binder; read from the provider. */
    private val starting = ConcurrentHashMap<String, Record>()

    fun bind(intent: Intent, executor: Executor, connection: ServiceConnection) {
        checkMainThread()
        val component = requireNotNull(intent.component) { "the intent has no component" }
        require(component.packageName == context.packageName) {
            "ShellServices outside of this app are not supported"
        }
        val record = records.getOrPut(component) { Record(component, Intent(intent)).also(::start) }
        record.connections[connection] = executor
        record.service?.let { service -> executor.execute { connection.onServiceConnected(component, service) } }
    }

    fun unbind(connection: ServiceConnection) {
        checkMainThread()
        val record = records.values.firstOrNull { connection in it.connections } ?: return
        record.connections.remove(connection)
        if (record.connections.isEmpty()) end(record, TRANSACTION_UNBIND)
    }

    fun stop(intent: Intent) {
        checkMainThread()
        val component = intent.component ?: return
        val record = records[component] ?: run {
            // Not bound here: a daemon may still run. A process asks it to stop and exits.
            thread(name = "shell-service-stop") {
                runCatching {
                    val process = launcher.launch(argv(component))
                    process.outputStream.use { it.write("\n$ACTION_STOP\n".toByteArray()) }
                    process.waitFor()
                }.onFailure { Log.w(TAG, "can't stop ${component.className}", it) }
            }
            return
        }
        val connections = record.connections.toList()
        record.connections.clear()
        end(record, TRANSACTION_STOP)
        if (record.service != null) {
            connections.forEach { (conn, executor) -> executor.execute { conn.onServiceDisconnected(record.component) } }
        }
    }

    /** Asks the process to go ([code]: unbind or stop), or kills it if it can't be asked yet. */
    private fun end(record: Record, code: Int) {
        records.remove(record.component)
        starting.remove(record.token)
        thread(name = "shell-service-end") {
            val asked = record.control?.let { control ->
                runCatching { transact(control, code, oneway = true) {} }.isSuccess
            } ?: false
            if (!asked) record.process?.destroy()
        }
    }

    /**
     * The service process's command line. Under `sh`, not exec'd: the server kills a shell's whole
     * process group when the app dies, and only a process that doesn't lead its group can leave it
     * (setsid) to stay on as a daemon. `; exit $?` so sh can't exec its last command either.
     */
    private fun argv(component: ComponentName): Array<String> {
        val app = context
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return arrayOf(
            "/system/bin/sh", "-c", "\"\$@\"; exit \$?", "shellservice",
            "/system/bin/app_process",
            "-Djava.class.path=${app.applicationInfo.sourceDir}",
            *(if (debuggable) DEBUG_ARGS else emptyArray()),
            "/system/bin",
            "--nice-name=${app.packageName}:shell_service",
            ShellServiceRemote::class.java.name,
            app.packageName, component.className,
        )
    }

    private fun start(record: Record) {
        failures.remove(record.component)
        starting[record.token] = record
        val argv = argv(record.component)
        // The launcher may block (binder, AxManager's permission dialog): not on the main thread.
        thread(name = "shell-service-start") {
            val process = try {
                launcher.launch(argv)
            } catch (e: Throwable) {
                Log.e(TAG, "can't start ${record.component.className}", e)
                main.post { gone(record) }
                return@thread
            }
            record.process = process
            // On stdin, not argv: any process of the server's uid can read /proc/<pid>/cmdline,
            // and with the token it could hand us its own binder first.
            runCatching { process.outputStream.use { it.write("${record.token}\n$ACTION_BIND\n".toByteArray()) } }
            thread(name = "shell-service-err", isDaemon = true) {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        Log.w(TAG, line)
                        synchronized(record.stderr) {
                            record.stderr.addLast(line)
                            if (record.stderr.size > 20) record.stderr.removeFirst()
                        }
                    }
                }
            }
            val code = process.waitFor()
            Log.i(TAG, "${record.component.className}'s launch exited with $code")
            // Once its control binder is in, that binder's death says when the service is gone:
            // a daemon handed over by this process outlives it.
            main.post { if (record.control == null) gone(record) }
        }
    }

    /** From [ShellServiceProvider], on a binder thread: the process with [token] is up. */
    fun attach(token: String, control: IBinder): IBinder? {
        val record = starting.remove(token) ?: return null
        record.control = control
        runCatching { control.linkToDeath({ main.post { gone(record) } }, 0) }
        // Not here: the process's main thread is still waiting on this very call.
        thread(name = "shell-service-bind") {
            var binder: IBinder? = null
            var error: String? = null
            runCatching {
                transact(control, TRANSACTION_BIND, oneway = false, write = { record.intent.writeToParcel(it, 0) }) {
                    // Throws what the process's onTransact threw outside its own catch.
                    it.readException()
                    binder = it.readStrongBinder()
                    error = it.readString()
                    if (binder == null && error == null) error = "empty reply (${it.dataSize()} bytes)"
                }
            }.onFailure { error = "bind transaction failed: ${Log.getStackTraceString(it)}" }
            main.post { connected(record, binder, error) }
        }
        return record.appToken
    }

    private fun connected(record: Record, service: IBinder?, error: String?) {
        if (records[record.component] !== record) return // unbound or stopped meanwhile
        if (service == null) {
            // onBind failed or returned nothing (onNullBinding is API 28+).
            failures[record.component] = error ?: "no binder"
            Log.e(TAG, "${record.component.className}: no binder: $error")
            if (Build.VERSION.SDK_INT >= 28) {
                record.connections.forEach { (conn, executor) -> executor.execute { conn.onNullBinding(record.component) } }
            }
            return
        }
        record.service = service
        record.connections.forEach { (conn, executor) ->
            executor.execute { conn.onServiceConnected(record.component, service) }
        }
    }

    /** The process is gone (or never came up). */
    private fun gone(record: Record) {
        starting.remove(record.token)
        if (records[record.component] !== record) return
        records.remove(record.component)
        if (record.service != null) {
            record.connections.forEach { (conn, executor) -> executor.execute { conn.onServiceDisconnected(record.component) } }
        } else {
            val tail = synchronized(record.stderr) { record.stderr.joinToString("\n") }
            failures[record.component] = "its process never connected" + if (tail.isEmpty()) "" else ":\n$tail"
            Log.e(TAG, "${record.component.className} never connected" + if (tail.isEmpty()) "" else ":\n$tail")
            record.connections.forEach { (conn, executor) -> executor.execute { conn.onBindingDied(record.component) } }
        }
    }

    private inline fun transact(
        binder: IBinder,
        code: Int,
        oneway: Boolean,
        write: (Parcel) -> Unit = {},
        read: (Parcel) -> Unit,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            write(data)
            binder.transact(code, data, reply, if (oneway) IBinder.FLAG_ONEWAY else 0)
            if (!oneway) read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun checkMainThread() =
        check(Looper.myLooper() == Looper.getMainLooper()) { "ShellService.bind/unbind/stop: main thread only" }
}

/**
 * Where a service process hands in its control binder. Exported, so it takes calls only from the
 * server's uids (root, shell) and only with a token one of our binds is waiting on.
 */
class ShellServiceProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ShellServiceClient.METHOD_ATTACH || extras == null) return null
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != 2000) { // root, shell
            Log.w("ShellService", "attach from uid $uid refused")
            return null
        }
        val token = extras.getString(ShellServiceClient.EXTRA_TOKEN) ?: return null
        val control = extras.getBinder(ShellServiceClient.EXTRA_BINDER) ?: return null
        val appToken = ShellServiceClient.attach(token, control) ?: return null
        return Bundle().apply { putBinder(ShellServiceClient.EXTRA_BINDER, appToken) }
    }

    override fun onCreate(): Boolean {
        ShellServiceClient.init(context!!)
        return true
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}

// ---- in the service's process ----

/**
 * The `app_process` entry: `<package> <service class>`; on stdin the token, then what for
 * (bind or stop). Runs as the server's uid.
 */
internal object ShellServiceRemote {
    private const val TAG = "ShellService"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private lateinit var packageName: String
    private lateinit var className: String
    private var service: ShellService? = null
    private var binder: IBinder? = null
    private var intent: Intent? = null

    /** One for every app process we serve: the app links to its death. */
    private val control by lazy { Control() }

    /** The app process we serve now; null while none is bound (a daemon between binds). */
    private var appToken: IBinder? = null

    /** [ShellService.onUnbind] kept us: the next bind gets [ShellService.onRebind]. */
    private var unbound = false

    private var daemon = false

    private val authority get() = packageName + ShellServiceClient.AUTHORITY_SUFFIX

    /** Where a daemon of this service waits for the next bind (abstract, per uid). */
    private val socketName get() = "shellservice.${Os.getuid()}.$packageName/$className"

    @JvmStatic
    fun main(args: Array<String>) {
        val stdin = System.`in`.bufferedReader()
        val token = stdin.readLine().orEmpty()
        val action = stdin.readLine() ?: ShellServiceClient.ACTION_BIND
        if (args.size < 2 || (token.isEmpty() && action == ShellServiceClient.ACTION_BIND)) {
            System.err.println("shellservice: usage: <package> <class>, the token and bind|stop on stdin")
            exitProcess(2)
        }
        packageName = args[0]
        className = args[1]

        // A daemon of this service already runs: it takes the bind (or stop) itself.
        handOver(action, token)?.let { exitProcess(it) }
        if (action == ShellServiceClient.ACTION_STOP) exitProcess(0) // nothing to stop

        // The name Studio's debugger and Logcat list this process under.
        runCatching { DdmHandleAppName.setAppName("$packageName:${className.substringAfterLast('.')}", 0) }
        Looper.prepareMainLooper()

        val app = try {
            sendControl(authority, token, control)
        } catch (e: Throwable) {
            System.err.println("shellservice: can't reach $packageName: $e")
            null
        } ?: run {
            System.err.println("shellservice: $packageName refused us (uid ${Os.getuid()})")
            exitProcess(1)
        }
        serve(app)
        Looper.loop()
    }

    /**
     * Passes [action] to this service's daemon, if one listens: the exit code then, null without
     * one. For a bind the daemon hands its control binder to the app with [token] itself.
     */
    private fun handOver(action: String, token: String): Int? {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName))
        } catch (_: IOException) {
            socket.close()
            return null
        }
        return socket.use {
            it.outputStream.write("$action $token\n".toByteArray())
            val reply = it.inputStream.bufferedReader().readLine()
            if (reply == "ok") 0 else {
                System.err.println("shellservice: the running daemon said: $reply")
                1
            }
        }
    }

    /** [app] is the app process we serve now; when it dies, it unbinds us. */
    private fun serve(app: IBinder) {
        appToken = app
        runCatching {
            app.linkToDeath({ main.post { if (appToken === app) unbind() } }, 0)
        }.onFailure { main.post { if (appToken === app) unbind() } }
    }

    /** Created on the first bind: the class from our classpath (base.apk), this package's Context. */
    private fun bindOnMain(intent: Intent): IBinder? {
        binder?.let { existing ->
            if (unbound) {
                unbound = false
                service?.onRebind(intent)
            }
            this.intent = intent
            return existing
        }
        val created = service ?: run {
            // As Context: the stub's getSystemContext returns ContextImpl, package-private on
            // devices, and calling through it there is an IllegalAccessError.
            val thread = ActivityThread.systemMain()
            val system: Context = thread.systemContext
            val context = withUidOpPackage(
                thread, system,
                system.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY),
            )
            (Class.forName(className).getDeclaredConstructor().newInstance() as ShellService).also {
                it.attach(context)
                service = it
                it.onCreate()
            }
        }
        this.intent = intent
        return created.onBind(intent).also { binder = it }
    }

    /**
     * A package context opened from the system context keeps the system's op package, "android"
     * (ContextImpl takes it from its container), and services that check it against the calling
     * uid refuse shell: "Package android does not belong to 2000". So for shell, the same package
     * remade with the uid's own package (com.android.shell) as op package, which the context's
     * AttributionSource, ContentResolver and system services are built with. Root has no package
     * and AppOps lets uid 0 through whatever it names: left as it is.
     */
    private fun withUidOpPackage(thread: ActivityThread, system: Context, context: Context): Context {
        val opPackage = system.packageManager.getPackagesForUid(Os.getuid())?.firstOrNull() ?: return context
        return try {
            val impl = Class.forName("android.app.ContextImpl")
            val loadedApk = impl.getDeclaredField("mPackageInfo").apply { isAccessible = true }.get(context)
            try {
                impl.getDeclaredMethod(
                    "createAppContext", ActivityThread::class.java, Class.forName("android.app.LoadedApk"), String::class.java
                ).apply { isAccessible = true }.invoke(null, thread, loadedApk, opPackage) as Context
            } catch (_: NoSuchMethodException) {
                // Older ContextImpls have no op package override: before 12 the field is what's read.
                impl.getDeclaredField("mOpPackageName").apply { isAccessible = true }.set(context, opPackage)
                context
            }
        } catch (e: ReflectiveOperationException) {
            System.err.println("shellservice: op package stays ${context.opPackageName}: $e")
            context
        }
    }

    /** The app unbound, or died: [ShellService.onUnbind] decides whether we stay. On main. */
    private fun unbind() {
        appToken = null
        val svc = service
        val last = intent
        if (svc == null || last == null) exitProcess(0) // never bound: nothing to keep
        val keep = runCatching { svc.onUnbind(last) }.getOrDefault(false)
        if (!keep) destroy()
        unbound = true
        if (!daemon) becomeDaemon()
    }

    /**
     * Stays on without the app: out of the shell's process group, which the server kills with the
     * app; stdio to /dev/null, as the pipes to the app break; then waits on [socketName].
     */
    private fun becomeDaemon() {
        daemon = true
        try {
            Os.setsid()
        } catch (e: ErrnoException) {
            Log.w(TAG, "$className can't leave the shell's process group, it dies with the app: $e")
        }
        runCatching {
            val devNull = Os.open("/dev/null", OsConstants.O_RDWR, 0)
            Os.dup2(devNull, 0)
            Os.dup2(devNull, 1)
            Os.dup2(devNull, 2)
            Os.close(devNull)
        }
        val server = try {
            LocalServerSocket(socketName)
        } catch (e: IOException) {
            Log.w(TAG, "$className: no daemon socket, binds will start a new process: $e")
            return
        }
        Log.i(TAG, "$className stays on as a daemon, pid ${Os.getpid()}")
        thread(name = "shell-service-daemon", isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "shell-service-handover", isDaemon = true) { takeOver(client) }
            }
        }
    }

    /** A new launch of this service found us: `bind <token>` or `stop`. */
    private fun takeOver(client: LocalSocket) = client.use {
        // Same uid only: the server's, as the launch that found us.
        if (it.peerCredentials.uid != Os.getuid()) return
        val line = it.inputStream.bufferedReader().readLine() ?: return
        val action = line.substringBefore(' ')
        val reply = when (action) {
            ShellServiceClient.ACTION_STOP -> {
                main.post { destroy() }
                "ok"
            }
            ShellServiceClient.ACTION_BIND -> {
                val app = runCatching { sendControl(authority, line.substringAfter(' '), control) }.getOrNull()
                if (app == null) "refused by $packageName" else {
                    main.post { serve(app) }
                    "ok"
                }
            }
            else -> "unknown action $action"
        }
        it.outputStream.write("$reply\n".toByteArray())
    }

    /** Destroys the service and exits. */
    fun destroy(): Nothing {
        service?.let { runCatching { it.onDestroy() } }
        exitProcess(0)
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return task.get()
    }

    /** What the app calls once we're up: bind, unbind, stop. */
    private class Control : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            ShellServiceClient.TRANSACTION_BIND -> {
                val result = runCatching {
                    val intent = Intent.CREATOR.createFromParcel(data)
                    onMain { bindOnMain(intent) }
                }
                val error = result.exceptionOrNull()?.let { e ->
                    Log.getStackTraceString((e as? ExecutionException)?.cause ?: e)
                }
                error?.let { System.err.println("shellservice: onBind failed: $it") }
                // The binder, then why there's none: the app shows it (ShellService.lastFailure).
                reply?.writeNoException()
                reply?.writeStrongBinder(result.getOrNull())
                reply?.writeString(error ?: if (result.getOrNull() == null) "onBind returned null" else null)
                true
            }
            ShellServiceClient.TRANSACTION_UNBIND -> {
                main.post { unbind() }
                true
            }
            ShellServiceClient.TRANSACTION_STOP -> {
                main.post { destroy() }
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun sendControl(authority: String, token: String, control: IBinder): IBinder? {
        val provider = ActivityManagerApis.getContentProviderExternal(authority, 0, null, authority)
            ?: throw IOException("no provider $authority")
        try {
            val extras = Bundle().apply {
                putString(ShellServiceClient.EXTRA_TOKEN, token)
                putBinder(ShellServiceClient.EXTRA_BINDER, control)
            }
            return call(provider, authority, ShellServiceClient.METHOD_ATTACH, extras)?.getBinder(ShellServiceClient.EXTRA_BINDER)
        } finally {
            runCatching { ActivityManagerApis.removeContentProviderExternal(authority, null) }
        }
    }

    // IContentProvider.call per API level, as the server's IContentProviderCompat. Typed nulls:
    // the 5-argument overloads differ only in their first parameter's type.
    private fun call(provider: IContentProvider, authority: String, method: String, extras: Bundle): Bundle? {
        val none: String? = null
        return when {
            Build.VERSION.SDK_INT >= 31 -> provider.call(
                AttributionSource.Builder(Os.getuid()).build(), authority, method, none, extras
            )
            Build.VERSION.SDK_INT == 30 -> provider.call(none, none, authority, method, none, extras)
            Build.VERSION.SDK_INT == 29 -> provider.call(none, authority, method, none, extras)
            else -> provider.call(none, method, none, extras)
        }
    }
}
