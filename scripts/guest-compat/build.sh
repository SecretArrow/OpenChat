#!/usr/bin/env bash
# Builds the guest syscall-compat preload (glibc amd64) and prints its SHA-256.
# Android's x86_64 zygote seccomp policy answers legacy file syscalls
# (rename/unlink/mkdir/stat/...) with ENOSYS — glibc still issues them, bionic
# never does (and arm64 has no legacy rename, which is why real arm64 devices
# are unaffected). The *at() variants are allowed; this preload maps the
# legacy wrappers onto them. Output is committed as an APK asset and
# SHA-256-pinned in UbuntuInstaller (COMPAT_LIB_SHA256).
#
# Verify with seccomp_sim: gcc -O2 -o sim seccomp_sim.c && ./sim
#   -> without preload: CHILD_RENAME_FAILED errno=38 (Function not implemented)
#   -> with preload:    CHILD_RENAME_OK
set -euo pipefail
cd "$(dirname "$0")"
gcc -O2 -fPIC -shared -Wall -Wextra -o libopenchat_compat.so compat.c
cp libopenchat_compat.so "$1/app/src/main/assets/ubuntu/compat/x86_64/libopenchat_compat.so" 2>/dev/null || true
sha256sum libopenchat_compat.so
