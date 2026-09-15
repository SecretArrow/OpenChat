/* Simulate Android's zygote seccomp policy for the legacy rename(2) syscall:
 * everything allowed, but __NR_rename returns ERRNO(ENOSYS) — then run a
 * real glibc `mv` child (same class of binary as the guest's apt/dpkg):
 *   1) without the compat preload  -> expected failure (errno 38)
 *   2) with LD_PRELOAD compat .so  -> expected success
 * This reproduces the exact E2E failure and proves the fix mechanism. */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int install_policy(void) {
    struct sock_filter filter[] = {
        /* [0] load nr */
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        /* [1] if nr == __NR_rename -> fall through to ERRNO, else ALLOW */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_rename, 0, 1),
        /* [2] */
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (SECCOMP_RET_DATA & ENOSYS)),
        /* [3] */
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { .len = (unsigned short)4, .filter = filter };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return -1;
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog);
}

static int run_child(char *const envp[]) {
    pid_t pid = fork();
    if (pid == 0) {
        char *argv[] = { "./child_rename", NULL };
        execve("./child_rename", argv, envp);
        _exit(127);
    }
    int st = 0; waitpid(pid, &st, 0);
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

int main(void) {
    if (install_policy()) { perror("seccomp"); return 1; }
    char *env_plain[] = { "PATH=/usr/bin:/bin", NULL };
    char *env_preload[3];
    char preload[512];
    snprintf(preload, sizeof(preload), "LD_PRELOAD=%s/libopenchat_compat.so",
             getenv("PWD"));
    env_preload[0] = preload; env_preload[1] = "PATH=/usr/bin:/bin"; env_preload[2] = NULL;

    unlink("/tmp/compat_t_b");
    printf("== without preload (reproduces E2E failure) ==\n");
    int rc1 = run_child(env_plain);
    printf("child rc=%d %s\n", rc1, rc1 == 0 ? "(UNEXPECTED)" : "(failed as expected)");

    unlink("/tmp/compat_t_b");
    printf("== with preload (the fix) ==\n");
    int rc2 = run_child(env_preload);
    printf("child rc=%d %s\n", rc2, rc2 == 0 ? "(FIX WORKS)" : "(fix failed!)");
    unlink("/tmp/compat_t_a"); unlink("/tmp/compat_t_b");
    return rc2 == 0 ? 0 : 2;
}
