#define _GNU_SOURCE
#include <jni.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <limits.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>

static void throw_io(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass klass = (*env)->FindClass(env, "java/io/IOException");
    (*env)->ThrowNew(env, klass, message);
}

// execve consumes standard UTF-8. JNI GetStringUTFChars uses Modified UTF-8,
// which encodes supplementary characters as surrogate bytes and cannot represent argv NUL.
static char *copy_process_utf8(JNIEnv *env, jstring value) {
    if (value == NULL) { errno = EINVAL; return NULL; }
    size_t count = (size_t)(*env)->GetStringLength(env, value);
    if (count > (SIZE_MAX - 1) / 3) { errno = ENOMEM; return NULL; }
    const jchar *input = (*env)->GetStringChars(env, value, NULL);
    if (input == NULL) { errno = ENOMEM; return NULL; }
    char *output = malloc(count * 3 + 1);
    if (output == NULL) { (*env)->ReleaseStringChars(env, value, input); errno = ENOMEM; return NULL; }
    size_t length = 0;
    for (size_t index = 0; index < count; index++) {
        uint32_t point = input[index];
        if (point == 0) goto invalid;
        if (point >= 0xd800 && point <= 0xdbff) {
            if (index + 1 >= count || input[index + 1] < 0xdc00 || input[index + 1] > 0xdfff) goto invalid;
            point = 0x10000 + ((point - 0xd800) << 10) + (input[++index] - 0xdc00);
        } else if (point >= 0xdc00 && point <= 0xdfff) goto invalid;
        if (point < 0x80) output[length++] = (char)point;
        else if (point < 0x800) {
            output[length++] = (char)(0xc0 | (point >> 6)); output[length++] = (char)(0x80 | (point & 0x3f));
        } else if (point < 0x10000) {
            output[length++] = (char)(0xe0 | (point >> 12)); output[length++] = (char)(0x80 | ((point >> 6) & 0x3f)); output[length++] = (char)(0x80 | (point & 0x3f));
        } else {
            output[length++] = (char)(0xf0 | (point >> 18)); output[length++] = (char)(0x80 | ((point >> 12) & 0x3f));
            output[length++] = (char)(0x80 | ((point >> 6) & 0x3f)); output[length++] = (char)(0x80 | (point & 0x3f));
        }
    }
    output[length] = 0; (*env)->ReleaseStringChars(env, value, input); return output;
invalid:
    free(output); (*env)->ReleaseStringChars(env, value, input); errno = EINVAL; return NULL;
}

static void reset_child_signals(void) {
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    for (int number = 1; number < NSIG; number++) {
        if (number != SIGKILL && number != SIGSTOP) sigaction(number, &action, NULL);
    }
}

static int child_fd_limit(void) {
    struct rlimit limits;
    if (getrlimit(RLIMIT_NOFILE, &limits) < 0 || limits.rlim_cur > 1048576) return -1;
    return (int)limits.rlim_cur;
}

static void close_inherited_fds(int limit) {
    // Do not inherit ART/WebView/Binder/database descriptors into Debian. Raw
    // close is intentional here: Android fdsan ownership tags belong to the
    // pre-fork Java runtime, which this child is about to replace with execve.
    for (int fd = 3; fd < limit; fd++) syscall(__NR_close, fd);
}

JNIEXPORT jlong JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_pageSize(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return sysconf(_SC_PAGESIZE);
}

// The path must be checked by the Kotlin caller against nativeLibraryDir.
// All allocations happen before fork; the child performs only syscall wrappers.
JNIEXPORT jintArray JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_spawnPty(
    JNIEnv *env, jobject self, jobjectArray jargs, jobjectArray jenv, jint rows, jint cols) {
    (void)self;
    jsize argc = (*env)->GetArrayLength(env, jargs), envc = (*env)->GetArrayLength(env, jenv);
    char **args = calloc((size_t)argc + 1, sizeof(char *));
    char **vars = calloc((size_t)envc + 1, sizeof(char *));
    int master = -1, slave = -1, failure = 0, fd_limit = child_fd_limit();
    pid_t pid = -1;
    if (!args || !vars) { failure = ENOMEM; goto cleanup; }
    if (fd_limit < 0) { failure = EINVAL; goto cleanup; }
    for (int list = 0; list < 2; list++) {
        jobjectArray input = list ? jenv : jargs;
        char **output = list ? vars : args;
        jsize count = list ? envc : argc;
        for (jsize i = 0; i < count; i++) {
            jstring str = (jstring)(*env)->GetObjectArrayElement(env, input, i);
            output[i] = copy_process_utf8(env, str);
            int encoding_failure = output[i] ? 0 : errno;
            (*env)->DeleteLocalRef(env, str);
            if (!output[i]) { failure = encoding_failure; goto cleanup; }
        }
    }
    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) || unlockpt(master)) { failure = errno; goto cleanup; }
    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name))) { failure = errno; goto cleanup; }
    slave = open(slave_name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) { failure = errno; goto cleanup; }
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
    if (ioctl(master, TIOCSWINSZ, &size)) { failure = errno; goto cleanup; }
    pid = fork();
    if (pid == 0) {
        reset_child_signals();
        close(master);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(125);
        for (int fd = 0; fd < 3; fd++) if (dup2(slave, fd) < 0) _exit(125);
        if (slave > 2) close(slave);
        close_inherited_fds(fd_limit);
        execve(args[0], args, vars);
        _exit(126);
    }
    if (pid < 0) failure = errno;
