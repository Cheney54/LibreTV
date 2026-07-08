package com.libretv.android;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
    private static ProgressDialog progressDialog;
    private static String pendingApkUrl;
    private static String pendingFallbackUrl;
    private static String pendingVersionName;
    private static int pendingVersionCode;
    private static boolean pendingForceUpdate;
    private static long pendingApkSize;
    private static String pendingSha256;
    private static File downloadedFile;
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);
    private static Runnable directProgressTick;
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
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = activity.getFilesDir();
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
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = activity.getFilesDir();
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
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
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
            if (pendingApkUrl != null) {
                UpdateChecker.showDownloadFailedDialog(
                    activity, pendingApkUrl, pendingFallbackUrl, pendingVersionName,
                    pendingVersionCode, pendingForceUpdate, pendingApkSize, pendingSha256);
            } else {
                Toast.makeText(activity, "下载失败，请检查网络", Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void showProgress(Activity activity) {
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("正在下载更新");
        progressDialog.setMessage("准备中…");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        if (pendingForceUpdate) {
            progressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "退出应用", (d, w) -> activity.finishAffinity());
        }
        progressDialog.show();
    }

    private static void updateProgress(long bytes, long total) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        if (total > 0) {
            progressDialog.setIndeterminate(false);
            progressDialog.setProgress((int) Math.min(100L, bytes * 100L / total));
            float pct = (total > 0) ? (100f * bytes / total) : 0f;
            String msg = String.format(java.util.Locale.US, "下载中 %.1f / %.1f MB （%.1f%%）",
                bytes / 1048576f, total / 1048576f, pct);
            progressDialog.setMessage(msg);
        } else {
            progressDialog.setIndeterminate(true);
            progressDialog.setMessage(String.format(java.util.Locale.US, "下载中 %.1f MB…", bytes / 1048576f));
        }
    }

    private static void dismissProgress() {
        if (directProgressTick != null) {
            MAIN.removeCallbacks(directProgressTick);
            directProgressTick = null;
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
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
                        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (dir == null) dir = activity.getFilesDir();
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

        MAIN.post(new Runnable() {
            int ticks;
            @Override
            public void run() {
                if (activeDownloadId < 0 || progressDialog == null || !progressDialog.isShowing()) return;
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(activeDownloadId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int bi = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int ti = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        long b = (bi != -1) ? cursor.getLong(bi) : 0L;
                        long t = (ti != -1) ? cursor.getLong(ti) : pendingApkSize;
                        updateProgress(b, t);
                    }
                } catch (Throwable ignore) {
                }
                ticks++;
                if (ticks < 600) MAIN.postDelayed(this, 500);
            }
        });
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
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(60_000);
                connection.setRequestProperty("User-Agent", "LibreTV-Android/" + BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Connection", "keep-alive");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code == 429) {
                    connection.disconnect();
                    return false;
                }
                if (code >= 301 && code <= 308) {
                    String loc = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (loc == null) return false;
                    currentUrl = new URL(u, loc).toString();
                    continue;
                }
                if (code >= 400) {
                    connection.disconnect();
                    return false;
                }
                long total = pendingApkSize;
                String cl = connection.getHeaderField("Content-Length");
                if (!TextUtils.isEmpty(cl)) {
                    try { total = Long.parseLong(cl); } catch (Throwable ignore) {}
                }
                InputStream in = connection.getInputStream();
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (target.exists()) target.delete();
                final long finalTotal = total;
                lastProgressUiMs = 0L;
                long written = copyStream(in, new FileOutputStream(target), (b, t) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUiMs >= 180L) {
                        lastProgressUiMs = now;
                        MAIN.post(() -> updateProgress(b, finalTotal));
                    }
                }, 0L, total, true);
                connection.disconnect();
                boolean sizeOk = pendingApkSize <= 0 || Math.abs(written - pendingApkSize) < 4096L;
                return sizeOk && target.exists() && target.length() > 100 * 1024L;
            }
            return false;
        } catch (Throwable t) {
            if (connection != null) {
                try { connection.disconnect(); } catch (Throwable ignore) {}
            }
            return false;
        }
    }

    private interface ProgressListener {
        void onProgress(long bytes, long total);
    }

    private static long copyStream(InputStream in, FileOutputStream out, ProgressListener listener,
                                   long startBytes, long totalBytes, boolean closeIn) throws java.io.IOException {
        try {
            byte[] buf = new byte[8192];
            long bytes = startBytes;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelled.get()) break;
                out.write(buf, 0, n);
                bytes += n;
                if (listener != null) listener.onProgress(bytes, totalBytes);
            }
            out.flush();
            return bytes;
        } finally {
            try { out.close(); } catch (Throwable ignore) {}
            if (closeIn) {
                try { in.close(); } catch (Throwable ignore) {}
            }
        }
    }

    private static void handleDownloadedFile(Activity activity, File file) {
        if (file == null || !file.exists()) {
            notifyDownloadFailed(activity);
            return;
        }
        if (!TextUtils.isEmpty(pendingSha256)) {
            try {
                String actual = sha256(file);
                if (!pendingSha256.equalsIgnoreCase(actual)) {
                    Toast.makeText(activity, "文件校验失败，已删除", Toast.LENGTH_LONG).show();
                    try { file.delete(); } catch (Throwable ignore) {}
                    notifyDownloadFailed(activity);
                    return;
                }
            } catch (Throwable t) {
                try { file.delete(); } catch (Throwable ignore) {}
                notifyDownloadFailed(activity);
                return;
            }
        }
        installWithPermissionCheck(activity, file);
    }

    private static void installWithPermissionCheck(Activity activity, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean ok;
            try {
                ok = activity.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                ok = true;
            }
            if (!ok) {
                try {
                    SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    sp.edit().putString(KEY_PENDING_INSTALL_PATH, file.getAbsolutePath()).apply();
                } catch (Throwable ignore) {
                }
                try {
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
            activity.startActivity(install);
        } catch (Throwable e) {
            Toast.makeText(activity, "无法打开安装程序: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
