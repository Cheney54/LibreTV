package com.libretv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateChecker {
    private static final String PREFS = "libretv_update";
    private static final String KEY_SKIP_CODE = "skip_version_code";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_REDIRECTS = 5;

    private UpdateChecker() {
    }

    public static void checkOnLaunch(Activity activity) {
        if (TextUtils.isEmpty(BuildConfig.UPDATE_CHECK_URL)) {
            return;
        }
        EXECUTOR.execute(() -> {
            UpdateInfo info = fetchUpdateInfo();
            if (info == null) {
                return;
            }
            int current = BuildConfig.VERSION_CODE;
            if (info.versionCode <= current) {
                return;
            }
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!info.forceUpdate && prefs.getInt(KEY_SKIP_CODE, -1) == info.versionCode) {
                return;
            }
            MAIN.post(() -> showUpdateDialog(activity, info, false));
        });
    }

    public static void checkManual(Activity activity, ManualCheckCallback callback) {
        if (TextUtils.isEmpty(BuildConfig.UPDATE_CHECK_URL)) {
            MAIN.post(() -> callback.onResult(false, "未配置更新地址，请在 app/build.gradle 设置 UPDATE_CHECK_URL"));
            return;
        }
        EXECUTOR.execute(() -> {
            UpdateInfo info = fetchUpdateInfo();
            if (info == null) {
                MAIN.post(() -> callback.onResult(false, "无法获取更新信息：可能网络不可用，或 GitHub 访问限流（429），请稍后再试"));
                return;
            }
            int current = BuildConfig.VERSION_CODE;
            if (info.versionCode <= current) {
                MAIN.post(() -> callback.onResult(true, "当前已是最新版本 " + BuildConfig.VERSION_NAME));
                return;
            }
            MAIN.post(() -> {
                showUpdateDialog(activity, info, true);
                callback.onResult(true, "发现新版本 " + info.versionName);
            });
        });
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo info, boolean fromManual) {
        if (activity.isFinishing()) {
            return;
        }
        String sizeText = "";
        if (info.apkSize > 0) {
            float mb = info.apkSize / 1024.0f / 1024.0f;
            sizeText = String.format(java.util.Locale.US, "　·　大小：%.2f MB", mb);
        }
        String message = "新版本：" + info.versionName + "（" + info.versionCode + "）" + sizeText + "\n"
            + "当前版本：" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）";
        if (!TextUtils.isEmpty(info.changelog)) {
            message += "\n\n更新说明：\n" + info.changelog;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setMessage(message)
            .setCancelable(!info.forceUpdate)
            .setPositiveButton("立即更新", (d, w) -> ApkUpdateDownloader.start(
                activity, info.apkUrl, info.fallbackUrl, info.versionName, info.versionCode,
                info.forceUpdate, info.apkSize, info.sha256));

        if (!info.forceUpdate) {
            builder.setNegativeButton("暂不更新", (d, w) -> skipVersion(activity, info.versionCode));
        }
        builder.show();
    }

    static void skipVersion(Activity activity, int versionCode) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIP_CODE, versionCode)
            .apply();
    }

    static void showDownloadFailedDialog(Activity activity, String apkUrl, String fallbackUrl,
                                         String versionName, int versionCode, boolean forceUpdate,
                                         long apkSize, String sha256) {
        if (activity.isFinishing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("更新失败")
            .setMessage("下载未完成，可能是 GitHub 临时限流或当前网络不稳定。\n可稍后重试，或切换到备用下载地址。")
            .setPositiveButton("重试", (d, w) -> ApkUpdateDownloader.start(
                activity, apkUrl, fallbackUrl, versionName, versionCode, forceUpdate, apkSize, sha256));
        if (!forceUpdate) {
            builder.setNegativeButton("暂不更新", (d, w) -> skipVersion(activity, versionCode));
        }
        builder.setCancelable(!forceUpdate);
        builder.show();
    }

    private static UpdateInfo fetchUpdateInfo() {
        String urlText = BuildConfig.UPDATE_CHECK_URL;
        int attempts = 0;
        while (urlText != null && attempts < MAX_REDIRECTS) {
            attempts++;
            HttpURLConnection connection = null;
            try {
                URL u = new URL(urlText);
                connection = (HttpURLConnection) u.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(15_000);
                connection.setRequestProperty("User-Agent", "LibreTV-Android/" + BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept", "application/json,text/plain;q=0.9,*/*;q=0.8");
                connection.setRequestProperty("Cache-Control", "no-cache");

                int code = connection.getResponseCode();
                if (code == 429) {
                    return null;
                }
                if (code >= 301 && code <= 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        return null;
                    }
                    URL base = u;
                    urlText = new URL(base, location).toString();
                    connection.disconnect();
                    continue;
                }
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) {
                    return null;
                }
                String body = readUtf8(stream);
                JSONObject json = new JSONObject(body);
                UpdateInfo info = new UpdateInfo();
                info.versionCode = json.optInt("versionCode", 0);
                info.versionName = json.optString("versionName", "");
                info.apkUrl = json.optString("apkUrl", "");
                info.fallbackUrl = json.optString("fallbackUrl", "");
                info.changelog = json.optString("changelog", "");
                info.forceUpdate = json.optBoolean("forceUpdate", false);
                info.apkSize = json.optLong("apkSize", 0L);
                info.sha256 = json.optString("sha256", "");
                int minVersionCode = json.optInt("minVersionCode", 0);
                if (minVersionCode > 0 && BuildConfig.VERSION_CODE < minVersionCode) {
                    info.forceUpdate = true;
                }
                if (info.versionCode <= 0 || TextUtils.isEmpty(info.apkUrl)) {
                    return null;
                }
                if (!info.apkUrl.startsWith("http://") && !info.apkUrl.startsWith("https://")) {
                    return null;
                }
                if (!TextUtils.isEmpty(info.fallbackUrl)
                    && !info.fallbackUrl.startsWith("http://")
                    && !info.fallbackUrl.startsWith("https://")) {
                    info.fallbackUrl = "";
                }
                return info;
            } catch (Exception ignored) {
                return null;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return null;
    }

    private static String readUtf8(InputStream input) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static final class UpdateInfo {
        int versionCode;
        String versionName;
        String apkUrl;
        String fallbackUrl;
        String changelog;
        boolean forceUpdate;
        long apkSize;
        String sha256;
    }

    public interface ManualCheckCallback {
        void onResult(boolean ok, String message);
    }
}
