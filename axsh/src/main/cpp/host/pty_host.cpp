#include <jni.h>
#include <unistd.h>
#include <termios.h>
#include <fcntl.h>
#include <cstdlib>
#include <wait.h>
#include <android/log.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include "logging.h"
#include "pts.h"

/**
 * In the child, just before exec: the shell keeps stdin, stdout and stderr and nothing else of
 * ours. Every fd the server has open is inherited otherwise, and outlives the server in each
 * shell still running: its @axeron listener (a restarted server can't bind it), its pipes, other
 * sessions' PTY masters. Raw syscalls: fdsan would abort on close() of an fd a Java object owns.
 */
static void close_inherited_fds() {
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 3u, ~0u, 0u) == 0) return;
#endif
    rlimit limit{};
    const int max = getrlimit(RLIMIT_NOFILE, &limit) == 0 && limit.rlim_cur != RLIM_INFINITY
                    ? static_cast<int>(limit.rlim_cur) : 4096;
    for (int fd = 3; fd < max; ++fd) syscall(__NR_close, fd);
}

static int setWindowSize(int ptmx, jlong size) {
    static_assert(sizeof(jlong) == sizeof(winsize));
    winsize w = *((winsize *) &size);

    LOGD("setWindowSize %d %d %d %d", w.ws_row, w.ws_col, w.ws_xpixel, w.ws_ypixel);

    if (ioctl(ptmx, TIOCSWINSZ, &w) == -1) {
        PLOGE("ioctl TIOCGWINSZ");
        return -1;
    }
    return 0;
}

// libcore/ojluni/src/main/native/UNIXProcess_md.c

static void *xmalloc(JNIEnv *env, size_t size) {
    void *p = malloc(size);
    if (p == nullptr)
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), nullptr);
    else
        memset(p, 0, size);
    return p;
}

#define NEW(type, n) ((type *) xmalloc(env, (n) * sizeof(type)))

static const char *getBytes(JNIEnv *env, jbyteArray arr) {
    return arr == nullptr ? nullptr : (const char *) env->GetByteArrayElements(arr, nullptr);
}

static void releaseBytes(JNIEnv *env, jbyteArray arr, const char *parr) {
    if (parr != nullptr)
        env->ReleaseByteArrayElements(arr, (jbyte *) parr, JNI_ABORT);
}

static void initVectorFromBlock(const char **vector, const char *block, int count) {
    int i;
    const char *p;
    for (i = 0, p = block; i < count; i++) {
        /* Invariant: p always points to the start of a C string. */
        vector[i] = p;
        while (*(p++));
    }
    vector[count] = nullptr;
}

