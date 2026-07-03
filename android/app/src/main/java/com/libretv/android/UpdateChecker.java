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

/**
 * 从远程 JSON 检查 APK 新版本，支持下载安装与强制更新。
 * 配置见 BuildConfig.UPDATE_CHECK_URL，JSON 格式见 app-update.json.example
 */
public final class UpdateChecker {
    private static final String PREFS = "libretv_update";
    private static final String KEY_SKIP_CODE = "skip_version_code";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

    /** 设置页手动检查，始终提示结果 */
    public static void checkManual(Activity activity, ManualCheckCallback callback) {
        if (TextUtils.isEmpty(BuildConfig.UPDATE_CHECK_URL)) {
            MAIN.post(() -> callback.onResult(false, "未配置更新地址，请在 app/build.gradle 设置 UPDATE_CHECK_URL"));
            return;
        }
        EXECUTOR.execute(() -> {
            UpdateInfo info = fetchUpdateInfo();
            if (info == null) {
                MAIN.post(() -> callback.onResult(false, "无法获取更新信息，请检查网络或更新地址"));
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
        String message = "新版本：" + info.versionName + "（" + info.versionCode + "）\n"
            + "当前版本：" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）";
        if (!TextUtils.isEmpty(info.changelog)) {
            message += "\n\n更新说明：\n" + info.changelog;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setMessage(message)
            .setCancelable(!info.forceUpdate)
            .setPositiveButton("立即更新", (d, w) -> ApkUpdateDownloader.start(
                activity, info.apkUrl, info.versionName, info.versionCode, info.forceUpdate));

        if (!info.forceUpdate) {
            builder.setNegativeButton("暂不更新", (d, w) -> skipVersion(activity, info.versionCode));
        }
        builder.show();
    }

    /** 用户选择跳过该版本（网络差或暂不升级） */
    static void skipVersion(Activity activity, int versionCode) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIP_CODE, versionCode)
            .apply();
    }

    static void showDownloadFailedDialog(Activity activity, String apkUrl, String versionName, int versionCode, boolean forceUpdate) {
        if (activity.isFinishing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("更新失败")
            .setMessage("下载未完成，可能是网络不稳定。请换网络后重试，或稍后再更新。")
            .setPositiveButton("重试", (d, w) -> ApkUpdateDownloader.start(activity, apkUrl, versionName, versionCode, forceUpdate));
        if (!forceUpdate) {
            builder.setNegativeButton("暂不更新", (d, w) -> skipVersion(activity, versionCode));
        }
        builder.setCancelable(!forceUpdate);
        builder.show();
    }

    private static UpdateInfo fetchUpdateInfo() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BuildConfig.UPDATE_CHECK_URL).openConnection();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("User-Agent", "LibreTV-Android/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
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
            info.changelog = json.optString("changelog", "");
            info.forceUpdate = json.optBoolean("forceUpdate", false);
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
            return info;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
        String changelog;
        boolean forceUpdate;
    }

    public interface ManualCheckCallback {
        void onResult(boolean ok, String message);
    }
}