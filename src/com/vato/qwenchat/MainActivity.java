package com.vato.qwenchat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
import android.view.KeyEvent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

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

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            Log.d("VatoWeb", consoleMessage.message() + " (line " + consoleMessage.lineNumber() + ")");
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


        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
        setupMediaSession();
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
    @Override
    protected void onResume() {
        super.onResume();
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (uploadMessage != null) {
            uploadMessage.onReceiveValue(null);
            uploadMessage = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (voiceBridge != null) {
            voiceBridge.destroy();
            voiceBridge = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private MediaSession mediaSession;
    private long lastHeadsetClickTime = 0;
    private long lastPhysicalEventTime = 0;
    private final Handler mediaHandler = new Handler(Looper.getMainLooper());
    SingleClickRunnable pendingSingleClick = null;

    static class HeadsetMediaSessionCallback extends MediaSession.Callback {
        private final MainActivity activity;
        HeadsetMediaSessionCallback(MainActivity activity) {
            this.activity = activity;
        }
        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            if (Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null) {
                    return activity.handleHeadsetKeyEvent(event);
                }
            }
            return super.onMediaButtonEvent(mediaButtonIntent);
        }
    }

    static class SingleClickRunnable implements Runnable {
        private final MainActivity activity;
        SingleClickRunnable(MainActivity activity) {
            this.activity = activity;
        }
        @Override
        public void run() {
            activity.sendHeadsetClickToJs("single");
            activity.pendingSingleClick = null;
        }
    }

    private void setupMediaSession() {
        try {
            if (mediaSession != null) {
                mediaSession.setActive(true);
                return;
            }
            mediaSession = new MediaSession(this, "VatoMediaSession");
            mediaSession.setCallback(new HeadsetMediaSessionCallback(this));
            PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build();
            mediaSession.setPlaybackState(state);
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setActive(true);
            Log.i("VatoMedia", "MediaSession registered & active for Bluetooth headsets");
        } catch (Exception e) {
            Log.e("VatoMedia", "setupMediaSession failed", e);
        }
    }

    boolean handleHeadsetKeyEvent(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return false;
        }
        long eventTime = event.getEventTime();
        if (eventTime > 0 && eventTime == lastPhysicalEventTime) {
            return true;
        }
        lastPhysicalEventTime = eventTime;
        return handleHeadsetKeyCode(event.getKeyCode());
    }

    boolean handleHeadsetKeyCode(int keyCode) {
        Log.i("VatoMedia", "Headset key received: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_ENDCALL) {
            if (pendingSingleClick != null) {
                mediaHandler.removeCallbacks(pendingSingleClick);
                pendingSingleClick = null;
            }
            sendHeadsetClickToJs("double");
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK 
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE 
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY 
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MUTE
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                || keyCode == KeyEvent.KEYCODE_CALL) {
            long now = System.currentTimeMillis();
            if (now - lastHeadsetClickTime >= 80 && now - lastHeadsetClickTime < 450) {
                lastHeadsetClickTime = 0;
                if (pendingSingleClick != null) {
                    mediaHandler.removeCallbacks(pendingSingleClick);
                    pendingSingleClick = null;
                }
                sendHeadsetClickToJs("double");
                return true;
            }
            if (now - lastHeadsetClickTime < 80) {
                return true;
            }
            lastHeadsetClickTime = now;
            if (pendingSingleClick != null) {
                mediaHandler.removeCallbacks(pendingSingleClick);
            }
            pendingSingleClick = new SingleClickRunnable(this);
            mediaHandler.postDelayed(pendingSingleClick, 320);
            return true;
        }
        return false;
    }

    private void sendHeadsetClickToJs(String type) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.__onHeadsetClick && window.__onHeadsetClick('" + type + "')", null);
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleHeadsetKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            if (handleHeadsetKeyCode(keyCode)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    static class VoiceBridge {
        private final Activity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private TextToSpeech tts;
        private boolean ttsReady = false;
        private Locale pendingTtsLocale = null;
        private SpeechRecognizer speechRecognizer;
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
                            applyFemaleVoice(pendingTtsLocale != null ? pendingTtsLocale : new Locale("en", "IN"));
                        }
                    });
                    tts.setOnUtteranceProgressListener(new TtsListener(this));
                } catch (Exception ignored) {}
            });
        }

        void destroy() {
            if (tts != null) {
                try {
                    tts.stop();
                    tts.shutdown();
                } catch (Exception ignored) {}
                tts = null;
            }
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.destroy();
                } catch (Exception ignored) {}
                speechRecognizer = null;
            }
        }

        private void applyFemaleVoice(Locale locale) {
            if (tts == null) return;
            try {
                tts.setLanguage(locale);
                Set<Voice> voices = tts.getVoices();
                if (voices != null && !voices.isEmpty()) {
                    String targetLang = locale.getLanguage().toLowerCase(Locale.ROOT);
                    Voice candidate = null;
                    for (Voice v : voices) {
                        Locale vLoc = v.getLocale();
                        if (vLoc != null && targetLang.equals(vLoc.getLanguage().toLowerCase(Locale.ROOT))) {
                            String name = v.getName().toLowerCase(Locale.ROOT);
                            boolean isFemale = name.contains("guc") || name.contains("guf") || name.contains("ene") || name.contains("enc") || name.contains("end") || name.contains("cfl") || name.contains("female") || name.contains("girl") || name.contains("woman") || name.contains("-f-") || name.endsWith("-f");
                            if (isFemale) {
                                if (!v.isNetworkConnectionRequired()) {
                                    candidate = v;
                                    break;
                                } else if (candidate == null) {
                                    candidate = v;
                                }
                            }
                        }
                    }
                    if (candidate != null) {
                        tts.setVoice(candidate);
                        Log.i("VatoTTS", "Selected female voice: " + candidate.getName());
                    } else {
                        Log.w("VatoTTS", "No female voice matched for " + targetLang);
                    }
                }
                // Positive, upbeat, friendly girl tone
                tts.setPitch(1.22f);
                tts.setSpeechRate(1.05f);
            } catch (Exception e) {
                Log.e("VatoTTS", "applyFemaleVoice failed", e);
            }
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
                if (speechRecognizer == null) return;
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);
                try { speechRecognizer.startListening(intent); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopListening() {
            activity.runOnUiThread(() -> {
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer.cancel();
                        speechRecognizer.stopListening();
                    } catch (Exception ignored) {}
                }
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
                    applyFemaleVoice(locale);
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
        public String getAvailableVoices(String lang) {
            if (tts == null || !ttsReady) return "[]";
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices == null) return "[]";
                Locale loc = toLocale(lang);
                String l = loc.getLanguage().toLowerCase(Locale.ROOT);
                JSONArray arr = new JSONArray();
                for (Voice v : voices) {
                    Locale vLoc = v.getLocale();
                    if (vLoc != null && l.equals(vLoc.getLanguage().toLowerCase(Locale.ROOT))) {
                        JSONObject obj = new JSONObject();
                        obj.put("name", v.getName());
                        obj.put("quality", v.getQuality());
                        obj.put("network", v.isNetworkConnectionRequired());
                        arr.put(obj);
                    }
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void openTtsSettings() {
            try {
                Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception ignored) {}
            }
        }

        private boolean isHeadsetConnected() {
            if (audio == null) return false;
            try {
                if (audio.isBluetoothA2dpOn() || audio.isBluetoothScoOn() || audio.isWiredHeadsetOn()) {
                    return true;
                }
                for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int type = device.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || type == AudioDeviceInfo.TYPE_USB_HEADSET
                            || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        public void setCallMode(boolean on) {
            activity.runOnUiThread(() -> {
                if (activity instanceof MainActivity) {
                    MainActivity act = (MainActivity) activity;
                    if (act.mediaSession != null) {
                        PlaybackState state = new PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                            .setState(on ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 0, 1.0f)
                            .build();
                        act.mediaSession.setPlaybackState(state);
                        act.mediaSession.setActive(true);
                    }
                }
                Log.i("VatoAudio", "Call mode updated: on=" + on);
            });
        }

        void onPermissionResult(boolean granted) {
            js("window.__onVoicePermission && window.__onVoicePermission(" + granted + ")");
            if (granted && pendingAutoStart) { pendingAutoStart = false; startListening(pendingStartLang); }
        }

        private Bundle utteranceBundle(String id) {
            Bundle b = new Bundle();
            b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            b.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
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
            if (speechRecognizer != null) return;
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity.getApplicationContext());
                speechRecognizer.setRecognitionListener(new VoiceRecognitionListener(this));
            } catch (Exception ignored) {}
        }

        static class VoiceRecognitionListener implements RecognitionListener {
            private final VoiceBridge bridge;
            VoiceRecognitionListener(VoiceBridge bridge) { this.bridge = bridge; }
            private String firstResult(Bundle b) {
                ArrayList<String> r = b != null ? b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                return (r != null && !r.isEmpty()) ? r.get(0) : "";
            }
            @Override public void onReadyForSpeech(Bundle p) {
                bridge.main.post(() -> bridge.js("window.__onVoiceReady && window.__onVoiceReady()"));
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {
                bridge.main.post(() -> bridge.js("window.__onVoiceEnd && window.__onVoiceEnd()"));
            }
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
