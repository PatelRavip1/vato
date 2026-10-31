# Plan

- [x] Step 1: Inspect codebase and ideate in notes.md
  - Verified ADB device connection to Pixel 10 Pro XL (192.168.6.111:44337)
  - Verified package `com.vato.qwenchat` and activity `MainActivity`
  - Tested UI dump and screencap functionality
- [x] Step 2: Implement minimal test runner `tests/test_mobile_app.sh`
  - a) Device ping & adb connection state check (192.168.6.111:44337)
  - b) Package installation check for `com.vato.qwenchat`
  - c) Timed launch of `MainActivity` with `am start -W`
  - d) Process alive verification with `pidof`
  - e) Logcat scan for zero fatal crash / AndroidRuntime exceptions
  - f) UI hierarchy dump via `uiautomator dump` and pull, asserting essential elements
  - g) Screencap capture (`vato_test_screen.png`) pulled to `tests/artifacts/`
- [x] Step 3: Run automated verification tests
  - Executed `tests/test_mobile_app.sh` against live Pixel 10 Pro XL
  - Warm and cold starts verified; all 7 assertion steps passed with exit code 0
  - Artifacts pulled: `tests/artifacts/window_dump.xml` and `tests/artifacts/vato_test_screen.png`
- [x] Step 4: Update ledger and commit to `ao/mobile-e2e-test`
