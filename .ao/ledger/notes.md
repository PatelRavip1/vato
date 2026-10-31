# Ideation & Notes: Native Android TTS Enhancements

## Context
Implement Native Android TTS enhancements in `src/com/vato/qwenchat/MainActivity.java` for chat auto-speak.
Requirements:
1. TTS Start Event: `TtsListener.onStart(String utteranceId)` invokes `window.__onTtsStart && window.__onTtsStart("...")`.
2. Audio Focus & Ducking: In `speak()` when `!callMode`, request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` via `AudioManager`. Abandon audio focus in `notifyDone()` and `notifyError()` (and `stopSpeaking()`).
3. Language Support Query: Add `@JavascriptInterface public boolean isLanguageSupported(String lang)` returning `tts != null && ttsReady && tts.isLanguageAvailable(toLocale(lang)) >= TextToSpeech.LANG_AVAILABLE`.

## Candidate Approaches

### Approach 1: Minimal Direct AudioManager Request / Abandon (Selected)
- Reuse or instantiate `audio` in `VoiceBridge` (already initialized in `setCallMode`, can ensure `getAudio()` or lazy init).
- In `speak()`:
  - If `!callMode`: `ensureAudio(); if (audio != null) audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);`
- In `notifyDone()` and `notifyError()`:
  - If `!callMode && audio != null`: `audio.abandonAudioFocus(null);`
  - Also in `stopSpeaking()` if audio focus was held or abandon unconditionally if `!callMode`.
- In `TtsListener.onStart(String id)`:
  - Call `bridge.notifyStart(id)` which posts `window.__onTtsStart && window.__onTtsStart("...")`.
- In `isLanguageSupported(String lang)`:
  - `return tts != null && ttsReady && tts.isLanguageAvailable(toLocale(lang)) >= TextToSpeech.LANG_AVAILABLE;`
- **Pros**: Minimal diff, uses existing Android platform API compatible with API 21+ / Android 8+. Clean and direct.
- **Cons / Pitfalls**: Ensure audio focus listener parameter `null` is allowed by `requestAudioFocus` (it is valid in Android `AudioManager.requestAudioFocus(OnAudioFocusChangeListener, int, int)` when not monitoring focus changes, or provide a no-op listener `focusChange -> {}`).

### Approach 2: AudioFocusRequest with AudioAttributes (API 26+)
- Use `AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)`.
- **Pros**: Modern Android API.
- **Cons**: Adds boilerplate, requires conditional SDK version check (`Build.VERSION.SDK_INT >= 26`) or causes deprecation warnings, more lines of code.

### Approach 3: Singleton Audio Focus Manager
- Over-engineered wrapper class managing focus states.
- **Cons**: Violates ponytail principles. Unnecessary abstraction.

## Selected Direction
Approach 1: Direct `requestAudioFocus` / `abandonAudioFocus` with a no-op listener or null. Safe across Android versions, smallest diff, zero unnecessary abstraction.
