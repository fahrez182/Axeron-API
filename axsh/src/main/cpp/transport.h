#pragma once

#include <cstddef>
#include <cstdint>

namespace axsh {

// Connects to the AxManager server. Returns a connected fd, or -1 on failure.
int connect_server();

// Connects to this app's own AxshBroker (@axsh.<uid>) and checks it runs as our uid.
// Returns a connected fd, or -1 on failure.
int connect_broker();

// Starts AxshBootstrap in app_process from the APK this binary was installed with, connected to
// us by a socketpair, and returns our end. It speaks the broker protocol. -1 on failure, e.g.
// when this binary was copied out of an APK's lib directory.
int spawn_bootstrap();

// Reads the one-byte reply status together with any fds sent alongside it (SCM_RIGHTS).
// fds[0..nfds) are set to -1 where nothing arrived. Returns false on EOF or error.
bool recv_status(int fd, uint8_t* status, int* fds, size_t nfds);

// Full-write / full-read helpers. Return false on EOF or error.
bool write_all(int fd, const void* data, size_t len);
bool read_all(int fd, void* data, size_t len);

// Convenience: a length-prefixed little-endian uint32.
bool write_u32(int fd, uint32_t value);

}  // namespace axsh
