# Axeron API

The libraries apps use to talk to [AxManager](https://github.com/fahrez182/AxManager)'s server, which
runs as `shell` (ADB) or `root`. AxManager itself is built on them; it includes this repository as
its `api` submodule.

Guides: [axmanager.app](https://axmanager.app), in particular
[Run in any terminal](https://axmanager.app/guide/axsh) and
[Connect with an app](https://axmanager.app/guide/connect-app).

## Modules

| Module | What it is |
|---|---|
| `api` | The client API: `Axeron` (binder, permission, listeners), `relaunchServer`, file service, plugins, server info |
| `aidl` | The server's binder interfaces (`IAxeronService`, `IAxshProcess`, file service, Shizuku's) |
| `shared` | Constants shared by the server and its clients (`AxeronApiConstant`, `ShizukuApiConstant`) |
| `provider` | `AxeronProvider`, through which the server hands an app its binder |
| `server-shared` | Server-side code shared with the manager |
| `axsh` | The `axsh` shell: native client `libaxsh.so`, its in-app broker, the `app_process` bootstrap, and `AxShellService` |
| `demo-axsh` | Test bench app for `axsh` (see below) |

## Using it

### From JitPack

Add the repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the modules you need, at a release tag (or a commit hash, or `main-SNAPSHOT`):

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.fahrez182.Axeron-API:axsh:2.1.0")
    // axsh brings aidl along at runtime, and its own API (AxBroker, Axsh, AxShellService) uses
    // none of its types. Add it, at the same version, only to call the server's binder
    // interfaces directly (IAxeronService, …).
    // implementation("com.github.fahrez182.Axeron-API:aidl:2.1.0")
}
```

Published modules: `axsh`, and `aidl`, which it runs on. `shared`, `api` and `provider` are
AxManager's own: `axsh` only copies a few constants out of `shared` at build time, so apps never
need it. Releases up to 2.1.0 still have `shared` on JitPack, and up to 2.0.0 `api` and
`provider` too.

`axsh`'s public API all starts with `Ax`: `Axsh`, `AxBroker` and `AxShellService`. Up to 2.1.0
the last two were `Broker` and `ShellService`; those names still compile from Kotlin, deprecated.
Its other public classes are AxManager's own: using them from Kotlin is a compile error.

### As Gradle projects, or from Maven local

Include the modules as Gradle projects (as AxManager does), or publish them to Maven local and
depend on them from there:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// settings.gradle.kts: mavenLocal() in dependencyResolutionManagement.repositories

// build.gradle.kts
// group dev.frb.axeron, version = api_version_code from manifest.gradle.kts (21000 for 2.1.0)
dependencies {
    implementation("dev.frb.axeron:axsh:21000")
    // As from JitPack: only to call the server's binder interfaces directly.
    // implementation("dev.frb.axeron:aidl:21000")
}
```

Every app needs AxManager's permission (Allowed, Ask or Denied in AxManager's Privilege page).
With **Ask**, the first call shows AxManager's dialog.

## axsh in an app

An app runs shell commands through axsh in one of two ways.

### Without a dependency: AxManager's `libaxsh.so`

```xml
<queries>
    <package android:name="frb.axeron.manager" />
</queries>
```

```kotlin
val dir = packageManager.getApplicationInfo("frb.axeron.manager", 0).nativeLibraryDir
val axsh = File(dir, "libaxsh.so")   // changes on every AxManager update: resolve it each start
val process = Runtime.getRuntime().exec(arrayOf(axsh.path, "-c", "id"))
```

Nothing to bundle, and it always matches the installed AxManager. In ADB mode each run starts a
short-lived helper in `app_process` that asks AxManager for the server's binder, as in Termux.
After that first command the helper leaves `libaxsh.so --axsh-daemon` behind (a few MB, no JVM),
which hands later commands straight to the server; AxManager's **Keep axsh ready** (per app, in
its Privilege details) sets how long it stays: smart (default, 5 idle minutes), no limit or off.

### With the `axsh` module: the in-app broker

```kotlin
// build.gradle.kts
implementation(project(":axsh"))   // or the Maven artifact (see Using it)
```

```kotlin
import frb.axeron.shell.AxBroker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AxBroker.start(this)   // listens on @axsh.<uid>, in this process
    }
}
```

The broker fetches the binder once and keeps it. An app that already holds the binder passes it:
`AxBroker.start(this) { Axeron.getBinder() }`. Then run commands one of two ways:

**`Axsh`, in this process.** A shell on the server as a `java.lang.Process`, from one binder call:
nothing is exec'd, so there is nothing to extract. Call it off the main thread.

```kotlin
import frb.axeron.shell.Axsh

val process = Axsh.newProcess("-c", "id")             // sh's arguments; none for a shell on stdin
val run = Axsh.exec(arrayOf("ls", "-l"), dir = "/")   // a program as it is, no shell around it
val term = Axsh.newTerminal(rows = 24, cols = 80)     // on a PTY: setWindowSize() when it resizes
```

**Your own `libaxsh.so`, as a program.** For what takes a binary rather than a `Process`, such as a
terminal emulator, or to run it like AxManager's with `Runtime.exec`. The broker serves each run of it from this
process, so a command costs a socket connect and one binder call. It is exec'd from the native
library folder, so it must be extracted there:

