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
import android.ddm.DdmHandleAppName
import android.database.Cursor
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * POC: libsu's RootService for AxManager, used the same way, in ADB mode as well as root.
 *
 * ```
 * class MyService : AxShellService() {
 *     override fun onBind(intent: Intent): IBinder = object : IMyService.Stub() { … }
 * }
 * AxShellService.bind(Intent(context, MyService::class.java), connection)   // main thread
 * AxShellService.unbind(connection)
 * ```
 *
 * As RootService's, an app's services share one `app_process`, started through axsh
 * ([setLauncher]) as the server's uid (shell or root) with this app's base.apk as classpath, and
 * gone with the app. Those bound with [CATEGORY_DAEMON_MODE] share another, which outlives it.
 * A process exits once it hosts no service. Each service's [onCreate], [onBind], [onUnbind],
 * [onRebind] and [onDestroy] run on its process's main thread; its Context is this package's
 * (user 0 only in this POC). Connections share one [onBind] binder. If the process dies,
 * connections get onServiceDisconnected; if it never comes up, onBindingDied.
 *
 * Where RootService fails in ADB mode, this differs: it starts on any shell (libsu only from a
 * root one), its classpath is base.apk (not a jar in the app's private cache, which shell can't
 * read), and it hands its binder in through [ShellServiceProvider], which checks the caller's uid
 * (not a broadcast needing BROADCAST_PACKAGE_REMOVED).
 */
abstract class AxShellService : ContextWrapper(null) {

    open fun onCreate() {}

    abstract fun onBind(intent: Intent): IBinder

    /**
     * The last connection is gone (unbound, or the app died). True keeps this instance for the
     * next bind, which calls [onRebind]; in the daemon process ([CATEGORY_DAEMON_MODE]) that bind
     * may come from a later run of the app. False: [onDestroy].
     */
    open fun onUnbind(intent: Intent): Boolean = false

    /** A bind reached this service after [onUnbind] returned true. */
    open fun onRebind(intent: Intent) {}

    open fun onDestroy() {}

    /** Ends this service: [onDestroy], and its connections get onServiceDisconnected. */
    fun stopSelf() {
        ShellServiceRemote.stopSelf(this)
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
        /**
         * An intent with this category binds its service in the daemon process, as libsu's
         * RootService.CATEGORY_DAEMON_MODE: it outlives the app, so a service [onUnbind] keeps
         * is there for the app's next run.
         */
        const val CATEGORY_DAEMON_MODE = "frb.axeron.shell.DAEMON_MODE"

        @JvmStatic
        fun bind(intent: Intent, connection: ServiceConnection) =
            ShellServiceClient.bind(intent, ShellServiceClient.mainExecutor, connection)

        @JvmStatic
        fun bind(intent: Intent, executor: Executor, connection: ServiceConnection) =
            ShellServiceClient.bind(intent, executor, connection)

        @JvmStatic
        fun unbind(connection: ServiceConnection) = ShellServiceClient.unbind(connection)

        /**
         * Stops the service whatever its connections; they get onServiceDisconnected. Also one
         * in the daemon process this app process isn't bound to (left by an earlier run).
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
         * Default: [Axsh.exec] through this app's AxBroker, which needs [AxBroker.start]. An app
         * without a broker sets its own, e.g. one that runs a libaxsh.so with `-c`.
         */
        @JvmStatic
        fun setLauncher(launcher: Launcher) {
            ShellServiceClient.launcher = launcher
        }
    }
}

// ---- in the app ----

/**
 * The app side: one [Proc] per process kind (shared, daemon), touched on the main thread; what is
 * sent to a process goes through its [Proc.queue], in order.
 */
internal object ShellServiceClient {
    private const val TAG = "AxShellService"

    const val AUTHORITY_SUFFIX = ".shellservice"
    const val METHOD_ATTACH = "attach"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BINDER = "binder"

    // Transactions on a service process's control binder (ShellServiceRemote.Control).
    /** class name, intent → binder, why there's none. */
    const val TRANSACTION_BIND = IBinder.FIRST_CALL_TRANSACTION

    /** class name → whether the process now goes (it hosts nothing). */
    const val TRANSACTION_UNBIND = IBinder.FIRST_CALL_TRANSACTION + 1

    /** As unbind, the service destroyed whatever its onUnbind. */
    const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 2

    /** Oneway: the process may exit (it said it goes). */
    const val TRANSACTION_EXIT = IBinder.FIRST_CALL_TRANSACTION + 3

