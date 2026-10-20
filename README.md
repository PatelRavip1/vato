# Vato

Minimal Android chat client for a **local** LLM. A tiny Java WebView app ships a
chat UI that talks to a Rust proxy exposing an OpenAI-compatible API; the proxy
shells out to the local `omp` CLI to run the model. No cloud, no SDK/Gradle —
just `aapt2`/`javac`/`d8` + `cargo`.

## Architecture

```
┌────────────────────────┐   HTTP    ┌──────────────────────┐  spawns  ┌────────────┐
│ Android WebView (Java) │ ────────► │ vato-proxy (Rust)    │ ───────► │ omp CLI     │
│  assets/index.html     │  /v1/...  │  :8080               │          │ (model:      │
│  chat UI               │ ◄──────── │  OpenAI-compat API   │ ◄──────── │  unqwen)     │
└────────────────────────┘           └──────────────────────┘          └────────────┘
```

1. **Android app** (`src/com/vato/qwenchat/MainActivity.java`) — single `Activity`
   wrapping a `WebView` that loads the bundled chat UI (`assets/index.html`). Handles
   file upload (`onShowFileChooser`), downloads, and back-navigation.
2. **WebView UI** posts to `/v1/chat/completions`; server URL is selectable via chips
   (Tailscale / LAN / localhost) in the UI.
3. **`vato-proxy`** (`server/`) — stdlib-only Rust (no async runtime). Exposes:
   - `GET  /v1/models` — model listing
   - `POST /v1/chat/completions` — builds a prompt (system + recent history + user msg)
     and runs `omp -p --model unqwen -e extensions/sqz.ts`, returning a clean reply.

## Layout

| Path | What |
|------|------|
| `AndroidManifest.xml` | package `com.vato.qwenchat`, minSdk 24, targetSdk 34 |
| `src/com/vato/qwenchat/MainActivity.java` | WebView host + file chooser / download handling |
| `assets/index.html` | bundled chat UI (the page the WebView loads) |
| `server/src/main.rs` | `vato-proxy` — OpenAI-compat HTTP → `omp` bridge |
| `build.sh` | aapt2 → javac → d8 → zipalign → apksigner pipeline |

## Build & run

**Proxy**
```bash
cd server && cargo run --release   # listens on :8080
```

**APK**
```bash
./build.sh   # → vato.apk
```
Needs Android build-tools `34.0.0`, platform `android-34`, and JDK 17+.
The debug keystore is auto-generated on first run.

## Config knobs

- Model / `omp` path: `OMP_BIN`, `VATO_MODEL` environment variables (defaults: `omp`, `unqwen`).
- Default server the app dials: `DEFAULT_SERVER` in `assets/index.html` (configurable via in-app Settings).