static jintArray RishHost_startHost(
        JNIEnv *env, jclass clazz,
        jbyteArray argBlock, jint argc,
        jbyteArray envBlock, jint envc,
        jbyteArray dirBlock,
        jbyte tty,
        jint stdin_read, jint stdout_write, jint stderr_write) {

    bool in_tty = tty & ATTY_IN;
    bool out_tty = tty & ATTY_OUT;
    bool err_tty = tty & ATTY_ERR;
    // Without a PTY the client's pipes become the shell's stdio as they are: nothing is copied
    // here, and once we drop our copies the client sees EOF when the shell is done (and vice versa).
    bool direct = tty == 0;

    LOGD("[START] RishHost_startHost");
    LOGD("[MODE ] tty=0x%02x in_tty=%d out_tty=%d err_tty=%d",
         tty, in_tty ? 1 : 0, out_tty ? 1 : 0, err_tty ? 1 : 0);

    LOGD("[FD   ] client stdin_read=%d stdout_write=%d stderr_write=%d",
         stdin_read, stdout_write, stderr_write); // [LOG+]

    int ptmx = -1;
    if (tty) {
        ptmx = open_ptmx();
        if (ptmx == -1) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Unable to open ptmx");
            return nullptr;
        }
        LOGD("[PTY  ] ptmx=%d", ptmx);
    } else {
        LOGD("[PIPE ] tty==0, PTY disabled"); // [LOG+]
    }

    int stdin_pipe[2]{-1}, stdout_pipe[2]{-1}, stderr_pipe[2]{-1};

    LOGD("[TTY? ] istty in=%d out=%d err=%d",
         (tty & ATTY_IN) ? 1 : 0,
         (tty & ATTY_OUT) ? 1 : 0,
         (tty & ATTY_ERR) ? 1 : 0);

    if (!in_tty && !direct) {
        pipe2(stdin_pipe, 0);
        LOGD("[PIPE ] stdin_pipe r=%d w=%d", stdin_pipe[0], stdin_pipe[1]); // [LOG+]
    }
    if (!out_tty && !direct) {
        pipe2(stdout_pipe, 0);
        LOGD("[PIPE ] stdout_pipe r=%d w=%d", stdout_pipe[0], stdout_pipe[1]); // [LOG+]
    }
    if (!err_tty && !direct) {
        pipe2(stderr_pipe, 0);
        LOGD("[PIPE ] stderr_pipe r=%d w=%d", stderr_pipe[0], stderr_pipe[1]); // [LOG+]
    }

    // The full argv, program first: argv[0] is what gets exec'd.
    const char *pargBlock = getBytes(env, argBlock);
    const char **argv = NEW(const char *, argc + 1);
    initVectorFromBlock(argv, pargBlock, argc);

    for (int i = 0; i < argc; ++i) {
        LOGD("[ARGV ] arg%d=%s", i, argv[i]);
    }

    const char **envv = nullptr;
    const char *penvBlock = nullptr;
    if (envc > 0) {
        penvBlock = getBytes(env, envBlock);
        envv = NEW(const char *, envc + 1);

        initVectorFromBlock(envv, penvBlock, envc);
        LOGD("[ENV  ] envc=%d", envc); // [LOG+]
    } else {
        LOGD("[ENV  ] no env"); // [LOG+]
    }

    const char *pdir = nullptr;
    if (dirBlock) {
        pdir = getBytes(env, dirBlock);
        LOGD("[CWD  ] requested dir=%s", pdir); // [LOG+]
    }

    auto pid = fork();
    if (pid == -1) {
        releaseBytes(env, argBlock, pargBlock);
        releaseBytes(env, envBlock, penvBlock);
        releaseBytes(env, dirBlock, pdir);
        LOGE("[FORK ] fork failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Unable to fork");
        return nullptr;
    }

    /* =========================
     * PARENT
     * ========================= */
    if (pid > 0) {
        releaseBytes(env, argBlock, pargBlock);
        releaseBytes(env, envBlock, penvBlock);
        releaseBytes(env, dirBlock, pdir);
        LOGD("[PARENT] pid=%d", pid); // [LOG+]

        if (direct) {
            // The child has them; ours would keep the client from ever seeing EOF.
            LOGD("[PARENT] direct: client pipes are the child's stdio");
            close(stdin_read);
            close(stdout_write);
            if (stderr_write >= 0) close(stderr_write);
            auto result = env->NewIntArray(2);
            env->SetIntArrayRegion(result, 0, 1, &pid);
            env->SetIntArrayRegion(result, 1, 1, &ptmx);
            return result;
        }

        auto called = std::make_shared<std::atomic_bool>(false);
        auto func = [pid, called]() {
            if (called->exchange(true)) {
                return;
            }

            LOGW("[PARENT] client dead, kill child pid=%d", pid);
            kill(pid, SIGKILL);
        };

        if (in_tty) {
            LOGD("[PARENT] stdin: client -> ptmx"); // [LOG+]
            transfer_async(stdin_read, ptmx);
        } else {
            LOGD("[PARENT] stdin: client -> pipe"); // [LOG+]
            transfer_async(stdin_read, stdin_pipe[1]);
            close(stdin_pipe[0]);
        }

        if (out_tty) {
            LOGD("[PARENT] stdout: ptmx -> client"); // [LOG+]
            transfer_async(ptmx, stdout_write, func);
        } else {
            LOGD("[PARENT] stdout: pipe -> client"); // [LOG+]
            transfer_async(stdout_pipe[0], stdout_write, func);
            close(stdout_pipe[1]);
        }

        if (!err_tty) {
            LOGD("[PARENT] stderr: pipe -> client"); // [LOG+]
            transfer_async(stderr_pipe[0], stderr_write);
            close(stderr_pipe[1]);
        } else {
            LOGD("[PARENT] stderr: via ptmx"); // [LOG+]
        }

        auto result = env->NewIntArray(2);
        env->SetIntArrayRegion(result, 0, 1, &pid);
        env->SetIntArrayRegion(result, 1, 1, &ptmx);
        return result;
    } else {
        /* =========================
     * CHILD
     * ========================= */
        LOGD("[CHILD ] pid=%d starting", getpid()); // [LOG+]

        if (setsid() < 0) {
            PLOGE("[CHILD ] setsid failed");
            exit(1);
        }

        if (pdir) {
            LOGD("[CHILD ] chdir attempt: %s", pdir);
            if (access(pdir, X_OK) == 0) {
                if (chdir(pdir) == -1) {
                    PLOGE("[CHILD ] chdir failed");
                } else {
                    LOGD("[CHILD ] chdir ok");
                }
            } else {
                PLOGE("[CHILD ] access denied");
            }
        }

        int pts = -1;
        if (tty) {
            char pts_slave[PATH_MAX]{0};
            if (ptsname_r(ptmx, pts_slave, PATH_MAX - 1) == -1) {
                PLOGE("[CHILD ] ptsname_r failed");
                exit(1);
            }

            if ((pts = open(pts_slave, O_RDWR)) == -1) {
                PLOGE("[CHILD ] open pts failed");
            }
            LOGD("[CHILD ] pts=%d (%s)", pts, pts_slave);
        } else {
            LOGD("[CHILD ] no PTY, pipe mode");
        }

        if (direct) {
            dup2(stdin_read, STDIN_FILENO);
            dup2(stdout_write, STDOUT_FILENO);
            dup2(stderr_write >= 0 ? stderr_write : stdout_write, STDERR_FILENO);
            const int client_fds[] = {stdin_read, stdout_write, stderr_write};
            for (int fd : client_fds) {
                if (fd > STDERR_FILENO) close(fd);
            }
            LOGD("[CHILD ] stdio <- client pipes");
        } else if (in_tty) {
            dup2(pts, STDIN_FILENO);
            LOGD("[CHILD ] stdin <- pts");
        } else {
            dup2(stdin_pipe[0], STDIN_FILENO);
            close(stdin_pipe[1]);
            LOGD("[CHILD ] stdin <- pipe");
        }

        if (direct) {
            // stdout and stderr were set above.
        } else if (out_tty) {
            dup2(pts, STDOUT_FILENO);
            LOGD("[CHILD ] stdout <- pts");
        } else {
            dup2(stdout_pipe[1], STDOUT_FILENO);
            close(stdout_pipe[0]);
            LOGD("[CHILD ] stdout <- pipe");
        }

        if (direct) {
            // As above.
        } else if (err_tty) {
            dup2(pts, STDERR_FILENO);
            LOGD("[CHILD ] stderr <- pts");
        } else {
            dup2(stderr_pipe[1], STDERR_FILENO);
            close(stderr_pipe[0]);
            LOGD("[CHILD ] stderr <- pipe");
        }

        LOGD("[CHILD ] isatty stdin=%d stdout=%d stderr=%d",
             isatty(STDIN_FILENO),
             isatty(STDOUT_FILENO),
             isatty(STDERR_FILENO));

        if (pts != -1) {
            close(pts);
        }

        LOGD("[EXEC ] exec %s", argv[0]); // [LOG+]
        // Last: logging above may still use an fd of its own.
        close_inherited_fds();

        if (envv) {
            execvpe(argv[0], (char *const *) argv, (char *const *) envv);
        } else {
            execvp(argv[0], (char *const *) argv);
        }
        PLOGE("execv");
        _exit(127);
    }
}


