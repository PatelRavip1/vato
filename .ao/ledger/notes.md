# Ideation & Verification Notes: bump-version

## Candidate Approaches
- **Approach A (Selected & Verified)**:
  - Update `AndroidManifest.xml`: `android:versionCode="5"`, `android:versionName="1.4.0"`.
  - Update `assets/index.html`: Update UI display `Version 1.4.0` (line 1107) and internal update guard `CURRENT_VERSION = 5` (line 1997).
  - Build `vato.apk` with `bash build.sh` using repository `debug.keystore`.
  - Install to running emulator (`emulator-5554`) with `adb -s emulator-5554 install -r vato.apk`.
  - Verify with dumpsys package output and run `DEVICE=emulator-5554 bash tests/test_mobile_app.sh`.
  - Fast-forward `master` to match cleanly.
- **Approach B (Partial UI update)**:
  - Update `AndroidManifest.xml` and only text `Version 1.4.0` in `assets/index.html`.
  - Pitfall: Leaves `CURRENT_VERSION = 4` in JS, causing update logic to misidentify version 5.
- **Approach C (Broader scope - Server update)**:
  - Also update `server/src/main.rs` version endpoint.
  - Pitfall: Exceeds task scope ("Update Android app version"), breaks existing Rust tests asserting `1.3.0`.

## Verification Results
1. **Manifest & Badging Inspection**:
   - `aapt2 dump badging vato.apk`: `package: name='com.vato.qwenchat' versionCode='5' versionName='1.4.0'`
2. **Installation & Package Dumpsys**:
   - `adb -s emulator-5554 shell dumpsys package com.vato.qwenchat | grep -iE 'versionCode|versionName'`:
     - `versionCode=5 minSdk=24 targetSdk=34`
     - `versionName=1.4.0`
3. **On-Device E2E Test Suite (`tests/test_mobile_app.sh`)**:
   - Step 1: Device ping & ADB connection state verified (`device`).
   - Step 2: Package installation verified (`com.vato.qwenchat`).
   - Step 3: Activity cold launch status confirmed OK.
   - Step 4: Process PID confirmed alive.
   - Step 5: Logcat scan: 0 fatal crashes, 0 AndroidRuntime exceptions.
   - Step 6: UI hierarchy dump validated: package, WebView, and content root present.
   - Step 7: Screencap captured (`tests/artifacts/vato_test_screen.png`).
   - Status: ALL TESTS PASSED.
