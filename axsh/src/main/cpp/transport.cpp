#include "transport.h"
#include "axsh_protocol.h"

#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace axsh {

namespace {

// Abstract namespace: leading NUL, then the name. No filesystem entry, no label to create.
bool abstract_address(const char* name, sockaddr_un* addr, socklen_t* len) {
    const size_t name_len = std::strlen(name);
    if (name_len + 1 > sizeof(addr->sun_path)) {
        errno = ENAMETOOLONG;
        return false;
    }
    *addr = {};
    addr->sun_family = AF_UNIX;
    std::memcpy(addr->sun_path + 1, name, name_len);
    *len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_len);
    return true;
}

int connect_abstract(const char* name) {
    sockaddr_un addr;
    socklen_t len;
    if (!abstract_address(name, &addr, &len)) return -1;

    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), len) != 0) {
        const int err = errno;
        ::close(fd);
        errno = err;
        return -1;
    }
    return fd;
}

void broker_name(char* out, size_t size) {
    std::snprintf(out, size, "%s%u", BROKER_PREFIX, static_cast<unsigned>(::getuid()));
}

}  // namespace

int connect_server() {
    return connect_abstract(SOCKET_NAME);
}

int connect_broker() {
    char name[32];
    broker_name(name, sizeof(name));

    const int fd = connect_abstract(name);
    if (fd < 0) return -1;

    // Abstract names are first come, first served: make sure we hand our stdio to our own app.
    ucred cred{};
    socklen_t cred_len = sizeof(cred);
    if (::getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &cred_len) != 0 || cred.uid != ::getuid()) {
        ::close(fd);
        errno = EPERM;
        return -1;
    }
    return fd;
}

int listen_broker() {
    char name[32];
    broker_name(name, sizeof(name));
    sockaddr_un addr;
    socklen_t len;
    if (!abstract_address(name, &addr, &len)) return -1;

    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (::bind(fd, reinterpret_cast<sockaddr*>(&addr), len) != 0 || ::listen(fd, 16) != 0) {
        const int err = errno;
        ::close(fd);
        errno = err;
        return -1;
    }
    return fd;
}

namespace {

bool own_exe(char* out, size_t size) {
    const ssize_t n = ::readlink("/proc/self/exe", out, size - 1);
    if (n <= 0) return false;
    out[n] = '\0';
    return true;
}

// /data/app/<...>/lib/<abi>/libaxsh.so -> /data/app/<...>/base.apk
bool find_own_apk(char* out, size_t size) {
    char exe[PATH_MAX];
    if (!own_exe(exe, sizeof(exe))) return false;

    char* lib = nullptr;
    for (char* p = std::strstr(exe, "/lib/"); p != nullptr; p = std::strstr(p + 1, "/lib/")) lib = p;
    if (lib == nullptr) return false;
    *lib = '\0';

    if (std::snprintf(out, size, "%s/base.apk", exe) >= static_cast<int>(size)) return false;
    return ::access(out, R_OK) == 0;
}

}  // namespace

// libaxshboot.so next to us: the bootstrap's own dex, a small fraction of the APK to start with.
bool find_boot_dex(const char* exe, char* out, size_t size) {
    const char* slash = std::strrchr(exe, '/');
    if (slash == nullptr) return false;
    const int dir_len = static_cast<int>(slash - exe);
    if (std::snprintf(out, size, "%.*s/libaxshboot.so", dir_len, exe) >= static_cast<int>(size)) return false;
    return ::access(out, R_OK) == 0;
}

int spawn_bootstrap() {
    // Ourselves, so the bootstrap can start us again as the daemon.
    char exe[PATH_MAX];
    // What app_process loads AxshBootstrap from: our own dex, else (older APKs) the whole APK.
    char classpath_file[PATH_MAX];
    if (!own_exe(exe, sizeof(exe)) ||
        (!find_boot_dex(exe, classpath_file, sizeof(classpath_file)) &&
         !find_own_apk(classpath_file, sizeof(classpath_file)))) {
        errno = ENOENT;
        return -1;
    }

    int sv[2];
    if (::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) != 0) return -1;

    const pid_t pid = ::fork();
    if (pid < 0) {
        const int err = errno;
        ::close(sv[0]);
        ::close(sv[1]);
        errno = err;
        return -1;
    }

    if (pid == 0) {
        // Its own session: a bootstrap the user keeps alive outlives this terminal, which would
        // otherwise hang it up along with the rest of its process group.
        ::setsid();

        // dup() drops CLOEXEC, so only this end survives the exec.
        const int child_fd = ::dup(sv[1]);
        if (child_fd < 0) ::_exit(127);

        // Our stdin belongs to the remote shell; the bootstrap only reports errors on stderr.
        const int null_fd = ::open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd >= 0) {
            ::dup2(null_fd, STDIN_FILENO);
            ::dup2(null_fd, STDOUT_FILENO);
        }

        char classpath[PATH_MAX + 32];
        std::snprintf(classpath, sizeof(classpath), "-Djava.class.path=%s", classpath_file);
        char fd_arg[16];
        std::snprintf(fd_arg, sizeof(fd_arg), "%d", child_fd);
        // When we started it (SystemClock.uptimeMillis's clock), so it can log its JVM startup.
        timespec now{};
        ::clock_gettime(CLOCK_MONOTONIC, &now);
        char spawned_at[24];
        std::snprintf(spawned_at, sizeof(spawned_at), "%lld",
                      static_cast<long long>(now.tv_sec) * 1000 + now.tv_nsec / 1'000'000);

        char* const argv[] = {
                const_cast<char*>("/system/bin/app_process"),
                classpath,
                const_cast<char*>("/system/bin"),
                const_cast<char*>("--nice-name=axsh_bootstrap"),
                const_cast<char*>(BOOTSTRAP_CLASS),
                fd_arg,
                exe,
                spawned_at,
                nullptr,
        };
        ::execv(argv[0], argv);
        std::fprintf(stderr, "axsh: can't exec app_process (%s)\n", std::strerror(errno));
        ::_exit(127);
    }

    ::close(sv[1]);
    return sv[0];
}

bool write_with_fds(int fd, const void* data, size_t len, const int* fds, size_t nfds) {
    if (len == 0) return false;
    if (nfds == 0) return write_all(fd, data, len);
    if (nfds > static_cast<size_t>(PIPE_FDS)) {
        errno = EINVAL;
        return false;
    }

    // One byte with the fds, then the rest as plain data.
    iovec iov{const_cast<void*>(data), 1};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * PIPE_FDS)]{};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);

    cmsghdr* c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET;
    c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
    std::memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);

    ssize_t n;
    do {
        n = ::sendmsg(fd, &msg, MSG_NOSIGNAL);
    } while (n < 0 && errno == EINTR);
    if (n != 1) return false;

    return write_all(fd, static_cast<const uint8_t*>(data) + 1, len - 1);
}

bool write_all(int fd, const void* data, size_t len) {
    const auto* p = static_cast<const uint8_t*>(data);
    while (len > 0) {
        ssize_t n = ::write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

bool read_all(int fd, void* data, size_t len) {
    auto* p = static_cast<uint8_t*>(data);
    while (len > 0) {
        ssize_t n = ::read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

bool write_u32(int fd, uint32_t value) {
    return write_all(fd, &value, sizeof(value));
}

}  // namespace axsh
