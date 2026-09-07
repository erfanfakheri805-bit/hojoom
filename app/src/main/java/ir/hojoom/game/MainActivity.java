package ir.hojoom.game;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import ir.tapsell.plus.AdRequestCallback;
import ir.tapsell.plus.AdShowListener;
import ir.tapsell.plus.TapsellPlus;
import ir.tapsell.plus.TapsellPlusInitListener;
import ir.tapsell.plus.model.AdNetworkError;
import ir.tapsell.plus.model.AdNetworks;
import ir.tapsell.plus.model.TapsellPlusAdModel;
import ir.tapsell.plus.model.TapsellPlusErrorModel;

public class MainActivity extends Activity {

    // ==== Tapsell credentials (provided by the developer) ====
    private static final String TAPSELL_APP_KEY =
            "pfiflkqemsftghjaieegkrskfihomcqengmgebrmdasjttphqcdhmrgkditpckbobjdrla";
    private static final String INTERSTITIAL_ZONE_ID =
            "6a997cb695d0ac48db76b6c2";

    // Minimum time between two shown ads, as a native-side safety net
    // (the JS side already limits ad frequency to roughly once every
    // 3 stage-complete/game-over events).
    private static final long MIN_AD_INTERVAL_MS = 90_000L;

    private WebView webView;
    private volatile String pendingInterstitialResponseId = null;
    private volatile boolean interstitialRequestInFlight = false;
    private long lastAdShownAt = 0L;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API 26+
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        // Whatever transiently took focus (a call, another app's sound,
        // the ad) has let go - bring the music back immediately instead
        // of waiting for the next window-focus/visibility event.
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            resumeGameMusic();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        applyImmersiveMode();

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AdsBridge(), "AndroidAds");
        webView.loadUrl("file:///android_asset/index.html");

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        requestAudioFocus();

        initTapsell();
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.requestAudioFocus(audioFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void initTapsell() {
        TapsellPlus.initialize(this, TAPSELL_APP_KEY, new TapsellPlusInitListener() {
            @Override
            public void onInitializeSuccess(AdNetworks adNetworks) {
                preloadInterstitial();
            }

            @Override
            public void onInitializeFailed(AdNetworks adNetworks, AdNetworkError adNetworkError) {
                // Initialization failed; a later JS-triggered call to
                // showInterstitial() will simply try to (re)preload.
            }
        });
    }

    private void preloadInterstitial() {
        if (interstitialRequestInFlight || pendingInterstitialResponseId != null) return;
        interstitialRequestInFlight = true;
        TapsellPlus.requestInterstitialAd(this, INTERSTITIAL_ZONE_ID, new AdRequestCallback() {
            @Override
            public void response(TapsellPlusAdModel tapsellPlusAdModel) {
                super.response(tapsellPlusAdModel);
                interstitialRequestInFlight = false;
                pendingInterstitialResponseId = tapsellPlusAdModel.getResponseId();
            }

            @Override
            public void error(String s) {
                super.error(s);
                interstitialRequestInFlight = false;
            }
        });
    }

    /** Bridge exposed to the game's JavaScript as window.AndroidAds */
    private class AdsBridge {
        @JavascriptInterface
        public void showInterstitial() {
            runOnUiThread(MainActivity.this::tryShowInterstitial);
        }
    }

    private void tryShowInterstitial() {
        long now = System.currentTimeMillis();
        if (now - lastAdShownAt < MIN_AD_INTERVAL_MS) return;

        final String responseId = pendingInterstitialResponseId;
        if (responseId == null) {
            preloadInterstitial();
            return;
        }
        pendingInterstitialResponseId = null;
        lastAdShownAt = now;

        TapsellPlus.showInterstitialAd(this, responseId, new AdShowListener() {
            @Override
            public void onClosed(TapsellPlusAdModel tapsellPlusAdModel) {
                super.onClosed(tapsellPlusAdModel);
                resumeGameMusic();
                preloadInterstitial();
            }

            @Override
            public void onError(TapsellPlusErrorModel tapsellPlusErrorModel) {
                super.onError(tapsellPlusErrorModel);
                resumeGameMusic();
                preloadInterstitial();
            }
        });
    }

    /**
     * Showing the ad hands Android's audio focus to the ad activity, which
     * pauses the game's background music. Tell the WebView page to resume
     * it once we're back.
     */
    private void resumeGameMusic() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "if(window.MusicManager) MusicManager.resumeIfPaused();", null);
            }
        });
    }

    private void applyImmersiveMode() {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                getWindow().getInsetsController().setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
            resumeGameMusic();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                //noinspection deprecation
                audioManager.abandonAudioFocus(audioFocusListener);
            }
        }
        super.onDestroy();
    }
}
