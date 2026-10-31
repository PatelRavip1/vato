# Implementation Plan: Native Android TTS Enhancements

## Steps
1. [x] Inspect MainActivity.java and test build setup via ./build.sh
2. [x] Record candidate approaches and notes in .ao/ledger/notes.md
3. [x] Edit `MainActivity.java`:
   - Add `notifyStart(String id)` in `VoiceBridge` invoking JS `window.__onTtsStart && window.__onTtsStart(...)`
   - Wire `TtsListener.onStart(String id)` -> `bridge.notifyStart(id)`
   - Implement audio focus request with `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` when speaking (if `!callMode`)
   - Abandon audio focus in `notifyDone()`, `notifyError()`, and `stopSpeaking()` when `!callMode`
   - Implement `@JavascriptInterface public boolean isLanguageSupported(String lang)`
4. [x] Build & Verify with `./build.sh`
5. [x] Commit cleanly to branch `ao/native-tts-focus`
