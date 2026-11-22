package com.shashti.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Ad blocking the way Brave / uBlock Origin do it: the public filter lists, applied by {@link FilterEngine}.
 * Lists are downloaded on first start and refreshed weekly in the background.
 */
public class AdBlock {
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    // url, file, use generic hiding rules from this list
    private static final String[][] LISTS = {
        {"https://easylist.to/easylist/easylist.txt", "easylist.txt", "1"},
        {"https://easylist.to/easylist/easyprivacy.txt", "easyprivacy.txt", "0"},
        {"https://ublockorigin.github.io/uAssets/filters/filters.txt", "ubo_filters.txt", "1"},
        {"https://easylist-downloads.adblockplus.org/Liste_AR.txt", "liste_ar.txt", "1"},
    };

    private final SharedPreferences prefs;
    private final File dir;
    private volatile FilterEngine engine = new FilterEngine();
    public volatile boolean ready = false;

    public AdBlock(Context c) {
        prefs = c.getSharedPreferences("adblock", Context.MODE_PRIVATE);
        dir = c.getFilesDir();
        new Thread(this::loadLists).start();
    }

    private void loadLists() {
        FilterEngine e = new FilterEngine();
        for (String[] l : LISTS) {
            File f = new File(dir, l[1]);
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) download(l[0], f);
            if (f.exists()) e.load(f, l[2].equals("1"));
        }
        engine = e;
        ready = true;
    }

    private static void download(String url, File to) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            if (con.getResponseCode() == 200) {
                File tmp = new File(to.getPath() + ".tmp");
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (tmp.length() > 1000) tmp.renameTo(to);
            }
            con.disconnect();
        } catch (Exception ignored) { }
    }

    public boolean enabled() { return prefs.getBoolean("on", true); }
    public void setEnabled(boolean on) { prefs.edit().putBoolean("on", on).apply(); }

    /** Sites the user switched blocking off for. */
    public boolean isAllowed(String host) { return host != null && prefs.getStringSet("allow2", new HashSet<>()).contains(FilterEngine.base(host)); }
    public void toggleAllow(String host) {
        Set<String> a = new HashSet<>(prefs.getStringSet("allow2", new HashSet<>()));
        String b = FilterEngine.base(host);
        if (!a.remove(b)) a.add(b);
        prefs.edit().putStringSet("allow2", a).apply();
    }

    private boolean active(String pageHost) { return enabled() && ready && !isAllowed(pageHost); }

    public boolean shouldBlock(String url, String pageHost, int type) {
        return active(pageHost) && engine.shouldBlock(url, pageHost, type);
    }

    /** CSS rule hiding the list's ad elements on this host ("" when none). */
    public String css(String host, String pageHost) {
        if (!active(pageHost) || host == null) return "";
        String sel = engine.hideSelectors(host);
        return sel.isEmpty() ? "" : sel + "{display:none!important}";
    }

    public int rules() { return engine.ruleCount; }
}