    /** On the app's token, oneway from the process: class name, a service that stopped itself. */
    const val TRANSACTION_SERVICE_GONE = IBinder.FIRST_CALL_TRANSACTION

    // What a service process is started for: its stdin is the token, then one of these lines.
    const val ACTION_BIND = "bind"
    const val ACTION_STOP = "stop" // + " <class>"

    const val KIND_SHARED = "shared"
    const val KIND_DAEMON = "daemon"

    private val main = Handler(Looper.getMainLooper())
    val mainExecutor = Executor { main.post(it) }

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
    var launcher = AxShellService.Launcher(::defaultLaunch)

    /**
     * Through axsh, which needs [AxBroker.start] to have run: without it AxBroker has no way to
     * the binder, and [Axsh.exec] says so at once with an IOException. There is no fallback to
     * the manager's libaxsh.so: finding it takes the manager's package name, which nothing here
     * knows.
     */
    private fun defaultLaunch(argv: Array<String>): java.lang.Process = Axsh.exec(argv)

    /** This app, kept by [ShellServiceProvider.onCreate]: providers start before the Application. */
    @Volatile
    private var appContext: Context? = null

    val context: Context
        get() = checkNotNull(appContext) { "ShellServiceProvider isn't running: is the axsh manifest merged?" }

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    /** One service bound from this app process. */
    private class Bound(val component: ComponentName, val intent: Intent, val retried: Boolean) {
        val connections = LinkedHashMap<ServiceConnection, Executor>()
        var binder: IBinder? = null
    }

    /** One service process, as this app process knows it. */
    private class Proc(val daemon: Boolean) {
        val token: String = UUID.randomUUID().toString()

        /** By component; main thread. */
        val bound = HashMap<ComponentName, Bound>()

