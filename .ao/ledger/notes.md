# Ideation Notes: client-sensevoice

## Candidate Approaches

- **Approach A (Dual Audio Pipeline: Native AudioRecord in MainActivity + UI Transcription Handling)**:
  `MainActivity.java`'s `VoiceBridge` records 16kHz 16-bit mono PCM via `AudioRecord`, encodes a 44-byte RIFF WAV in-memory, and posts it directly to `${serverUrl}/v1/audio/transcriptions` via background `HttpURLConnection`. When transcription returns `{"text": "...", "emotion": "..."}`, it dispatches to `window.__onVoiceFinal(text, emotion)`. In `assets/index.html`, pass `serverUrl` to `VoiceBridge.startListening(lang, serverUrl)`, provide a web-level `sendAudioForTranscription` helper, and parse `[EMOTION]` tokens into CSS emotion badges (`[HAPPY]`, `[SAD]`, `[ANGRY]`, `[NEUTRAL]`, etc.).

- **Approach B (Web MediaRecorder only inside WebView)**:
  Rely purely on browser `navigator.mediaDevices.getUserMedia` and `MediaRecorder` in `index.html`.
  *Pitfall*: Android WebView on `file:///android_asset/` is an insecure context (`isSecureContext === false`), which completely blocks `navigator.mediaDevices.getUserMedia` in modern Chromium.

- **Approach C (Android SpeechRecognizer only)**:
  Use Google's built-in `SpeechRecognizer`.
  *Pitfall*: Does not route audio to `/v1/audio/transcriptions` on vato-proxy and cannot produce SenseVoice emotion tags.

## Selected Approach
Approach A: Native Android `AudioRecord` produces pure 16kHz mono WAV audio, routes cleanly to vato-proxy `/v1/audio/transcriptions`, and UI renders responsive emotion badges for `[HAPPY]`, `[ANGRY]`, `[SAD]`, etc.

## Edge Cases & Pitfalls
- **Emulator Ping in test runner**: When `DEVICE="emulator-5554"`, pinging `DEVICE_IP` fails because it defaults to `192.168.6.111`. Need to detect `emulator-*` and ping `127.0.0.1` or bypass ICMP ping.
- **Microphone Permissions**: Ensure runtime permission `RECORD_AUDIO` is checked and requested before recording starts.
- **WAV Header Generation**: 44-byte RIFF header must have correct `sampleRate=16000`, `channels=1`, `bitsPerSample=16`, and byte lengths.
- **Emotion Badge Sanitization**: Prevent XSS by escaping text before wrapping in emotion badge spans, or transform within `renderMarkdown` after entity escaping.
