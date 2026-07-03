package com.libretv.android;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 使用 DownloadManager 下载 APK 并调起安装界面。
 */
public final class ApkUpdateDownloader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long activeDownloadId = -1L;
    private static BroadcastReceiver downloadReceiver;
    private static ProgressDialog progressDialog;

    private ApkUpdateDownloader() {
    }

    public static void start(Activity activity, String apkUrl, String versionName) {
        if (apkUrl == null || (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://"))) {
            Toast.makeText(activity, "无效的 APK 地址", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            unregisterReceiver(activity);
            dismissProgress();

            String fileName = "libretv-update-" + (versionName != null ? versionName.replaceAll("[^a-zA-Z0-9._-]", "_") : "latest") + ".apk";
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                dir = activity.getFilesDir();
            }
            File target = new File(dir, fileName);
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("LibreTV 更新");
            request.setDescription("正在下载 " + (versionName != null ? versionName : "新版本"));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                request.setDestinationUri(Uri.fromFile(target));
            }
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(activity, "无法启动下载", Toast.LENGTH_SHORT).show();
                return;
            }

            activeDownloadId = dm.enqueue(request);
            showProgress(activity);
            registerReceiver(activity, dm, target);
        } catch (Exception e) {
            Toast.makeText(activity, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void showProgress(Activity activity) {
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("正在下载更新");
        progressDialog.setMessage("请稍候…");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    private static void dismissProgress() {
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
                if (id != activeDownloadId) {
                    return;
                }
                unregisterReceiver(activity);
                dismissProgress();
                activeDownloadId = -1L;

                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                            installApk(activity, localUri, targetFile);
                            return;
                        }
                        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        Toast.makeText(activity, "下载失败，代码: " + reason, Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                Toast.makeText(activity, "下载失败", Toast.LENGTH_SHORT).show();
            }
        };
        activity.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        MAIN.post(new Runnable() {
            int ticks;

            @Override
            public void run() {
                if (activeDownloadId < 0 || progressDialog == null || !progressDialog.isShowing()) {
                    return;
                }
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(activeDownloadId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int bytes = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        int total = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (total > 0) {
                            progressDialog.setIndeterminate(false);
                            progressDialog.setProgress((int) (bytes * 100L / total));
                        }
                    }
                }
                ticks++;
                if (ticks < 600) {
                    MAIN.postDelayed(this, 500);
                }
            }
        });
    }

    private static void installApk(Activity activity, String localUri, File fallbackFile) {
        File apkFile = fallbackFile;
        if (localUri != null && !localUri.isEmpty()) {
            try {
                Uri parsed = Uri.parse(localUri);
                if ("file".equals(parsed.getScheme())) {
                    apkFile = new File(parsed.getPath());
                }
            } catch (Exception ignored) {
            }
        }
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(activity, "安装包不存在", Toast.LENGTH_SHORT).show();
            return;
        }
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
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开安装程序: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void unregisterReceiver(Context context) {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
    }
}