#pragma once

#include <cstddef>
#include <cstdint>

namespace axsh {

// Connects to the AxManager server and checks it runs as root or shell.
// Returns a connected fd, or -1 on failure.
int connect_server();

// Connects to this app's own AxBroker (@axsh.<uid>) and checks it runs as our uid.
// Returns a connected fd, or -1 on failure.
int connect_broker();

// Listens on this app's broker socket (@axsh.<uid>). -1 if it can't, e.g. a broker is there.
int listen_broker();

// Starts Bootstrap in app_process from the APK this binary was installed with, connected to
// us by a socketpair, and returns our end. It speaks the same protocol. -1 on failure, e.g.
// when this binary was copied out of an APK's lib directory.
int spawn_bootstrap();

// `--axsh-daemon`: see DAEMON_FLAG. Returns the exit code.
int run_daemon(const char* minutes);

// Writes all of data, with fds[0..nfds) attached to its first byte as SCM_RIGHTS.
bool write_with_fds(int fd, const void* data, size_t len, const int* fds, size_t nfds);

// Full-write / full-read helpers. Return false on EOF or error.
bool write_all(int fd, const void* data, size_t len);
bool read_all(int fd, void* data, size_t len);

// Convenience: a length-prefixed little-endian uint32.
bool write_u32(int fd, uint32_t value);

}  // namespace axsh
