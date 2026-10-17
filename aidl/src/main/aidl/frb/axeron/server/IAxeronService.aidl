// IAxeronService.aidl
package frb.axeron.server;

import frb.axeron.server.IFileService;
import frb.axeron.server.IAxeronApplication;
import frb.axeron.server.IAxshProcess;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuApplication;
import rikka.parcelablelist.ParcelableListSlice;
parcelable ServerInfo;
parcelable PluginInfo;
parcelable Environment;

interface IAxeronService {
    IFileService getFileService() = 2;
    ServerInfo getServerInfo() = 4;
    ParcelableListSlice<PackageInfo> getPackages(int flags) = 6;
    ParcelableListSlice<PluginInfo> getPlugins() = 7;
    PluginInfo getPluginById(in String id) = 8;
    boolean isFirstInit(boolean markAsFirstInit) = 9;
    IShizukuService getShizukuService() = 10;
    void enableShizukuService(boolean enable) = 11;
    Environment getEnvironment(int envType) = 12;
    void setNewEnvironment(in Environment env) = 13;
    // 15-18 were get/setSystemProperty and add/removeUserService, 23 newProcess, 102
    // dispatchPackageChanged: Shizuku's (IShizukuService) alone, served through ShizukuIntercept.
    void requestPermission(int requestCode) = 19;
    boolean checkSelfPermission() = 20;
    boolean shouldShowRequestPermissionRationale() = 21;
    void attachApplication(in IShizukuApplication application,in Bundle args) = 22;
    void attachUserService(in IBinder binder, in Bundle options) = 101;
    oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data) = 104;
    int getFlagsForUid(int uid, int mask) = 105;
    void updateFlagsForUid(int uid, int mask, int value) = 106;
    int checkPermission(String permission) = 107;
    void setPreferSystemBinaries(boolean prefer) = 108;
    int[] getFlagsForUids(in int[] uids, int mask) = 109;
    void setWakeLock(boolean enabled) = 110;
    boolean isWakeLockHeld() = 111;
    // Runs cmd (full argv) on the caller's own fds: stdin is read, stdout and stderr are written.
    // Without pty they are the process's stdio as they are, so nothing passes through the server.
    // With pty the process gets a fresh PTY as its controlling terminal and the server copies
    // between it and stdin/stdout; stderr may be null then. size: see
    // IAxshProcess.setWindowSize, 0 to keep the default.
    IAxshProcess newAxshProcess(in String[] cmd, in String[] env, in String dir,
            in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout,
            in ParcelFileDescriptor stderr, boolean pty, long size) = 112;
    // One end of a unix stream socket into the server's axsh sessions, for a helper of the
    // caller's uid (axsh's native daemon). Each byte sent on it carries one connected axsh
    // client socket (SCM_RIGHTS), which the server then serves itself, as on its own socket;
    // only clients of the caller's uid are taken. Closed when the server stops.
    ParcelFileDescriptor openAxshChannel() = 113;
    // 114 was closeAxshChannels: a change of the axsh keep-alive flags (updateFlagsForUid) does it.
    void exit() = 16777114;
}