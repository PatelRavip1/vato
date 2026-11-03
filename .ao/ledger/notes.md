# Ideation Notes: merge-sensevoice

## Candidate Approaches

- **Approach A (Sequential Git Merge & Ledger Consolidation - Recommended)**:
  1. Merge `ao/backend-sensevoice` into `ao/merge-sensevoice` (clean merge, no code conflict).
  2. Merge `ao/client-sensevoice` into `ao/merge-sensevoice` (conflict limited to `.ao/ledger/*`).
  3. Resolve ledger conflict by checking out / committing integration ledger tracking the combined SenseVoice capability.
  4. Run `cargo check` and `cargo test` in `server/`.
  5. Run `bash build.sh` to produce `vato.apk`.
  6. Install on emulator (`adb -s emulator-5554 install -r vato.apk`) and run `DEVICE=emulator-5554 bash tests/test_mobile_app.sh` (verify all 7 steps).
  7. Update `master` to reflect the integrated commit.

- **Approach B (Octopus Merge)**:
  Attempt `git merge ao/backend-sensevoice ao/client-sensevoice`.
  Downside: Git octopus merge refuses to resolve conflicts automatically when `.ao/ledger/*` differs across heads, requiring abort and fallback to sequential merge.

- **Approach C (Cherry-pick / Rebase onto master)**:
  Cherry-pick commits `67c0176` and `d29ed80`.
  Downside: Loses true merge commit ancestry and branch topology; deviates from standard integration PR/merge practice.

## Selected Approach
Approach A: Clean sequential merge preserving commit history, explicit ledger conflict resolution, followed by the full verification suite (server cargo tests, APK build, emulator installation, and 7-step E2E test).

## Pitfalls & Edge Cases
- **Ledger Conflicts**: Both feature branches touched `.ao/ledger/*`. Must resolve cleanly without dropping integration ledger state.
- **Server Dependencies**: Verify `server/src/main.rs` and `server/Cargo.toml` compile cleanly without missing crates or broken types.
- **Android Asset Packaging**: Ensure `assets/index.html` changes from `client-sensevoice` package properly into `vato.apk`.
- **Emulator UI Readiness**: Emulator `emulator-5554` must be unlocked and responsive to `adb shell input` for `tests/test_mobile_app.sh` 7-step assertions.
- **Master Branch Synchronization**: Root repo `/home/ravi/projects/server/vato` has `master` checked out. Must cleanly fast-forward/update `master` once verified.
