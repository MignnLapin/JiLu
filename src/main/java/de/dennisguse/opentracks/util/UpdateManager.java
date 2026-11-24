package de.dennisguse.opentracks.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.dennisguse.opentracks.BuildConfig;
import de.dennisguse.opentracks.R;

/**
 * Checks GitHub releases for a newer APK and installs it via the system package installer.
 */
public final class UpdateManager {

    private static final String TAG = UpdateManager.class.getSimpleName();

    private static final String OWNER = "MignnLapin";
    private static final String REPO = "JiLu";
    private static final String API_URL = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    private static final String LATEST_PAGE_URL = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest";

    private static final String PREFS_NAME = "update_manager";
    private static final String PREF_PENDING_APK = "pending_apk";
    private static final String PREF_LAST_CHECK = "last_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static boolean checking = false;

    private UpdateManager() {
    }

    public static void check(Activity activity, boolean manual) {
        if (checking) {
            return;
        }

        long lastCheck = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(PREF_LAST_CHECK, 0);
        if (!manual && System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS) {
            return;
        }

        checking = true;
        if (manual) {
            Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        }

        Context appContext = activity.getApplicationContext();
        EXECUTOR.execute(() -> {
            ReleaseInfo release = null;
            try {
                release = fetchLatestRelease();
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
            }
            checking = false;

            final ReleaseInfo result = release;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }

                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();

                if (result == null || result.versionCode <= BuildConfig.VERSION_CODE) {
                    if (manual) {
                        Toast.makeText(activity, result == null ? R.string.update_check_failed : R.string.update_latest, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                promptInstall(activity, result);
            });
        });
    }

    private static void promptInstall(Activity activity, ReleaseInfo release) {
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available_title, release.tag))
                .setMessage(R.string.update_available_message)
                .setPositiveButton(R.string.update_now, (dialog, which) -> downloadAndInstall(activity, release))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void downloadAndInstall(Activity activity, ReleaseInfo release) {
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();

        EXECUTOR.execute(() -> {
            File apkFile = null;
            try {
                apkFile = downloadApk(activity.getApplicationContext(), release.apkUrl);
            } catch (Exception e) {
                Log.w(TAG, "Update download failed", e);
            }

            final File downloaded = apkFile;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (downloaded == null) {
                    Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                install(activity, downloaded);
            });
        });
    }

    /**
     * Continues an install that waited for the "install unknown apps" permission.
     */
    public static void resumePendingInstall(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String path = prefs.getString(PREF_PENDING_APK, null);
        if (path == null) {
            return;
        }

        File file = new File(path);
        if (!file.exists()) {
            prefs.edit().remove(PREF_PENDING_APK).apply();
            return;
        }

        if (activity.getPackageManager().canRequestPackageInstalls()) {
            prefs.edit().remove(PREF_PENDING_APK).apply();
            install(activity, file);
        }
    }

    private static void install(Activity activity, File apkFile) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_PENDING_APK, apkFile.getAbsolutePath()).apply();
            Toast.makeText(activity, R.string.update_install_permission, Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + BuildConfig.APPLICATION_ID));
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(settingsIntent);
            return;
        }

        Uri apkUri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(installIntent);
        } catch (Exception e) {
            Log.w(TAG, "No package installer available", e);
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        try {
            return fetchFromApi();
        } catch (Exception e) {
            Log.w(TAG, "GitHub API failed, falling back to release page", e);
            return fetchFromReleasePage();
        }
    }

    private static ReleaseInfo fetchFromApi() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "JiLu-Updater");

        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }

            String body = readAll(connection.getInputStream());
            JSONObject json = new JSONObject(body);
            String tag = json.optString("tag_name", "");
            String apkUrl = null;

            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }

            if (tag.isEmpty() || apkUrl == null) {
                throw new IllegalStateException("No APK asset found");
            }
            return new ReleaseInfo(tag, apkUrl, versionCodeFromTag(tag));
        } finally {
            connection.disconnect();
        }
    }

    private static ReleaseInfo fetchFromReleasePage() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_PAGE_URL).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "JiLu-Updater");

        try {
            connection.getResponseCode();
            String finalUrl = connection.getURL().toString();
            int tagIndex = finalUrl.indexOf("/tag/");
            if (tagIndex < 0) {
                throw new IllegalStateException("No release tag in " + finalUrl);
            }
            String tag = finalUrl.substring(tagIndex + "/tag/".length());
            String apkUrl = "https://github.com/" + OWNER + "/" + REPO + "/releases/download/" + tag + "/JiLu-" + tag + ".apk";
            return new ReleaseInfo(tag, apkUrl, versionCodeFromTag(tag));
        } finally {
            connection.disconnect();
        }
    }

    private static File downloadApk(Context context, String apkUrl) throws Exception {
        File updateDir = new File(context.getCacheDir(), "updates");
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            throw new IllegalStateException("Cannot create update directory");
        }
        File apkFile = new File(updateDir, "update.apk");
        if (apkFile.exists() && !apkFile.delete()) {
            throw new IllegalStateException("Cannot delete old update file");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "JiLu-Updater");

        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(apkFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }

        PackageInfo packageInfo = context.getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        if (packageInfo == null) {
            apkFile.delete();
            throw new IllegalStateException("Downloaded file is not an APK");
        }

        long downloadedVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
        if (downloadedVersion <= BuildConfig.VERSION_CODE) {
            apkFile.delete();
            throw new IllegalStateException("Downloaded APK is not newer");
        }

        return apkFile;
    }

    private static String readAll(InputStream input) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream in = input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, "UTF-8"));
            }
        }
        return builder.toString();
    }

    /**
     * Maps a release tag like "v2.1" to 20100 so that it can be compared to the versionCode.
     */
    private static long versionCodeFromTag(String tag) {
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String[] parts = version.split("\\.");
        long major = parts.length > 0 ? parseLong(parts[0]) : 0;
        long minor = parts.length > 1 ? parseLong(parts[1]) : 0;
        long patch = parts.length > 2 ? parseLong(parts[2]) : 0;
        return major * 10000 + minor * 100 + patch;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class ReleaseInfo {
        final String tag;
        final String apkUrl;
        final long versionCode;

        ReleaseInfo(String tag, String apkUrl, long versionCode) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.versionCode = versionCode;
        }
    }
}
