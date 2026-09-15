/*
 * OpenChat Ubuntu guest syscall-compat preload (amd64/glibc only).
 *
 * Android's zygote seccomp policy (x86_64, per-targetSdk) rejects the legacy
 * file syscalls with ENOSYS instead of executing them: bionic never issues
 * them (it uses the *at() variants exclusively), but glibc on x86_64 still
 * calls e.g. rename(2) directly — so apt/dpkg inside proot fail with
 * "rename failed: Function not implemented" (errno 38). On arm64 the kernel
 * ABI has no legacy rename at all, which is why real arm64 devices never saw
 * this. The *at() syscalls themselves are in the allowlist (bionic uses
 * them), so re-implementing the legacy wrappers on top of them restores a
 * fully working guest userspace.
 *
 * Loaded via /etc/ld.so.preload inside the rootfs (written by the app's
 * installer). Uses raw syscalls only — no TLS, no malloc, no dlopen — safe
 * to preload into every dynamic guest process. Symbols resolve ahead of
 * libc for external callers (PLT); glibc-internal calls keep their hidden
 * aliases, so libc itself is unaffected.
 *
 * Build (glibc amd64): gcc -O2 -fPIC -shared -o libopenchat_compat.so compat.c
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/time.h>
#include <time.h>
#include <utime.h>
#include <unistd.h>
#include <stddef.h>

/* ---- legacy path syscalls -> *at(AT_FDCWD, ...) ------------------------ */

int rename(const char *oldp, const char *newp) {
    return (int)syscall(SYS_renameat, AT_FDCWD, oldp, AT_FDCWD, newp);
}

int unlink(const char *path) {
    return (int)syscall(SYS_unlinkat, AT_FDCWD, path, 0);
}

int rmdir(const char *path) {
    return (int)syscall(SYS_unlinkat, AT_FDCWD, path, AT_REMOVEDIR);
}

int mkdir(const char *path, mode_t mode) {
    return (int)syscall(SYS_mkdirat, AT_FDCWD, path, mode);
}

int link(const char *oldp, const char *newp) {
    return (int)syscall(SYS_linkat, AT_FDCWD, oldp, AT_FDCWD, newp, 0);
}

int symlink(const char *target, const char *linkpath) {
    return (int)syscall(SYS_symlinkat, target, AT_FDCWD, linkpath);
}

ssize_t readlink(const char *path, char *buf, size_t bufsz) {
    return syscall(SYS_readlinkat, AT_FDCWD, path, buf, bufsz);
}

int chmod(const char *path, mode_t mode) {
    return (int)syscall(SYS_fchmodat, AT_FDCWD, path, mode);
}

int chown(const char *path, uid_t owner, gid_t group) {
    return (int)syscall(SYS_fchownat, AT_FDCWD, path, owner, group, 0);
}

int lchown(const char *path, uid_t owner, gid_t group) {
    return (int)syscall(SYS_fchownat, AT_FDCWD, path, owner, group, AT_SYMLINK_NOFOLLOW);
}

int access(const char *path, int mode) {
    return (int)syscall(SYS_faccessat, AT_FDCWD, path, mode);
}

int mknod(const char *path, mode_t mode, dev_t dev) {
    return (int)syscall(SYS_mknodat, AT_FDCWD, path, mode, dev);
}

int creat(const char *path, mode_t mode) {
    return (int)syscall(SYS_openat, AT_FDCWD, path,
                        O_WRONLY | O_CREAT | O_TRUNC, mode);
}

/* ---- stat family -------------------------------------------------------
 * x86_64 struct stat is the only layout the kernel fills for both __NR_stat
 * and __NR_newfstatat, so newfstatat(buf) is byte-identical for the caller.
 * glibc < 2.33 exports path-stat through __xstat/__lxstat (__fxstat for
 * fd-stat); _STAT_VER is 1 on x86_64 for every glibc in our target rootfs. */

int stat(const char *path, struct stat *buf) {
    return (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, 0);
}

int lstat(const char *path, struct stat *buf) {
    return (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW);
}

int __xstat(int ver, const char *path, struct stat *buf) {
    if (ver != 1) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, 0);
}

int __lxstat(int ver, const char *path, struct stat *buf) {
    if (ver != 1) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW);
}

int __fxstat(int ver, int fd, struct stat *buf) {
    if (ver != 1) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, fd, "", buf, AT_EMPTY_PATH);
}

int __fxstatat(int ver, int fd, const char *path, struct stat *buf, int flag) {
    if (ver != 1) { errno = EINVAL; return -1; }
    return (int)syscall(SYS_newfstatat, fd, path, buf, flag);
}

/* ---- timestamps --------------------------------------------------------- */

int utimes(const char *path, const struct timeval tv[2]) {
    if (tv == NULL) {
        return (int)syscall(SYS_utimensat, AT_FDCWD, path, NULL, 0);
    }
    struct timespec ts[2] = {
        { tv[0].tv_sec, tv[0].tv_usec * 1000 },
        { tv[1].tv_sec, tv[1].tv_usec * 1000 },
    };
    return (int)syscall(SYS_utimensat, AT_FDCWD, path, ts, 0);
}

int utime(const char *path, const struct utimbuf *times) {
    if (times == NULL) {
        return (int)syscall(SYS_utimensat, AT_FDCWD, path, NULL, 0);
    }
    struct timespec ts[2] = {
        { times->actime, 0 },
        { times->modtime, 0 },
    };
    return (int)syscall(SYS_utimensat, AT_FDCWD, path, ts, 0);
}

/* ---- legacy pipe (bash pipelines) ---------------------------------------
 * glibc pipe() may use __NR_pipe on x86_64; pipe2(0) is identical. */

int pipe(int fds[2]) {
    return (int)syscall(SYS_pipe2, fds, 0);
}
