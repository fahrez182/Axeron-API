#include "axsh_protocol.h"
#include "transport.h"

#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <termios.h>
#include <unistd.h>

extern char** environ;

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

// Our stdin goes into the remote shell's stdin pipe; closing it is the shell's EOF.
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

bool is_pty_env(const char* entry) {
    const char* eq = std::strchr(entry, '=');
    if (eq == nullptr) return false;
    const size_t name_len = static_cast<size_t>(eq - entry);
    for (const char* name : PTY_ENV) {
        const size_t len = std::strlen(name);
        const bool prefix = name[len - 1] == '_';  // "LC_"
        if (prefix ? (name_len > len && std::strncmp(entry, name, len) == 0)
                   : (name_len == len && std::strncmp(entry, name, len) == 0)) {
            return true;
        }
    }
    return false;
}

void window_size(uint16_t* rows, uint16_t* cols) {
    winsize ws{};
    if (::ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) != 0 && ::ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) != 0) {
        ws.ws_row = 0;
        ws.ws_col = 0;
    }
    *rows = ws.ws_row;
    *cols = ws.ws_col;
}

// remote: the pipe ends the server gets, PIPE_FDS or PTY_FDS of them (see axsh_protocol.h).
bool send_handshake(int fd, char** args, int count, bool pty, const int* remote) {
    uint8_t head[6];
    std::memcpy(head, MAGIC, 4);
    head[4] = VERSION;
    head[5] = pty ? FLAG_PTY : 0;
    if (!write_with_fds(fd, head, sizeof(head), remote, pty ? PTY_FDS : PIPE_FDS)) return false;

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

    // The server supplies the Axeron environment; a terminal only adds what describes it.
    if (!pty) return write_u32(fd, 0);

    uint32_t envc = 0;
    for (char** e = environ; *e != nullptr; ++e) {
        if (is_pty_env(*e)) ++envc;
    }
    if (!write_u32(fd, envc)) return false;
    for (char** e = environ; *e != nullptr; ++e) {
        if (is_pty_env(*e) && !write_arg(*e)) return false;
    }

    uint16_t size[2];
    window_size(&size[0], &size[1]);
    return write_all(fd, size, sizeof(size));
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

// Reads the closing FRAME_EXIT, if any. Anything else ends the session with code 1.
int read_exit(int fd) {
    uint8_t type;
    int32_t code;
    if (read_all(fd, &type, 1) && type == FRAME_EXIT && read_all(fd, &code, 4)) return code;
    return 1;
}

// ---- without a PTY: the pipes are the shell's own stdio ----

// in: we write the shell's stdin. out, err: we read its stdout and stderr.
int relay_pipes(int fd, int in, int out, int err) {
    if (!start_detached(stdin_to_pipe, in)) {
        std::fprintf(stderr, "axsh: can't start stdin relay\n");
        ::close(in);
        ::close(out);
        ::close(err);
        return 1;
    }

    pollfd polls[2] = {{out, POLLIN, 0}, {err, POLLIN, 0}};
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

// ---- PTY: our terminal goes raw, the server's PTY does the line discipline ----

termios saved_termios{};
bool termios_saved = false;

void restore_terminal() {
    if (termios_saved) {
        ::tcsetattr(STDIN_FILENO, TCSAFLUSH, &saved_termios);
        termios_saved = false;
    }
}

void restore_and_die(int sig) {
    restore_terminal();
    std::signal(sig, SIG_DFL);
    ::raise(sig);
}

// Raw input: every key, ^C included, goes to the remote PTY, which handles echo and signals.
bool make_terminal_raw() {
    if (::tcgetattr(STDIN_FILENO, &saved_termios) != 0) return false;
    termios raw = saved_termios;
    ::cfmakeraw(&raw);
    if (::tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw) != 0) return false;
    termios_saved = true;
    std::atexit(restore_terminal);
    const int fatal[] = {SIGHUP, SIGTERM, SIGQUIT};
    for (int sig : fatal) std::signal(sig, restore_and_die);
    return true;
}

// SIGWINCH only pokes this pipe; the relay loop reads the new size and sends it on.
int winch_pipe[2] = {-1, -1};

void on_winch(int) {
    const int saved = errno;
    const char c = 0;
    (void) ::write(winch_pipe[1], &c, 1);
    errno = saved;
}

void watch_window() {
    if (winch_pipe[0] >= 0) return;  // a session tried before this one set it up
    if (::pipe2(winch_pipe, O_CLOEXEC | O_NONBLOCK) != 0) return;
    struct sigaction sa{};
    sa.sa_handler = on_winch;
    sa.sa_flags = SA_RESTART;
    ::sigaction(SIGWINCH, &sa, nullptr);
}

bool send_winch(int fd) {
    uint8_t frame[5];
    uint16_t size[2];
    window_size(&size[0], &size[1]);
    frame[0] = FRAME_WINCH;
    std::memcpy(frame + 1, size, sizeof(size));
    return write_all(fd, frame, sizeof(frame));
}

// in: we write the terminal's input there. out: we read its output.
int relay_pty(int fd, int in, int out) {
    if (!start_detached(stdin_to_pipe, in)) {
        std::fprintf(stderr, "axsh: can't start stdin relay\n");
        ::close(in);
        ::close(out);
        return 1;
    }

    pollfd polls[2] = {{out, POLLIN, 0}, {winch_pipe[0], POLLIN, 0}};
    char buf[8192];
    for (;;) {
        if (::poll(polls, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (polls[1].revents & POLLIN) {
            char drain[16];
            (void) ::read(winch_pipe[0], drain, sizeof(drain));
            send_winch(fd);
        }
        if (polls[0].revents == 0) continue;
        ssize_t n = ::read(out, buf, sizeof(buf));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;  // the shell is gone and the server closed its end
        write_all(STDOUT_FILENO, buf, static_cast<size_t>(n));
    }
    ::close(out);
    return read_exit(fd);
}

// ---- one session, whoever serves it ----

void close_pipes(int (*pipes)[2], int n) {
    for (int i = 0; i < n; ++i) {
        if (pipes[i][0] >= 0) ::close(pipes[i][0]);
        if (pipes[i][1] >= 0) ::close(pipes[i][1]);
    }
}

// run_session's result when the other end hung up before any reply: nobody took the session.
// Nothing of ours was read yet, so main tries the next way in, as when it couldn't connect.
constexpr int HUNG_UP = -1;

int run_session(int fd, char** args, int count, bool pty) {
    // [0] stdin: the server reads [0], we write [1]. [1] stdout, [2] stderr: the server writes
    // [1], we read [0]. A PTY merges stderr into stdout.
    int pipes[PIPE_FDS][2] = {{-1, -1}, {-1, -1}, {-1, -1}};
    const int n = pty ? PTY_FDS : PIPE_FDS;
    for (int i = 0; i < n; ++i) {
        if (::pipe2(pipes[i], O_CLOEXEC) != 0) {
            close_pipes(pipes, n);
            return 126;
        }
    }
    if (pty) watch_window();

    const int remote[PIPE_FDS] = {pipes[0][0], pipes[1][1], pipes[2][1]};
    const bool sent = send_handshake(fd, args, count, pty, remote);
    // The server has its own copies of these ends now, and ours would hide its EOF.
    for (int i = 0; i < n; ++i) {
        ::close(remote[i]);
    }
    pipes[0][0] = pipes[1][1] = pipes[2][1] = -1;

    uint8_t status;
    if (!sent || !read_all(fd, &status, 1)) {
        close_pipes(pipes, n);
        return HUNG_UP;
    }
    if (status != STATUS_OK) {
        report_failure(fd, status);
        close_pipes(pipes, n);
        return 126;
    }

    if (!pty) return relay_pipes(fd, pipes[0][1], pipes[1][0], pipes[2][0]);

    // Raw only once the session is up, so errors above print on a sane terminal.
    make_terminal_raw();
    const int code = relay_pty(fd, pipes[0][1], pipes[1][0]);
    restore_terminal();
    return code;
}

}  // namespace

int main(int argc, char** argv) {
    std::signal(SIGPIPE, SIG_IGN);

    if (argc >= 2 && std::strcmp(argv[1], DAEMON_FLAG) == 0) return run_daemon(argc >= 3 ? argv[2] : nullptr);

    // An interactive terminal gets a PTY on the server; anything else gets plain pipes.
    const bool pty = ::isatty(STDIN_FILENO) && ::isatty(STDOUT_FILENO);

    // Server socket first, under the name left for us (works whenever SELinux allows it, e.g.
    // root mode); otherwise the app's own broker, which reaches the server over binder;
    // otherwise a one-shot broker in app_process from our own APK, so apps that only exec this
    // binary (Termux) work too. One that hangs up without a word counts as not there.
    int (*const ways[])() = {connect_server, connect_broker, spawn_bootstrap};
    int server_err = 0;
    bool answered_nothing = false;
    for (int i = 0; i < 3; ++i) {
        const int fd = ways[i]();
        if (fd < 0) {
            if (i == 0) server_err = errno;
            continue;
        }
        const int code = run_session(fd, argv + 1, argc - 1, pty);
        ::close(fd);
        if (code != HUNG_UP) return code;
        answered_nothing = true;
    }
    if (answered_nothing) {
        std::fprintf(stderr, "axsh: no reply from AxManager\n");
        return 126;
    }
    std::fprintf(stderr, "axsh: AxManager server is not reachable (%s), and there is no broker to fall back on (%s)\n",
                 std::strerror(server_err), std::strerror(errno));
    return 127;
}
