# Release Test Report

- Generated: 2026-09-14T10:29:09.047745+00:00
- Application: com.openchat.android.MainActivity
- Package: `com.openchat.android`
- Version: 0.1.9 (code 101)
- Commit: `77557f7cd92b8c707d672a3ee5dc49604fe2a6a5`
- APK: `OpenChat-x86_64.apk`
- Android API: 30
- Device: sdk_gphone_x86_64
- Mode: DEEP · Seed: 1337

## Summary

**FAIL**

## Feature Discovery

- Discovered elements (peak on one screen): 1
- Tested: 11
- Skipped by safety engine: 0
- Screens seen: 2
- Confirmation flows cancelled safely: 0

## Crashes

- none

## ANR

- none

## Lifecycle

- pass: 6 · fail: 1

## Permissions

- discovered: 8 (runtime grant/deny handled by the OS dialogs; dangerous prompts cancelled by the safety engine)

## Network

- pass: 0 · fail: 0

## Persistence

- verified via force-stop → restart → alive + usable UI

## Visual

- screenshots: 3

## Random Exploration

- seed 1337 (replay with --seed 1337)

## Auto Fixes

- last action: failure (see action-trace.json)

## Regression Tests

- every fixed failure re-runs the failed action + full regression via the CI loop

## Remaining Problems

- [dead] app process is gone
- [lifecycle] force-stop: process died after lifecycle force-stop

## Final Recommendation

**DO NOT RELEASE**