static void RishHost_setWindowSize(JNIEnv *env, jclass clazz, jint ptmx, jlong size) {
    setWindowSize(ptmx, size);
}

static jint RishHost_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    if (pid < 0)
        return -1;

    int status;
    int w;
    do {
        w = TEMP_FAILURE_RETRY(waitpid(pid, &status, 0));
        if (w == -1) {
            if (errno == ECHILD) {
                return 0;
            }
            PLOGE("waitpid");
            return -1;
        }

        if (WIFEXITED(status)) {
            LOGD("exited with %d", WEXITSTATUS(status));
            return WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            LOGD("killed by signal %d", WTERMSIG(status));
            return 0;
        }
    } while (!WIFEXITED(status) && !WIFSIGNALED(status));

    return -1;
}

// libaxsh_host.so: loaded only by the server, which hosts the remote shells (PTY or pipes).
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;

    auto clazz = env->FindClass("frb/axeron/axsh/host/PtyHost");
    if (clazz == nullptr) return JNI_ERR;
    JNINativeMethod methods[] = {
            {"start",         "([BI[BI[BBIII)[I", (void *) RishHost_startHost},
            {"setWindowSize", "(IJ)V",            (void *) RishHost_setWindowSize},
            {"waitFor",       "(I)I",             (void *) RishHost_waitFor},
    };
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
