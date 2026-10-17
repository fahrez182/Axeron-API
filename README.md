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
| `axsh` | The `axsh` shell: native client `libaxsh.so`, its in-app broker and the `app_process` bootstrap |
| `demo-axsh` | Test bench app for `axsh` (see below) |

## Using it

Include the modules you need as Gradle projects (as AxManager does), or publish them to Maven
local and depend on them from there:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// group dev.frb.axeron, version = api_version_code from manifest.gradle.kts
implementation("dev.frb.axeron:api:<version>")
implementation("dev.frb.axeron:axsh:<version>")
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
    `PATH` and callable, not marked as a plugin process, its `system.prop`, its own reports.
- **Custom command**, through the selected runner.

Compare *Latency* with the switch on and off, and with each runner, to see what each path costs.
