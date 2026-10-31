# Ideation Notes: Auto-Speak & Bubble Speak in Regular Chat

## Candidate Approaches

### Approach 1: Inline TTS helper with state tracking on active bubble & speech synthesis callback
- Auto-Speak toggle button in header `.header-actions` beside `#liveTalkBtn` with localStorage persistence (`vato_auto_speak`, default `true`).
- When auto-speak is enabled, `sendMessage()` extracts clean text via `stripMarkdownForTTS(text)` and calls `voiceBridge.speak(cleanText, lang, 'chat-reply-' + Date.now())`.
- For bubble speak: add a `.bubble-speak-btn` inside `.bubble-meta` on assistant bubbles. Clicking toggles speak/stop for that bubble's content, changing icon state (playing vs idle).
- Global callbacks `window.__onTtsDone(id)` and `window.__onTtsError(id)` update active bubble icon state when playback finishes.
- Interruption: `sendMessage()`, opening Live Call, or clicking stop stops ongoing TTS.
- Tradeoffs: Simple, zero external dependencies, robust lifecycle handling, natural UX.

### Approach 2: Global Audio player manager object
- Create a complex state machine class managing queues, pausing, resume, utterance listeners, and audio visualization.
- Tradeoffs: Over-engineered for a simple Text-To-Speech bridge interface with queue flush. Violates Ponytail simplicity.

## Chosen Approach
Approach 1: Direct, robust, minimal code. Uses existing `window.AndroidVoiceBridge` directly.

## Edge Cases & Pitfalls
- `stripMarkdownForTTS`: Must handle code blocks (` ```...``` `), inline code (`` `...` ``), markdown links (`[text](url)` -> `text`), image tags (`![alt](url)` -> empty), bold/italics (`**`, `*`, `__`, `_`, `~~`), headers (`#+ `), blockquotes (`>`), HTML tags (`<...>`), table markers (`|`), and multiple consecutive whitespaces/newlines.
- Language detection: Regex `/[\u0A80-\u0AFF]/` for Gujarati (`gu-IN`), fallback to `en-IN`.
- Safety when `window.AndroidVoiceBridge` is undefined (browser preview/testing): guard with optional chaining or existence checks.
- Interruption: On `sendMessage()`, `liveStart()`, or toggle OFF, immediately call `voiceBridge.stopSpeaking()`.
