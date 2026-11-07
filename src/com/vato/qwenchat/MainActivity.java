package com.vato.qwenchat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.webkit.PermissionRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
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

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            activity.runOnUiThread(() -> {
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        return;
                    }
                }
                request.deny();
            });
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra("eval_js")) {
            String jsCode = intent.getStringExtra("eval_js");
            if (webView != null && jsCode != null) {
                runOnUiThread(() -> webView.evaluateJavascript(jsCode, null));
            }
        }
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
        private AudioRecord audioRecord;
        private Thread recordingThread;
        private final AtomicBoolean isRecording = new AtomicBoolean(false);
        private final AtomicBoolean isCancelled = new AtomicBoolean(false);
        private String activeServerUrl = null;
        private String pendingServerUrl = null;
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
            stopSenseVoiceRecording();
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
                Locale uk = Locale.UK;
                tts.setLanguage(uk);
                Set<Voice> voices = tts.getVoices();
                if (voices != null && !voices.isEmpty()) {
                    Voice candidate = null;
                    for (Voice v : voices) {
                        Locale vLoc = v.getLocale();
                        if (vLoc != null && "en".equalsIgnoreCase(vLoc.getLanguage()) && "gb".equalsIgnoreCase(vLoc.getCountry())) {
                            String name = v.getName().toLowerCase(Locale.ROOT);
                            boolean isFemale = name.contains("female") || name.contains("girl") || name.contains("woman")
                                || name.contains("gba") || name.contains("gbb") || name.contains("gbc") || name.contains("gbf")
                                || name.contains("rjs") || name.contains("-f-") || name.endsWith("-f");
                            if (isFemale) {
                                candidate = v;
                                if (!v.isNetworkConnectionRequired()) break;
                            } else if (candidate == null) {
                                candidate = v;
                            }
                        }
                    }
                    if (candidate == null) {
                        for (Voice v : voices) {
                            Locale vLoc = v.getLocale();
                            if (vLoc != null && "en".equalsIgnoreCase(vLoc.getLanguage())) {
                                String name = v.getName().toLowerCase(Locale.ROOT);
                                if (name.contains("female") || name.contains("-f-") || name.contains("ene") || name.contains("enc")) {
                                    candidate = v;
                                    break;
                                }
                            }
                        }
                    }
                    if (candidate != null) {
                        tts.setVoice(candidate);
                        Log.i("VatoTTS", "Selected British female voice: " + candidate.getName());
                    }
                }
                tts.setPitch(1.15f);
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
        public void setServerUrl(String url) {
            this.activeServerUrl = url != null ? url.trim() : null;
        }

        @JavascriptInterface
        public void startListening(String lang) {
            startListening(lang, activeServerUrl);
        }

        @JavascriptInterface
        public void startListening(String lang, String serverUrl) {
            final Locale locale = toLocale(lang);
            final String targetUrl = (serverUrl != null && !serverUrl.trim().isEmpty()) ? serverUrl.trim() : activeServerUrl;
            activity.runOnUiThread(() -> {
                if (!hasAudioPermission()) {
                    pendingAutoStart = true;
                    pendingStartLang = lang;
                    pendingServerUrl = targetUrl;
                    requestAudioPermission();
                    return;
                }
                pendingAutoStart = false;
                startNativeSpeechRecognizer(locale);
            });
        }

        private void startNativeSpeechRecognizer(Locale locale) {
            ensureRecognizer();
            if (speechRecognizer == null) return;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
            boolean isEn = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
            String langTag = isEn ? "en-US" : (locale != null ? locale.toLanguageTag() : "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            try { speechRecognizer.startListening(intent); } catch (Exception ignored) {}
        }

        private void startSenseVoiceRecording(String serverUrl, Locale locale) {
            stopSenseVoiceRecording();
            if (speechRecognizer != null) {
                try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            }
            final int sampleRate = 16000;
            final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            final int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            final int bufferSize = Math.max(minBufferSize, 4096);

            try {
                audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                );
            } catch (SecurityException se) {
                Log.e("VatoVoice", "AudioRecord permission denied", se);
                startNativeSpeechRecognizer(locale);
                return;
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w("VatoVoice", "AudioRecord not initialized, falling back to SpeechRecognizer");
                startNativeSpeechRecognizer(locale);
                return;
            }

            isCancelled.set(false);
            isRecording.set(true);
            try {
                audioRecord.startRecording();
            } catch (Exception e) {
                Log.e("VatoVoice", "Failed to start AudioRecord", e);
                startNativeSpeechRecognizer(locale);
                return;
            }

            js("window.__onVoiceReady && window.__onVoiceReady()");

            recordingThread = new Thread(() -> {
                ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[bufferSize];
                long silenceStart = -1;
                long speechStart = -1;
                final int SILENCE_THRESHOLD = 500;
                final long MAX_RECORD_TIME_MS = 25000;
                final long SILENCE_LIMIT_MS = 2000;
                long recordStartTime = System.currentTimeMillis();

                try {
                    while (isRecording.get()) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            pcmStream.write(buffer, 0, read);

                            long sum = 0;
                            for (int i = 0; i < read; i += 2) {
                                short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                                sum += (long) sample * sample;
                            }
                            double rms = Math.sqrt((double) sum / (read / 2.0));

                            long now = System.currentTimeMillis();
                            if (rms > SILENCE_THRESHOLD) {
                                if (speechStart == -1) speechStart = now;
                                silenceStart = -1;
                            } else if (speechStart != -1) {
                                if (silenceStart == -1) silenceStart = now;
                                else if (now - silenceStart >= SILENCE_LIMIT_MS) {
                                    break;
                                }
                            }

                            if (now - recordStartTime > MAX_RECORD_TIME_MS) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("VatoVoice", "Recording loop error", e);
                } finally {
                    try {
                        if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop();
                        }
                    } catch (Exception ignored) {}
                    isRecording.set(false);
                }

                js("window.__onVoiceEnd && window.__onVoiceEnd()");

                if (isCancelled.get()) {
                    return;
                }

                byte[] pcmData = pcmStream.toByteArray();
                if (pcmData.length > sampleRate * 2 * 0.4) {
                    byte[] wavBytes = encodeWav(pcmData, sampleRate, 1, 16);
                    postTranscription(serverUrl, wavBytes);
                } else {
                    main.post(() -> js("window.__onVoiceError && window.__onVoiceError(6)"));
                }
            });
            recordingThread.start();
        }

        private void stopSenseVoiceRecording() {
            isRecording.set(false);
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                } catch (Exception ignored) {}
                try {
                    audioRecord.release();
                } catch (Exception ignored) {}
                audioRecord = null;
            }
        }

        private static byte[] encodeWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
            int totalAudioLen = pcmData.length;
            int totalDataLen = totalAudioLen + 36;
            int byteRate = sampleRate * channels * (bitsPerSample / 8);
            int blockAlign = channels * (bitsPerSample / 8);

            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            header[4] = (byte) (totalDataLen & 0xff);
            header[5] = (byte) ((totalDataLen >> 8) & 0xff);
            header[6] = (byte) ((totalDataLen >> 16) & 0xff);
            header[7] = (byte) ((totalDataLen >> 24) & 0xff);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
            header[20] = 1; header[21] = 0;
            header[22] = (byte) channels; header[23] = 0;
            header[24] = (byte) (sampleRate & 0xff);
            header[25] = (byte) ((sampleRate >> 8) & 0xff);
            header[26] = (byte) ((sampleRate >> 16) & 0xff);
            header[27] = (byte) ((sampleRate >> 24) & 0xff);
            header[28] = (byte) (byteRate & 0xff);
            header[29] = (byte) ((byteRate >> 8) & 0xff);
            header[30] = (byte) ((byteRate >> 16) & 0xff);
            header[31] = (byte) ((byteRate >> 24) & 0xff);
            header[32] = (byte) blockAlign; header[33] = 0;
            header[34] = (byte) bitsPerSample; header[35] = 0;
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            header[40] = (byte) (totalAudioLen & 0xff);
            header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
            header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
            header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

            byte[] wav = new byte[header.length + pcmData.length];
            System.arraycopy(header, 0, wav, 0, header.length);
            System.arraycopy(pcmData, 0, wav, header.length, pcmData.length);
            return wav;
        }

        private void postTranscription(String serverUrl, byte[] wavData) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    String base = serverUrl.replaceAll("/+$", "");
                    URL url = new URL(base + "/v1/audio/transcriptions");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("Content-Type", "audio/wav");
                    conn.setRequestProperty("Content-Length", String.valueOf(wavData.length));

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(wavData);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        ByteArrayOutputStream resp = new ByteArrayOutputStream();
                        try (InputStream is = conn.getInputStream()) {
                            byte[] b = new byte[2048];
                            int r;
                            while ((r = is.read(b)) != -1) resp.write(b, 0, r);
                        }
                        String jsonStr = resp.toString("UTF-8");
                        JSONObject json = new JSONObject(jsonStr);
                        String text = json.optString("text", "");
                        String emotion = json.optString("emotion", "");
                        if (text.isEmpty() && !emotion.isEmpty()) {
                            text = "[" + emotion.toUpperCase(Locale.ROOT) + "]";
                        }
                        final String fText = text;
                        final String fEmotion = emotion;
                        main.post(() -> js("window.__onVoiceFinal && window.__onVoiceFinal(" +
                            JSONObject.quote(fText) + ", " + JSONObject.quote(fEmotion) + ")"));
                    } else {
                        Log.w("VatoVoice", "Transcription endpoint returned HTTP " + code);
                        main.post(() -> js("window.__onVoiceError && window.__onVoiceError(7)"));
                    }
                } catch (Exception e) {
                    Log.e("VatoVoice", "Failed to reach transcription endpoint", e);
                    main.post(() -> js("window.__onVoiceError && window.__onVoiceError(7)"));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        @JavascriptInterface
        public void stopListening() {
            activity.runOnUiThread(() -> {
                stopSenseVoiceRecording();
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer.cancel();
                        speechRecognizer.stopListening();
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void cancelListening() {
            activity.runOnUiThread(() -> {
                isCancelled.set(true);
                stopListening();
            });
        }

        @JavascriptInterface
        public void transcribeAudio(String serverUrl, String base64Wav) {
            if (base64Wav == null || base64Wav.isEmpty()) return;
            try {
                byte[] wav = android.util.Base64.decode(base64Wav, android.util.Base64.DEFAULT);
                postTranscription(serverUrl != null && !serverUrl.isEmpty() ? serverUrl : activeServerUrl, wav);
            } catch (Exception e) {
                Log.e("VatoVoice", "Failed to decode base64 audio", e);
            }
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
            if (granted && pendingAutoStart) { pendingAutoStart = false; startListening(pendingStartLang, pendingServerUrl); }
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
