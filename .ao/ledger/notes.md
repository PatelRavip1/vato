# Ideation Notes

## Candidate Approaches
- **Approach A: Pure Bash + ADB Script (Selected)**
  - Self-contained executable script `tests/test_mobile_app.sh` utilizing standard `adb` CLI commands with `set -euo pipefail`.
  - Directly handles connection verification, package query, timing capture with `am start -W`, process check with `pidof`, logcat crash scanning, UI hierarchy dump verification, and screencap artifact retrieval.
  - Minimal diff, zero external dependencies beyond `adb`, standard coreutils, and bash.

- **Approach B: Python-based ADB Runner**
  - Python test runner calling `adb` via `subprocess` or `adbutils`.
  - More structured assertions but introduces Python environment dependency and unnecessary boilerplate.

- **Approach C: On-Device Shell Script**
  - Runs inside `adb shell` entirely.
  - Artifact retrieval to host requires separate host-side pulling commands, splitting logic into two steps.

## Edge Cases & Pitfalls
1. **ADB Wi-Fi State & Reconnection**:
   - Device may drop Wi-Fi socket if idle. Script checks `adb get-state` and attempts `adb connect 192.168.6.111:44337` if not connected.
2. **Screen Off / Lock State**:
   - `uiautomator dump` fails if the device screen is off. Script ensures wake-up via `input keyevent KEYCODE_WAKEUP` and `wm dismiss-keyguard`.
3. **Stale Logcat Records**:
   - Logcat may contain crashes from earlier runs. Script clears logcat prior to launch (`adb logcat -c`) or filters crash buffers (`-b crash`) and checks errors during the test run.
4. **UI Hierarchy Timing**:
   - WebView takes milliseconds to initialize. `am start -W` waits for launch, plus a short 1-second settle time before `uiautomator dump`.
5. **UI Assertion Criteria**:
   - App renders via root WebView inside `android:id/content` for package `com.vato.qwenchat`. Script asserts package, content frame, and `WebView` presence in the dumped XML.
