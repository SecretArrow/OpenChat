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
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/time.h>
#include <time.h>
#include <utime.h>
#include <unistd.h>

/* ---- legacy path syscalls -> *at(AT_FDCWD, ...) ------------------------ */

/* Hardlink fallback: on some Android seccomp policies / proot builds the
 * linkat syscall is answered with EACCES/EPERM even though the rest of the
 * *at() family is allowed. dpkg's backup links ("unable to make backup link
 * of './usr/bin/perl' before installing new version") only need the file's
 * content preserved under a second name, so a byte copy is a faithful
 * substitute that lets package upgrades proceed. Real hardlinks are still
 * used whenever the kernel permits them — the fallback only engages on
 * denial, and only for regular files. */
static int copy_as_link_fallback(const char *oldp, const char *newp) {
    struct stat st;
    if (syscall(SYS_newfstatat, AT_FDCWD, oldp, &st, 0) != 0) return -1;
    if (!S_ISREG(st.st_mode)) { errno = EPERM; return -1; }

    int in = (int)syscall(SYS_openat, AT_FDCWD, oldp, O_RDONLY, 0);
    if (in < 0) return -1;
    int out = (int)syscall(SYS_openat, AT_FDCWD, newp,
                           O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 0777);
    if (out < 0) {
        int saved = errno;
        syscall(SYS_close, in);
        errno = saved;
        return -1;
    }

    char buf[65536];
    for (;;) {
        ssize_t r = syscall(SYS_read, in, buf, sizeof(buf));
        if (r < 0) goto fail;
        if (r == 0) break;
        ssize_t off = 0;
        while (off < r) {
            ssize_t w = syscall(SYS_write, out, buf + off, (size_t)(r - off));
            if (w < 0) goto fail;
            off += w;
        }
    }
    syscall(SYS_close, in);
    syscall(SYS_close, out);
    return 0;

fail: {
        int saved = errno;
        syscall(SYS_close, in);
        syscall(SYS_close, out);
        syscall(SYS_unlinkat, AT_FDCWD, newp, 0);
        errno = saved;
        return -1;
    }
}

static int link_denied(int e) {
    return e == EACCES || e == EPERM || e == ENOSYS || e == EXDEV;
}

/* ---- hardlink-fallback bookkeeping (shadow-utils lock protocol) --------
 * shadow's do_lock_file (groupadd/useradd/adduser, libcommonio) creates a
 * file, hardlinks a lock name onto it, then verifies acquisition by checking
 * that the base file now reports st_nlink == 2 (check_link_count). Real
 * hardlinks are unavailable under the zygote seccomp filter — link/linkat
 * are answered ENOSYS (bionic never links; the run-as probe proves SELinux
 * itself permits them, LINK_RC=0), so the fallback below copies the content
 * instead. The copy keeps the second name's CONTENT (what dpkg's backup
 * links need) but not the link COUNT (what shadow needs) — without help
 * every groupadd exits 10 "can't update group file" after its 15×1s lock
 * retry loop (E2E run 19: openssh-client addgroup).
 *
 * So: each fallback copy records the BASE path in a bounded ring, and the
 * stat family below reports st_nlink == 2 for a recorded path. shadow
 * checks the count immediately after the link call, so a fixed-size ring
 * without expiry is sufficient; concurrent groupadds are not a real
 * scenario in this single-user container (the spoofed lock degrades to a
 * best-effort mutex, same trust level as the rest of the shim).
 */
#define FB_SLOTS 16
#define FB_PATH 1024
static struct {
    atomic_int used;
    char path[FB_PATH];
} g_fb_slots[FB_SLOTS];
static atomic_int g_fb_next = 0;

static void fb_record(const char *path) {
    if (path == NULL || strlen(path) >= FB_PATH) return;
    int slot = __atomic_fetch_add(&g_fb_next, 1, __ATOMIC_RELAXED) % FB_SLOTS;
    atomic_int *used = &g_fb_slots[slot].used;
    __atomic_store_n(used, 0, __ATOMIC_SEQ_CST);   /* readers skip during rewrite */
    memcpy(g_fb_slots[slot].path, path, strlen(path) + 1);
    __atomic_store_n(used, 1, __ATOMIC_SEQ_CST);
}

