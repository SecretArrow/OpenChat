/* Child for seccomp_sim: calls glibc rename() directly (the legacy syscall
 * apt/dpkg issue) — dies with errno 38 under the simulating filter without
 * the compat preload, succeeds with it. */
#include <stdio.h>
#include <string.h>
#include <errno.h>
int main(void) {
    FILE *f = fopen("/tmp/compat_t_a", "w");
    if (!f) { perror("setup"); return 2; }
    fputs("x", f);
    fclose(f);
    if (rename("/tmp/compat_t_a", "/tmp/compat_t_b") != 0) {
        printf("CHILD_RENAME_FAILED errno=%d (%s)\n", errno, strerror(errno));
        return 1;
    }
    printf("CHILD_RENAME_OK\n");
    return 0;
}
