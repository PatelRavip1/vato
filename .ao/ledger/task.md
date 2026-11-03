# Task: audit-doc

Create audit_date.md (and symlink or copy to audit_2026-11-03.md for date stamp: 2026-11-03) in the root of vato documenting the full code audit and remediation details:
1. Executive Summary & Audit Date (2026-11-03).
2. Backend Proxy Audit & Fixes (server/src/main.rs, server/Cargo.toml):
   - Query routing fix (split_once('?'))
   - CLI flag injection delimiter ('--') and '@' sanitization
   - Mutex concurrency guard around OMP
   - Multi-image leak cleanup and body pre-allocation bounds
3. Android Host & Security (MainActivity.java, AndroidManifest.xml):
   - Disabled setAllowFileAccess and setMixedContentMode
   - Gated web debugging behind FLAG_DEBUGGABLE
   - Full onDestroy() teardown (webView, TTS, SpeechRecognizer, file chooser reset)
   - android:allowBackup set to false
4. Frontend Chat UI (assets/index.html):
   - Safe textContent update banner (XSS fix)
   - Markdown codeblock '$' token regex corruption fix
   - AbortSignal.timeout(30000) fetch deadlock prevention
   - Array.isArray() chat history guard and error bubble isolation
   - Viewport zoom accessibility restoration
5. Verification Proof:
   - cargo check / cargo test results (5 passed)
   - build.sh APK build results
Commit audit_date.md to the branch and update .ao/ledger/.
