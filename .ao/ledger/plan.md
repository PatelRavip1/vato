# Plan: merge-sensevoice

- [x] Step 1: Merge `ao/backend-sensevoice` into integration branch.
- [x] Step 2: Merge `ao/client-sensevoice` and resolve any ledger conflicts.
- [x] Step 3: Run `cargo check` and `cargo test` in `server/`.
- [x] Step 4: Run `bash build.sh` to compile `vato.apk`.
- [x] Step 5: Install APK to running emulator (`emulator-5554`).
- [x] Step 6: Execute `DEVICE=emulator-5554 bash tests/test_mobile_app.sh` and ensure all 7 verification steps pass.
- [x] Step 7: Update `master` branch cleanly and finalize ledger.
