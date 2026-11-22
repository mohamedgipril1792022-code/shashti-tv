package com.shashti.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Simple host-based ad blocker: a built-in list plus a public hosts list refreshed weekly. */
public class AdBlock {
    private static final String LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final String[] BUILTIN = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "imasdk.googleapis.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "onclkds.com",
        "adsterra.com", "adsterratools.com", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net",
        "mgid.com", "revcontent.com", "taboola.com", "outbrain.com", "adnxs.com", "adcash.com",
        "hilltopads.net", "hilltopads.com", "clickadu.com", "a-ads.com", "monetag.com", "highperformanceformat.com",
        "highperformancedformats.com", "profitableratecpm.com", "effectiveratecpm.com", "displayvertising.com",
        "pushwhy.com", "richpush.co", "evadav.com", "galaksion.com", "admaven.com", "ad-maven.com",
        "adskeeper.com", "adskeeper.co.uk", "yllix.com", "bidvertiser.com", "trafficstars.com", "tsyndicate.com",
        "realsrv.com", "zeydoo.com", "dolohen.com", "ak.amskiploomr.com", "acint.net", "unityads.unity3d.com",
        "applovin.com", "applvn.com", "inmobi.com", "smaato.net", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "adform.net", "yandex.ru/ads", "an.yandex.ru", "mc.yandex.ru",
        "clickaine.com", "adspyglass.com", "pemsrv.com", "betterads.co", "notifpush.com", "pushame.com"
    };

    private final Set<String> hosts = Collections.synchronizedSet(new HashSet<>());
    private final SharedPreferences prefs;
    private final File cache;
    public int blocked = 0;

    public AdBlock(Context c) {
        prefs = c.getSharedPreferences("adblock", Context.MODE_PRIVATE);
        cache = new File(c.getFilesDir(), "hosts.txt");
        Collections.addAll(hosts, BUILTIN);
        new Thread(this::loadAndRefresh).start();
    }

    public boolean enabled() { return prefs.getBoolean("on", true); }
    public void setEnabled(boolean on) { prefs.edit().putBoolean("on", on).apply(); }

    public boolean isAd(String host) {
        if (host == null || !enabled()) return false;
        host = host.toLowerCase();
        while (host.indexOf('.') > 0) {
            if (hosts.contains(host)) return true;
            host = host.substring(host.indexOf('.') + 1);
        }
        return false;
    }

    private void loadAndRefresh() {
        if (cache.exists()) parse(cache);
        if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > WEEK) {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(LIST_URL).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                if (con.getResponseCode() == 200) {
                    File tmp = new File(cache.getPath() + ".tmp");
                    try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    if (tmp.renameTo(cache)) parse(cache);
                }
                con.disconnect();
            } catch (Exception ignored) { }
        }
    }

    private void parse(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                if (p.length >= 2 && (p[0].equals("0.0.0.0") || p[0].equals("127.0.0.1"))) {
                    String h = p[1].toLowerCase();
                    if (!h.equals("localhost") && !h.equals("0.0.0.0") && h.contains(".")) hosts.add(h);
                }
            }
        } catch (Exception ignored) { }
    }

    /** Rough "same site" check: compares the last two labels of the host names. */
    public static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        return base(a).equals(base(b));
    }
    private static String base(String h) {
        h = h.toLowerCase();
        String[] p = h.split("\\.");
        return p.length <= 2 ? h : p[p.length - 2] + "." + p[p.length - 1];
    }
}
