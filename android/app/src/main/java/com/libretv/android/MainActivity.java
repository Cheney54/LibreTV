package com.libretv.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
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

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
    private String nativeLiveUrl;
    private int nativeRetryCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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
        enterImmersiveMode();
        webView.loadUrl(LOCAL_ORIGIN + "/index.html");
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

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.setWebViewClient(new LibreTvWebViewClient());
        chromeClient = new LibreTvChromeClient();
        webView.setWebChromeClient(chromeClient);
        webView.addJavascriptInterface(new AndroidLivePlayerBridge(), "AndroidLivePlayer");
    }

    private void configureNativePlayer() {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000,
                300_000,
                3_000,
                8_000
            )
            .setBackBuffer(120_000, true)
            .build();

        nativePlayer = new ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build();
        nativePlayer.setPlayWhenReady(true);
        nativePlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                retryNativePlayback();
            }
        });

        nativePlayerView = new PlayerView(this);
        nativePlayerView.setBackgroundColor(Color.BLACK);
        nativePlayerView.setPlayer(nativePlayer);
        nativePlayerView.setUseController(true);
        nativePlayerView.setVisibility(View.GONE);
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
                hideNativePlayer();
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
        if (nativePlayer != null) {
            nativePlayer.release();
            nativePlayer = null;
        }
        super.onDestroy();
    }

    private class AndroidLivePlayerBridge {
        @JavascriptInterface
        public void play(String title, String url) {
            runOnUiThread(() -> showNativePlayer(url));
        }
    }

    private void showNativePlayer(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return;
        }

        nativeLiveUrl = url;
        nativeRetryCount = 0;
        nativePlayerView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        playNativeUrl(url);
        enterImmersiveMode();
    }

    private void playNativeUrl(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Referer", originFor(url));

        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        HlsMediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(mediaItem);

        nativePlayer.setMediaSource(mediaSource);
        nativePlayer.prepare();
        nativePlayer.play();
    }

    private void retryNativePlayback() {
        if (nativeLiveUrl == null || nativeRetryCount >= 8) {
            return;
        }

        nativeRetryCount++;
        nativePlayerView.postDelayed(() -> {
            if (nativePlayerView.getVisibility() == View.VISIBLE && nativeLiveUrl != null) {
                playNativeUrl(nativeLiveUrl);
            }
        }, Math.min(15_000, 2_000L * nativeRetryCount));
    }

    private void hideNativePlayer() {
        if (nativePlayer != null) {
            nativePlayer.stop();
            nativePlayer.clearMediaItems();
        }
        nativeLiveUrl = null;
        nativeRetryCount = 0;
        nativePlayerView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        enterImmersiveMode();
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
            rootLayout.setVisibility(View.GONE);
            setContentView(customView);
            enterImmersiveMode();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            customView = null;
            rootLayout.setVisibility(View.VISIBLE);
            setContentView(rootLayout);
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            enterImmersiveMode();
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
