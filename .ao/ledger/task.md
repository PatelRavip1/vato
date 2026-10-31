# Task: mobile-e2e-test

Create an automated on-device test runner script and execute full mobile app tests on the connected Pixel 10 Pro XL phone.

# Context & Goals
- Project: /home/ravi/projects/vato
- Target Device: Pixel 10 Pro XL at 192.168.6.111:44337 (ADB Wi-Fi debugging)
- Package Name: com.vato.qwenchat
- Main Activity: com.vato.qwenchat/.MainActivity

# Requirements
1. Test Runner Script:
   - Create tests/test_mobile_app.sh (executable) that automates:
     a) Device ping & adb connection state check (192.168.6.111:44337).
     b) Verification that package com.vato.qwenchat is installed.
     c) Launch MainActivity with timing (adb -s 192.168.6.111:44337 shell am start -W -n com.vato.qwenchat/.MainActivity).
     d) Verification that the process is alive (adb shell pidof com.vato.qwenchat).
     e) Logcat scan for zero fatal crash / AndroidRuntime exceptions.
     f) UI hierarchy dump via uiautomator dump (/sdcard/window_dump.xml) and pull, asserting essential elements exist.
     g) Screencap capture (/sdcard/vato_test_screen.png) and pull into tests/artifacts/.
2. Run & Verify:
   - Execute tests/test_mobile_app.sh immediately against the live phone.
   - Assert all tests pass with exit code 0.
3. Ledger:
   - Track progress in .ao/ledger/ (task.md, plan.md, notes.md, tasks.json).
   - Commit cleanly to branch ao/mobile-e2e-test.
