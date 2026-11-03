# Task: client-sensevoice

You are the Android & UI Client Worker for Vato.
Target files: assets/index.html, src/com/vato/qwenchat/MainActivity.java, tests/test_mobile_app.sh.
Goal: Support voice audio with SenseVoice transcription and verify on emulator:
1. Android & UI Integration:
   - In assets/index.html and MainActivity.java: Ensure audio recorded from user is routed to /v1/audio/transcriptions on vato-proxy.
   - Render emotion badges (e.g. [HAPPY], [ANGRY], [SAD], etc.) in the chat UI when present in transcription.
2. Emulator & Test Runner:
   - Update tests/test_mobile_app.sh to support DEVICE="emulator-5554" (bypass ICMP ping or ping 127.0.0.1 when DEVICE is emulator).
   - Compile APK with .
   - Install APK on running emulator via Performing Streamed Install.
   - Run  and ensure all 7 verification steps pass.
3. Ledger & verification:
   - Maintain worktree ledger at .ao/ledger/ (task.md, plan.md, notes.md, tasks.json).
   - Commit cleanly to your branch.
