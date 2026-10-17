#include "transport.h"
#include "axsh_protocol.h"

#include <android/log.h>
#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AxshDaemon", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "AxshDaemon", __VA_ARGS__)

namespace axsh {

namespace {

// How often an idle daemon checks whether to leave.
constexpr int CHECK_MS = 30'000;

long long now_ms() {
    timespec ts{};
    ::clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000 + ts.tv_nsec / 1'000'000;
}

}  // namespace

// The channel is our stdin; stdout and stderr are /dev/null (see AxshBootstrap.startDaemon).
// Idle means no client passed on for that long: a session runs on in the server either way.
int run_daemon(const char* minutes_arg) {
    const int channel = STDIN_FILENO;
    int type = 0;
    socklen_t type_len = sizeof(type);
    if (::getsockopt(channel, SOL_SOCKET, SO_TYPE, &type, &type_len) != 0 || type != SOCK_STREAM) {
        // Typed into a terminal by hand: there is no channel to serve.
        return 2;
    }
    // Not the terminal's session, nor the bootstrap's: we outlive both.
    ::setsid();

    const long minutes = minutes_arg != nullptr ? std::strtol(minutes_arg, nullptr, 10) : 0;
    const long long idle_limit = minutes < 0 ? -1 : static_cast<long long>(minutes) * 60'000;

    const int listener = listen_broker();
    if (listener < 0) {
        LOGI("broker socket taken (%s), leaving", std::strerror(errno));
        return 0;
    }

    // AxManager updated: our lib directory is gone, so the next run starts the new code.
    char self[PATH_MAX];
    const ssize_t self_len = ::readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (self_len > 0) self[self_len] = '\0';

    LOGI("listening, idle limit %ld min", minutes);
    long long last = now_ms();
    pollfd polls[2] = {{listener, POLLIN, 0}, {channel, POLLIN, 0}};
    for (;;) {
        const int n = ::poll(polls, 2, CHECK_MS);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGW("poll failed: %s", std::strerror(errno));
            break;
        }
        if (polls[1].revents != 0) {
            // The server never writes here: this is its end closing (it stopped).
            LOGI("server channel closed, leaving");
            break;
        }
        if (polls[0].revents & POLLIN) {
            const int client = ::accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
            if (client < 0) continue;
            const char marker = 'C';
            const bool sent = write_with_fds(channel, &marker, 1, &client, 1);
            ::close(client);
            if (!sent) {
                LOGW("can't pass a client to the server: %s", std::strerror(errno));
                break;
            }
            last = now_ms();
            continue;
        }
        if (n == 0) {
            if (self_len > 0 && ::access(self, X_OK) != 0) {
                LOGI("%s is gone, leaving", self);
                break;
            }
            if (idle_limit >= 0 && now_ms() - last >= idle_limit) {
                LOGI("idle for %ld min, leaving", minutes);
                break;
            }
        }
    }
    ::close(listener);
    return 0;
}

}  // namespace axsh
