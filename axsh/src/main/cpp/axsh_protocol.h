// Wire protocol between the native `axsh` client and whoever serves it: the server's own socket
// (@axeron, AxshServer), an app's Broker (@axsh.<uid>), or Bootstrap over a socketpair.
// All three run the same code, frb.axeron.shell.Sessions.
//
// The client always brings its own pipes, and the shell is started on them
// (IAxeronService.newAxshProcess): without a PTY they are the shell's stdio as they are, so no
// byte of its input or output goes through the socket, the broker or the server.
//
// Handshake (client -> server), little-endian:
//   char   magic[4]   "AXSH"; its first byte carries the client's pipes as SCM_RIGHTS
//   uint8  version
//   uint8  flags       bit0 = PTY (interactive), bit1 = login shell
//   uint32 argc        followed by argc × (uint32 len, len bytes)
//   uint32 envc        followed by envc × (uint32 len, len bytes)
//   if PTY: uint16 rows, uint16 cols   initial window size, 0 0 if unknown
//
// Pipes, in order:
//   without PTY (PIPE_FDS): read end of the shell's stdin, write ends of its stdout and stderr
//   with PTY (PTY_FDS):     read end of the terminal's input, write end of its output; the server
//                           puts the shell on a fresh PTY and copies between it and these, and
//                           stderr goes to the PTY too
// The client keeps the other ends.
//
// Env: without a PTY it is ignored, the shell gets the Axeron environment. With one, entries
// named in PTY_ENV are laid over it; all others are dropped.
//
// Reply (server -> client):
//   uint8  status      0 = OK, otherwise the connection closes after a message
//   if status != OK: uint32 msglen, msglen bytes
//
// Then (after STATUS_OK):
//   client -> server: FRAME_WINCH (uint16 rows, uint16 cols) whenever the window changes
//   server -> client: one FRAME_EXIT (int32 exit code) once the shell is gone
// Closing the socket before FRAME_EXIT makes the server kill the shell.
#pragma once

#include <cstdint>

namespace axsh {

// Name in the abstract (Linux) namespace, so no filesystem path or selinux label is involved
// in discovery. The server owns the listener.
inline constexpr char SOCKET_NAME[] = "axeron";

// Broker socket: BROKER_PREFIX + decimal uid, also abstract. Must match Broker.socketName().
inline constexpr char BROKER_PREFIX[] = "axsh.";

// Without a broker, the client runs this class from its own APK in app_process and talks the
// same protocol to it over a socketpair. Must match frb.axeron.shell.Bootstrap.
inline constexpr char BOOTSTRAP_CLASS[] = "frb.axeron.shell.Bootstrap";

// `libaxsh.so --axsh-daemon <minutes>`: the app's broker without a JVM, started by the
// bootstrap with a channel into the server (IAxeronService.openAxshChannel) as its stdin. It
// listens on the broker socket and passes each client's socket down the channel, one byte with
// SCM_RIGHTS each; the server serves the session. Must match Bootstrap.DAEMON_FLAG.
inline constexpr char DAEMON_FLAG[] = "--axsh-daemon";

inline constexpr int PIPE_FDS = 3;
inline constexpr int PTY_FDS = 2;

// What the terminal says about itself; the rest of the client env stays behind (Termux's PATH
// and LD_PRELOAD point where the server can't go). LC_* is matched as a prefix.
inline constexpr const char* PTY_ENV[] = {"TERM", "COLORTERM", "LANG", "LC_"};

inline constexpr char MAGIC[4] = {'A', 'X', 'S', 'H'};
inline constexpr uint8_t VERSION = 1;

enum Flags : uint8_t {
    FLAG_PTY = 1u << 0,
    FLAG_LOGIN = 1u << 1,
};

enum Status : uint8_t {
    STATUS_OK = 0,
    STATUS_DENIED = 1,
    STATUS_ERROR = 2,
};

enum Frame : uint8_t {
    FRAME_EXIT = 0x02,
    FRAME_WINCH = 0x03,
};

}  // namespace axsh
