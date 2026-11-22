package com.shashti.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        {"https://ublockorigin.github.io/uAssets/filters/filters.txt", "ubo_filters.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2020.txt", "ubo_2020.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2021.txt", "ubo_2021.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2022.txt", "ubo_2022.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2023.txt", "ubo_2023.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2024.txt", "ubo_2024.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2025.txt", "ubo_2025.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/filters-2026.txt", "ubo_2026.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt", "ubo_quick.txt", "1"},
        {"https://ublockorigin.github.io/uAssets/filters/unbreak.txt", "ubo_unbreak.txt", "1"},
        {"https://easylist-downloads.adblockplus.org/Liste_AR.txt", "liste_ar.txt", "1"},
    };

    private final SharedPreferences prefs;
    private final File dir;
    private final Context ctx;
    private Map<String, String> aliases;
    private final LinkedHashSet<String> log = new LinkedHashSet<>();
    private volatile FilterEngine engine = new FilterEngine();
    public volatile boolean ready = false;

    public AdBlock(Context c) {
        prefs = c.getSharedPreferences("adblock", Context.MODE_PRIVATE);
        dir = c.getFilesDir();
        ctx = c.getApplicationContext();
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

    /** Settings for the document-start script in every frame: null when blocking is off here. */
    public String stealthConfig(String pageHost, String frameHost) {
        if (pageHost == null || !active(pageHost)) return "null";
        org.json.JSONArray js = new org.json.JSONArray();
        for (String raw : engine.scriptlets(frameHost)) js.put(new org.json.JSONArray(splitArgs(raw)));
        return "{\"stealth\":true,\"js\":" + js + "}";
    }

    /** "name, a\\, b, 'c'" -> [name, "a, b", c] */
    static List<String> splitArgs(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == ',') { cur.append(','); i++; }
            else if (c == ',') { out.add(unq(cur.toString().trim())); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(unq(cur.toString().trim()));
        return out;
    }
    private static String unq(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"' || s.charAt(0) == '`') && s.charAt(s.length() - 1) == s.charAt(0))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private boolean active(String pageHost) { return enabled() && ready && !isAllowed(pageHost); }

    public boolean shouldBlock(String url, String pageHost, int type) { return match(url, pageHost, type) != null; }

    /** null = let through, "" = block, otherwise a replacement resource name (see {@link #resource}). */
    public String match(String url, String pageHost, int type) {
        if (!active(pageHost)) return null;
        if (playerFile(url)) return null;
        String r = engine.match(url, pageHost, type);
        if (r == null) return null;
        String h = FilterEngine.hostOf(url.toLowerCase(java.util.Locale.ROOT));
        if (hostAllowed(pageHost, h)) return null;
        synchronized (log) { log.remove(h); log.add(h); if (log.size() > 60) log.remove(log.iterator().next()); }
        return r;
    }

    private static final String[] PLAYER_HOSTS = {"jwpcdn.com", "jwplayer.com", "jwpsrv.com", "jwplatform.com", "jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com"};
    private static final java.util.regex.Pattern PLAYER_PATH = java.util.regex.Pattern.compile(
        "/(jwplayer[^/]*|jquery[^/]*|jw[0-9.]*\\.js|provider\\.[a-z0-9.]+\\.js|hls(\\.light)?(\\.min)?\\.js|video(\\.min)?\\.js|videojs[^/]*|plyr[^/]*|clappr[^/]*|dash\\.all[^/]*|shaka-player[^/]*|flowplayer[^/]*|mediaelement[^/]*|artplayer[^/]*|dplayer[^/]*|fluidplayer[^/]*)(\\?|$)");

    /** The video player's own library (JW Player, hls.js, video.js...): never touched, even if a list matches it —
     *  JW Player stops with "failed to load (101102/104153)" when one of its parts is missing. Its ads come from ad servers, still blocked. */
    static boolean playerFile(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        String h = FilterEngine.hostOf(u);
        for (String p : PLAYER_HOSTS) if (h.equals(p) || h.endsWith("." + p)) return h.contains("jw") || PLAYER_PATH.matcher(u).find();
        int q = u.indexOf('?');
        String path = q > 0 ? u.substring(0, q) : u;
        return PLAYER_PATH.matcher(path.substring(path.lastIndexOf('/'))).find();
    }

    // ---- what was blocked on the current page, and letting one server through on one site

    public void clearLog() { synchronized (log) { log.clear(); } }
    public List<String> blockedHosts() { synchronized (log) { List<String> l = new ArrayList<>(log); java.util.Collections.reverse(l); return l; } }

    private Set<String> allowedHosts() { return prefs.getStringSet("ah", new HashSet<>()); }
    public boolean hostAllowed(String pageHost, String host) {
        return pageHost != null && allowedHosts().contains(FilterEngine.base(pageHost) + "|" + host);
    }
    public List<String> allowedHostsFor(String pageHost) {
        List<String> l = new ArrayList<>();
        String p = FilterEngine.base(pageHost) + "|";
        for (String s : allowedHosts()) if (s.startsWith(p)) l.add(s.substring(p.length()));
        return l;
    }
    public void toggleHost(String pageHost, String host) {
        Set<String> a = new HashSet<>(allowedHosts());
        String k = FilterEngine.base(pageHost) + "|" + host;
        if (!a.remove(k)) a.add(k);
        prefs.edit().putStringSet("ah", a).apply();
    }

    // ---- replacement resources (uBlock Origin's neutered scripts, empty VAST, silent media...)

    /** @return the asset to serve instead of a blocked request, or null to send an empty answer */
    public android.webkit.WebResourceResponse resource(String name) {
        if (name == null || name.isEmpty() || name.equals("empty") || name.equals("none")) return null;
        try {
            if (aliases == null) {
                Map<String, String> m = new HashMap<>();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(ctx.getAssets().open("redirect/aliases.txt"), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) { int t = line.indexOf('\t'); if (t > 0) m.put(line.substring(0, t), line.substring(t + 1)); }
                }
                aliases = m;
            }
            String f = aliases.get(name);
            if (f == null) return null;
            String mime = f.endsWith(".js") ? "application/javascript" : f.endsWith(".xml") ? "text/xml"
                    : f.endsWith(".json") ? "application/json" : f.endsWith(".gif") ? "image/gif"
                    : f.endsWith(".png") ? "image/png" : f.endsWith(".mp3") ? "audio/mpeg"
                    : f.endsWith(".mp4") ? "video/mp4" : f.endsWith(".css") ? "text/css"
                    : f.endsWith(".html") ? "text/html" : "text/plain";
            android.webkit.WebResourceResponse r = new android.webkit.WebResourceResponse(mime, "utf-8", ctx.getAssets().open("redirect/" + f));
            Map<String, String> hd = new HashMap<>();
            hd.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(hd);
            return r;
        } catch (Exception e) { return null; }
    }

    /** CSS rule hiding the list's ad elements on this host ("" when none). */
    public String css(String host, String pageHost) {
        if (!active(pageHost) || host == null) return "";
        // inside another site's frame (usually the video player) only that site's own rules apply
        boolean sameSite = FilterEngine.sameSite(host, pageHost);
        String sel = engine.hideSelectors(host, sameSite);
        return sel.isEmpty() ? "" : sel + "{display:none!important}";
    }

    public int rules() { return engine.ruleCount; }
}