```kotlin
// build.gradle.kts: only when you run libaxsh.so yourself
android {
    packaging { jniLibs { useLegacyPackaging = true } }
}
```

```kotlin
val axsh = File(applicationInfo.nativeLibraryDir, "libaxsh.so")
```

### Running `libaxsh.so`, AxManager's or your own

`libaxsh.so` takes `sh`'s arguments. Without a terminal its pipes become the remote shell's stdio
(separate stdout and stderr, the shell's exit code); in a terminal it opens a PTY. One process per
command, off the main thread:

```kotlin
val process = Runtime.getRuntime().exec(arrayOf(axsh.path, "-c", "pm list packages"))
val output = process.inputStream.bufferedReader().readText()
val errors = process.errorStream.bufferedReader().readText()
val code = process.waitFor()
```

**Optional, libsu**: if you already use [libsu](https://github.com/topjohnwu/libsu), it can keep one
axsh shell open for every command, from `libaxsh.so` or, with the module, from `Axsh`:

```kotlin
Shell.setDefaultBuilder(Shell.Builder.create().setCommands(axsh.path))
val shell = Shell.Builder.create().build(Axsh.newProcess())   // no libaxsh.so to run
```

## AxShellService: your own code as shell or root

libsu's `RootService`, but through axsh, so it works in ADB mode too. Your service class runs in
an `app_process` started as the server's uid (2000 in ADB mode, 0 with root), loaded from your
APK. Needs the `axsh` module, but not `useLegacyPackaging`: with `AxBroker.start` the process starts
through `Axsh.exec`, without it through AxManager's `libaxsh.so`, never through your own.

An AIDL interface, as for any bound service:

```aidl
// src/main/aidl/com/example/IMyService.aidl
interface IMyService {
    int getUid();
    String exec(String command);
}
```

```kotlin
// build.gradle.kts
android { buildFeatures { aidl = true } }
```

The service. Everything in it runs as shell or root:

```kotlin
class MyService : AxShellService() {
    override fun onBind(intent: Intent): IBinder {
        return object : IMyService.Stub() {
            override fun getUid(): Int {
                return Os.getuid()   // 2000 or 0
            }

            override fun exec(command: String): String {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                return process.inputStream.bufferedReader().readText()
            }
        }
    }
}
```

Bind it from the app, on the main thread:

```kotlin
val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val service = IMyService.Stub.asInterface(binder)
        // call it off the main thread: service.exec("id")
    }

    override fun onServiceDisconnected(name: ComponentName) {}
}

AxShellService.bind(Intent(context, MyService::class.java), connection)
// …
AxShellService.unbind(connection)
```

No manifest entry: the `axsh` module brings its provider, and its ProGuard rules keep every
`AxShellService` subclass.

**Same as libsu's RootService**: `onCreate`, `onBind`, `onUnbind`, `onRebind`, `onDestroy`,
`stopSelf()`; `bind`, `unbind`, `stop`, main thread only; one process for all of an app's
services (statics are shared), gone with the app.

**Daemon mode**: bind with `intent.addCategory(AxShellService.CATEGORY_DAEMON_MODE)` and return
`true` from `onUnbind`. The service then runs in a second process that outlives the app; the next
bind, even after the app restarts, reaches the same instance and calls `onRebind`.
`AxShellService.stop(intent)` ends it.

**Also**:
- The service's Context reports the uid's own package (`com.android.shell` in ADB mode), so
  system services that check it (clipboard, AppOps) accept it.
- `getApplicationContext()` returns the service itself: no `App` runs there.
- In a debuggable app, the process shows in Android Studio's *Attach Debugger* as
  `<package>:shell_shared` or `<package>:shell_daemon`.
- `AxShellService.lastFailure(component)` says why a bind gave `onBindingDied` or `onNullBinding`.

**Starting the process yourself**: libsu's `bindOrTask` hands back the start command for a shell
of your choice. Here, set it once for all binds with `AxShellService.setLauncher { argv -> … }`,
which gets the `app_process` argv and returns the started `Process`.

**Not (yet) like libsu**: user 0 only.

## demo-axsh

A test bench for all of the above. Run it with AxManager's server running.

- **`axsh_direct_connection`** (switch at the top, restarts the app): on, the app's own
  `libaxsh.so` and `AxBroker`; off, AxManager's `libaxsh.so` with no broker, as in Termux.
- **Runner**: a new `axsh -c` process per command, or libsu's cached shell.
- **Tests**:
  - connection: latency, concurrent sessions, isolation between commands;
  - shell: exit code, separate streams, stdin, large output, environment;
  - privileges: uid and SELinux context, `pm`, `settings`, `dumpsys`;
  - the **Test Function** plugin (`plugins/test_function` in AxManager): installed, `axtest` on
    `PATH` and callable, not marked as a plugin process, its `system.prop`, its own reports;
  - libsu `RootService`, root mode only;
  - `AxShellService`: uid, `Runtime.exec` inside, two services in one process with a shared
    static, the clipboard from its Context, `getApplicationContext()`;
  - `AxShellService` daemon: same pid across an unbind and across closing the app, a new one after
    `stop`.
- **Custom command**, through the selected runner.

Compare *Latency* with the switch on and off, and with each runner, to see what each path costs.
