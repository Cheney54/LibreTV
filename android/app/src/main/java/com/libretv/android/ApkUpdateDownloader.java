package com.libretv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApkUpdateDownloader {
    private static final String PREFS = "libretv_update";
    private static final String KEY_PENDING_INSTALL_PATH = "pending_install_path";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int MAX_REDIRECTS = 6;

    private static long activeDownloadId = -1L;
    private static BroadcastReceiver downloadReceiver;
    private static AlertDialog progressDialog;
    private static ProgressBar progressBar;
    private static TextView progressMessage;
    private static String pendingApkUrl;
    private static String pendingFallbackUrl;
    private static String pendingVersionName;
    private static int pendingVersionCode = -1;
    private static boolean pendingForceUpdate;
    private static long pendingApkSize;
    private static String pendingSha256;
    private static File downloadedFile;
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);
    private static Runnable directProgressTick;
    private static Runnable downloadManagerTick;
    private static long lastProgressUiMs;

    private ApkUpdateDownloader() {
    }

    public static void start(Activity activity, String apkUrl, String fallbackUrl, String versionName,
                             int versionCode, boolean forceUpdate, long apkSize, String sha256) {
        pendingApkUrl = apkUrl;
        pendingFallbackUrl = !TextUtils.isEmpty(fallbackUrl) ? fallbackUrl : "";
        pendingVersionName = versionName;
        pendingVersionCode = versionCode;
        pendingForceUpdate = forceUpdate;
        pendingApkSize = apkSize;
        pendingSha256 = sha256;
        cancelled.set(false);
        downloadedFile = null;
        startDownload(activity, apkUrl, versionName, true);
    }

    public static void resumeIfPending(Activity activity) {
        if (activity == null) return;
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String path = prefs.getString(KEY_PENDING_INSTALL_PATH, null);
            if (TextUtils.isEmpty(path)) return;
            File f = new File(path);
            if (!f.exists() || f.length() < 100 * 1024L) {
                prefs.edit().remove(KEY_PENDING_INSTALL_PATH).apply();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    return;
                }
            }
            prefs.edit().remove(KEY_PENDING_INSTALL_PATH).apply();
            installApkFile(activity, f);
        } catch (Throwable ignore) {
        }
    }

    public static void onActivityDestroyed(Activity activity) {
        cancelled.set(true);
        try { unregisterReceiver(activity); } catch (Throwable ignore) {}
        dismissProgress();
        pendingApkUrl = null;
        pendingFallbackUrl = null;
        pendingVersionName = null;
        pendingVersionCode = -1;
        pendingForceUpdate = false;
        pendingApkSize = 0L;
        pendingSha256 = null;
        downloadedFile = null;
        directProgressTick = null;
        downloadManagerTick = null;
        activeDownloadId = -1L;
    }

    private static File getApkDownloadDir(Activity activity) {
        File dir = new File(activity.getFilesDir(), "apks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void startDownload(Activity activity, String apkUrl, String versionName, boolean allowDirectFallback) {
        if (apkUrl == null || (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://"))) {
            Toast.makeText(activity, "无效的 APK 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            unregisterReceiver(activity);
            dismissProgress();

            String safeVersion = versionName != null ? versionName.replaceAll("[^a-zA-Z0-9._-]", "_") : "latest";
            String fileName = "libretv-update-" + safeVersion + ".apk";
            File dir = getApkDownloadDir(activity);
            File target = new File(dir, fileName);
            if (target.exists()) target.delete();
            downloadedFile = target;

            boolean dmOk = tryDownloadManager(activity, apkUrl, target, fileName, versionName);
            if (!dmOk) {
                if (allowDirectFallback) {
                    startDirectDownload(activity, apkUrl, pendingFallbackUrl, target, versionName);
                } else {
                    notifyDownloadFailed(activity);
                }
            }
        } catch (Throwable t) {
            if (allowDirectFallback) {
                String safeVersion = versionName != null ? versionName.replaceAll("[^a-zA-Z0-9._-]", "_") : "latest";
                File dir = getApkDownloadDir(activity);
                File target = new File(dir, "libretv-update-" + safeVersion + ".apk");
                if (target.exists()) target.delete();
                downloadedFile = target;
                startDirectDownload(activity, apkUrl, pendingFallbackUrl, target, versionName);
            } else {
                notifyDownloadFailed(activity);
            }
        }
    }

    private static boolean tryDownloadManager(Activity activity, String apkUrl, File target, String fileName, String versionName) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("LibreTV 更新");
            request.setDescription("正在下载 " + (versionName != null ? versionName : "新版本"));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.allowScanningByMediaScanner();
            if (pendingApkSize > 0) {
                try { request.addRequestHeader("Accept-Encoding", "identity"); } catch (Throwable ignore) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "apks/" + fileName);
            } else {
                request.setDestinationUri(Uri.fromFile(target));
            }
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return false;

            activeDownloadId = dm.enqueue(request);
            showProgress(activity);
            registerReceiver(activity, dm, target);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void notifyDownloadFailed(Activity activity) {
        MAIN.post(() -> {
            dismissProgress();
            if (pendingApkUrl != null && activity != null) {
                UpdateChecker.showDownloadFailedDialog(
                    activity, pendingApkUrl, pendingFallbackUrl, pendingVersionName,
                    pendingVersionCode, pendingForceUpdate, pendingApkSize, pendingSha256);
            } else if (activity != null) {
                Toast.makeText(activity, "下载失败，请检查网络", Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void showProgress(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad16 = dp(activity, 20);
        int pad24 = dp(activity, 24);
        root.setPadding(pad24, pad16, pad24, 0);
        FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.setLayoutParams(rootLp);

        TextView title = new TextView(activity);
        title.setText("正在下载更新");
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1A2436);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        title.setLayoutParams(titleLp);
        root.addView(title);

        progressMessage = new TextView(activity);
        progressMessage.setText("准备中…");
        progressMessage.setTextSize(14f);
        progressMessage.setTextColor(0xFF52607A);
        progressMessage.setPadding(0, dp(activity, 10), 0, 0);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressMessage.setLayoutParams(msgLp);
        root.addView(progressMessage);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 10));
        pbLp.topMargin = dp(activity, 14);
        pbLp.bottomMargin = pad16;
        progressBar.setLayoutParams(pbLp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF38BDF8));
            } catch (Throwable ignore) {}
        }
        root.addView(progressBar);

        AlertDialog.Builder ab = new AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false);
        if (pendingForceUpdate) {
            ab.setNegativeButton("退出应用", (d, w) -> activity.finishAffinity());
        }
        progressDialog = ab.create();
        progressDialog.setCanceledOnTouchOutside(false);
        try {
            progressDialog.show();
        } catch (Throwable ignore) {
            try {
                activity.runOnUiThread(() -> {
                    try { progressDialog.show(); } catch (Throwable ignore2) {}
                });
            } catch (Throwable ignore2) {}
        }
    }

    private static int dp(Context ctx, int dips) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (dips * d + 0.5f);
    }

    private static void updateProgress(final long bytes, final long total) {
        if (progressDialog == null || !progressDialog.isShowing()
            || progressBar == null || progressMessage == null) return;
        long now = System.currentTimeMillis();
        if (now - lastProgressUiMs < 150 && bytes != total) return;
        lastProgressUiMs = now;
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress((int) Math.min(100L, bytes * 100L / total));
            float pct = (100f * bytes / total);
            String msg = String.format(java.util.Locale.US, "下载中 %.1f / %.1f MB （%.1f%%）",
                bytes / 1048576f, total / 1048576f, pct);
            progressMessage.setText(msg);
        } else {
            progressBar.setIndeterminate(true);
            progressMessage.setText(String.format(java.util.Locale.US, "下载中 %.1f MB…", bytes / 1048576f));
        }
    }

    private static void dismissProgress() {
        if (directProgressTick != null) {
            try { MAIN.removeCallbacks(directProgressTick); } catch (Throwable ignore) {}
            directProgressTick = null;
        }
        if (downloadManagerTick != null) {
            try { MAIN.removeCallbacks(downloadManagerTick); } catch (Throwable ignore) {}
            downloadManagerTick = null;
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            try { progressDialog.dismiss(); } catch (Throwable ignore) {}
        }
        progressDialog = null;
        progressBar = null;
        progressMessage = null;
    }

    private static void registerReceiver(Activity activity, DownloadManager dm, File targetFile) {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != activeDownloadId) return;
                unregisterReceiver(activity);
                activeDownloadId = -1L;

                boolean ok = false;
                long bytesSoFar = 0L;
                long total = pendingApkSize;
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        int status = (statusIdx != -1) ? cursor.getInt(statusIdx) : DownloadManager.STATUS_FAILED;
                        if (bytesIdx != -1) bytesSoFar = cursor.getLong(bytesIdx);
                        if (totalIdx != -1) {
                            long t = cursor.getLong(totalIdx);
                            if (t > 0) total = t;
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            ok = true;
                            int localIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                            String localUri = (localIdx != -1) ? cursor.getString(localIdx) : null;
                            File resolved = resolveDownloadedFile(activity, localUri, targetFile, id, dm);
                            if (resolved != null) downloadedFile = resolved;
                        }
                    }
                } catch (Throwable ignore) {
                }
                dismissProgress();
                if (ok && downloadedFile != null && downloadedFile.exists()) {
                    handleDownloadedFile(activity, downloadedFile);
                } else {
                    String fallback = pendingFallbackUrl;
                    if (!TextUtils.isEmpty(fallback)) {
                        pendingFallbackUrl = "";
                        File dir = getApkDownloadDir(activity);
                        String safeVersion = pendingVersionName != null ? pendingVersionName.replaceAll("[^a-zA-Z0-9._-]", "_") : "latest";
                        File t2 = new File(dir, "libretv-update-" + safeVersion + ".apk");
                        if (t2.exists()) t2.delete();
                        downloadedFile = t2;
                        startDirectDownload(activity, fallback, "", t2, pendingVersionName);
                    } else {
                        notifyDownloadFailed(activity);
                    }
                }
            }
        };
        activity.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        final DownloadManager dmRef = dm;
        final int[] ticks = {0};
        downloadManagerTick = new Runnable() {
            @Override
            public void run() {
                if (activeDownloadId < 0 || progressDialog == null || !progressDialog.isShowing()) return;
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(activeDownloadId);
                try (Cursor cursor = dmRef.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int bi = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int ti = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        long b = (bi != -1) ? cursor.getLong(bi) : 0L;
                        long t = (ti != -1) ? cursor.getLong(ti) : pendingApkSize;
                        updateProgress(b, t);
                    }
                } catch (Throwable ignore) {
                }
                ticks[0]++;
                if (ticks[0] < 600) MAIN.postDelayed(this, 500);
            }
        };
        MAIN.post(downloadManagerTick);
    }

    private static File resolveDownloadedFile(Activity activity, String localUri, File fallback, long id, DownloadManager dm) {
        if (fallback != null && fallback.exists() && fallback.length() > 100 * 1024L) {
            return fallback;
        }
        if (!TextUtils.isEmpty(localUri)) {
            try {
                Uri u = Uri.parse(localUri);
                if ("file".equals(u.getScheme())) {
                    File f = new File(u.getPath());
                    if (f.exists() && f.length() > 0) return f;
                } else if ("content".equals(u.getScheme())) {
                    try {
                        InputStream in = activity.getContentResolver().openInputStream(u);
                        if (in != null) {
                            File outFile = fallback != null ? fallback :
                                new File(activity.getFilesDir(), "libretv-update-downloaded.apk");
                            copyStream(in, new FileOutputStream(outFile), null, 0L, 0L, false);
                            if (outFile.exists() && outFile.length() > 100 * 1024L) return outFile;
                        }
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        try {
            java.lang.reflect.Method m = DownloadManager.class.getMethod("getUriForDownloadedFile", long.class);
            Object r = m.invoke(dm, id);
            if (r != null) {
                Uri uri = (Uri) r;
                InputStream in = activity.getContentResolver().openInputStream(uri);
                if (in != null) {
                    File outFile = fallback != null ? fallback :
                        new File(activity.getFilesDir(), "libretv-update-downloaded.apk");
                    copyStream(in, new FileOutputStream(outFile), null, 0L, 0L, false);
                    if (outFile.exists() && outFile.length() > 100 * 1024L) return outFile;
                }
            }
        } catch (Throwable ignore) {
        }
        return (fallback != null && fallback.exists() && fallback.length() > 0) ? fallback : null;
    }

    private static void startDirectDownload(Activity activity, String primary, String fallback, File target, String versionName) {
        showProgress(activity);
        IO.execute(() -> {
            try {
                boolean ok = directDownloadOne(primary, target);
                if (!ok && !TextUtils.isEmpty(fallback)) {
                    if (target.exists()) target.delete();
                    ok = directDownloadOne(fallback, target);
                }
                final boolean finalOk = ok;
                MAIN.post(() -> {
                    dismissProgress();
                    if (finalOk && target.exists()) {
                        handleDownloadedFile(activity, target);
                    } else {
                        notifyDownloadFailed(activity);
                    }
                });
            } catch (Throwable t) {
                MAIN.post(() -> notifyDownloadFailed(null));
            }
        });
    }

    private static boolean directDownloadOne(String urlString, File target) {
        HttpURLConnection connection = null;
        try {
            int redirects = 0;
            String currentUrl = urlString;
            while (redirects < MAX_REDIRECTS) {
                redirects++;
                if (cancelled.get()) return false;
                URL u = new URL(currentUrl);
                connection = (HttpURLConnection) u.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("User-Agent", "LibreTV-Android-Update/1.0");
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive, */*");
                connection.setRequestProperty("Connection", "keep-alive");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307
                    || code == 308) {
                    String loc = connection.getHeaderField("Location");
                    try { connection.disconnect(); } catch (Throwable ignore) {}
                    connection = null;
                    if (TextUtils.isEmpty(loc)) return false;
                    currentUrl = new URL(u, loc).toString();
                    continue;
                }
                if (code == 429) {
                    try { connection.disconnect(); } catch (Throwable ignore) {}
                    return false;
                }
                if (code >= 400) {
                    try { connection.disconnect(); } catch (Throwable ignore) {}
                    return false;
                }
                try {
                    long contentLen = connection.getContentLengthLong();
                    if (contentLen <= 0 && pendingApkSize > 0) contentLen = pendingApkSize;
                    InputStream in = connection.getInputStream();
                    final long total = contentLen;
                    boolean ok = copyStream(in, new FileOutputStream(target), progressBytes -> {
                        long nowT = System.currentTimeMillis();
                        if (nowT - lastProgressUiMs >= 180) {
                            lastProgressUiMs = nowT;
                            MAIN.post(() -> updateProgress(progressBytes, total));
                        }
                    }, 0L, total, true);
                    if (ok && target.exists() && target.length() > 100 * 1024L) {
                        MAIN.post(() -> updateProgress(target.length(), total));
                        return true;
                    }
                    return false;
                } finally {
                    try { connection.disconnect(); } catch (Throwable ignore) {}
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (connection != null) connection.disconnect(); } catch (Throwable ignore) {}
        }
    }

    interface ProgressConsumer { void accept(long bytes); }

    private static boolean copyStream(InputStream in, FileOutputStream out,
                                      ProgressConsumer consumer, long initial, long total,
                                      boolean withProgress) throws Exception {
        try {
            byte[] buf = new byte[131072];
            long bytes = initial;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelled.get()) return false;
                out.write(buf, 0, n);
                bytes += n;
                if (withProgress && consumer != null) consumer.accept(bytes);
            }
            out.flush();
            return true;
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
            try { out.close(); } catch (Throwable ignore) {}
        }
    }

    private static void handleDownloadedFile(Activity activity, File file) {
        try {
            String expected = pendingSha256 != null ? pendingSha256.trim() : "";
            if (!expected.isEmpty() && expected.length() == 64) {
                String actual = sha256(file);
                if (!expected.equalsIgnoreCase(actual)) {
                    Toast.makeText(activity, "安装包校验失败（下载可能被劫持），请重试", Toast.LENGTH_LONG).show();
                    try { if (file.exists()) file.delete(); } catch (Throwable ignore) {}
                    String fallback = pendingFallbackUrl;
                    if (!TextUtils.isEmpty(fallback)) {
                        pendingFallbackUrl = "";
                        String safeVersion = pendingVersionName != null ? pendingVersionName.replaceAll("[^a-zA-Z0-9._-]", "_") : "latest";
                        File dir = getApkDownloadDir(activity);
                        File t2 = new File(dir, "libretv-update-" + safeVersion + ".apk");
                        downloadedFile = t2;
                        startDirectDownload(activity, fallback, "", t2, pendingVersionName);
                    }
                    return;
                }
            }
        } catch (Throwable ignore) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                try {
                    SharedPreferences.Editor ed = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                    ed.putString(KEY_PENDING_INSTALL_PATH, file.getAbsolutePath());
                    ed.apply();
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    Toast.makeText(activity, "请允许安装未知来源应用，然后返回即可继续安装", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(activity, "无法打开权限设置：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
                return;
            }
        }
        installApkFile(activity, file);
    }

    static void installApkFile(Activity activity, File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Throwable e) {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle("安装失败")
                    .setMessage("系统安装程序无法打开。\n\n可选操作：\n1. 使用系统文件管理器找到下载的 APK（/Android/data/" + activity.getPackageName() + "/files/Download/apks/）手动点击安装\n2. 分享 APK 到其他安装器（如应用商店/包管理器）")
                    .setPositiveButton("分享 APK", (d, w) -> {
                        try {
                            Intent share = new Intent(Intent.ACTION_SEND);
                            Uri shareUri;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                shareUri = FileProvider.getUriForFile(activity,
                                    activity.getPackageName() + ".fileprovider", apkFile);
                                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } else {
                                shareUri = Uri.fromFile(apkFile);
                            }
                            share.setType("application/vnd.android.package-archive");
                            share.putExtra(Intent.EXTRA_STREAM, shareUri);
                            activity.startActivity(Intent.createChooser(share, "分享 APK 到安装器"));
                        } catch (Throwable t) {
                            Toast.makeText(activity, "分享失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("打开文件管理器", (d, w) -> {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            File dir = apkFile.getParentFile();
                            Uri dirUri;
                            if (dir != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                dirUri = FileProvider.getUriForFile(activity,
                                    activity.getPackageName() + ".fileprovider", dir);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } else if (dir != null) {
                                dirUri = Uri.fromFile(dir);
                            } else {
                                dirUri = Uri.fromFile(apkFile);
                            }
                            i.setDataAndType(dirUri, "resource/folder");
                            if (i.resolveActivity(activity.getPackageManager()) == null) {
                                i.setDataAndType(Uri.fromFile(apkFile), "*/*");
                            }
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(Intent.createChooser(i, "选择文件管理器"));
                        } catch (Throwable t2) {
                            Toast.makeText(activity, "无法打开：" + t2.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            } catch (Throwable t2) {
                Toast.makeText(activity, "无法打开安装程序: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void unregisterReceiver(Context context) {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver);
            } catch (Throwable ignore) {
            }
            downloadReceiver = null;
        }
    }
}
