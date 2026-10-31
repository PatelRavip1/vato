package com.vato.qwenchat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
import android.view.WindowManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private static final int FILECHOOSER_RESULTCODE = 1001;
    private static final int VOICE_PERM_REQUEST = 1002;
    private VoiceBridge voiceBridge;

    static class CustomWebChromeClient extends WebChromeClient {
        private final MainActivity activity;

        public CustomWebChromeClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (activity.uploadMessage != null) {
                activity.uploadMessage.onReceiveValue(null);
                activity.uploadMessage = null;
            }
            activity.uploadMessage = filePathCallback;

            Intent intent = null;
            try {
                intent = fileChooserParams.createIntent();
            } catch (Exception e) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
            }

            try {
                activity.startActivityForResult(intent, FILECHOOSER_RESULTCODE);
            } catch (Exception e) {
                if (activity.uploadMessage != null) {
                    activity.uploadMessage.onReceiveValue(null);
                    activity.uploadMessage = null;
                }
                return false;
            }
            return true;
        }
    }

    static class CustomDownloadListener implements android.webkit.DownloadListener {
        private final MainActivity activity;
        public CustomDownloadListener(MainActivity activity) {
            this.activity = activity;
        }
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                activity.startActivity(intent);
            } catch (Exception ignored) {}
        }
    }
    static class CustomWebViewClient extends WebViewClient {
        private final MainActivity activity;
        public CustomWebViewClient(MainActivity activity) {
            this.activity = activity;
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    activity.startActivity(intent);
                    return true;
                } catch (Exception ignored) {}
            }
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(0xFF1F2C34);
        window.setNavigationBarColor(0xFF121B22);


        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new CustomWebViewClient(this));
        webView.setWebChromeClient(new CustomWebChromeClient(this));
        webView.setDownloadListener(new CustomDownloadListener(this));
        voiceBridge = new VoiceBridge(this);
        webView.addJavascriptInterface(voiceBridge, "AndroidVoiceBridge");
        voiceBridge.init();

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == VOICE_PERM_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (voiceBridge != null) voiceBridge.onPermissionResult(granted);
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                "(function() { var o = document.getElementById('liveTalkOverlay'); if (o && o.classList.contains('open')) { if (window.__exitLiveTalk) window.__exitLiveTalk(); return true; } var m = document.getElementById('settingsModal'); if (m && m.classList.contains('open')) { m.classList.remove('open'); return true; } return false; })()",
                value -> {
                    if (!"true".equals(value)) {
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            MainActivity.super.onBackPressed();
                        }
                    }
                }
            );
            return;
        }
        super.onBackPressed();
    }

    static class VoiceBridge {
        private final Activity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private TextToSpeech tts;
        private boolean ttsReady = false;
        private Locale pendingTtsLocale = null;
        private SpeechRecognizer recognizer;
        private boolean pendingAutoStart = false;
        private String pendingStartLang = "default";
        private AudioManager audio;
        private boolean callMode = false;
        private int prevMode = AudioManager.MODE_NORMAL;
        private boolean prevSpeaker = false;
        private void requestFocusForTts() {
            if (!callMode) {
                ensureAudio();
                if (audio != null) {
                    try { audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); } catch (Exception ignored) {}
                }
            }
        }

        private void abandonFocusForTts() {
            if (!callMode && audio != null) {
                try { audio.abandonAudioFocus(null); } catch (Exception ignored) {}
            }
        }

        private void notifyStart(String id) {
            main.post(() -> js("window.__onTtsStart && window.__onTtsStart(" + JSONObject.quote(id) + ")"));
        }

        private void notifyDone(String id) {
            abandonFocusForTts();
            main.post(() -> js("window.__onTtsDone && window.__onTtsDone(" + JSONObject.quote(id) + ")"));
        }

        private void notifyError(String id, int code) {
            abandonFocusForTts();
            main.post(() -> js("window.__onTtsError && window.__onTtsError(" + JSONObject.quote(id) + ", " + code + ")"));
        }

        VoiceBridge(Activity activity) {
            this.activity = activity;
        }

        void init() {
            main.post(() -> {
                try {
                    tts = new TextToSpeech(activity.getApplicationContext(), status -> {
                        if (status == TextToSpeech.SUCCESS) {
                            ttsReady = true;
                            if (pendingTtsLocale != null) tts.setLanguage(pendingTtsLocale);
                        }
                    });
                    tts.setOnUtteranceProgressListener(new TtsListener(this));
                } catch (Exception ignored) {}
            });
        }

        private void js(String call) {
            try { activity.runOnUiThread(() -> { if (activity instanceof MainActivity) ((MainActivity) activity).webView.evaluateJavascript(call, null); }); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean hasAudioPermission() {
            return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestAudioPermission() {
            if (hasAudioPermission()) { onPermissionResult(true); return; }
            activity.runOnUiThread(() -> activity.requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO}, VOICE_PERM_REQUEST));
        }

        @JavascriptInterface
        public void startListening(String lang) {
            final Locale locale = toLocale(lang);
            activity.runOnUiThread(() -> {
                if (!hasAudioPermission()) { pendingAutoStart = true; pendingStartLang = lang; requestAudioPermission(); return; }
                pendingAutoStart = false;
                ensureRecognizer();
                if (recognizer == null) return;
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                try { recognizer.startListening(intent); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopListening() {
            activity.runOnUiThread(() -> {
                if (recognizer != null) { try { recognizer.stopListening(); } catch (Exception ignored) {} }
            });
        }

        @JavascriptInterface
        public void speak(String text, String lang, String utteranceId) {
            final Locale locale = toLocale(lang);
            activity.runOnUiThread(() -> {
                if (tts == null) { init(); return; }
                if (!ttsReady) { pendingTtsLocale = locale; return; }
                try {
                    requestFocusForTts();
                    tts.setLanguage(locale);
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, utteranceBundle(utteranceId), utteranceId);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            activity.runOnUiThread(() -> {
                abandonFocusForTts();
                if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
            });
        }

        @JavascriptInterface
        public boolean isLanguageSupported(String lang) {
            return tts != null && ttsReady && tts.isLanguageAvailable(toLocale(lang)) >= TextToSpeech.LANG_AVAILABLE;
        }

        @JavascriptInterface
        public boolean isTtsReady() {
            return ttsReady;
        }

        @JavascriptInterface
        public void setCallMode(boolean on) {
            activity.runOnUiThread(() -> {
                ensureAudio();
                if (audio == null) return;
                try {
                    if (on && !callMode) {
                        prevMode = audio.getMode();
                        prevSpeaker = audio.isSpeakerphoneOn();
                        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        audio.setSpeakerphoneOn(true);
                        int max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                        int target = (int) Math.ceil(max * 0.9);
                        if (audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < target) {
                            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0);
                        }
                        callMode = true;
                    } else if (!on && callMode) {
                        audio.setSpeakerphoneOn(prevSpeaker);
                        audio.setMode(prevMode == AudioManager.MODE_IN_COMMUNICATION ? AudioManager.MODE_NORMAL : prevMode);
                        callMode = false;
                    }
                } catch (Exception ignored) {}
            });
        }

        void onPermissionResult(boolean granted) {
            js("window.__onVoicePermission && window.__onVoicePermission(" + granted + ")");
            if (granted && pendingAutoStart) { pendingAutoStart = false; startListening(pendingStartLang); }
        }

        private Bundle utteranceBundle(String id) {
            Bundle b = new Bundle();
            b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            return b;
        }

        private Locale toLocale(String lang) {
            try {
                if (lang == null || lang.isEmpty() || "default".equals(lang)) return Locale.getDefault();
                String[] p = lang.contains("-") ? lang.split("-") : new String[]{lang};
                return p.length >= 2 ? new Locale(p[0], p[1]) : new Locale(p[0]);
            } catch (Exception e) { return Locale.ENGLISH; }
        }

        private void ensureAudio() {
            if (audio == null) {
                try { audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE); } catch (Exception ignored) {}
            }
        }

        private void ensureRecognizer() {
            if (recognizer != null) return;
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity.getApplicationContext());
                recognizer.setRecognitionListener(new VoiceRecognitionListener(this));
            } catch (Exception ignored) {}
        }

        static class VoiceRecognitionListener implements RecognitionListener {
            private final VoiceBridge bridge;
            VoiceRecognitionListener(VoiceBridge bridge) { this.bridge = bridge; }
            private String firstResult(Bundle b) {
                ArrayList<String> r = b != null ? b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                return (r != null && !r.isEmpty()) ? r.get(0) : "";
            }
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int code) {
                final int c = code;
                bridge.main.post(() -> bridge.js("window.__onVoiceError && window.__onVoiceError(" + c + ")"));
            }
            @Override public void onPartialResults(Bundle b) {
                String t = firstResult(b);
                bridge.main.post(() -> bridge.js("window.__onVoicePartial && window.__onVoicePartial(" + JSONObject.quote(t) + ")"));
            }
            @Override public void onResults(Bundle b) {
                String t = firstResult(b);
                bridge.main.post(() -> bridge.js("window.__onVoiceFinal && window.__onVoiceFinal(" + JSONObject.quote(t) + ")"));
            }
            @Override public void onEvent(int type, Bundle data) {}
        }
    }

    static class TtsListener extends UtteranceProgressListener {
        private final VoiceBridge bridge;
        TtsListener(VoiceBridge bridge) { this.bridge = bridge; }
        @Override public void onStart(String id) { bridge.notifyStart(id); }
        @Override public void onDone(String id) { bridge.notifyDone(id); }
        @Override @Deprecated public void onError(String id) { bridge.notifyError(id, -1); }
        @Override public void onError(String id, int code) { bridge.notifyError(id, code); }
    }
}