        /** What is sent to the process, one at a time, off the main thread. */
        val queue: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "shell-service-ops").apply { isDaemon = true } }

        /** Held by the process: it learns of this app process's death, and tells it of stopSelf. */
        val appToken: IBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != TRANSACTION_SERVICE_GONE) return super.onTransact(code, data, reply, flags)
                val className = data.readString() ?: return true
                main.post { serviceGone(this@Proc, className) }
                return true
            }
        }

        @Volatile
        var process: java.lang.Process? = null

        @Volatile
        var control: IBinder? = null

        /** It said it goes, or died: nothing more is sent, later binds go to a new process. */
        @Volatile
        var exiting = false

        /** What the launch wrote to stderr lately, for when it never connects. */
        val stderr = ArrayDeque<String>()
    }

    private val procs = HashMap<Boolean, Proc>()

    /** Why each component's last bind ended in onNullBinding / onBindingDied. */
    val failures = ConcurrentHashMap<ComponentName, String>()

    /** Processes waiting to hand in their control binder; read from the provider. */
    private val starting = ConcurrentHashMap<String, Proc>()

    private fun isDaemon(intent: Intent) = intent.hasCategory(AxShellService.CATEGORY_DAEMON_MODE)

    fun bind(intent: Intent, executor: Executor, connection: ServiceConnection) {
        checkMainThread()
        bind(intent, executor, connection, retried = false)
    }

    private fun bind(intent: Intent, executor: Executor, connection: ServiceConnection, retried: Boolean) {
        val component = requireNotNull(intent.component) { "the intent has no component" }
        require(component.packageName == context.packageName) {
            "ShellServices outside of this app are not supported"
        }
        val proc = procs.getOrPut(isDaemon(intent)) { Proc(isDaemon(intent)).also(::start) }
        val bound = proc.bound.getOrPut(component) {
            failures.remove(component)
            Bound(component, Intent(intent), retried).also { if (proc.control != null) sendBind(proc, it) }
        }
        bound.connections[connection] = executor
        bound.binder?.let { service -> executor.execute { connection.onServiceConnected(component, service) } }
    }

    fun unbind(connection: ServiceConnection) {
        checkMainThread()
        for (proc in procs.values) {
            val bound = proc.bound.values.firstOrNull { connection in it.connections } ?: continue
            bound.connections.remove(connection)
            if (bound.connections.isEmpty()) {
                proc.bound.remove(bound.component)
                end(proc, TRANSACTION_UNBIND, bound.component.className)
            }
            return
        }
    }

    fun stop(intent: Intent) {
        checkMainThread()
        val component = intent.component ?: return
        val daemon = isDaemon(intent)
        val proc = procs[daemon]
        proc?.bound?.remove(component)?.let { bound ->
            if (bound.binder != null) {
                bound.connections.forEach { (conn, executor) -> executor.execute { conn.onServiceDisconnected(component) } }
            }
        }
        if (proc?.control != null) {
            end(proc, TRANSACTION_STOP, component.className)
        } else if (daemon) {
            // Not connected to the daemon process here: one may still run. A launch asks it.
            thread(name = "shell-service-stop") {
                runCatching {
                    val process = launcher.launch(argv(daemon = true))
                    process.outputStream.use { it.write("\n$ACTION_STOP ${component.className}\n".toByteArray()) }
                    process.waitFor()
                }.onFailure { Log.w(TAG, "can't stop ${component.className}", it) }
            }
        }
    }

    /** Unbind or stop ([code]) [className] in [proc]; the process may then go. */
    private fun end(proc: Proc, code: Int, className: String) = proc.queue.execute {
        if (proc.exiting) return@execute
        val control = proc.control ?: return@execute
        val goes = try {
            var goes = false
            transact(control, code, oneway = false, write = { it.writeString(className) }) { goes = it.readInt() != 0 }
            goes
        } catch (_: Exception) {
            true // dead: its death notice does the rest
        }
        if (goes) retire(proc)
    }

    /** On [proc]'s queue: it goes. Let it exit; what is bound to it meanwhile moves on. */
    private fun retire(proc: Proc) {
        proc.exiting = true
        proc.control?.let { control -> runCatching { transact(control, TRANSACTION_EXIT, oneway = true) {} } }
        main.post { drop(proc, died = false) }
    }

    private fun sendBind(proc: Proc, bound: Bound) = proc.queue.execute {
        // A bind sent here after the process said it goes: drop() moves it to the next one.
        if (proc.exiting) return@execute
        val control = proc.control ?: return@execute
        var binder: IBinder? = null
        var error: String? = null
        try {
            transact(control, TRANSACTION_BIND, oneway = false, write = {
                it.writeString(bound.component.className)
                bound.intent.writeToParcel(it, 0)
            }) {
                // Throws what the process's onTransact threw outside its own catch.
                it.readException()
                binder = it.readStrongBinder()
                error = it.readString()
                if (binder == null && error == null) error = "empty reply (${it.dataSize()} bytes)"
            }
        } catch (_: DeadObjectException) {
            // It exited under us (its last service stopped itself): the next process takes it.
            retire(proc)
            return@execute
        } catch (e: Exception) {
            error = "bind transaction failed: ${Log.getStackTraceString(e)}"
        }
        main.post { connected(proc, bound, binder, error) }
    }

    /**
     * The argv of a service process. Under `sh`, not exec'd: the server kills a shell's whole
     * process group when the app dies, and only a process that doesn't lead its group can leave it
     * (setsid) to stay on as the daemon. `; exit $?` so sh can't exec its last command either.
     */
    private fun argv(daemon: Boolean): Array<String> {
        val app = context
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val kind = if (daemon) KIND_DAEMON else KIND_SHARED
        return arrayOf(
            "/system/bin/sh", "-c", "\"\$@\"; exit \$?", "shellservice",
            "/system/bin/app_process",
            "-Djava.class.path=${app.applicationInfo.sourceDir}",
            *(if (debuggable) DEBUG_ARGS else emptyArray()),
            "/system/bin",
            "--nice-name=${app.packageName}:shell_$kind",
            ShellServiceRemote::class.java.name,
            app.packageName, kind,
        )
    }

    private fun start(proc: Proc) {
        starting[proc.token] = proc
        val argv = argv(proc.daemon)
        // The launcher may block (binder, AxManager's permission dialog): not on the main thread.
        thread(name = "shell-service-start") {
            val process = try {
                launcher.launch(argv)
            } catch (e: Throwable) {
                Log.e(TAG, "can't start a service process", e)
                main.post { drop(proc, died = true) }
                return@thread
            }
            proc.process = process
            // On stdin, not argv: any process of the server's uid can read /proc/<pid>/cmdline,
            // and with the token it could hand us its own binder first.
            runCatching { process.outputStream.use { it.write("${proc.token}\n$ACTION_BIND\n".toByteArray()) } }
            thread(name = "shell-service-err", isDaemon = true) {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        Log.w(TAG, line)
                        synchronized(proc.stderr) {
                            proc.stderr.addLast(line)
                            if (proc.stderr.size > 20) proc.stderr.removeFirst()
                        }
                    }
                }
            }
            val code = process.waitFor()
            Log.i(TAG, "service process launch exited with $code")
            // Once its control binder is in, that binder's death says when the process is gone:
            // the daemon, found running by this launch, outlives it.
            main.post { if (proc.control == null) drop(proc, died = true) }
        }
    }

    /** From [ShellServiceProvider], on a binder thread: the process with [token] is up. */
    fun attach(token: String, control: IBinder): IBinder? {
        val proc = starting.remove(token) ?: return null
        proc.control = control
        runCatching { control.linkToDeath({ main.post { drop(proc, died = true) } }, 0) }
        // Not here: the process's main thread may still be waiting on this very call.
        main.post {
            if (proc.exiting) return@post
            proc.bound.values.forEach { sendBind(proc, it) }
            // Unbound before it came up: a new shared process has nothing to do. The daemon may
            // host services kept from before: it stays.
            if (proc.bound.isEmpty() && !proc.daemon) proc.queue.execute { retire(proc) }
        }
        return proc.appToken
    }

    private fun connected(proc: Proc, bound: Bound, service: IBinder?, error: String?) {
        if (proc.bound[bound.component] !== bound) return // unbound or stopped meanwhile
        if (service == null) {
            // onBind failed or returned nothing (onNullBinding is API 28+).
            failures[bound.component] = error ?: "no binder"
            Log.e(TAG, "${bound.component.className}: no binder: $error")
            if (Build.VERSION.SDK_INT >= 28) {
                bound.connections.forEach { (conn, executor) -> executor.execute { conn.onNullBinding(bound.component) } }
            }
            return
        }
        bound.binder = service
        bound.connections.forEach { (conn, executor) -> executor.execute { conn.onServiceConnected(bound.component, service) } }
    }

    /** A service called stopSelf. */
    private fun serviceGone(proc: Proc, className: String) {
        val bound = proc.bound.values.firstOrNull { it.component.className == className } ?: return
        proc.bound.remove(bound.component)
        if (bound.binder != null) {
            bound.connections.forEach { (conn, executor) -> executor.execute { conn.onServiceDisconnected(bound.component) } }
        }
    }

    /**
     * [proc] is gone ([died]) or going. Connected services are disconnected; binds still waiting
     * move to a new process once, unless it never came up: then onBindingDied.
     */
    private fun drop(proc: Proc, died: Boolean) {
        proc.exiting = true
        starting.remove(proc.token)
        if (procs[proc.daemon] === proc) procs.remove(proc.daemon)
        proc.queue.shutdown()
        val all = proc.bound.values.toList()
        proc.bound.clear()
        val tail = synchronized(proc.stderr) { proc.stderr.joinToString("\n") }
        for (bound in all) {
            when {
                bound.binder != null -> bound.connections.forEach { (conn, executor) ->
                    executor.execute { conn.onServiceDisconnected(bound.component) }
                }
                proc.control != null && !bound.retried -> bound.connections.forEach { (conn, executor) ->
                    bind(bound.intent, executor, conn, retried = true)
                }
                else -> {
                    val why = if (died && proc.control == null) "its process never connected" else "its process went"
                    failures[bound.component] = why + if (tail.isEmpty()) "" else ":\n$tail"
                    Log.e(TAG, "${bound.component.className}: $why" + if (tail.isEmpty()) "" else ":\n$tail")
                    bound.connections.forEach { (conn, executor) -> executor.execute { conn.onBindingDied(bound.component) } }
                }
            }
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
        check(Looper.myLooper() == Looper.getMainLooper()) { "AxShellService.bind/unbind/stop: main thread only" }
}

/**
 * Where a service process hands in its control binder. Exported, so it takes calls only from the
 * server's uids (root, shell) and only with a token one of our binds is waiting on.
 */
@InternalAxeronApi
class ShellServiceProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ShellServiceClient.METHOD_ATTACH || extras == null) return null
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != 2000) { // root, shell
            Log.w("AxShellService", "attach from uid $uid refused")
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
 * The `app_process` entry: `<package> <shared|daemon>`; on stdin the token, then `bind` or
 * `stop <class>`. Runs as the server's uid and hosts the app's services of that kind.
 */
internal object ShellServiceRemote {
    private const val TAG = "AxShellService"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private lateinit var packageName: String
    private var daemon = false

    /** A service this process hosts; [boundBy] the app process bound to it, null while kept unbound. */
    private class Hosted(val service: AxShellService) {
        var binder: IBinder? = null
        var intent: Intent? = null
        var boundBy: IBinder? = null
    }

    /** By class name; main thread. */
    private val hosted = HashMap<String, Hosted>()

    /** One for every app process we serve: the app links to its death. */
    private val control by lazy { Control() }

    /** The app process we serve now (the daemon serves each run of the app in turn). */
    @Volatile
    private var app: IBinder? = null

    /** Told the app it goes: no more binds. */
    @Volatile
    private var exiting = false

    private val authority get() = packageName + ShellServiceClient.AUTHORITY_SUFFIX

    /** Where the daemon waits for the app's next runs (abstract, per uid and package). */
    private val socketName get() = "shellservice.${Os.getuid()}.$packageName"

    /** All services share it: this package's, with the uid's own op package. Main thread. */
    private val packageContext: Context by lazy {
        // As Context: the stub's getSystemContext returns ContextImpl, package-private on
        // devices, and calling through it there is an IllegalAccessError.
        val thread = ActivityThread.systemMain()
        val system: Context = thread.systemContext
        withUidOpPackage(
            thread, system,
            system.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val stdin = System.`in`.bufferedReader()
        val token = stdin.readLine().orEmpty()
        val action = stdin.readLine() ?: ShellServiceClient.ACTION_BIND
        if (args.size < 2) {
            System.err.println("shellservice: usage: <package> <shared|daemon>, the token and an action on stdin")
            exitProcess(2)
        }
        packageName = args[0]
        daemon = args[1] == ShellServiceClient.KIND_DAEMON
        val stopping = action.startsWith(ShellServiceClient.ACTION_STOP)

        // The daemon already runs: it takes the bind (or stop) itself.
        if (daemon) {
            val line = if (stopping) action else "${ShellServiceClient.ACTION_BIND} $token"
            handOver(line)?.let { exitProcess(it) }
        }
        if (stopping) exitProcess(0) // nothing to stop
        if (token.isEmpty()) {
            System.err.println("shellservice: no token on stdin")
            exitProcess(2)
        }

        // The name Studio's debugger and Logcat list this process under.
        runCatching { DdmHandleAppName.setAppName("$packageName:shell_${args[1]}", 0) }
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
        if (daemon) becomeDaemon()
        serve(app)
        Looper.loop()
    }

    /**
     * Passes [line] (`bind <token>` or `stop <class>`) to the running daemon: the exit code
     * then, null when none runs or it is going.
     */
    private fun handOver(line: String): Int? {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName))
        } catch (_: IOException) {
            socket.close()
            return null
        }
        return socket.use {
            it.outputStream.write("$line\n".toByteArray())
            when (val reply = it.inputStream.bufferedReader().readLine()) {
                "ok" -> 0
                null, "exiting" -> null
                else -> {
                    System.err.println("shellservice: the running daemon said: $reply")
                    1
                }
            }
        }
    }

    /** [app] is the app process we serve now; when it dies, it unbinds what it bound. */
    private fun serve(app: IBinder) {
        this.app = app
        try {
            app.linkToDeath({ main.post { appDied(app) } }, 0)
        } catch (_: Exception) {
            main.post { appDied(app) }
        }
    }

    private fun appDied(dead: IBinder) {
        if (app === dead) app = null
        if (!daemon) {
            // The shared process goes with the app.
            hosted.keys.toList().forEach { destroy(it) }
            exitProcess(0)
        }
        hosted.filterValues { it.boundBy === dead }.keys.forEach { unbind(it) }
        if (hosted.isEmpty()) exitProcess(0)
    }

    /** First bind: the class from our classpath (base.apk), [packageContext]. */
    private fun bindOnMain(className: String, intent: Intent): IBinder? {
        check(!exiting) { "the service process is exiting" }
        val entry = hosted[className] ?: run {
            val service = Class.forName(className).getDeclaredConstructor().newInstance() as AxShellService
            service.attach(packageContext)
            Hosted(service).also {
                hosted[className] = it
                service.onCreate()
            }
        }
        val binder = entry.binder
        if (binder == null) {
            val made = try {
                entry.service.onBind(intent)
            } catch (e: Throwable) {
                destroy(className)
                throw e
            }
            entry.binder = made
        } else if (entry.boundBy == null) {
            entry.service.onRebind(intent)
        }
        entry.intent = intent
        entry.boundBy = app
        return entry.binder
    }

    /** No connection left: [AxShellService.onUnbind] decides whether it stays. */
    private fun unbind(className: String) {
        val entry = hosted[className] ?: return
        entry.boundBy = null
        val keep = entry.intent?.let { runCatching { entry.service.onUnbind(it) }.getOrDefault(false) } ?: false
        if (!keep) destroy(className)
    }

    private fun destroy(className: String) {
        val entry = hosted.remove(className) ?: return
        runCatching { entry.service.onDestroy() }
    }

    /** After an unbind or stop: whether this process now goes, told to the app. */
    private fun goes(): Boolean {
        if (hosted.isEmpty()) exiting = true
        return exiting
    }

    fun stopSelf(service: AxShellService) {
        main.post {
            val className = hosted.entries.firstOrNull { it.value.service === service }?.key ?: return@post
            destroy(className)
            app?.let { app ->
                runCatching {
                    val data = Parcel.obtain()
                    try {
                        data.writeString(className)
                        app.transact(ShellServiceClient.TRANSACTION_SERVICE_GONE, data, null, IBinder.FLAG_ONEWAY)
                    } finally {
                        data.recycle()
                    }
                }
            }
            if (hosted.isEmpty()) exitProcess(0)
        }
    }

    /**
     * Stays on without the app: out of the shell's process group, which the server kills with the
     * app; stdio to /dev/null, as the pipes to the app break; then waits on [socketName].
     */
    private fun becomeDaemon() {
        try {
            Os.setsid()
        } catch (e: ErrnoException) {
            Log.w(TAG, "can't leave the shell's process group, the daemon dies with the app: $e")
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
            Log.w(TAG, "no daemon socket, the app's next run starts a new daemon: $e")
            return
        }
        Log.i(TAG, "$packageName's daemon process, pid ${Os.getpid()}")
        thread(name = "shell-service-daemon", isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "shell-service-handover", isDaemon = true) { takeOver(client) }
            }
        }
    }

    /** A launch for a later run of the app found us: `bind <token>` or `stop <class>`. */
    private fun takeOver(client: LocalSocket) = client.use {
        // Same uid only: the server's, as the launch that found us.
        if (it.peerCredentials.uid != Os.getuid()) return
        val line = it.inputStream.bufferedReader().readLine() ?: return
        val action = line.substringBefore(' ')
        val arg = line.substringAfter(' ', "")
        val reply = when {
            exiting -> "exiting"
            action == ShellServiceClient.ACTION_STOP -> {
                onMain {
                    destroy(arg)
                    if (hosted.isEmpty()) main.post { exitProcess(0) }
                }
                "ok"
            }
            action == ShellServiceClient.ACTION_BIND -> {
                val app = runCatching { sendControl(authority, arg, control) }.getOrNull()
                if (app == null) "refused by $packageName" else {
                    main.post { serve(app) }
                    "ok"
                }
            }
            else -> "unknown action $action"
        }
        it.outputStream.write("$reply\n".toByteArray())
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

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return task.get()
    }

    /** What the app calls once we're up: bind, unbind, stop, exit. */
    private class Control : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            ShellServiceClient.TRANSACTION_BIND -> {
                val result = runCatching {
                    val className = requireNotNull(data.readString()) { "no class name" }
                    val intent = Intent.CREATOR.createFromParcel(data)
                    onMain { bindOnMain(className, intent) }
                }
                val error = result.exceptionOrNull()?.let { e ->
                    Log.getStackTraceString((e as? ExecutionException)?.cause ?: e)
                }
                error?.let { System.err.println("shellservice: onBind failed: $it") }
                // The binder, then why there's none: the app shows it (AxShellService.lastFailure).
                reply?.writeNoException()
                reply?.writeStrongBinder(result.getOrNull())
                reply?.writeString(error ?: if (result.getOrNull() == null) "onBind returned null" else null)
                true
            }
            ShellServiceClient.TRANSACTION_UNBIND, ShellServiceClient.TRANSACTION_STOP -> {
                val className = data.readString().orEmpty()
                val goes = onMain {
                    if (code == ShellServiceClient.TRANSACTION_STOP) destroy(className) else unbind(className)
                    goes()
                }
                reply?.writeInt(if (goes) 1 else 0)
                true
            }
            ShellServiceClient.TRANSACTION_EXIT -> {
                main.post {
                    hosted.keys.toList().forEach { destroy(it) }
                    exitProcess(0)
                }
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

/** The name up to 2.1.0; renamed so the public API all starts with Ax. */
@Deprecated("Renamed to AxShellService", ReplaceWith("AxShellService", "frb.axeron.shell.AxShellService"))
typealias ShellService = AxShellService
