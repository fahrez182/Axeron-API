// Wire protocol between the native `axsh` client and the AxManager server.
//
// Handshake (client -> server), little-endian:
//   char   magic[4]   "AXSH"
//   uint8  version
//   uint8  flags       bit0 = PTY (interactive), bit1 = login shell
//   uint32 argc        followed by argc × (uint32 len, len bytes)
//   uint32 envc        followed by envc × (uint32 len, len bytes)
//
// Reply (server -> client):
//   uint8  status      0 = OK, otherwise the connection closes after a message
//   if status != OK: uint32 msglen, msglen bytes
//
// Stream (both directions, 1:1 with the remote shell's stdio):
//   uint8  type
//   FRAME_DATA : uint32 len, len bytes
//   FRAME_EXIT : int32  exit code
//   FRAME_WINCH: uint16 rows, uint16 cols
//
// Broker (fallback when SELinux won't let an app reach the server's socket, i.e. ADB mode):
// the client connects to @axsh.<uid>, served by AxshBroker inside the app itself, and sends the
// same handshake. The reply is the same status byte, but with STATUS_OK it carries the remote
// shell's stdin, stdout and stderr pipes as SCM_RIGHTS, in that order. The client then talks to
// the pipes directly; the socket only delivers one FRAME_EXIT once the shell is gone. Closing
// the socket early makes the broker kill the remote shell.
#pragma once

#include <cstdint>

namespace axsh {

// Name in the abstract (Linux) namespace, so no filesystem path or selinux label is involved
// in discovery. The server owns the listener.
inline constexpr char SOCKET_NAME[] = "axeron";

// Broker socket: BROKER_PREFIX + decimal uid, also abstract. Must match AxshBroker.socketName().
inline constexpr char BROKER_PREFIX[] = "axsh.";
inline constexpr int BROKER_FDS = 3;

// Without a broker, the client runs this class from its own APK in app_process and talks the
// broker protocol to it over a socketpair. Must match frb.axeron.axsh.AxshBootstrap.
inline constexpr char BOOTSTRAP_CLASS[] = "frb.axeron.axsh.AxshBootstrap";

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
    FRAME_DATA = 0x01,
    FRAME_EXIT = 0x02,
    FRAME_WINCH = 0x03,
};

}  // namespace axsh
