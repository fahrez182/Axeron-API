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
| `axsh` | The `axsh` shell: native client `libaxsh.so`, its in-app broker, the `app_process` bootstrap, and `ShellService` |
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
    implementation("com.github.fahrez182.Axeron-API:api:2.0.0")
    implementation("com.github.fahrez182.Axeron-API:axsh:2.0.0")
}
```

Published modules: `api`, `aidl`, `shared`, `provider`, `axsh`. Each brings what it runs on
(`api`: `aidl`, `shared`, `axsh`; `axsh`: `aidl`, `shared`); declare one yourself only to use
its classes directly.

### As Gradle projects, or from Maven local

Include the modules as Gradle projects (as AxManager does), or publish them to Maven local and
depend on them from there:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// group dev.frb.axeron, version = api_version_code from manifest.gradle.kts (20000 for 2.0.0)
implementation("dev.frb.axeron:api:20000")
implementation("dev.frb.axeron:axsh:20000")
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
ProcessBuilder(axsh.path, "-c", "id").start()
```

Nothing to bundle, and it always matches the installed AxManager. In ADB mode each run starts a
short-lived helper in `app_process` that asks AxManager for the server's binder, as in Termux.
After that first command the helper leaves `libaxsh.so --axsh-daemon` behind (a few MB, no JVM),
which hands later commands straight to the server; AxManager's **Keep axsh ready** (per app, in
its Privilege details) sets how long it stays: smart (default, 5 idle minutes), no limit or off.

### With the `axsh` module: the in-app broker

```kotlin
// build.gradle.kts
implementation(project(":axsh"))           // or the Maven artifact
android {
    packaging { jniLibs { useLegacyPackaging = true } }   // libaxsh.so is exec'd
}
```

```kotlin
import frb.axeron.shell.Broker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Broker.start(this)   // listens on @axsh.<uid>, in this process
    }
}

val axsh = File(applicationInfo.nativeLibraryDir, "libaxsh.so")
```

The broker fetches the binder once and serves every later `libaxsh.so` run of this app from the
running process, so a command costs a socket connect and one binder call. An app that already
holds the binder passes it: `Broker.start(this) { Axeron.getBinder() }`.

### Either way

`libaxsh.so` takes `sh`'s arguments. Without a terminal its pipes become the remote shell's stdio
(separate stdout and stderr, the shell's exit code); in a terminal it opens a PTY. It works as a
[libsu](https://github.com/topjohnwu/libsu) shell too, which keeps one session open:

```kotlin
Shell.setDefaultBuilder(Shell.Builder.create().setCommands(axsh.path))
```

## ShellService: your own code as shell or root

libsu's `RootService`, but through axsh, so it works in ADB mode too. Your service class runs in
an `app_process` started as the server's uid (2000 in ADB mode, 0 with root), loaded from your
APK. Needs the `axsh` module; `Broker.start` is optional (without it, AxManager's `libaxsh.so`
starts the process).

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
class MyService : ShellService() {
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

ShellService.bind(Intent(context, MyService::class.java), connection)
// …
ShellService.unbind(connection)
```

No manifest entry: the `axsh` module brings its provider, and its ProGuard rules keep every
`ShellService` subclass.

**Same as libsu's RootService**: `onCreate`, `onBind`, `onUnbind`, `onRebind`, `onDestroy`,
`stopSelf()`; `bind`, `unbind`, `stop`, main thread only; one process for all of an app's
services (statics are shared), gone with the app.

**Daemon mode**: bind with `intent.addCategory(ShellService.CATEGORY_DAEMON_MODE)` and return
`true` from `onUnbind`. The service then runs in a second process that outlives the app; the next
bind, even after the app restarts, reaches the same instance and calls `onRebind`.
`ShellService.stop(intent)` ends it.

**Also**:
- The service's Context reports the uid's own package (`com.android.shell` in ADB mode), so
  system services that check it (clipboard, AppOps) accept it.
- `getApplicationContext()` returns the service itself: no `Application` runs there.
- In a debuggable app, the process shows in Android Studio's *Attach Debugger* as
  `<package>:shell_shared` or `<package>:shell_daemon`.
- `ShellService.lastFailure(component)` says why a bind gave `onBindingDied` or `onNullBinding`.

**Starting the process yourself**: libsu's `bindOrTask` hands back the start command for a shell
of your choice. Here, set it once for all binds with `ShellService.setLauncher { argv -> … }`,
which gets the `app_process` argv and returns the started `Process`.

**Not (yet) like libsu**: user 0 only.

## demo-axsh

A test bench for all of the above. Run it with AxManager's server running.

- **`axsh_direct_connection`** (switch at the top, restarts the app): on, the app's own
  `libaxsh.so` and `Broker`; off, AxManager's `libaxsh.so` with no broker, as in Termux.
- **Runner**: a new `axsh -c` process per command, or libsu's cached shell.
- **Tests**:
  - connection: latency, concurrent sessions, isolation between commands;
  - shell: exit code, separate streams, stdin, large output, environment;
  - privileges: uid and SELinux context, `pm`, `settings`, `dumpsys`;
  - the **Test Function** plugin (`plugins/test_function` in AxManager): installed, `axtest` on
    `PATH` and callable, not marked as a plugin process, its `system.prop`, its own reports;
  - libsu `RootService`, root mode only;
  - `ShellService`: uid, `Runtime.exec` inside, two services in one process with a shared
    static, the clipboard from its Context, `getApplicationContext()`;
  - `ShellService` daemon: same pid across an unbind and across closing the app, a new one after
    `stop`.
- **Custom command**, through the selected runner.

Compare *Latency* with the switch on and off, and with each runner, to see what each path costs.
