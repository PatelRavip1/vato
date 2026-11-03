# Task: merge-sensevoice

You are the Integration & Merge Worker for Vato.
Goal: Merge SenseVoice-Small slices, verify build and on-device test on running emulator, and update master:
1. Merge branches:
   - Merge 'ao/backend-sensevoice' and 'ao/client-sensevoice' cleanly into master (resolve any minor conflicts in docs or cargo).
2. Verification:
   - Run 'cargo check' and 'cargo test' in server/.
   - Run 'bash build.sh' to build vato.apk.
   - Install to running emulator: 'adb -s emulator-5554 install -r vato.apk'.
   - Run 'DEVICE=emulator-5554 bash tests/test_mobile_app.sh' and ensure all 7 verification steps pass.
3. Ledger:
   - Update .ao/ledger/ (task.md, plan.md, tasks.json) documenting successful integration and emulator verification.
   - Commit cleanly.