cleanup:
    if (slave >= 0) close(slave);
    if (args) { for (jsize i = 0; i < argc; i++) free(args[i]); free(args); }
    if (vars) { for (jsize i = 0; i < envc; i++) free(vars[i]); free(vars); }
    if (failure) {
        if (master >= 0) close(master);
        throw_io(env, strerror(failure));
        return NULL;
    }
    jint values[2] = { master, pid };
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) { close(master); kill(-pid, SIGKILL); waitpid(pid, NULL, 0); return NULL; }
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_waitFor(JNIEnv *env, jobject self, jint pid) {
    (void)env; (void)self;
    int status;
    while (waitpid(pid, &status, 0) < 0) { if (errno != EINTR) return -1; }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

JNIEXPORT void JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_signalGroup(JNIEnv *env, jobject self, jint pid, jint sig) {
    (void)env; (void)self;
    if (pid > 1) kill(-pid, sig);
}

JNIEXPORT void JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_resize(JNIEnv *env, jobject self, jint fd, jint rows, jint cols) {
    (void)self;
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
    if (ioctl(fd, TIOCSWINSZ, &size) < 0) throw_io(env, strerror(errno));
}

// Non-interactive commands use the same native supervision as PTYs. Android's
// java.lang.Process.destroyForcibly() is not a reliable SIGKILL contract on all
// devices; PRoot deliberately ignores SIGTERM, so retain its actual native PID.
JNIEXPORT jintArray JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_spawnProcess(
    JNIEnv *env, jobject self, jobjectArray jargs, jobjectArray jenv) {
    (void)self;
    jsize argc = (*env)->GetArrayLength(env, jargs), envc = (*env)->GetArrayLength(env, jenv);
    char **args = calloc((size_t)argc + 1, sizeof(char *));
    char **vars = calloc((size_t)envc + 1, sizeof(char *));
    int channel[2] = {-1, -1}, failure = 0, fd_limit = child_fd_limit();
    pid_t pid = -1;
    if (!args || !vars || argc < 1) { failure = ENOMEM; goto cleanup_process; }
    if (fd_limit < 0) { failure = EINVAL; goto cleanup_process; }
    for (int list = 0; list < 2; list++) {
        jobjectArray input = list ? jenv : jargs;
        char **output = list ? vars : args;
        jsize count = list ? envc : argc;
        for (jsize i = 0; i < count; i++) {
            jstring str = (jstring)(*env)->GetObjectArrayElement(env, input, i);
            output[i] = copy_process_utf8(env, str);
            int encoding_failure = output[i] ? 0 : errno;
            (*env)->DeleteLocalRef(env, str);
            if (!output[i]) { failure = encoding_failure; goto cleanup_process; }
        }
    }
    if (pipe2(channel, O_CLOEXEC) < 0) { failure = errno; goto cleanup_process; }
    pid = fork();
    if (pid == 0) {
        reset_child_signals();
        close(channel[0]);
        if (setsid() < 0) _exit(125);
        int null_input = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (null_input < 0 || dup2(null_input, STDIN_FILENO) < 0 ||
            dup2(channel[1], STDOUT_FILENO) < 0 || dup2(channel[1], STDERR_FILENO) < 0) _exit(125);
        if (null_input > 2) close(null_input);
        if (channel[1] > 2) close(channel[1]);
        close_inherited_fds(fd_limit);
        execve(args[0], args, vars);
        _exit(126);
    }
    if (pid < 0) failure = errno;
cleanup_process:
    if (channel[1] >= 0) close(channel[1]);
    if (args) { for (jsize i = 0; i < argc; i++) free(args[i]); free(args); }
    if (vars) { for (jsize i = 0; i < envc; i++) free(vars[i]); free(vars); }
    if (failure) {
        if (channel[0] >= 0) close(channel[0]);
        throw_io(env, strerror(failure)); return NULL;
    }
    jint values[2] = {channel[0], pid};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) { close(channel[0]); kill(pid, SIGKILL); waitpid(pid, NULL, 0); return NULL; }
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_signalProcess(JNIEnv *env, jobject self, jint pid, jint sig) {
    (void)env; (void)self;
    if (pid > 1) kill(pid, sig);
}

JNIEXPORT jint JNICALL Java_io_github_opencodeandroid_runtime_NativeHost_setSymlinkModifiedTime(
    JNIEnv *env, jobject self, jbyteArray path, jlong milliseconds) {
    (void)self;
    if (path == NULL) return EINVAL;
    jsize length = (*env)->GetArrayLength(env, path);
    if (length < 1 || length >= PATH_MAX) return ENAMETOOLONG;
    char name[PATH_MAX];
    (*env)->GetByteArrayRegion(env, path, 0, length, (jbyte *)name);
    if ((*env)->ExceptionCheck(env)) return EIO;
    if (memchr(name, '\0', (size_t)length) != NULL) return EINVAL;
    name[length] = '\0';
    struct timespec times[2] = {{ .tv_sec = 0, .tv_nsec = UTIME_OMIT },
                               { .tv_sec = milliseconds / 1000, .tv_nsec = (milliseconds % 1000) * 1000000 }};
    if (times[1].tv_nsec < 0) { times[1].tv_sec--; times[1].tv_nsec += 1000000000; }
    if (utimensat(AT_FDCWD, name, times, AT_SYMLINK_NOFOLLOW) < 0) return errno;
    return 0;
}
