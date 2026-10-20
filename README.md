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

## Prerequisites & Setup (Fresh System)

### 1. Backend Proxy (`vato-proxy`)
- **Rust & Cargo:** 1.70+ (`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`)
- **C compiler / headers:** `gcc` / `clang` / `build-essential` (for `libc` bindings)
- **OMP CLI:** `omp` installed and available in `$PATH` (or specified via `OMP_BIN`)
- **Model:** A working model configured in `omp` (defaults to `unqwen`, override with `VATO_MODEL=<name>`)

### 2. Android APK Build
No Android Studio or Gradle needed — builds entirely via standard CLI tools:
- **JDK 17+ or 21:** `javac` and `keytool` in `$PATH` or `$JAVA_HOME/bin`
- **Android SDK:** Set `ANDROID_HOME` (defaults to `~/.android/sdk`):
  - Platform: `platforms/android-34/android.jar`
  - Build Tools: `build-tools/34.0.0` (must contain `aapt2`, `d8`, `zipalign`, `apksigner`)
- **Utilities:** `bash`, `zip`, `du`

Install Android SDK CLI components via `sdkmanager`:
```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### 3. Android Client Device
- Android 7.0+ (API 24+) phone or emulator
- Connected to the same local Wi-Fi or Tailscale network as your host

---

## Build & Run

**1. Start the proxy:**
```bash
cd server && cargo run --release   # listens on port 8080
```

**2. Build the APK:**
```bash
./build.sh   # builds vato.apk; generates debug keystore automatically on first run
```

**3. Install on phone:**
```bash
adb install -r vato.apk
# Or navigate to http://<host-ip>:8080/download on your phone's browser
```

## Config knobs

- Model / `omp` path: `OMP_BIN`, `VATO_MODEL` environment variables (defaults: `omp`, `unqwen`).
- Default server the app dials: `DEFAULT_SERVER` in `assets/index.html` (configurable via in-app Settings).

## Security & Network Exposure

`vato-proxy` binds across all local IPv4 interfaces on port 8080 with `Access-Control-Allow-Origin: *` and **no authentication**. Incoming prompts are forwarded directly to `omp`.

- **Local LAN / Tailscale only:** Designed strictly for a trusted local home Wi-Fi or private Tailscale VPN.
- **Do not expose to public internet:** Never port-forward port 8080 or run `vato-proxy` on a public IP (e.g., VPS / cloud host) without an authenticating reverse proxy.
- **Shared Wi-Fi warning:** On untrusted or public Wi-Fi networks (cafes, dorms, coworking spaces), other devices on the same subnet can reach the proxy and trigger queries.
