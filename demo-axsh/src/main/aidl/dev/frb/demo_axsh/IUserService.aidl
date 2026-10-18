package dev.frb.demo_axsh;

// The demo services' interface. Every method runs in the service's own process, so as the
// server's uid: shell (2000) in ADB mode, root (0) in root mode.
interface IUserService {
    int getUid();

    int getPid();

    String getSeLinuxContext();

    // [command] through `sh -c`, started with Runtime.exec in the service's process: its output.
    String exec(String command);

    // The service Context's op package, what system services check against the uid.
    String getOpPackageName();

    // ClipboardManager.hasPrimaryClip with the service's Context; throws SecurityException if refused.
    boolean hasPrimaryClip();

    // The service's getApplicationContext(), as a string ("null" when there is none).
    String getApplicationContextName();

    // Adds 1 to SharedCounter.value, a static in the service's process, and returns it.
    int incrementSharedCounter();
}
