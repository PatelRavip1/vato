# Task: auto-speak

Implement Auto-Speak toggle and bubble speak in regular chat interface (assets/index.html) so users can type or voice-dictate via Gboard in Gujarati/English and automatically hear the assistant's response via Android TextToSpeech.

# Context & Interfaces
- Root: /home/ravi/projects/vato
- Android bridge: window.AndroidVoiceBridge is exposed by src/com/vato/qwenchat/MainActivity.java.
  Available JS methods:
  - voiceBridge.speak(text, lang, utteranceId) (lang: 'gu-IN' or 'en-IN')
  - voiceBridge.stopSpeaking()
  - voiceBridge.isTtsReady()
  - Global callbacks: window.__onTtsDone(id) and window.__onTtsError(id, code).
- UI file: assets/index.html
  - Header actions: #liveTalkBtn (WhatsApp Voice Call button), #updateAppBtn.
  - Chat loop: sendMessage() handles user message submission, calls /v1/chat/completions, appends assistant bubble (botMsg), and saves to history.
  - Bubble rendering: appendMessageUI(msg, scroll).

# Requirements
1. Auto-Speak Toggle in Header:
   - Add a speaker toggle button in .header-actions beside #liveTalkBtn with a clean SVG speaker icon (speaker-with-sound waves when ON, speaker-muted when OFF).
   - Tooltip / title: 'Auto-Speak Responses'.
   - State persisted in localStorage under key vato_auto_speak (default true). Clicking toggles state and immediately calls voiceBridge.stopSpeaking() if turning off.
2. Auto-TTS on AI Response:
   - In sendMessage(), after assistant message is received and rendered:
     If autoSpeakEnabled is true, trigger voiceBridge.speak(cleanText, lang, 'chat-reply-' + Date.now()).
   - Language detection: if cleanText contains Gujarati Unicode characters [\u0A80-\u0AFF], set lang = 'gu-IN', else 'en-IN'.
   - Text cleaning: write helper function stripMarkdownForTTS(text) to remove Markdown syntax (code blocks, backticks, bold/italics markers, table pipes, markdown links [text](url) -> text, image links ![alt](url) -> empty, raw HTML tags) and collapse whitespace so TTS reads natural conversational speech.
3. Bubble Speak Button:
   - On assistant message bubbles (.message-row.assistant .bubble), add a subtle speak icon button allowing manual tap-to-speak or tap-to-stop for that specific bubble.
4. Interruption / Stop Speaking:
   - When the user sends a new message or switches screens / opens Live Call mode, call voiceBridge.stopSpeaking().
5. Ledger & Verification:
   - Use .ao/ledger/ (task.md, plan.md, notes.md, tasks.json) to track progress.
   - Run ./build.sh to compile, DEX, and package vato.apk.
   - Check adb devices -l and install the newly built APK onto the connected phone:
     adb -s 192.168.6.111:44337 install -r vato.apk
   - Verify Success from the installation.
   - Commit changes to the worktree branch.
