package com.shashti.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks the published version on GitHub Pages and installs a newer APK. */
public class Updater {
    static final String VERSION_URL = "https://mohamedgipril1792022-code.github.io/shashti-tv/version.json";
    static final String APK_URL = "https://github.com/mohamedgipril1792022-code/shashti-tv/releases/latest/download/shashti-tv.apk";

    static int myCode(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    static void check(Activity a, boolean manual) {
        if (!manual) {
            long last = a.getSharedPreferences("upd", Activity.MODE_PRIVATE).getLong("last", 0);
            if (System.currentTimeMillis() - last < 15L * 60 * 1000) return; // at most every 15 minutes
        }
        a.getSharedPreferences("upd", Activity.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply();
        if (manual) Toast.makeText(a, "بدوّر على تحديث…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(new String(get(VERSION_URL + "?t=" + System.currentTimeMillis()), "UTF-8"));
                int code = j.getInt("code");
                String name = j.optString("name", String.valueOf(code));
                a.runOnUiThread(() -> {
                    if (code <= myCode(a)) {
                        if (manual) Toast.makeText(a, "✅ عندك آخر نسخة", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(a)
                        .setTitle("🔄 فيه نسخة جديدة (" + name + ")")
                        .setMessage("تحب تحدّث دلوقتي؟ مواقعك وتكملاتك مش هتتمسح.")
                        .setPositiveButton("حدّث", (d, w) -> download(a))
                        .setNegativeButton("بعدين", null)
                        .show();
                });
            } catch (Exception e) {
                if (manual) a.runOnUiThread(() -> Toast.makeText(a, "تعذّر التحقق من التحديث", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static void download(Activity a) {
        Toast.makeText(a, "⬇ بنزّل التحديث… ثواني والتثبيت هيفتح لوحده", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                byte[] apk = get(APK_URL);
                File f = new File(a.getExternalFilesDir(null), "sajo-tv-update.apk");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(apk); }
                a.runOnUiThread(() -> install(a, f));
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "تعذّر تنزيل التحديث", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static File pending = null;

    /** Back from the "allow installs" screen: carry on with the install by itself. */
    static void resume(Activity a) {
        if (pending == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) return;
        File f = pending; pending = null;
        install(a, f);
    }

    private static void install(Activity a, File f) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            pending = f;
            Toast.makeText(a, "اسمح للتطبيق إنه يثبّت التحديثات وارجع — التثبيت هيكمّل لوحده", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Uri uri = Build.VERSION.SDK_INT >= 24
                ? FileProvider.getUriForFile(a, a.getPackageName() + ".files", f)
                : Uri.fromFile(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    private static byte[] get(String url) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toByteArray();
            } finally { c.disconnect(); }
        }
        throw new Exception("too many redirects");
    }
}
