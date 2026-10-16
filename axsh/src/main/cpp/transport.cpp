#include "transport.h"
#include "axsh_protocol.h"

#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace axsh {

namespace {

int connect_abstract(const char* name) {
    const size_t name_len = std::strlen(name);
    sockaddr_un addr{};
    if (name_len + 1 > sizeof(addr.sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }

    int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    addr.sun_family = AF_UNIX;
    // Abstract namespace: leading NUL, then the name. No filesystem entry, no label to create.
    addr.sun_path[0] = '\0';
    std::memcpy(addr.sun_path + 1, name, name_len);

    const socklen_t len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_len);
    if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), len) != 0) {
        const int err = errno;
        ::close(fd);
        errno = err;
        return -1;
    }
    return fd;
}

}  // namespace

int connect_server() {
    return connect_abstract(SOCKET_NAME);
}

int connect_broker() {
    char name[32];
    std::snprintf(name, sizeof(name), "%s%u", BROKER_PREFIX, static_cast<unsigned>(::getuid()));

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

namespace {

// /data/app/<...>/lib/<abi>/libaxsh.so -> /data/app/<...>/base.apk
bool find_own_apk(char* out, size_t size) {
    char exe[PATH_MAX];
    const ssize_t n = ::readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) return false;
    exe[n] = '\0';

    char* lib = nullptr;
    for (char* p = std::strstr(exe, "/lib/"); p != nullptr; p = std::strstr(p + 1, "/lib/")) lib = p;
    if (lib == nullptr) return false;
    *lib = '\0';

    if (std::snprintf(out, size, "%s/base.apk", exe) >= static_cast<int>(size)) return false;
    return ::access(out, R_OK) == 0;
}

}  // namespace

int spawn_bootstrap() {
    char apk[PATH_MAX];
    if (!find_own_apk(apk, sizeof(apk))) {
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
        std::snprintf(classpath, sizeof(classpath), "-Djava.class.path=%s", apk);
        char fd_arg[16];
        std::snprintf(fd_arg, sizeof(fd_arg), "%d", child_fd);

        char* const argv[] = {
                const_cast<char*>("/system/bin/app_process"),
                classpath,
                const_cast<char*>("/system/bin"),
                const_cast<char*>("--nice-name=axsh_bootstrap"),
                const_cast<char*>(BOOTSTRAP_CLASS),
                fd_arg,
                nullptr,
        };
        ::execv(argv[0], argv);
        std::fprintf(stderr, "axsh: can't exec app_process (%s)\n", std::strerror(errno));
        ::_exit(127);
    }

    ::close(sv[1]);
    return sv[0];
}

bool recv_status(int fd, uint8_t* status, int* fds, size_t nfds) {
    for (size_t i = 0; i < nfds; ++i) fds[i] = -1;

    iovec iov{status, 1};
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * BROKER_FDS)];
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);

    ssize_t n;
    do {
        n = ::recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
    } while (n < 0 && errno == EINTR);
    if (n != 1) return false;

    for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c != nullptr; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        const size_t count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        const int* received = reinterpret_cast<const int*>(CMSG_DATA(c));
        for (size_t i = 0; i < count; ++i) {
            if (i < nfds && fds[i] < 0) {
                fds[i] = received[i];
            } else {
                ::close(received[i]);
            }
        }
    }
    return true;
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
