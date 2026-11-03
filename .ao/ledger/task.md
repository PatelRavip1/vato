# Task: backend-sensevoice

You are the Backend & Proxy Worker for Vato.
Target files: server/src/main.rs, server/Cargo.toml, and server/sensevoice.py.
Goal: Implement Option 1 SenseVoice-Small pipeline for Vato:
1. SenseVoice-Small model & runner:
   - Implement local SenseVoice-Small runner (e.g. via sherpa-onnx with uv run --python 3.12 --with sherpa-onnx or standalone script) with low-latency (<60ms) transcription and emotion tags preserved ([HAPPY], [SAD], [ANGRY], [NEUTRAL], [LAUGHTER], etc.).
2. Proxy audio endpoint:
   - Add POST /v1/audio/transcriptions endpoint to vato-proxy (server/src/main.rs).
   - Accept audio payloads, run SenseVoice transcription, and return JSON: {"text": "...", "emotion": "..."}.
3. Emotion-aware prompt:
   - Update Vato system prompt in server/src/main.rs so unqwen / omp leverages emotion tags for empathetic and context-aware responses.
4. Ledger & verification:
   - Maintain worktree ledger at .ao/ledger/ (task.md, plan.md, notes.md, tasks.json).
   - Verify with cargo check and cargo test in server/.
   - Commit cleanly to your branch.
