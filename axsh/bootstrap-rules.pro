# The bootstrap's own dex, libaxshboot.so (see bootstrapDex in build.gradle.kts): app_process
# starts Bootstrap.main, and R8 keeps only what that reaches.
-keep class frb.axeron.shell.Bootstrap {
    public static void main(java.lang.String[]);
}

# Readable stack traces in logcat; the size this saves is not worth it.
-dontobfuscate

# Hidden framework classes (IActivityManager, IIntentReceiver, ...) are there at runtime.
-dontwarn **
