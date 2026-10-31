# Task: native-tts-focus

Implement Native Android TTS enhancements in src/com/vato/qwenchat/MainActivity.java for chat auto-speak.

# Context & Goals
- Project: /home/ravi/projects/vato
- Target file: src/com/vato/qwenchat/MainActivity.java
- Work exclusively on the Native Android Java slice without conflicting with assets/index.html.

# Requirements
1. TTS Start Event:
   - In VoiceBridge.TtsListener (or utterance listener), implement onStart(String utteranceId) to invoke JS callback:
     main.post(() -> js("window.__onTtsStart && window.__onTtsStart(" + JSONObject.quote(utteranceId) + ")"));
2. Audio Focus & Ducking:
   - When VoiceBridge.speak() is called (when not in full callMode), request transient audio focus with ducking (AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) so other apps/music duck gracefully while Vato reads chat responses.
   - In notifyDone() and notifyError(), abandon audio focus cleanly.
3. Language Support Query:
   - Add @JavascriptInterface public boolean isLanguageSupported(String lang):
     Checks tts != null && ttsReady && tts.isLanguageAvailable(toLocale(lang)) >= TextToSpeech.LANG_AVAILABLE.
4. Build & Verify:
   - Compile Java with ./build.sh (or javac --release 8 -cp "$PLATFORM" -d obj src/com/vato/qwenchat/MainActivity.java).
   - Ensure clean build with no Java errors.
   - Track progress in .ao/ledger/ (task.md, plan.md, notes.md, tasks.json).
   - Commit cleanly to branch ao/native-tts-focus.
