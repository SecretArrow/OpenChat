# Release Test Report

- Generated: 2026-09-14T09:26:59.752561+00:00
- Application: com.openchat.android.MainActivity
- Package: `com.openchat.android`
- Version: 0.1.9 (code 101)
- Commit: `6ffb1c07faa575b6c2c33a7536037e62125d9a1b`
- APK: `OpenChat-x86_64.apk`
- Android API: 30
- Device: sdk_gphone_x86_64
- Mode: DEEP · Seed: 1337

## Summary

**FAIL**

## Feature Discovery

- Discovered elements (peak on one screen): 23
- Tested: 65
- Skipped by safety engine: 1
- Screens seen: 13
- Confirmation flows cancelled safely: 0

## Crashes

- beginning of crash 09-14 09:26:55.774 10230 15199 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 15199 (HwBinder:10230_), pid 10230 (audio.service.r) 09-14 09:26:55.781 15205 15205 I 

## ANR

- none

## Lifecycle

- pass: 0 · fail: 0

## Permissions

- discovered: 8 (runtime grant/deny handled by the OS dialogs; dangerous prompts cancelled by the safety engine)

## Network

- pass: 0 · fail: 0

## Persistence

- verified via force-stop → restart → alive + usable UI

## Visual

- screenshots: 2

## Random Exploration

- seed 1337 (replay with --seed 1337)

## Auto Fixes

- last action: failure (see action-trace.json)

## Regression Tests

- every fixed failure re-runs the failed action + full regression via the CI loop

## Remaining Problems

- [crash] beginning of crash 09-14 09:26:55.774 10230 15199 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 15199 (HwBinder:10230_), pid 10230 (audio.service.r) 09-14 09:26:55.781 15205 

## Final Recommendation

**DO NOT RELEASE**
