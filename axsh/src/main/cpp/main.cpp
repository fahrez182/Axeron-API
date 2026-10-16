#include "axsh_protocol.h"
#include "transport.h"

#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

using namespace axsh;

namespace {

// Remote shell that runs inside the AxManager server (shell or root, whichever it started as).
constexpr const char* REMOTE_SHELL = "/system/bin/sh";

void* fd_arg(int fd) {
    return reinterpret_cast<void*>(static_cast<intptr_t>(fd));
}

int arg_fd(void* raw) {
    return static_cast<int>(reinterpret_cast<intptr_t>(raw));
}

// Starts a detached thread: the stdin reader may stay blocked on read(0) after the remote shell
// is gone, and must not hold up our exit.
bool start_detached(void* (*fn)(void*), int fd) {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, fn, fd_arg(fd)) != 0) return false;
    pthread_detach(thread);
    return true;
}

// Direct mode: our stdin goes to the server as FRAME_DATA.
void* stdin_to_frames(void* raw) {
    const int fd = arg_fd(raw);
    char buf[8192];
    for (;;) {
        ssize_t n = ::read(STDIN_FILENO, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;

        uint8_t hdr[5];
        hdr[0] = FRAME_DATA;
        const uint32_t len = static_cast<uint32_t>(n);
        std::memcpy(hdr + 1, &len, 4);
        if (!write_all(fd, hdr, sizeof(hdr)) || !write_all(fd, buf, static_cast<size_t>(n))) {
            break;
        }
    }
    // Half-close so the server sees EOF on the remote shell's stdin.
    ::shutdown(fd, SHUT_WR);
    return nullptr;
}

// Broker mode: our stdin goes straight into the remote shell's stdin pipe.
void* stdin_to_pipe(void* raw) {
    const int pipe = arg_fd(raw);
    char buf[8192];
    for (;;) {
        ssize_t n = ::read(STDIN_FILENO, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        if (!write_all(pipe, buf, static_cast<size_t>(n))) break;
    }
    ::close(pipe);
    return nullptr;
}

bool send_handshake(int fd, char** args, int count) {
    uint8_t head[6];
    std::memcpy(head, MAGIC, 4);
    head[4] = VERSION;
    head[5] = (::isatty(STDIN_FILENO) || ::isatty(STDOUT_FILENO)) ? FLAG_PTY : 0;
    if (!write_all(fd, head, sizeof(head))) return false;

    // Remote argv is `/system/bin/sh` plus the caller's args, i.e. plain sh semantics:
    //   axsh            -> sh (interactive)
    //   axsh -c CMD     -> sh -c CMD
    //   axsh script.sh  -> sh script.sh
    if (!write_u32(fd, static_cast<uint32_t>(count) + 1)) return false;

    const auto write_arg = [fd](const char* s) {
        const uint32_t len = static_cast<uint32_t>(std::strlen(s));
        return write_u32(fd, len) && (len == 0 || write_all(fd, s, len));
    };

    if (!write_arg(REMOTE_SHELL)) return false;
    for (int i = 0; i < count; ++i) {
        if (!write_arg(args[i])) return false;
    }

    // No env yet: the server supplies the Axeron environment for the remote shell.
    return write_u32(fd, 0);
}

// Prints the message that follows a non-OK status.
void report_failure(int fd, uint8_t status) {
    uint32_t len = 0;
    if (read_all(fd, &len, 4) && len > 0 && len < (1u << 20)) {
        char* msg = static_cast<char*>(std::malloc(len + 1));
        if (msg != nullptr) {
            if (read_all(fd, msg, len)) {
                msg[len] = '\0';
                std::fprintf(stderr, "axsh: %s\n", msg);
            }
            std::free(msg);
        }
    } else {
        std::fprintf(stderr, "axsh: request denied (status %u)\n", status);
    }
}

bool read_reply(int fd) {
    uint8_t status;
    if (!read_all(fd, &status, 1)) {
        std::fprintf(stderr, "axsh: no reply from server\n");
        return false;
    }
    if (status == STATUS_OK) return true;
    report_failure(fd, status);
    return false;
}

// Reads the closing FRAME_EXIT, if any. Anything else ends the session with code 1.
int read_exit(int fd) {
    uint8_t type;
    int32_t code;
    if (read_all(fd, &type, 1) && type == FRAME_EXIT && read_all(fd, &code, 4)) return code;
    return 1;
}

int relay_frames(int fd) {
    if (!start_detached(stdin_to_frames, fd)) {
        std::fprintf(stderr, "axsh: can't start stdin relay\n");
        return 1;
    }

    int code = 0;
    char buf[8192];
    for (;;) {
        uint8_t type;
        if (!read_all(fd, &type, 1)) break;

        if (type == FRAME_DATA) {
            uint32_t len;
            if (!read_all(fd, &len, 4)) break;
            uint32_t remaining = len;
            while (remaining > 0) {
                const size_t chunk = remaining < sizeof(buf) ? remaining : sizeof(buf);
                if (!read_all(fd, buf, chunk)) { remaining = 0; break; }
                if (!write_all(STDOUT_FILENO, buf, chunk)) { remaining = 0; break; }
                remaining -= static_cast<uint32_t>(chunk);
            }
        } else if (type == FRAME_EXIT) {
            int32_t c;
            if (read_all(fd, &c, 4)) code = c;
            break;
        } else if (type == FRAME_WINCH) {
            uint16_t dims[2];
            if (!read_all(fd, dims, sizeof(dims))) break;
        } else {
            break;
        }
    }
    return code;
}

// pipes = remote stdin, stdout, stderr. The socket stays open only to learn the exit code.
int relay_pipes(int fd, const int pipes[BROKER_FDS]) {
    if (!start_detached(stdin_to_pipe, pipes[0])) {
        std::fprintf(stderr, "axsh: can't start stdin relay\n");
        ::close(pipes[0]);
        ::close(pipes[1]);
        ::close(pipes[2]);
        return 1;
    }

    pollfd polls[2] = {{pipes[1], POLLIN, 0}, {pipes[2], POLLIN, 0}};
    const int dest[2] = {STDOUT_FILENO, STDERR_FILENO};
    int open = 2;
    char buf[8192];
    while (open > 0) {
        if (::poll(polls, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        for (int i = 0; i < 2; ++i) {
            if (polls[i].fd < 0 || polls[i].revents == 0) continue;
            ssize_t n = ::read(polls[i].fd, buf, sizeof(buf));
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) {
                ::close(polls[i].fd);
                polls[i].fd = -1;  // poll() ignores negative fds
                --open;
                continue;
            }
            // Keep draining even if our side is gone, so the remote shell isn't stuck on a full pipe.
            write_all(dest[i], buf, static_cast<size_t>(n));
        }
    }
    return read_exit(fd);
}

int run_direct(int fd, char** args, int count) {
    if (!send_handshake(fd, args, count) || !read_reply(fd)) return 126;
    return relay_frames(fd);
}

int run_broker(int fd, char** args, int count) {
    if (!send_handshake(fd, args, count)) return 126;

    uint8_t status;
    int pipes[BROKER_FDS];
    if (!recv_status(fd, &status, pipes, BROKER_FDS)) {
        std::fprintf(stderr, "axsh: no reply from broker\n");
        return 126;
    }
    if (status != STATUS_OK) {
        report_failure(fd, status);
        return 126;
    }
    if (pipes[0] < 0 || pipes[1] < 0 || pipes[2] < 0) {
        std::fprintf(stderr, "axsh: broker sent no pipes\n");
        for (int p : pipes) if (p >= 0) ::close(p);
        return 126;
    }
    return relay_pipes(fd, pipes);
}

}  // namespace

int main(int argc, char** argv) {
    std::signal(SIGPIPE, SIG_IGN);

    // Server socket first (works whenever SELinux allows it, e.g. root mode); otherwise the
    // app's own broker, which reaches the server over binder; otherwise a one-shot broker in
    // app_process from our own APK, so apps that only exec this binary (Termux) work too.
    int fd = connect_server();
    if (fd >= 0) {
        const int code = run_direct(fd, argv + 1, argc - 1);
        ::close(fd);
        return code;
    }
    const int server_err = errno;

    fd = connect_broker();
    if (fd < 0) fd = spawn_bootstrap();
    if (fd < 0) {
        std::fprintf(stderr, "axsh: AxManager server is not reachable (%s), and there is no broker to fall back on (%s)\n",
                     std::strerror(server_err), std::strerror(errno));
        return 127;
    }
    const int code = run_broker(fd, argv + 1, argc - 1);
    ::close(fd);
    return code;
}
