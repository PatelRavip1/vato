# Ideation Notes: backend-sensevoice

## Candidate Approaches

### Approach A: Persistent Python Runner with CLI Fallback (Chosen)
- `server/sensevoice.py` uses `sherpa-onnx` (int8 quantized SenseVoice-Small model) with `soundfile`.
- Supports two modes:
  1. Daemon mode (`--daemon`): Model loaded once into memory; reads audio file paths from stdin line-by-line and outputs JSON results line-by-line over stdout. Measured latency: ~30ms for 2s speech (well within <60ms requirement).
  2. One-shot CLI mode (`sensevoice.py <audio_path>`): Direct standalone execution returning JSON.
- `vato-proxy` maintains a thread-safe worker handle to the daemon with automatic restart and one-shot fallback.
- Deliberate simplification: single persistent worker with mutex (`# ponytail: single worker lock, pool if concurrent STT spikes`).

### Approach B: Per-Request One-Shot CLI Invocation
- Spawn `uv run --python 3.12 --with sherpa-onnx python sensevoice.py <audio>` on every request.
- Simpler process lifecycle in Rust, but Python + ONNX model loading adds 250-400ms overhead per call, failing the <60ms requirement.

### Approach C: Pure Rust In-Process via `ort` Crate
- Add `ort` crate and re-implement mel-filterbank feature extraction and CTC/tokenizer in Rust.
- High complexity, requires downloading and linking native ONNX runtime C headers/libs, prone to build environment breakage.

## Edge Cases & Pitfalls
- **Audio Formats**: Client audio may arrive as multipart/form-data, raw audio/wav binary, or base64 JSON. Proxy must handle all three cleanly.
- **Emotion Tag Normalization**: SenseVoice yields `<|HAPPY|>`, `<|SAD|>`, `<|ANGRY|>`, `<|NEUTRAL|>`, `<|Laughter|>`. These must be mapped cleanly to `[HAPPY]`, `[SAD]`, `[ANGRY]`, `[NEUTRAL]`, `[LAUGHTER]` and reflected in both `emotion` and `text` fields so UI badges and LLM system prompt observe them.
- **Process Crash / Pipe Broken**: If Python daemon crashes or pipe closes, proxy must safely reap child, restart daemon, or fallback to one-shot execution without failing user requests.
- **Concurrency**: Guard daemon stdin/stdout with Mutex to prevent interleaved commands.
