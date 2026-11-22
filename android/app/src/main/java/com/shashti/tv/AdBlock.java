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
    // the same public filter lists ad-blocking browsers use: EasyList + the Arabic list
    private static final String[][] FILTER_LISTS = {
        {"https://easylist.to/easylist/easylist.txt", "easylist.txt", "0"},
        {"https://easylist-downloads.adblockplus.org/Liste_AR.txt", "liste_ar.txt", "1"},
        // the lists Brave and uBlock Origin use on top of EasyList
        {"https://ublockorigin.github.io/uAssets/filters/filters.txt", "ubo_filters.txt", "0"},
        {"https://easylist.to/easylist/easyprivacy.txt", "easyprivacy.txt", "0"},
    };
    /** site -> CSS selectors to hide there (from the lists). */
    private final java.util.Map<String, StringBuilder> cosmetic = new java.util.concurrent.ConcurrentHashMap<>();
    /** Selectors hidden on every site (Arabic list only — EasyList's generic set is too heavy for a TV box). */
    private final StringBuilder genericCss = new StringBuilder();
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
        loadLearned();
        new Thread(this::loadAndRefresh).start();
    }

    public boolean enabled() { return prefs.getBoolean("on", true); }

    /** Sites the user allowed: nothing is blocked while browsing them. */
    public Set<String> allowed() { return new HashSet<>(prefs.getStringSet("allow", new HashSet<>())); }
    public boolean isAllowed(String site) { return site != null && allowed().contains(base(site)); }
    public void toggleAllow(String site) {
        Set<String> a = allowed(); String b = base(site);
        if (!a.remove(b)) a.add(b);
        prefs.edit().putStringSet("allow", a).apply();
    }
    public void setEnabled(boolean on) { prefs.edit().putBoolean("on", on).apply(); }

    private static final java.util.Set<String> BUILTIN_SET = new HashSet<>(java.util.Arrays.asList(BUILTIN));

    /** Only the short built-in list of ad networks (the big public list sometimes contains video hosts). */
    public boolean isKnownAdNetwork(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        while (host.indexOf('.') > 0) {
            if (BUILTIN_SET.contains(host) || learned.contains(host)) return true;
            host = host.substring(host.indexOf('.') + 1);
        }
        return false;
    }

    // ---- learned from ads the user closed: {site: [ad hosts]} ----
    private final Set<String> learned = Collections.synchronizedSet(new HashSet<>());
    private void loadLearned() {
        learned.clear();
        try {
            org.json.JSONObject j = new org.json.JSONObject(prefs.getString("learn", "{}"));
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) { org.json.JSONArray a = j.getJSONArray(it.next()); for (int i = 0; i < a.length(); i++) learned.add(a.getString(i)); }
        } catch (Exception ignored) { }
    }
    public void learn(String site, String host) {
        if (site == null || site.isEmpty() || host == null || host.isEmpty() || sameSite(site, host)) return;
        try {
            org.json.JSONObject j = new org.json.JSONObject(prefs.getString("learn", "{}"));
            org.json.JSONArray a = j.optJSONArray(site); if (a == null) a = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) if (host.equals(a.getString(i))) return;
            a.put(host.toLowerCase()); j.put(site, a);
            prefs.edit().putString("learn", j.toString()).apply();
            loadLearned();
        } catch (Exception ignored) { }
    }
    public void forget(String site) {
        try { org.json.JSONObject j = new org.json.JSONObject(prefs.getString("learn", "{}")); j.remove(site); prefs.edit().putString("learn", j.toString()).apply(); loadLearned(); }
        catch (Exception ignored) { }
    }

    public boolean isAd(String host) {
        if (host == null || !enabled()) return false;
        host = host.toLowerCase();
        while (host.indexOf('.') > 0) {
            if (hosts.contains(host) || learned.contains(host)) return true;
            host = host.substring(host.indexOf('.') + 1);
        }
        return false;
    }

    private void loadFilterLists(File dir) {
        for (String[] fl : FILTER_LISTS) {
            File f = new File(dir, fl[1]);
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) download(fl[0], f);
            if (f.exists()) parseFilters(f, fl[2].equals("1"));
        }
    }

    private void download(String url, File to) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000); con.setReadTimeout(30000);
            if (con.getResponseCode() == 200) {
                File tmp = new File(to.getPath() + ".tmp");
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                tmp.renameTo(to);
            }
            con.disconnect();
        } catch (Exception ignored) { }
    }

    private void parseFilters(File f, boolean generic) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[' || line.contains("#@#")) continue;
                int hh = line.indexOf("##");
                if (hh >= 0) {
                    String sel = line.substring(hh + 2).trim();
                    if (sel.isEmpty() || sel.startsWith("+js") || sel.startsWith("^") || sel.contains(":-abp-") || sel.contains(":has-text") || sel.contains(":xpath")
                            || sel.contains(":style(") || sel.contains(":remove") || sel.contains(":matches-") || sel.contains(":upward") || sel.contains(":watch-attr") || sel.contains("{")) continue;
                    String doms = line.substring(0, hh).trim();
                    if (doms.isEmpty()) {
                        if (generic) synchronized (genericCss) { if (genericCss.length() > 0) genericCss.append(','); genericCss.append(sel); }
                    } else {
                        for (String d : doms.split(",")) {
                            d = d.trim().toLowerCase();
                            if (d.isEmpty() || d.startsWith("~") || d.contains("*")) continue;
                            StringBuilder b = cosmetic.get(d);
                            if (b == null) { b = new StringBuilder(); cosmetic.put(d, b); }
                            synchronized (b) { if (b.length() > 0) b.append(','); b.append(sel); }
                        }
                    }
                    continue;
                }
                // network rules of the simple form ||host^ (optionally $third-party)
                if (line.startsWith("||")) {
                    int end = 2;
                    while (end < line.length() && "^/$*".indexOf(line.charAt(end)) < 0) end++;
                    if (end >= line.length() || line.charAt(end) != '^') continue;
                    String rest = line.substring(end + 1);
                    if (!(rest.isEmpty() || rest.equals("$third-party") || rest.equals("$popup") || rest.equals("$third-party,popup"))) continue;
                    String h = line.substring(2, end).toLowerCase();
                    if (h.contains(".")) hosts.add(h);
                }
            }
        } catch (Exception ignored) { }
    }

    /** CSS that hides the list's ad boxes on this host (site rules + parent domains + Arabic generic rules). */
    public String cssFor(String host) {
        if (host == null || !enabled()) return "";
        StringBuilder out = new StringBuilder();
        String h = host.toLowerCase();
        while (h.indexOf('.') > 0) {
            StringBuilder b = cosmetic.get(h);
            if (b != null) synchronized (b) { if (out.length() > 0) out.append(','); out.append(b); }
            h = h.substring(h.indexOf('.') + 1);
        }
        synchronized (genericCss) { if (genericCss.length() > 0) { if (out.length() > 0) out.append(','); out.append(genericCss); } }
        return out.toString();
    }

    private void loadAndRefresh() {
        loadFilterLists(cache.getParentFile());
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
    public static String base(String h) {
        if (h == null) return "";
        h = h.toLowerCase();
        String[] p = h.split("\\.");
        return p.length <= 2 ? h : p[p.length - 2] + "." + p[p.length - 1];
    }
}
