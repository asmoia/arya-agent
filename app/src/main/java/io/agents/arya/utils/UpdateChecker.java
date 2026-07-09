// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Checks GitHub Releases for a newer version of Arya.
 * Prefers the asmoia/arya-agent repo; falls back to nothing if offline.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    /** Primary: Arya product releases */
    private static final String GITHUB_API =
            "https://api.github.com/repos/asmoia/arya-agent/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    public static void checkForUpdate(Activity activity) {
        boolean debugBuild = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        XLog.d(TAG, "Checking for updates on " + (debugBuild ? "debug" : "release") + " build");

        long lastCheck = KVUtils.INSTANCE.getLong("last_update_check", 0);
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            XLog.d(TAG, "Skipping update check, last check was "
                    + ((now - lastCheck) / 1000 / 60) + " min ago");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String currentVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;

                HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() != 200) {
                    XLog.w(TAG, "GitHub API returned " + conn.getResponseCode());
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject release = new JSONObject(sb.toString());
                String latestTag = release.getString("tag_name")
                        .replaceFirst("^v", "")
                        .replaceFirst("-.*", "");
                String htmlUrl = release.getString("html_url");

                // Prefer direct APK asset if present
                String apkUrl = htmlUrl;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", htmlUrl);
                            break;
                        }
                    }
                }

                XLog.i(TAG, "Current: " + currentVersion + ", Latest: " + latestTag);
                KVUtils.INSTANCE.putLong("last_update_check", now);

                if (isNewer(latestTag, currentVersion)) {
                    final String downloadUrl = apkUrl;
                    activity.runOnUiThread(() ->
                            showUpdateDialog(activity, latestTag, downloadUrl, debugBuild));
                }

            } catch (Exception e) {
                XLog.w(TAG, "Update check failed", e);
            }
        });
    }

    private static void showUpdateDialog(
            Activity activity, String latest, String url, boolean debugBuild
    ) {
        if (activity.isFinishing()) return;
        String msg = debugBuild
                ? "نسخه جدید آریا (v" + latest + ") آماده است.\n"
                + "توجه: بیلد debug فعلی ممکن است با release keystore فرق داشته باشد؛ "
                + "برای آپدیت بدون uninstall از APK امضاشدهٔ GitHub Releases استفاده کن."
                : "نسخه جدید آریا (v" + latest + ") آماده است. می‌توانی بدون uninstall آپدیت کنی.";
        new AlertDialog.Builder(activity)
                .setTitle("به‌روزرسانی آریا")
                .setMessage(msg)
                .setPositiveButton("دانلود", (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        XLog.w(TAG, "open update url failed", e);
                    }
                })
                .setNegativeButton("بعداً", null)
                .show();
    }

    /** Semver-ish compare: 0.2.0 > 0.1.0, 1.0 > 0.9.9 */
    static boolean isNewer(String latest, String current) {
        if (latest == null || current == null) return false;
        String[] la = latest.split("\\.");
        String[] cu = current.split("\\.");
        int n = Math.max(la.length, cu.length);
        for (int i = 0; i < n; i++) {
            int lv = i < la.length ? parsePart(la[i]) : 0;
            int cv = i < cu.length ? parsePart(cu[i]) : 0;
            if (lv != cv) return lv > cv;
        }
        return false;
    }

    private static int parsePart(String s) {
        try {
            String digits = s.replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) return 0;
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }
}
