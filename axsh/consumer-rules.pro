# Hidden APIs (ActivityThread, IActivityManager, ServiceManager…): compiled against stubs, given
# by the framework on the device; they're never in the APK, so R8 can't find them.
-dontwarn android.app.**
-dontwarn android.content.**
-dontwarn android.os.**
-dontwarn android.view.**
-dontwarn android.hardware.**
-dontwarn android.permission.**
-dontwarn android.ddm.**
-dontwarn com.android.internal.**

# AxShellService: app_process starts the remote side by name, which makes the service by name.
-keep class frb.axeron.shell.ShellServiceRemote {
    public static void main(java.lang.String[]);
}
-keep class * extends frb.axeron.shell.AxShellService {
    <init>();
}

# libaxsh.so starts this by name through app_process.
-keep class frb.axeron.shell.Bootstrap {
    public static void main(java.lang.String[]);
}
