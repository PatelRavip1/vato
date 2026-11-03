# Plan: merge-sensevoice

- [ ] Step 1: Merge `ao/backend-sensevoice` into integration branch.
- [ ] Step 2: Merge `ao/client-sensevoice` and resolve any ledger conflicts.
- [ ] Step 3: Run `cargo check` and `cargo test` in `server/`.
- [ ] Step 4: Run `bash build.sh` to compile `vato.apk`.
- [ ] Step 5: Install APK to running emulator (`emulator-5554`).
- [ ] Step 6: Execute `DEVICE=emulator-5554 bash tests/test_mobile_app.sh` and ensure all 7 verification steps pass.
- [ ] Step 7: Update `master` branch cleanly and finalize ledger.
