package com.libretv.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String LOCAL_HOST = "libretv.local";
    private static final String LOCAL_ORIGIN = "https://" + LOCAL_HOST;
    private static final String ASSET_ROOT = "site/";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 12; LibreTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private WebView webView;
    private LibreTvChromeClient chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout rootLayout;
    private PlayerView nativePlayerView;
    private ExoPlayer nativePlayer;
    private androidx.media3.common.Player.Listener nativePlayerListener;
    private String nativeLiveUrl;
    private int nativeRetryCount;
    private boolean nativeVodMode;
    private long nativeStartPositionMs;
    private boolean nativeEndHandled;
    private boolean nativeSeekApplied;

    private MediaSession mediaSession;
    private MediaRouter mediaRouter;
    private MediaRouteSelector mediaRouteSelector;
    private MediaRouter.Callback mediaRouterCallback;
    private String castDeviceName;
    private boolean isCastingActive;
    private final Object castMediaLock = new Object();
    private String castMediaTitle;
    private String castMediaUrl;
    private String castMediaPoster;
    private long castMediaDurationMs;
    private long castMediaPositionMs;
    private boolean castMediaIsPlaying;
    private androidx.mediarouter.media.MediaRouter.RouteInfo selectedCastRoute;

    private static final long VIDEO_CACHE_MAX_BYTES = 512L * 1024 * 1024;
    private static Cache sVideoDiskCache;
    private DefaultTrackSelector trackSelector;
    private DefaultTrackSelector.Parameters trackSelectorParams;
    private BandwidthMeter bandwidthMeter;
    private DataSource.Factory upstreamDataSourceFactory;
    private DataSource.Factory cachedDataSourceFactory;
    private LoadErrorHandlingPolicy loadErrorPolicy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // WebView 接管沉浸式：透明状态栏/导航栏，内容延伸到刘海/手势条（交给 CSS env(safe-area-inset-*) 控制）
        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        win.setStatusBarColor(0x00000000);    // 状态栏透明（#00000000 → 完全透明）
        win.setNavigationBarColor(0x00000000); // 导航栏透明
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+：不强制导航栏/状态栏对比色（配合透明导航条更沉浸）
            try {
                win.setStatusBarContrastEnforced(false);
                win.setNavigationBarContrastEnforced(false);
                win.setNavigationBarDividerColor(0x00000000);
            } catch (Throwable ignore) {}
            // Android 9+：允许内容绘制到刘海/挖孔区（SHORT_EDGES：横屏两侧短边也允许）
            try {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                win.setAttributes(lp);
            } catch (Throwable ignore) {}
        } else if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                win.setAttributes(lp);
            } catch (Throwable ignore) {}
        }

        rootLayout = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootLayout);
        configureWebView();
        configureNativePlayer();
        initCast();
        enterImmersiveMode();
        webView.loadUrl(LOCAL_ORIGIN + "/index.html");
        webView.postDelayed(() -> UpdateChecker.checkOnLaunch(this), 2500);
        ApkUpdateDownloader.resumeIfPending(this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(USER_AGENT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // === 移动端适配：防止字号错乱 + 禁止误缩放（配合 CSS touch-action: manipulation） ===
        // 1) 固定 WebView 渲染字号缩放为 100%（避免 Android 设置里「大字体/超大字体」把页面搞乱）
        settings.setTextZoom(100);
        // 2) 禁止手势缩放（pinch-zoom）和双击缩放，避免页面 200% 放大
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        // 3) 让 HTML viewport meta 生效（content="width=device-width, initial-scale=1, user-scalable=no, maximum-scale=1"）
        try { settings.setNeedInitialFocus(true); } catch (Throwable ignore) {}
        try { settings.setOffscreenPreRaster(true); } catch (Throwable ignore) {}

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.setWebViewClient(new LibreTvWebViewClient());
        chromeClient = new LibreTvChromeClient();
        webView.setWebChromeClient(chromeClient);
        webView.addJavascriptInterface(new AndroidLivePlayerBridge(), "AndroidLivePlayer");
        webView.addJavascriptInterface(new AndroidVodPlayerBridge(), "AndroidVodPlayer");
        webView.addJavascriptInterface(new AndroidAppUpdateBridge(), "AndroidAppUpdate");
        webView.addJavascriptInterface(new AndroidCastBridge(), "AndroidCast");
    }

    private class AndroidAppUpdateBridge {
        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        }

        @JavascriptInterface
        public boolean isUpdateEnabled() {
            return BuildConfig.UPDATE_CHECK_URL != null && !BuildConfig.UPDATE_CHECK_URL.isEmpty();
        }

        @JavascriptInterface
        public void checkForUpdate() {
            runOnUiThread(() -> UpdateChecker.checkManual(MainActivity.this, (ok, message) -> {
                if (!ok || (message != null && message.contains("已是最新"))) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }));
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private synchronized Cache getVideoDiskCache() {
        if (sVideoDiskCache == null) {
            try {
                File cacheDir = new File(getCacheDir(), "exoplayer_vod");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                CacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_MAX_BYTES);
                sVideoDiskCache = new SimpleCache(cacheDir, evictor);
            } catch (Throwable t) {
                sVideoDiskCache = null;
            }
        }
        return sVideoDiskCache;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void configureNativePlayer() {
        bandwidthMeter = new DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(6_000_000L)
            .setResetOnNetworkTypeChange(true)
            .build();

        AdaptiveTrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory();
        trackSelector = new DefaultTrackSelector(this, trackSelectionFactory);
        DefaultTrackSelector.Parameters.Builder paramsBuilder = trackSelector.getParameters().buildUpon()
            .setMaxVideoSize(1920, 1080)
            .setMaxVideoFrameRate(60)
            .setForceLowestBitrate(false)
            .setForceHighestSupportedBitrate(false);
        if (Build.VERSION.SDK_INT < 24) {
            paramsBuilder.setMaxVideoSize(1280, 720);
        }
        trackSelector.setParameters(paramsBuilder.build());
        trackSelectorParams = trackSelector.getParameters();

        RenderersFactory renderersFactory = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .forceEnableMediaCodecAsynchronousQueueing();

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                60_000,
                1_500,
                3_000
            )
            .setBackBuffer(60_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build();

        Map<String, String> baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", USER_AGENT);
        baseHeaders.put("Accept", "*/*");
        baseHeaders.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        baseHeaders.put("Connection", "keep-alive");

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(baseHeaders);
        upstreamDataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);

        Cache diskCache = getVideoDiskCache();
        if (diskCache != null) {
            cachedDataSourceFactory = new CacheDataSource.Factory()
                .setCache(diskCache)
                .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
                .setCacheReadDataSourceFactory(new DefaultDataSource.Factory(this))
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamPriority(C.PRIORITY_PLAYBACK);
        } else {
            cachedDataSourceFactory = upstreamDataSourceFactory;
        }

        loadErrorPolicy = new DefaultLoadErrorHandlingPolicy(6);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cachedDataSourceFactory);

        nativePlayer = new ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .setUseLazyPreparation(true)
            .setReleaseTimeoutMs(10_000L)
            .build();
        nativePlayer.setPlayWhenReady(true);
        nativePlayer.setAudioAttributes(
            new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                .build(),
            true
        );
        try { nativePlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT); } catch (Throwable ignore) {}
        nativePlayer.setVolume(1.0f);

        nativePlayerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                retryNativePlayback();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (!nativeVodMode || nativePlayer == null) {
                    return;
                }
                if (playbackState == Player.STATE_READY && !nativeSeekApplied && nativeStartPositionMs > 0) {
                    nativeSeekApplied = true;
                    nativePlayer.seekTo(nativeStartPositionMs);
                }
                if (playbackState == Player.STATE_ENDED && !nativeEndHandled) {
                    nativeEndHandled = true;
                    notifyNativeVodEnded();
                }
            }
        };
        nativePlayer.addListener(nativePlayerListener);

        nativePlayerView = new PlayerView(this);
        nativePlayerView.setBackgroundColor(Color.BLACK);
        nativePlayerView.setPlayer(nativePlayer);
        nativePlayerView.setUseController(true);
        nativePlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        nativePlayerView.setVisibility(View.GONE);
        nativePlayerView.setFullscreenButtonClickListener(new androidx.media3.ui.PlayerView.FullscreenButtonClickListener() {
            @Override
            public void onFullscreenButtonClick(boolean fullscreen) {
                if (!fullscreen) {
                    hideNativePlayer(true);
                } else {
                    enterImmersiveMode();
                }
            }
        });
        try { nativePlayerView.setKeepContentOnPlayerReset(true); } catch (Throwable ignore) {}
        try { nativePlayerView.setUseArtwork(true); } catch (Throwable ignore) {}
        rootLayout.addView(nativePlayerView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (nativePlayerView != null && nativePlayerView.getVisibility() == View.VISIBLE) {
                hideNativePlayer(true);
                return true;
            }
            if (customView != null) {
                chromeClient.onHideCustomView();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        ApkUpdateDownloader.onActivityDestroyed(this);
        if (nativePlayerView != null) {
            try { nativePlayerView.setControllerOnFullScreenModeChangedListener(null); } catch (Throwable ignore) {}
            try { nativePlayerView.setPlayer(null); } catch (Throwable ignore) {}
        }
        if (nativePlayer != null) {
            try { nativePlayer.removeListener(nativePlayerListener); } catch (Throwable ignore) {}
            nativePlayer.release();
            nativePlayer = null;
        }
        releaseCast();
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.removeAllViews();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                ViewParent p = webView.getParent();
                if (p instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) p).removeView(webView);
                }
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignore) {
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (webView != null) {
                webView.onPause();
                webView.pauseTimers();
            }
        } catch (Throwable ignore) {}
        if (mediaRouter != null && mediaRouterCallback != null) {
            mediaRouter.removeCallback(mediaRouterCallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
        } catch (Throwable ignore) {}
        if (mediaRouter != null && mediaRouterCallback != null && mediaRouteSelector != null) {
            mediaRouter.addCallback(mediaRouteSelector, mediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
        }
        ApkUpdateDownloader.resumeIfPending(this);
    }

    private class AndroidLivePlayerBridge {
        @JavascriptInterface
        public void play(String title, String url) {
            runOnUiThread(() -> showNativePlayer(url, false, 0));
        }
    }

    private class AndroidVodPlayerBridge {
        @JavascriptInterface
        public boolean supportsUrl(String url) {
            if (url == null) {
                return false;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains(".m3u8") || lower.contains("mpegurl");
        }

        @JavascriptInterface
        public void play(String title, String url, double startPositionSec) {
            runOnUiThread(() -> showNativePlayer(url, true, (long) (startPositionSec * 1000)));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> hideNativePlayer(false));
        }
    }

    private class AndroidCastBridge {
        @JavascriptInterface
        public boolean isSupported() {
            return true;
        }

        @JavascriptInterface
        public String getStatus() {
            synchronized (castMediaLock) {
                boolean connected = isCastingActive && selectedCastRoute != null;
                String device = connected && castDeviceName != null ? castDeviceName : "";
                String title = castMediaTitle != null ? castMediaTitle : "";
                return "{\"supported\":true,\"active\":" + connected + ",\"deviceName\":\"" + escapeJsString(device) + "\",\"mediaTitle\":\"" + escapeJsString(title) + "\"}";
            }
        }

        @JavascriptInterface
        public void openCastDialog() {
            runOnUiThread(() -> showCastRouteChooser());
        }

        @JavascriptInterface
        public void stopCasting() {
            runOnUiThread(() -> disconnectCastRoute());
        }

        @JavascriptInterface
        public void setMediaInfo(String title, String url, String poster, double durationSec, double positionSec, boolean isPlaying) {
            synchronized (castMediaLock) {
                castMediaTitle = title;
                castMediaUrl = url;
                castMediaPoster = poster;
                castMediaDurationMs = (long) (Math.max(0, durationSec) * 1000);
                castMediaPositionMs = (long) (Math.max(0, positionSec) * 1000);
                castMediaIsPlaying = isPlaying;
            }
            runOnUiThread(() -> {
                updateMediaSessionMetadataAndState();
                if (isCastingActive && selectedCastRoute != null) {
                    sendMediaToCastRoute();
                }
            });
        }

        @JavascriptInterface
        public void setPlaybackState(double positionSec, boolean isPlaying) {
            synchronized (castMediaLock) {
                castMediaPositionMs = (long) (Math.max(0, positionSec) * 1000);
                castMediaIsPlaying = isPlaying;
            }
            runOnUiThread(() -> {
                updateMediaSessionPlaybackState();
                if (isCastingActive && selectedCastRoute != null) {
                    sendPlaybackStateToCastRoute();
                }
            });
        }
    }

    private void showNativePlayer(String url, boolean vod, long startPositionMs) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return;
        }

        if (customView != null) {
            rootLayout.removeView(customView);
            customView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        }

        nativeVodMode = vod;
        nativeStartPositionMs = vod ? Math.max(0, startPositionMs) : 0;
        nativeSeekApplied = nativeStartPositionMs <= 0;
        nativeEndHandled = false;
        nativeLiveUrl = url;
        nativeRetryCount = 0;
        nativePlayerView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        playNativeUrl(url);
        enterImmersiveMode();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playNativeUrl(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Connection", "keep-alive");
        String referer = originFor(url);
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        String origin = originFor(url);
        if (origin != null && !origin.isEmpty()) {
            headers.put("Origin", origin);
        }

        MediaItem.Builder itemBuilder = new MediaItem.Builder()
            .setUri(Uri.parse(url));
        if (nativeStartPositionMs > 0) {
            itemBuilder.setClipStartPositionMs(nativeStartPositionMs);
        }
        MediaItem mediaItem = itemBuilder.build();

        DataSource.Factory playDsf;
        Cache diskCache = getVideoDiskCache();
        if (diskCache != null) {
            final Map<String, String> finalHeaders = headers;
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setDefaultRequestProperties(finalHeaders);
            DataSource.Factory upstream = new DefaultDataSource.Factory(this, httpFactory);
            playDsf = new CacheDataSource.Factory()
                .setCache(diskCache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheReadDataSourceFactory(new DefaultDataSource.Factory(this))
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamPriority(C.PRIORITY_PLAYBACK);
        } else {
            final Map<String, String> finalHeaders2 = headers;
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setDefaultRequestProperties(finalHeaders2);
            playDsf = new DefaultDataSource.Factory(this, httpFactory);
        }

        DefaultMediaSourceFactory playMsf = new DefaultMediaSourceFactory(this)
            .setDataSourceFactory(playDsf);

        try {
            nativePlayer.setMediaSource(playMsf.createMediaSource(mediaItem), nativeStartPositionMs > 0 ? nativeStartPositionMs : C.TIME_UNSET);
        } catch (Throwable t) {
            nativePlayer.setMediaItem(mediaItem, nativeStartPositionMs > 0 ? nativeStartPositionMs : C.TIME_UNSET);
        }
        nativePlayer.prepare();
        nativePlayer.play();
    }

    private void retryNativePlayback() {
        if (nativeLiveUrl == null || nativeRetryCount >= 8) {
            return;
        }

        nativeRetryCount++;
        final int rc = nativeRetryCount;
        long delayMs = rc <= 1 ? 300L
                    : rc <= 2 ? 800L
                    : rc <= 3 ? 2_000L
                    : rc <= 4 ? 5_000L
                    : Math.min(30_000L, 1_000L * (1L << Math.min(rc - 4, 5)));

        nativePlayerView.postDelayed(() -> {
            if (nativePlayerView.getVisibility() != View.VISIBLE || nativeLiveUrl == null || nativePlayer == null) {
                return;
            }
            try {
                nativePlayer.prepare();
                nativePlayer.play();
            } catch (Throwable t) {
                playNativeUrl(nativeLiveUrl);
            }
        }, delayMs);
    }

    private void hideNativePlayer(boolean returnToWeb) {
        if (nativePlayer != null) {
            try { nativePlayer.stop(); } catch (Throwable ignore) {}
            try { nativePlayer.clearMediaItems(); } catch (Throwable ignore) {}
        }
        nativeLiveUrl = null;
        nativeRetryCount = 0;
        nativeVodMode = false;
        nativeStartPositionMs = 0;
        nativeEndHandled = false;
        nativeSeekApplied = false;
        nativePlayerView.setVisibility(View.GONE);
        if (returnToWeb) {
            webView.setVisibility(View.VISIBLE);
            enterImmersiveMode();
            forceResumeWebPlayerState();
        }
    }

    private void notifyNativeVodEnded() {
        runOnUiThread(() -> {
            hideNativePlayer(false);
            webView.setVisibility(View.VISIBLE);
            webView.evaluateJavascript("window.__onNativeVodEnded && window.__onNativeVodEnded();", null);
            enterImmersiveMode();
            forceResumeWebPlayerState();
        });
    }

    private void forceResumeWebPlayerState() {
        if (webView == null) return;
        final String js =
            "(function(){" +
            "  try{" +
            "    var all = document.querySelectorAll('#player-loading, .player-loading-container, .player-loading-overlay');" +
            "    all.forEach(function(el){ el.style.display='none'; });" +
            "    var err = document.getElementById('error');" +
            "    if(err){ err.style.display='none'; }" +
            "    if(window.art){" +
            "      try{ window.art.play && window.art.play(); }catch(e){}" +
            "      try{ if(window.art.video && typeof window.art.video.play==='function'){ window.art.video.play().catch(function(){});} }catch(e){}" +
            "    }else{" +
            "      var vs = document.querySelectorAll('video');" +
            "      vs.forEach(function(v){ try{ v.play().catch(function(){}); }catch(e){} });" +
            "    }" +
            "  }catch(e){}" +
            "})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignore) {}
    }

    private static String escapeJsString(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private void initCast() {
        try {
            mediaRouter = MediaRouter.getInstance(getApplicationContext());
            mediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build();

            mediaRouterCallback = new MediaRouter.Callback() {
                @Override
                public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo route) {
                    selectedCastRoute = route;
                    castDeviceName = route.getName();
                    isCastingActive = true;
                    runOnUiThread(() -> {
                        updateMediaSessionMetadataAndState();
                        sendMediaToCastRoute();
                        notifyCastStatusToWeb();
                        Toast.makeText(MainActivity.this, "已连接投屏设备: " + castDeviceName, Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo route) {
                    if (route == selectedCastRoute) {
                        selectedCastRoute = null;
                        isCastingActive = false;
                        castDeviceName = null;
                        runOnUiThread(() -> {
                            notifyCastStatusToWeb();
                            Toast.makeText(MainActivity.this, "已断开投屏", Toast.LENGTH_SHORT).show();
                        });
                    }
                }

                @Override
                public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) {}

                @Override
                public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo route) {}
            };

            mediaRouter.addCallback(mediaRouteSelector, mediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
            initMediaSession();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void releaseCast() {
        try {
            if (mediaRouter != null && mediaRouterCallback != null) {
                mediaRouter.removeCallback(mediaRouterCallback);
            }
            if (mediaSession != null) {
                mediaSession.release();
                mediaSession = null;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void initMediaSession() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

            try {
                Class<?> builderClz = Class.forName("androidx.media3.session.MediaSession$Builder");
                Object builder = builderClz
                    .getMethod("Builder", Context.class)
                    .invoke(null, this);
                try { builderClz.getMethod("setSessionActivity", PendingIntent.class).invoke(builder, pi); } catch (Throwable ignore) {}
                try { builderClz.getMethod("setId", String.class).invoke(builder, "LibreTVCastSession"); } catch (Throwable ignore) {}
                try { builderClz.getMethod("setCallback", Class.forName("androidx.media3.session.MediaSession$Callback"))
                        .invoke(builder, null); } catch (Throwable ignore) {}
                mediaSession = (MediaSession) builderClz.getMethod("build").invoke(builder);
            } catch (Throwable t1) {
                try {
                    mediaSession = (MediaSession) MediaSession.class
                        .getConstructor(Context.class, String.class, Player.class, PendingIntent.class)
                        .newInstance(this, "LibreTVCastSession", null, pi);
                } catch (Throwable t2) {
                    mediaSession = null;
                }
            }
            if (mediaSession != null) {
                try {
                    java.lang.reflect.Method m = mediaSession.getClass().getMethod("setActive", boolean.class);
                    m.invoke(mediaSession, true);
                } catch (Throwable ignore) {}
                if (nativePlayer != null) {
                    try {
                        mediaSession.getClass().getMethod("setPlayer", Player.class).invoke(mediaSession, nativePlayer);
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            mediaSession = null;
        }
    }

    private void updateMediaSessionMetadataAndState() {
        try { updateMediaSessionMetadata(); } catch (Throwable ignore) {}
        try { updateMediaSessionPlaybackState(); } catch (Throwable ignore) {}
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null) return;
        try {
            synchronized (castMediaLock) {
                if (nativePlayer != null) {
                    try { mediaSession.setPlayer(nativePlayer); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void updateMediaSessionPlaybackState() {
        if (mediaSession == null) return;
        try {
            synchronized (castMediaLock) {
                if (nativePlayer != null) {
                    try { mediaSession.setPlayer(nativePlayer); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void showCastRouteChooser() {
        try {
            if (mediaRouter == null || mediaRouteSelector == null) {
                Toast.makeText(this, "投屏功能未初始化", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Class<?> clz = mediaRouter.getClass();
                java.lang.reflect.Method m = clz.getMethod("showRouteChooserDialog",
                    Class.forName("androidx.mediarouter.media.MediaRouteSelector"), int.class);
                m.invoke(mediaRouter, mediaRouteSelector, 0);
                return;
            } catch (Throwable ignore) {}
            Toast.makeText(this, "请在系统设置中选择投屏设备（DLNA/Chromecast）", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            t.printStackTrace();
            Toast.makeText(this, "打开投屏设备选择器失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void disconnectCastRoute() {
        try {
            if (mediaRouter != null) {
                mediaRouter.selectRoute(mediaRouter.getDefaultRoute());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void sendMediaToCastRoute() {
        if (mediaSession == null || selectedCastRoute == null) return;
        try {
            updateMediaSessionMetadata();
            updateMediaSessionPlaybackState();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void sendPlaybackStateToCastRoute() {
        if (mediaSession == null) return;
        try {
            updateMediaSessionPlaybackState();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void notifyCastStatusToWeb() {
        if (webView == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("(function(){var e=new CustomEvent('caststatuschange',{detail:");
            sb.append(getStatusJson());
            sb.append("});window.dispatchEvent(e);if(window.__onCastStatusChange){try{window.__onCastStatusChange(");
            sb.append(getStatusJson());
            sb.append(");}catch(_){}}})();");
            webView.evaluateJavascript(sb.toString(), null);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private String getStatusJson() {
        synchronized (castMediaLock) {
            boolean connected = isCastingActive && selectedCastRoute != null;
            String device = connected && castDeviceName != null ? castDeviceName : "";
            String title = castMediaTitle != null ? castMediaTitle : "";
            return "{\"supported\":true,\"active\":" + connected + ",\"deviceName\":\"" + escapeJsString(device) + "\",\"mediaTitle\":\"" + escapeJsString(title) + "\"}";
        }
    }

    private class LibreTvChromeClient extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            webView.setVisibility(View.INVISIBLE);
            if (nativePlayerView != null) {
                nativePlayerView.setVisibility(View.GONE);
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            );
            rootLayout.addView(customView, params);
            enterImmersiveMode();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            rootLayout.removeView(customView);
            customView = null;
            webView.setVisibility(View.VISIBLE);
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            enterImmersiveMode();
            forceResumeWebPlayerState();
        }
    }

    private class LibreTvWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return handleRequest(request.getUrl());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return handleRequest(Uri.parse(url));
        }
    }

    private WebResourceResponse handleRequest(Uri uri) {
        if (!LOCAL_HOST.equalsIgnoreCase(uri.getHost())) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || "/".equals(path)) {
            path = "/index.html";
        }

        try {
            if (path.startsWith("/proxy/")) {
                String fullUrl = uri.toString();
                String marker = LOCAL_ORIGIN + "/proxy/";
                int markerIndex = fullUrl.indexOf(marker);
                String encodedTarget = markerIndex >= 0
                    ? fullUrl.substring(markerIndex + marker.length())
                    : path.substring("/proxy/".length());
                return proxyRequest(encodedTarget);
            }
            if (path.startsWith("/s=")) {
                return assetResponse("index.html");
            }
            return assetResponse(path.substring(1));
        } catch (Exception error) {
            return textResponse(500, "text/plain", "LibreTV Android error: " + error.getMessage());
        }
    }

    private WebResourceResponse assetResponse(String assetPath) throws IOException {
        String normalized = assetPath.replace("..", "").replace("\\", "/");
        InputStream input = getAssets().open(ASSET_ROOT + normalized);
        return new WebResourceResponse(mimeTypeFor(normalized), "UTF-8", 200, "OK", corsHeaders(), input);
    }

    private WebResourceResponse proxyRequest(String encodedTarget) throws IOException {
        String targetUrl = URLDecoder.decode(encodedTarget, "UTF-8");
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            return textResponse(400, "text/plain", "Invalid proxy URL");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(45000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setRequestProperty("Referer", originFor(targetUrl));

        int statusCode = connection.getResponseCode();
        InputStream input = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) {
            input = new ByteArrayInputStream(new byte[0]);
        }

        String contentType = connection.getContentType();
        if (isM3u8(targetUrl, contentType)) {
            String content = readUtf8(input);
            String processed = processM3u8(targetUrl, content);
            return new WebResourceResponse("application/vnd.apple.mpegurl", "UTF-8", statusCode, "OK", corsHeaders(), stringStream(processed));
        }

        return new WebResourceResponse(contentTypeOrDefault(contentType), null, statusCode, "OK", corsHeaders(), input);
    }

    private String processM3u8(String targetUrl, String content) {
        if (!content.contains("#EXT-X-")) {
            return content;
        }

        if (content.contains("#EXT-X-STREAM-INF") || content.contains("#EXT-X-MEDIA:")) {
            return processMasterPlaylist(targetUrl, content);
        }
        return processMediaPlaylist(targetUrl, content);
    }

    private String processMasterPlaylist(String targetUrl, String content) {
        String baseUrl = baseUrlFor(targetUrl);
        String[] lines = content.split("\\n", -1);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String raw = stripCarriageReturn(lines[i]);
            String line = raw.trim();
            if (line.isEmpty()) {
                output.append(raw);
            } else if (line.startsWith("#")) {
                output.append(rewriteUriAttributes(raw, baseUrl));
            } else {
                output.append(toProxyUrl(resolveUrl(baseUrl, line)));
            }
            if (i < lines.length - 1) output.append('\n');
        }
        return output.toString();
    }

    private String processMediaPlaylist(String targetUrl, String content) {
        String baseUrl = baseUrlFor(targetUrl);
        String[] lines = content.split("\\n", -1);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String raw = stripCarriageReturn(lines[i]);
            String line = raw.trim();
            if (line.isEmpty()) {
                output.append(raw);
            } else if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) {
                output.append(rewriteUriAttributes(raw, baseUrl));
            } else if (line.startsWith("#")) {
                output.append(raw);
            } else {
                output.append(toProxyUrl(resolveUrl(baseUrl, line)));
            }
            if (i < lines.length - 1) output.append('\n');
        }
        return output.toString();
    }

    private String rewriteUriAttributes(String line, String baseUrl) {
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        String token = "URI=\"";
        while (true) {
            int start = line.indexOf(token, cursor);
            if (start < 0) {
                output.append(line.substring(cursor));
                break;
            }
            int valueStart = start + token.length();
            int end = line.indexOf('"', valueStart);
            if (end < 0) {
                output.append(line.substring(cursor));
                break;
            }
            output.append(line, cursor, valueStart);
            output.append(toProxyUrl(resolveUrl(baseUrl, line.substring(valueStart, end))));
            output.append('"');
            cursor = end + 1;
        }
        return output.toString();
    }

    private String toProxyUrl(String targetUrl) {
        return LOCAL_ORIGIN + "/proxy/" + Uri.encode(targetUrl);
    }

    private String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            return new URL(new URL(baseUrl), relativeUrl).toString();
        } catch (Exception error) {
            return relativeUrl;
        }
    }

    private String baseUrlFor(String targetUrl) {
        int slash = targetUrl.lastIndexOf('/');
        int scheme = targetUrl.indexOf("://");
        if (slash > scheme + 2) {
            return targetUrl.substring(0, slash + 1);
        }
        return targetUrl.endsWith("/") ? targetUrl : targetUrl + "/";
    }

    private String originFor(String targetUrl) {
        try {
            URL url = new URL(targetUrl);
            return url.getProtocol() + "://" + url.getHost() + "/";
        } catch (Exception error) {
            return "";
        }
    }

    private boolean isM3u8(String targetUrl, String contentType) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lowerType.contains("application/vnd.apple.mpegurl")
            || lowerType.contains("application/x-mpegurl")
            || lowerType.contains("audio/mpegurl")
            || targetUrl.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private String contentTypeOrDefault(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return "application/octet-stream";
        }
        int semicolon = contentType.indexOf(';');
        return semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    }

    private String mimeTypeFor(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        String ext = MimeTypeMap.getFileExtensionFromUrl(path);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type == null ? "application/octet-stream" : type;
    }

    private WebResourceResponse textResponse(int statusCode, String mimeType, String text) {
        return new WebResourceResponse(mimeType, "UTF-8", statusCode, "OK", corsHeaders(), stringStream(text));
    }

    private ByteArrayInputStream stringStream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }

    private String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private Map<String, String> corsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Cache-Control", "no-store");
        return headers;
    }
}