static int fb_matches(const char *path) {
    if (path == NULL) return 0;
    for (int i = 0; i < FB_SLOTS; i++) {
        if (__atomic_load_n(&g_fb_slots[i].used, __ATOMIC_ACQUIRE) == 1 &&
            strcmp(g_fb_slots[i].path, path) == 0) {
            return 1;
        }
    }
    return 0;
}

/* Reports a fallback-linked base file as nlink 2 (see block comment). */
static void fb_fix_nlink(const char *path, struct stat *buf) {
    if (buf != NULL && fb_matches(path) && buf->st_nlink == 1) {
        buf->st_nlink = 2;
    }
}

int rename(const char *oldp, const char *newp) {
    return (int)syscall(SYS_renameat, AT_FDCWD, oldp, AT_FDCWD, newp);
}

int link(const char *oldp, const char *newp) {
    int r = (int)syscall(SYS_linkat, AT_FDCWD, oldp, AT_FDCWD, newp, 0);
    if (r != 0 && link_denied(errno)) {
        int saved = errno;
        r = copy_as_link_fallback(oldp, newp);
        if (r == 0) fb_record(oldp);
        if (r != 0) errno = saved;
    }
    return r;
}

int linkat(int olddirfd, const char *oldp, int newdirfd, const char *newp, int flags) {
    int r = (int)syscall(SYS_linkat, olddirfd, oldp, newdirfd, newp, flags);
    if (r != 0 && flags == 0 && link_denied(errno)) {
        int saved = errno;
        r = copy_as_link_fallback(oldp, newp);
        if (r == 0) fb_record(oldp);
        if (r != 0) errno = saved;
    }
    return r;
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
    int r = (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, 0);
    if (r == 0) fb_fix_nlink(path, buf);
    return r;
}

int lstat(const char *path, struct stat *buf) {
    int r = (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW);
    if (r == 0) fb_fix_nlink(path, buf);
    return r;
}

int __xstat(int ver, const char *path, struct stat *buf) {
    if (ver != 1) { errno = EINVAL; return -1; }
    int r = (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, 0);
    if (r == 0) fb_fix_nlink(path, buf);
    return r;
}

int __lxstat(int ver, const char *path, struct stat *buf) {
    if (ver != 1) { errno = EINVAL; return -1; }
    int r = (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW);
    if (r == 0) fb_fix_nlink(path, buf);
    return r;
}

/* LFS aliases — same layout on x86_64, but LFS-built callers (shadow-utils,
 * dpkg: _FILE_OFFSET_BITS=64) reference the *64 symbols directly, so the
 * stat family must interpose consistently. struct stat64 is byte-identical
 * to struct stat on x86_64. */
extern int stat64 (const char *__restrict, struct stat64 *__restrict)
    __THROW __nonnull ((1, 2));
extern int lstat64 (const char *__restrict, struct stat64 *__restrict)
    __THROW __nonnull ((1, 2));
extern int __xstat64 (int, const char *__restrict, struct stat64 *__restrict)
    __THROW __nonnull ((2, 3));
extern int __lxstat64 (int, const char *__restrict, struct stat64 *__restrict)
    __THROW __nonnull ((2, 3));

int stat64 (const char *path, struct stat64 *buf) {
    return stat (path, (struct stat *) buf);
}

int lstat64 (const char *path, struct stat64 *buf) {
    return lstat (path, (struct stat *) buf);
}

int __xstat64 (int ver, const char *path, struct stat64 *buf) {
    return __xstat (ver, path, (struct stat *) buf);
}

int __lxstat64 (int ver, const char *path, struct stat64 *buf) {
    return __lxstat (ver, path, (struct stat *) buf);
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
