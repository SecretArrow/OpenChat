/*
 * Open Chat — real PTY bridge (JNI).
 *
 * Spawns a child process attached to a pseudo-terminal using forkpty(3),
 * so interactive programs (bash, apt, node, vim-like TUIs) behave like on a
 * real Linux terminal. Built for arm64-v8a, armeabi-v7a and x86_64.
 */
#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>

extern char **environ;

static char **copy_string_array(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (js == NULL) { out[i] = strdup(""); continue; }
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        out[i] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
    }
    out[n] = NULL;
    return out;
}

static void free_string_array(char **arr) {
    if (arr == NULL) return;
    for (int i = 0; arr[i] != NULL; i++) free(arr[i]);
    free(arr);
}

static jstring to_jstring_or_null(JNIEnv *env, const char *s) {
    if (s == NULL) return NULL;
    return (*env)->NewStringUTF(env, s);
}

/*
 * create(argv, cwd, envp, rows, cols) -> (pid << 32) | masterFd, or -1 on failure.
 * The child execs argv[0] (absolute path) with argv/envp inside the PTY.
 */
JNIEXPORT jlong JNICALL
Java_com_openchat_android_terminal_Pty_create(
        JNIEnv *env, jobject thiz,
        jobjectArray argv, jstring cwd, jobjectArray envp,
        jint rows, jint cols) {
    (void) thiz;

    char **c_argv = copy_string_array(env, argv);
    char **c_env = copy_string_array(env, envp);
    if (c_argv == NULL || c_argv[0] == NULL) {
        free_string_array(c_argv);
        free_string_array(c_env);
        return -1;
    }

    const char *c_cwd = NULL;
    char *cwd_copy = NULL;
    if (cwd != NULL) {
        const char *s = (*env)->GetStringUTFChars(env, cwd, NULL);
        if (s != NULL) {
            cwd_copy = strdup(s);
            (*env)->ReleaseStringUTFChars(env, cwd, s);
        }
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = rows > 0 ? (unsigned short) rows : 24;
    ws.ws_col = cols > 0 ? (unsigned short) cols : 80;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        free_string_array(c_argv);
        free_string_array(c_env);
        free(cwd_copy);
        return -1;
    }

    if (pid == 0) {
        /* Child: become the session leader via forkpty, reset signals, exec. */
        signal(SIGHUP, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        if (cwd_copy != NULL && chdir(cwd_copy) != 0) {
            /* fall back to / — keep going anyway */
            chdir("/");
        }
        setenv("PATH",
               "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
               1);
        execve(c_argv[0], c_argv, c_env != NULL ? c_env : environ);
        /* exec failed — report through the pty then die */
        const char *msg = "\r\n[openchat] exec failed: cannot start process\r\n";
        ssize_t ignored = write(master, msg, strlen(msg));
        (void) ignored;
        _exit(127);
    }

    free_string_array(c_argv);
    free_string_array(c_env);
    free(cwd_copy);

    return ((jlong) pid << 32) | (jlong) (master & 0x7fffffffLL);
}

JNIEXPORT jint JNICALL
Java_com_openchat_android_terminal_Pty_write(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray data, jint len) {
    (void) thiz;
    if (fd < 0 || len <= 0) return -1;
    jbyte *buf = (jbyte *) (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;
    ssize_t n = write(fd, buf, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint) n;
}

/* Blocking read; returns >0 bytes, 0 on EOF, -1 on error/EAGAIN-ish. */
JNIEXPORT jint JNICALL
Java_com_openchat_android_terminal_Pty_read(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len) {
    (void) thiz;
    if (fd < 0 || len <= 0) return -1;
    jbyte *cbuf = (jbyte *) malloc((size_t) len);
    if (cbuf == NULL) return -1;
    ssize_t n = read(fd, cbuf, (size_t) len);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, 0, (jsize) n, cbuf);
    }
    free(cbuf);
    if (n < 0 && (errno == EINTR)) return -2; /* retry */
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_openchat_android_terminal_Pty_resize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) thiz;
    if (fd < 0) return -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = rows > 0 ? (unsigned short) rows : 24;
    ws.ws_col = cols > 0 ? (unsigned short) cols : 80;
    return (jint) ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_openchat_android_terminal_Pty_kill(
        JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void) thiz;
    if (pid <= 1) return -1; /* never signal init */
    return (jint) kill((pid_t) pid, sig);
}

/*
 * wait(pid, block): returns exit status (0..255), or -1 when !block and
 * still running, or -2 when the pid is unknown/reaped already.
 */
JNIEXPORT jint JNICALL
Java_com_openchat_android_terminal_Pty_wait(
        JNIEnv *env, jobject thiz, jint pid, jboolean block) {
    (void) thiz;
    if (pid <= 0) return -2;
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, block ? 0 : WNOHANG);
    if (r == 0) return -1;
    if (r < 0) return -2;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_openchat_android_terminal_Pty_close(
        JNIEnv *env, jobject thiz, jint fd) {
    (void) thiz;
    if (fd >= 0) close(fd);
}

JNIEXPORT jstring JNICALL
Java_com_openchat_android_terminal_Pty_lastError(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    return to_jstring_or_null(env, strerror(errno));
}
