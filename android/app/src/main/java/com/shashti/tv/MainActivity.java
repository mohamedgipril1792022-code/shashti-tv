package com.shashti.tv;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Sajo TV: a launcher page plus a TV-friendly browser.
 * Ads are handled only by the public filter lists (EasyList, EasyPrivacy, uBlock filters, Liste AR) through FilterEngine,
 * the same way Brave and uBlock Origin do — nothing on the pages is clicked, closed or edited by guesswork.
 */
public class MainActivity extends Activity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private static final int FILE_REQ = 41;

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private WebViewAssetLoader assets;
    private AdBlock adblock;
    private CursorView cursor;
    private android.widget.TextView backBtn, homeBtn;
    private android.widget.LinearLayout navBar;
    private volatile String pageHost = null;   // host of the page on screen (null on the launcher)
    private volatile String pageUrl = null;    // its address (continue watching is kept per page)
    private volatile String pageTitle = "";
    private boolean longPressed = false, backLong = false;
    private long lastPlayerTap = 0, pressStart = 0, lastToast = 0;
    private String pendingAdd = null;
    private WebView bgTab;
    private String bgUrl = null;

    // the hiding rules of the lists, applied in every frame (players included) as soon as a document starts
    private static final String CSS_JS =
        "(function go(n){try{if(!window.Android||!Android.css){if((n|0)<200)setTimeout(function(){go((n|0)+1)},25);return}var c=Android.css(location.hostname);if(!c)return;" +
        "var s=document.createElement('style');s.textContent=c;function put(){(document.head||document.documentElement).appendChild(s)}" +
        "if(document.documentElement)put();document.addEventListener('DOMContentLoaded',put)}catch(e){}})()";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        freshStartOnce();
        android.app.UiModeManager um = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        isTv = (um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
                || getPackageManager().hasSystemFeature("android.software.leanback")
                || !getPackageManager().hasSystemFeature("android.hardware.touchscreen");
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        buildNavButtons();
        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        if (!isTv) { buildPhoneMenuButton(); buildZoomBar(); }   // phone only: the box stays exactly as it was
        setContentView(root);

        assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        adblock = new AdBlock(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(isTv);           // phone: pages at the phone's own width (readable text), not zoomed out
        s.setUseWideViewPort(true);
        // pinch with two fingers to zoom in/out (no +/- buttons on screen)
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);            // new windows go through onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", ""));
        baseUA = s.getUserAgentString();

        // don't announce the app's package in every request (some video hosts refuse in-app browsers)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.<String>emptySet());

        web.addJavascriptInterface(new Bridge(), "Android");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, CSS_JS, Collections.singleton("*"));
            String st = fullMode() ? asset("stealth.js") : null;   // box: only after the user says so (see fullMode)
            // continue watching (progress + resume): every page, box and phone
            String wt = asset("watch.js");
            if (wt != null) WebViewCompat.addDocumentStartJavaScript(web, wt, Collections.singleton("*"));
            // YouTube's ad blocking alone, on youtube.com only (the box gets it without the rest)
            if (!fullMode()) {
                String yt = asset("yt.js");
                if (yt != null) WebViewCompat.addDocumentStartJavaScript(web, yt, new java.util.HashSet<>(java.util.Arrays.asList("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com", "https://*.youtube.com")));
            }
            if (st != null) WebViewCompat.addDocumentStartJavaScript(web, st, Collections.singleton("*"));
            // phone: pages that forbid zooming are allowed to zoom (two-finger pinch)
            if (!isTv) WebViewCompat.addDocumentStartJavaScript(web,
                "(function(){function fix(){var m=document.querySelectorAll('meta[name=viewport]');for(var i=0;i<m.length;i++){var c=m[i].getAttribute('content')||'';" +
                "var n=c.replace(/user-scalable\\s*=\\s*(no|0)/ig,'user-scalable=yes').replace(/maximum-scale\\s*=\\s*[0-9.]+/ig,'maximum-scale=5');if(n!==c)m[i].setAttribute('content',n)}}" +
                "try{fix();var mo=new MutationObserver(fix);mo.observe(document.documentElement,{childList:true,subtree:true});document.addEventListener('DOMContentLoaded',function(){fix();setTimeout(function(){mo.disconnect()},3000)})}catch(e){}})()",
                Collections.singleton("*"));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse a = assets.shouldInterceptRequest(r.getUrl());
                if (a != null) return a;
                String page = pageHost;
                if (page == null || r.isForMainFrame()) return null;
                String m = adblock.match(r.getUrl().toString(), page, typeOf(r));
                if (m == null) return null;
                // a neutered stand-in (empty ad script, empty VAST...) keeps players that expect the ad code working,
                // and an answer that looks successful keeps anti-adblock checks from noticing
                WebResourceResponse rep = fullMode() ? adblock.resource(m) : null;
                if (rep == null && fullMode()) rep = adblock.resource(stubFor(typeOf(r), r.getUrl().toString()));
                if (rep == null) rep = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                java.util.Map<String, String> hd = new java.util.HashMap<>();
                String origin = r.getRequestHeaders() == null ? null : r.getRequestHeaders().get("Origin");
                hd.put("Access-Control-Allow-Origin", origin != null ? origin : "*");
                if (origin != null) hd.put("Access-Control-Allow-Credentials", "true");
                hd.put("Cache-Control", "no-store");
                rep.setResponseHeaders(hd);
                return rep;
            }
            // pages that change without loading (YouTube, other one-page sites): keep the address/title current
            @Override
            public void doUpdateVisitedHistory(WebView v, String u, boolean reload) {
                if (u != null && pageHost != null && !u.startsWith("https://appassets.androidplatform.net/")) { pageUrl = u; if (v.getTitle() != null) pageTitle = v.getTitle(); }
            }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (Build.VERSION.SDK_INT >= 23) logLine("❌ " + e.getDescription() + " ← " + r.getUrl());
            }
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                logLine("❌ HTTP " + e.getStatusCode() + " ← " + r.getUrl());
            }
            @Override
            public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                logLine("❌ شهادة SSL " + e.getPrimaryError() + " ← " + e.getUrl());
                h.cancel();
            }
            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                pageHost = (u == null || u.startsWith("https://appassets.androidplatform.net/")) ? null : Uri.parse(u).getHost();
                pageUrl = pageHost == null ? null : u;
                boxes = new float[0][];
                adblock.clearLog();
                if (stretched) setStretched(false);
                applyZoom();
                updatePhoneButton();
                synchronized (pageLog) { pageLog.clear(); }
            }
            @Override
            public void onPageFinished(WebView v, String u) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && pageHost != null) { v.evaluateJavascript(CSS_JS, null); ensureStealth(null); }
                if (isTv) v.postDelayed(MainActivity.this::refreshBoxes, 600);
                if (pageHost != null) { pageUrl = u; pageTitle = v.getTitle() == null ? "" : v.getTitle(); }   // the remote's box map: TV only
                updatePhoneButton();
                if (pendingAdd != null && atHome()) {
                    final String js = pendingAdd;
                    pendingAdd = null;
                    v.postDelayed(() -> v.evaluateJavascript(js, null), 300);
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                // frames built from about:/data:/blob: (many players) must load; only app links (intent://, market://) on the page itself stop
                if (sc == null || sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
                if (!"http".equals(sc) && !"https".equals(sc)) { logLine("⛔ رابط تطبيق: " + r.getUrl()); return r.isForMainFrame() || r.hasGesture(); }
                // box: YouTube opens in its TV interface (made for the remote, full-screen video)
                if (r.isForMainFrame() && ytRoute(v, r.getUrl().toString())) return true;
                // a page that jumps to a listed ad page (a popup-ad listed in the filter lists)
                if (pageHost != null && r.isForMainFrame() && adblock.shouldBlock(r.getUrl().toString(), pageHost, FilterEngine.POPUP)) {
                    blockedToast();
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String t) { if (pageHost != null && t != null) pageTitle = t; }
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    logLine("⚠ " + m.message() + " (" + Uri.parse(String.valueOf(m.sourceId())).getHost() + ")");
                return true;
            }
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view; customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
                view.requestFocus();
                // the mouse stays on top of the full-screen player and jumps between its buttons
                if (isTv) { cursor.setVisibility(View.VISIBLE); cursor.bringToFront(); }
                else {
                    // phone: turn to landscape and hide the bars while the video is full screen
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
                updatePhoneButton();
                if (mediaBar != null) mediaBar.bringToFront();
                setStretched(true);
                autoCloseAds();
            }
            @Override
            public void onHideCustomView() { exitFullscreen(); }

            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message msg) {
                WebView tmp = new WebView(MainActivity.this);
                final boolean[] done = {false};
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView t, WebResourceRequest r) {
                        if (!done[0]) { done[0] = true; newWindow(r.getUrl().toString(), gesture); }
                        return true;
                    }
                    @Override
                    public void onPageStarted(WebView t, String u, android.graphics.Bitmap f) {
                        if (!done[0] && u != null && u.startsWith("http")) { done[0] = true; t.stopLoading(); newWindow(u, gesture); }
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_REQ); }
                catch (Exception e) { fileCallback = null; Toast.makeText(MainActivity.this, "مفيش مدير ملفات على الجهاز", Toast.LENGTH_LONG).show(); return false; }
                return true;
            }
        });

        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
        getSharedPreferences("upd", MODE_PRIVATE).edit().putLong("last", 0).apply();
        web.postDelayed(() -> Updater.check(this, false), 4000);
    }

    /** Version 55 starts the ad blocking from scratch: forget every per-site setting from earlier versions. */
    private void freshStartOnce() {
        android.content.SharedPreferences p = getSharedPreferences("adblock", MODE_PRIVATE);
        if (p.getBoolean("fresh55", false)) return;
        boolean snap = p.getBoolean("snap", true);
        p.edit().clear().putBoolean("snap", snap).putBoolean("fresh55", true).apply();
        new File(getFilesDir(), "hosts.txt").delete();
    }

    /** Resource type for the filter rules (WebView does not tell it, so it is worked out from the request). */
    private static int typeOf(WebResourceRequest r) {
        Map<String, String> h = r.getRequestHeaders();
        String accept = h == null ? null : h.get("Accept");
        if (accept != null && accept.contains("text/html")) return FilterEngine.SUBDOCUMENT;
        String path = r.getUrl().getPath();
        path = path == null ? "" : path.toLowerCase();
        if (path.endsWith(".js") || path.endsWith(".mjs")) return FilterEngine.SCRIPT;
        if (path.endsWith(".css")) return FilterEngine.STYLESHEET;
        if (path.matches(".*\\.(png|jpe?g|gif|webp|svg|ico|bmp|avif)$")) return FilterEngine.IMAGE;
        if (path.matches(".*\\.(woff2?|ttf|otf|eot)$")) return FilterEngine.FONT;
        if (path.matches(".*\\.(mp4|m3u8|ts|webm|mp3|m4a|m4s|mpd|aac)$")) return FilterEngine.MEDIA;
        if (accept != null) {
            if (accept.startsWith("image/")) return FilterEngine.IMAGE;
            if (accept.startsWith("text/css")) return FilterEngine.STYLESHEET;
        }
        if (h != null && "XMLHttpRequest".equals(h.get("X-Requested-With"))) return FilterEngine.XHR;
        return FilterEngine.OTHER;
    }

    /** A new window (target=_blank / window.open). */
    private void newWindow(String url, boolean gesture) {
        if (atHome()) { web.loadUrl(url); return; }                                 // tiles of the launcher
        String host = Uri.parse(url).getHost();
        if (adblock.shouldBlock(url, pageHost, FilterEngine.POPUP)) { blockedToast(); return; }  // listed popup ad
        if (!gesture) { blockedToast(); return; }                                   // opened without a click
        if (FilterEngine.sameSite(pageHost, host)) { web.loadUrl(url); return; }    // same site: open here
        // another site after a click: keep the page you are on, open it quietly behind (Menu can show it)
        bgUrl = url;
        if (bgTab == null) {
            bgTab = new WebView(this);
            WebSettings bs = bgTab.getSettings();
            bs.setJavaScriptEnabled(true);
            bs.setDomStorageEnabled(true);
            bs.setMediaPlaybackRequiresUserGesture(true);
            bs.setSupportMultipleWindows(true);
            bgTab.setWebChromeClient(new WebChromeClient());
            bgTab.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    String sc = r.getUrl().getScheme();
                    return !"http".equals(sc) && !"https".equals(sc);
                }
            });
            bgTab.setAlpha(0f);
            root.addView(bgTab, 0, new FrameLayout.LayoutParams(1, 1));
        }
        bgTab.loadUrl(url);
        toast("📑 صفحة اتفتحت في الخلفية (Menu لو عايز تشوفها)");
    }

    private void blockedToast() { toast("🛡 اتمنع إعلان"); }

    private void toast(String t) {
        long now = System.currentTimeMillis();
        if (now - lastToast < 3500) return;
        lastToast = now;
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ on-screen buttons

    /** Phone or TV: the phone gets touch (no pointer), a ⋮ button for the site menu, and turns for full-screen video. */
    private boolean isTv = true;
    private android.widget.TextView phoneMenu;

    private void buildPhoneMenuButton() {
        float d = getResources().getDisplayMetrics().density;
        phoneMenu = new android.widget.TextView(this);
        phoneMenu.setText("⋮");
        phoneMenu.setTextColor(0xFFFFFFFF);
        phoneMenu.setTextSize(24);
        phoneMenu.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xB3000000);
        bg.setStroke((int) (1.5f * d), 0x66FFFFFF);
        phoneMenu.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (46 * d), (int) (46 * d), android.view.Gravity.BOTTOM | android.view.Gravity.LEFT);
        lp.setMargins((int) (14 * d), 0, 0, (int) (18 * d));
        phoneMenu.setOnClickListener(v -> showSiteMenu());
        phoneMenu.setVisibility(View.GONE);
        root.addView(phoneMenu, lp);
    }

    private void updatePhoneButton() {
        boolean onSite = !atHome() && customView == null;
        if (phoneMenu != null) phoneMenu.setVisibility(onSite ? View.VISIBLE : View.GONE);
        if (zoomBar != null) zoomBar.setVisibility(onSite && !stretched ? View.VISIBLE : View.GONE);
        if (cursor != null && cursor.getVisibility() == View.VISIBLE) cursor.bringToFront();
    }

    // ---- A− / A+ at the top of every site: text size, remembered per site
    private android.widget.LinearLayout zoomBar;
    private android.widget.TextView zoomMinus, zoomPlus;

    private android.widget.TextView zoomButton(String t) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.TextView b = new android.widget.TextView(this);
        b.setText(t);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(isTv ? 18 : 16);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xB3000000); bg.setCornerRadius(12 * d); bg.setStroke((int) (1.5f * d), 0x66FFFFFF);
        b.setBackground(bg);
        b.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), (int) (4 * d));
        return b;
    }

    private void buildZoomBar() {
        float d = getResources().getDisplayMetrics().density;
        zoomBar = new android.widget.LinearLayout(this);
        zoomMinus = zoomButton("A−");
        zoomPlus = zoomButton("A+");
        zoomBar.addView(zoomMinus);
        android.widget.LinearLayout.LayoutParams g = new android.widget.LinearLayout.LayoutParams(-2, -2);
        g.setMargins((int) (8 * d), 0, 0, 0);
        zoomBar.addView(zoomPlus, g);
        zoomMinus.setOnClickListener(v -> zoomText(-10));
        zoomPlus.setOnClickListener(v -> zoomText(10));
        zoomBar.setAlpha(0.85f);
        // bottom, next to the ⋮ button: the top is where sites keep their own search/menu buttons
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.LEFT);
        lp.setMargins((int) (70 * d), 0, 0, (int) (26 * d));
        zoomBar.setVisibility(View.GONE);
        root.addView(zoomBar, lp);
        // drag the A−/A+ bar anywhere with a finger; the place is remembered
        android.content.SharedPreferences zp = getSharedPreferences("adblock", MODE_PRIVATE);
        // ⋮ and A−/A+ move together, as one group
        final View[] group = phoneMenu != null ? new View[]{zoomBar, phoneMenu} : new View[]{zoomBar};
        for (View gv : group) { gv.setTranslationX(zp.getFloat("zbx", 0)); gv.setTranslationY(zp.getFloat("zby", 0)); }
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy, tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); tx = zoomBar.getTranslationX(); ty = zoomBar.getTranslationY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) > 10 * d) moved = true;
                        if (moved) {
                            // keep the whole group on the screen
                            float minX = -1e9f, maxX = 1e9f, minY = -1e9f, maxY = 1e9f;
                            for (View gv : group) {
                                minX = Math.max(minX, -gv.getLeft()); maxX = Math.min(maxX, root.getWidth() - gv.getRight());
                                minY = Math.max(minY, -gv.getTop()); maxY = Math.min(maxY, root.getHeight() - gv.getBottom());
                            }
                            float nx = Math.max(minX, Math.min(maxX, tx + dx)), ny = Math.max(minY, Math.min(maxY, ty + dy));
                            for (View gv : group) { gv.setTranslationX(nx); gv.setTranslationY(ny); }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) zp.edit().putFloat("zbx", zoomBar.getTranslationX()).putFloat("zby", zoomBar.getTranslationY()).apply();
                        else v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        };
        zoomMinus.setOnTouchListener(drag);
        zoomPlus.setOnTouchListener(drag);
        if (phoneMenu != null) phoneMenu.setOnTouchListener(drag);
    }

    private String zoomKey() { return "tz_" + (pageHost == null ? "" : FilterEngine.base(pageHost)); }

    private void zoomText(int delta) {
        if (pageHost == null) return;
        android.content.SharedPreferences p = getSharedPreferences("adblock", MODE_PRIVATE);
        int z = Math.max(50, Math.min(250, p.getInt(zoomKey(), 100) + delta));
        p.edit().putInt(zoomKey(), z).apply();
        web.getSettings().setTextZoom(z);
        Toast.makeText(this, "حجم الكلام " + z + "٪", Toast.LENGTH_SHORT).show();
    }

    private void applyZoom() {
        if (isTv) return;
        web.getSettings().setTextZoom(pageHost == null ? 100 : getSharedPreferences("adblock", MODE_PRIVATE).getInt(zoomKey(), 100));
    }

    private void buildNavButtons() {
        navBar = new android.widget.LinearLayout(this);
        navBar.setOrientation(android.widget.LinearLayout.VERTICAL);
        backBtn = navButton("⬅", 0xCCE50914);
        homeBtn = navButton("🏠", 0xCC333333);
        navBar.addView(backBtn);
        android.widget.LinearLayout.LayoutParams gap = new android.widget.LinearLayout.LayoutParams(-2, -2);
        gap.setMargins(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        navBar.addView(homeBtn, gap);
        navBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2);
        bl.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT;
        bl.setMargins((int) (30 * getResources().getDisplayMetrics().density), 0, 0, 0);
        root.addView(navBar, bl);
        backBtn.setOnClickListener(v -> goBackPage());
        homeBtn.setOnClickListener(v -> web.loadUrl(HOME));
    }

    private android.widget.TextView navButton(String text, int color) {
        android.widget.TextView b = new android.widget.TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(22);
        b.setGravity(android.view.Gravity.CENTER);
        int sz = (int) (52 * getResources().getDisplayMetrics().density);
        b.setWidth(sz); b.setHeight(sz);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color); bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setStroke((int) (2 * getResources().getDisplayMetrics().density), 0xAAFFFFFF);
        b.setBackground(bg);
        return b;
    }

    private boolean onButton(View b) { return onButton(b, 14); }
    private boolean onButton(View b, int padDp) {
        int[] p = new int[2], q = new int[2];
        b.getLocationOnScreen(p); root.getLocationOnScreen(q);
        float x = p[0] - q[0], y = p[1] - q[1], pad = padDp * getResources().getDisplayMetrics().density;
        return cursor.x >= x - pad && cursor.x <= x + b.getWidth() + pad && cursor.y >= y - pad && cursor.y <= y + b.getHeight() + pad;
    }
    private int repeat = 0;

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        if (!isTv) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        updatePhoneButton();
        web.evaluateJavascript("(function(){var f=window.__shFs;if(!f)return;f.el.setAttribute('style',f.st);(f.anc||[]).forEach(function(z){if(z[1]==null)z[0].removeAttribute('style');else z[0].setAttribute('style',z[1])});document.documentElement.style.overflow='';if(document.body)document.body.style.overflow='';window.__shFs=null})()", null);
        setStretched(false);
    }

    private boolean atHome() {
        String u = web.getUrl();
        return u == null || u.startsWith("https://appassets.androidplatform.net/");
    }

    private void goBackPage() {
        if (web.canGoBack()) web.goBack(); else web.loadUrl(HOME);
    }

    // ------------------------------------------------------------------ remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (ytTvPage() && customView == null && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_GUIDE) {
            // YouTube TV: the remote drives it exactly like the YouTube app on a TV box (no mouse)
            if (cursor.getVisibility() == View.VISIBLE) cursor.setVisibility(View.GONE);
            stopGlide();
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() >= 6 && !backLong) {
                    backLong = true; web.loadUrl(HOME); Toast.makeText(this, "🏠 الرئيسية", Toast.LENGTH_SHORT).show();
                } else if (e.getAction() == KeyEvent.ACTION_UP) { if (!backLong) ytKey(KeyEvent.KEYCODE_ESCAPE); backLong = false; }
                else if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) backLong = false;
                return true;
            }
            if (!web.hasFocus()) web.requestFocus();
            return web.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_BACK) {   // short = back, held = home
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) backLong = false;
                else if (!backLong && e.getRepeatCount() >= 6) {
                    backLong = true;
                    exitFullscreen();
                    if (!atHome()) { web.loadUrl(HOME); Toast.makeText(this, "🏠 الرئيسية", Toast.LENGTH_SHORT).show(); }
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!backLong) onBack();
                backLong = false;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_GUIDE) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (atHome()) web.evaluateJavascript("window.tileMenuFocused&&tileMenuFocused()", null);
                else showSiteMenu();
            }
            return true;
        }
        boolean pointer = !atHome();
        cursor.setVisibility(pointer && isTv ? View.VISIBLE : View.GONE);
        navBar.setVisibility(View.GONE);   // the side ⬅/🏠 buttons are off (Back and Menu do it)
        if (!pointer) return super.dispatchKeyEvent(e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getAction() != KeyEvent.ACTION_DOWN) { stopGlide(); hoverAtCursorNow(); return true; }
                lastKeyAt = SystemClock.uptimeMillis();
                if (e.getRepeatCount() == 0) pressStart = SystemClock.uptimeMillis();
                // one press jumps to the next box (link/button) or nudges the mouse; holding the arrow = the mouse
                // glides smoothly and picks up speed (like a browser's mouse), frame by frame instead of jumping
                // full screen: the mouse is locked and jumps from button to button of the player
                boolean haveFs; synchronized (fsBoxes) { haveFs = !fsBoxes.isEmpty(); }
                // full screen: a press jumps to the player's next button (or nudges the mouse if none is there); holding glides freely
                if (stretched && haveFs && e.getRepeatCount() == 0) { snapFs(code); return true; }
                if (e.getRepeatCount() == 0) { if (snapMode() && !stretched) snapTo(code); else freeMove(code, 0); }
                else if (glideKey != code) startGlide(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) longPressed = false;
                    else if (!longPressed && e.getRepeatCount() >= 8) { longPressed = true; showSiteMenu(); }
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (!longPressed) tapAtCursor();
                    longPressed = false;
                }
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (e.getAction() == KeyEvent.ACTION_DOWN) scrollPage(root.getHeight() * 0.8f);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_CHANNEL_UP:
                if (e.getAction() == KeyEvent.ACTION_DOWN) scrollPage(-root.getHeight() * 0.8f);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // never touches a video or a player frame (allowfullscreen / embed), and only things laid over the page
    private static final String BACK_JS = "(function(){if(window.__sajoCloseAd){if(__sajoCloseAd())return 'ad';var n=0;for(var i=0;i<frames.length;i++){try{frames[i].postMessage({sajoCloseAd:1},'*');n++}catch(e){}}if(n)return 'ask';var f=window.__shFs;if(f){f.el.setAttribute('style',f.st);(f.anc||[]).forEach(function(z){if(z[1]==null)z[0].removeAttribute('style');else z[0].setAttribute('style',z[1])});if(document.body)document.body.style.overflow='';window.__shFs=null;document.documentElement.style.overflow='';return 'fs'}return ''}var vw=innerWidth,vh=innerHeight,va=vw*vh,fs=document.fullscreenElement||document.webkitFullscreenElement;function isPlayer(e){if(e.tagName==='VIDEO'||e.querySelector('video'))return true;var fr=e.tagName==='IFRAME'?[e]:e.querySelectorAll('iframe');for(var i=0;i<fr.length;i++){var x=fr[i];if(x.hasAttribute('allowfullscreen')||x.hasAttribute('webkitallowfullscreen')||/fullscreen/i.test(x.getAttribute('allow')||'')||/embed|player|video|stream/i.test(x.src||''))return true}return false}function cands(e){var l=[];for(var n=e;n&&n!==document.body&&n!==document.documentElement;n=n.parentElement){var st=getComputedStyle(n),p=st.position;if(p!=='fixed'&&!(p==='absolute'&&(parseInt(st.zIndex)||0)>=1000))continue;var r=n.getBoundingClientRect();var a=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0))*Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));if(a<va*0.04)continue;if(r.top<=4&&r.height<vh*0.2&&r.width>vw*0.8)continue;l.unshift(n)}return l}var pts=[[.5,.5],[.3,.45],[.7,.45],[.5,.75],[.3,.7],[.7,.7],[.5,.92],[.5,.3]];for(var i=0;i<pts.length;i++){var x=vw*pts[i][0],y=vh*pts[i][1];var st=document.elementsFromPoint?document.elementsFromPoint(x,y):[document.elementFromPoint(x,y)];var top=st[0];if(!top)continue;var l=cands(top);for(var j=0;j<l.length;j++){var c=l[j];if(fs&&(c.contains(fs)||fs.contains(c)))continue;if(isPlayer(c))continue;c.remove();document.documentElement.style.overflow='';if(document.body)document.body.style.overflow='';return 'ad'}}return ''})()";

    private volatile boolean adClosedInFrame = false;

    // best icon the page offers (apple-touch-icon / largest icon), else a 256px favicon by Google
    private static final String ICON_JS = "(function(){var best='',bs=-1,ls=document.querySelectorAll('link[rel~=icon],link[rel*=apple-touch-icon],link[rel=\"shortcut icon\"]');" +
        "for(var i=0;i<ls.length;i++){var l=ls[i],h=l.href;if(!h)continue;var sz=16,m=/(\\d+)x(\\d+)/.exec(l.getAttribute('sizes')||'');if(m)sz=+m[1];if(/apple-touch/i.test(l.rel))sz=Math.max(sz,180);if(/\\.svg/i.test(h))sz=Math.max(sz,150);if(sz>bs){bs=sz;best=h}}" +
        "if(bs<96)best='https://www.google.com/s2/favicons?domain='+location.hostname+'&sz=256';return best})()";

    // ---- continue watching: where each episode page was left, and its next episode
    private static final String NEXT_JS = "(function(){var re=/(الحلق[هة]\\s*(ال)?(تالي[هة]|القادم[هة])|التالي[هة]?|next\\s*(episode|ep)?|الحلقة\\s*الجاية)/i,a=document.querySelectorAll('a[href]'),here=location.href.split('#')[0];" +
        "for(var i=0;i<a.length;i++){var t=(a[i].innerText||a[i].title||a[i].getAttribute('aria-label')||'').trim();if(t.length>40||!re.test(t))continue;var h=a[i].href.split('#')[0];if(h&&h!==here&&/^https?:/.test(h))return h}return ''})()";

    private org.json.JSONObject watchAll() {
        try { return new org.json.JSONObject(getSharedPreferences("adblock", MODE_PRIVATE).getString("watch", "{}")); }
        catch (Exception e) { return new org.json.JSONObject(); }
    }

    private synchronized void saveWatch(String url, double t, double d, String title, String next) {
        try {
            org.json.JSONObject all = watchAll();
            org.json.JSONObject o = all.optJSONObject(url);
            if (o == null) o = new org.json.JSONObject();
            o.put("t", t); o.put("d", d); o.put("at", System.currentTimeMillis());
            if (title != null && !title.isEmpty()) o.put("title", title);
            if (next != null && !next.isEmpty()) o.put("next", next);
            all.put(url, o);
            // keep the latest 80 pages
            if (all.length() > 80) {
                String oldest = null; long oa = Long.MAX_VALUE;
                java.util.Iterator<String> it = all.keys();
                while (it.hasNext()) { String k = it.next(); long a = all.optJSONObject(k) == null ? 0 : all.optJSONObject(k).optLong("at"); if (a < oa) { oa = a; oldest = k; } }
                if (oldest != null) all.remove(oldest);
            }
            getSharedPreferences("adblock", MODE_PRIVATE).edit().putString("watch", all.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static final String UNSTRETCH_JS = "(function(){var f=window.__shFs;if(!f)return '';f.el.setAttribute('style',f.st);(f.anc||[]).forEach(function(z){if(z[1]==null)z[0].removeAttribute('style');else z[0].setAttribute('style',z[1])});document.documentElement.style.overflow='';if(document.body)document.body.style.overflow='';window.__shFs=null;return 'fs'})()";
    private static final String ASK_FRAMES_JS = "(function(){var c=window.__sajoCloseAd&&__sajoCloseAd();for(var i=0;i<frames.length;i++){try{frames[i].postMessage({sajoCloseAd:1},'*')}catch(e){}}return c?'ad':''})()";

    /** Ads that pop up when the video goes full screen: looked for right away, and again a little later. */
    private void autoCloseAds() {
        for (int t : new int[]{700, 1800, 3500}) web.postDelayed(() -> ensureStealth(() -> web.evaluateJavascript(ASK_FRAMES_JS, null)), t);
    }
    private Toast mediaToast;
    private boolean stretched = false;
    private android.widget.TextView mediaBar;
    private final Runnable hideBar = () -> { if (mediaBar != null) mediaBar.setVisibility(View.GONE); };

    private void setStretched(boolean on) {
        stretched = on;
        cursor.ring = null;
        updatePhoneButton();
        if (on) cursor.bringToFront();
        root.removeCallbacks(fsBoxRefresher);
        if (on) { synchronized (fsBoxes) { fsBoxes.clear(); } root.postDelayed(fsBoxRefresher, 400); }
        if (!on) web.evaluateJavascript("window.__sajoFsClear&&__sajoFsClear()", null);
        boolean pointer = !atHome();
        cursor.setVisibility(pointer && isTv ? View.VISIBLE : View.GONE);
        navBar.setVisibility(View.GONE);   // the side ⬅/🏠 buttons are off (Back and Menu do it)
        if (!on) hideBar.run();
    }

    /** Our own control strip over the video: what the remote does + the time. */
    private void showMediaBar(String text) {
        if (mediaBar == null) {
            float d = getResources().getDisplayMetrics().density;
            mediaBar = new android.widget.TextView(this);
            mediaBar.setTextColor(0xFFFFFFFF);
            mediaBar.setTextSize(22);
            mediaBar.setGravity(android.view.Gravity.CENTER);
            mediaBar.setPadding((int) (24 * d), (int) (12 * d), (int) (24 * d), (int) (12 * d));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xCC000000); bg.setCornerRadius(18 * d); bg.setStroke((int) (2 * d), 0x66FFFFFF);
            mediaBar.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = (int) (36 * d);
            root.addView(mediaBar, lp);
        }
        mediaBar.setText(text);
        mediaBar.setVisibility(View.VISIBLE);
        mediaBar.bringToFront();
        root.removeCallbacks(hideBar);
        root.postDelayed(hideBar, 4000);
    }

    private void media(String cmd, int d) {
        ensureStealth(() -> media2(cmd, d));
    }

    private void media2(String cmd, int d) {
        web.evaluateJavascript("window.__sajoMedia&&__sajoMedia('" + cmd + "'," + d + ")", null);
    }

    private static String hms(double t) {
        int s = (int) Math.max(0, t), h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(java.util.Locale.US, "%d:%02d", m, s % 60);
    }

    private String stealthSrc;
    /** Our page helpers (Back closes ads, 🧹, video controls) — injected now if the page doesn't have them yet
     *  (old WebViews can't add them at page start). */
    /** The page helpers (stealth.js: YouTube, continue watching, Back for ads in players, 🧹, the guard...) and the
     *  replacement resources. They never reached the box before (the assets folder wasn't in the repo), so the box
     *  gets them only when the user turns them on; the phone has them. */
    private boolean fullMode() { return !isTv || getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("boxfull", false); }

    private void ensureStealth(Runnable then) {
        if (!fullMode()) { if (then != null) then.run(); return; }
        web.evaluateJavascript("!!window.__sajoMedia", v -> {
            if (!"true".equals(v)) {
                if (stealthSrc == null) stealthSrc = asset("stealth.js");
                if (stealthSrc != null) web.evaluateJavascript(stealthSrc, null);
            }
            if (then != null) then.run();
        });
    }

    private void zapAtCursor() {
        ensureStealth(this::zapAtCursor2);
    }

    private void zapAtCursor2() {
        float d = getResources().getDisplayMetrics().density;
        if (cursor.x < 0) { Toast.makeText(this, "حرّك الماوس على الإعلان الأول", Toast.LENGTH_SHORT).show(); return; }
        zapReplied = false;
        web.evaluateJavascript("window.__sajoZap?__sajoZap(" + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + "):'none'",
            v -> {
                if (v != null && (v.contains("ok") || v.contains("video"))) { zapReplied = true; zapResult(v); }
                // nothing found right under the pointer (or the frame didn't answer): look for any ad over the video
                else web.postDelayed(() -> { if (!zapReplied) zapFallback(); }, v != null && v.contains("frame") ? 600 : 0);
            });
    }

    private volatile boolean zapReplied = false;
    private final java.util.List<String> probeLines = new java.util.ArrayList<>();

    private void probeAtCursor() {
        float d = getResources().getDisplayMetrics().density;
        synchronized (probeLines) { probeLines.clear(); }
        final int x = Math.round(Math.max(0, cursor.x) / d), y = Math.round(Math.max(0, cursor.y) / d);
        ensureStealth(() -> web.evaluateJavascript("window.__sajoProbe&&__sajoProbe(" + x + "," + y + ")", null));
        web.postDelayed(() -> {
            StringBuilder b = new StringBuilder("الماوس: " + x + "," + y + "\n\n");
            synchronized (probeLines) { for (String l : probeLines) b.append(l).append("\n\n"); }
            new android.app.AlertDialog.Builder(this).setTitle("🔬 اللي تحت الماوس").setMessage(b.toString()).setPositiveButton("تمام", null).show();
        }, 1200);
    }

    private void zapFallback() {
        adClosedInFrame = false;
        web.evaluateJavascript(ASK_FRAMES_JS, v -> web.postDelayed(() -> {
            boolean ok = adClosedInFrame || (v != null && v.contains("ad"));
            Toast.makeText(this, ok ? "🧹 اتشال" : "مفيش إعلان لقيته — جرّب تحط الماوس على الإعلان نفسه", Toast.LENGTH_SHORT).show();
        }, 400));
    }

    private void zapResult(String v) {
        runOnUiThread(() -> Toast.makeText(this,
            v.contains("ok") ? "🧹 اتشال" : v.contains("video") ? "ده الفيديو نفسه — مش هلمسه" : "مفيش حاجة تحت الماوس أشيلها",
            Toast.LENGTH_SHORT).show());
    }

    private void onBack() {
        if (customView != null) {
            // an ad over the full-screen video closes first; otherwise leave full screen
            adClosedInFrame = false;
            web.evaluateJavascript(ASK_FRAMES_JS, v -> web.postDelayed(() -> {
                if (adClosedInFrame || (v != null && v.contains("ad"))) Toast.makeText(this, "🛡 اتقفل إعلان", Toast.LENGTH_SHORT).show();
                else exitFullscreen();
            }, 350));
            return;
        }
        if (!atHome()) {
            // the player stretched with a double OK comes back first
            // 1) the player stretched with a double OK comes back, 2) an ad laid over the page closes, 3) previous page
            ensureStealth(() -> web.evaluateJavascript(BACK_JS, v -> {
                if (v != null && v.contains("ask")) {
                    // the ad may be inside the player's frame: the frames answer through Android.adClosed()
                    adClosedInFrame = false;
                    web.postDelayed(() -> {
                        if (adClosedInFrame) Toast.makeText(this, "🛡 اتقفل إعلان", Toast.LENGTH_SHORT).show();
                        // no ad: leave the full-screen player first, then go back
                        else web.evaluateJavascript(UNSTRETCH_JS, r -> { if ((r != null && r.contains("fs")) || stretched) setStretched(false); else goBackPage(); });
                    }, 350);
                }
                else if (v != null && v.contains("ad")) Toast.makeText(this, "🛡 اتقفل إعلان", Toast.LENGTH_SHORT).show();
                else if ((v != null && v.contains("fs")) || stretched) setStretched(false);
                else goBackPage();
            }));
            return;
        }
        web.evaluateJavascript(
            "(function(){var m=document.getElementById('modal'),p=document.getElementById('push');" +
            "if(window.endMove&&endMove())return 1;" +
            "if(m&&m.classList.contains('open')){closeModal();return 1}" +
            "if(p&&p.classList.contains('open')){p.classList.remove('open');return 1}return 0})()",
            v -> { if (!"1".equals(v)) finish(); });
    }

    private void freeMove(int code, int repeatCount) {
                float d = getResources().getDisplayMetrics().density;
                int w = root.getWidth(), h = root.getHeight();
                long held = SystemClock.uptimeMillis() - pressStart;
                // short tap = fine move; holding speeds up smoothly
                // starts fine (to land on a small × button) and speeds up the longer the arrow is held
                float step = d * (repeatCount == 0 ? 14 : Math.min(6 + held / 6f, 140));
                if (cursor.x < 0) { cursor.x = w / 2f; cursor.y = h / 2f; }
                float margin = 30 * d;
                cursor.ring = null;
                if (stretched && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    // full screen: no page to scroll — the pointer goes all the way down and out of sight
                    cursor.y = Math.max(0, Math.min(h + 40 * d, cursor.y + (code == KeyEvent.KEYCODE_DPAD_UP ? -step : step)));
                } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (cursor.y - step < margin) scrollPage(-Math.min(step * 3f, 300 * d)); else cursor.y -= step;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (cursor.y + step > h - margin) scrollPage(Math.min(step * 3f, 300 * d)); else cursor.y += step;
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    cursor.x = Math.max(0, cursor.x - step);
                } else {
                    cursor.x = Math.min(w - 4 * d, cursor.x + step);
                }
                cursor.invalidate();
                hoverAtCursor();
    }

    // ---- full screen: boxes of the player's buttons, reported by every frame in screen coordinates
    private final java.util.List<float[]> fsBoxes = new java.util.ArrayList<>();
    private final Runnable fsBoxRefresher = new Runnable() {
        @Override public void run() {
            if (!stretched) return;
            web.evaluateJavascript("window.__sajoBoxes&&__sajoBoxes()", null);
            root.postDelayed(this, 1500);
        }
    };

    private void snapFs(int code) {
        float d = getResources().getDisplayMetrics().density;
        float cx = cursor.x / d, cy = cursor.y / d;
        if (cursor.x < 0) { cx = root.getWidth() / d / 2; cy = root.getHeight() / d / 2; }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        float[] best = null; double bs = 1e18;
        synchronized (fsBoxes) {
            for (float[] b : fsBoxes) {
                float bx = b[0] + b[2] / 2, by = b[1] + b[3] / 2;
                float ex = bx - cx, ey = by - cy;
                float main = dx != 0 ? ex * dx : ey * dy, side = dx != 0 ? Math.abs(ey) : Math.abs(ex);
                if (main < 4) continue;                        // not in that direction
                if (side > main * 2.5f + 40) continue;           // too far off to the side
                double sc = main + side * 2.2;
                if (sc < bs) { bs = sc; best = b; }
            }
        }
        if (best == null) {                                    // nothing that way: move the mouse anyway (never frozen) and wake the controls
            freeMove(code, 0);
            web.evaluateJavascript("window.__sajoBoxes&&__sajoBoxes()", null);
            return;
        }
        cursor.x = (best[0] + best[2] / 2) * d;
        cursor.y = (best[1] + best[3] / 2) * d;
        cursor.ring = new android.graphics.RectF((best[0] - 4) * d, (best[1] - 4) * d, (best[0] + best[2] + 4) * d, (best[1] + best[3] + 4) * d);
        cursor.invalidate();
        hoverAtCursorNow();
    }

    private int glideKey = 0;
    private final Runnable glide = new Runnable() {
        @Override public void run() {
            if (glideKey == 0) return;
            if (SystemClock.uptimeMillis() - lastKeyAt > 300) { glideKey = 0; return; }   // key released somewhere we didn't see
            float d = getResources().getDisplayMetrics().density;
            int w = root.getWidth(), h = root.getHeight();
            long held = SystemClock.uptimeMillis() - pressStart;
            float v = d * Math.min(2.5f + held / 70f, 24f);          // per frame: slow and precise at first, then fast
            if (cursor.x < 0) { cursor.x = w / 2f; cursor.y = h / 2f; }
            float margin = 30 * d;
            cursor.ring = null;
            switch (glideKey) {
                case KeyEvent.KEYCODE_DPAD_UP: if (stretched) cursor.y = Math.max(0, cursor.y - v); else if (cursor.y - v < margin) scrollPage(-v * 2.5f); else cursor.y -= v; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (stretched) cursor.y = Math.min(h + 40 * d, cursor.y + v); else if (cursor.y + v > h - margin) scrollPage(v * 2.5f); else cursor.y += v; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: cursor.x = Math.max(0, cursor.x - v); break;
                default: cursor.x = Math.min(w - 4 * d, cursor.x + v);
            }
            cursor.invalidate();
            if (glideYt) { mouseHover(); ytEdgeScroll(glideKey); } else hoverAtCursor();
            root.postOnAnimation(this);
        }
    };
    private boolean glideYt = false;
    private void startGlide(int code) { glideYt = ytTvPage(); glideKey = code; root.removeCallbacks(glide); root.postOnAnimation(glide); }
    private void stopGlide() { glideKey = 0; root.removeCallbacks(glide); }

    private boolean autoFs() { return getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("autofs", true); }

    private boolean snapMode() { return getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("snap", true); }

    private static final String COLLECT_JS = "(function(){var vw=innerWidth,vh=innerHeight,sx=scrollX,sy=scrollY,out=[],seen=[];var sel='a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,[role=button],[role=link],[role=tab],[role=menuitem],[onclick],[tabindex]:not([tabindex=\"-1\"]),label[for]';function ok(e,r){if(r.width<8||r.height<8)return false;if(r.bottom+sy<0)return false;var st=getComputedStyle(e);return st.visibility!=='hidden'&&st.display!=='none'&&parseFloat(st.opacity)>0.1}function add(e){var r=e.getBoundingClientRect();if(!ok(e,r))return; for(var i=0;i<seen.length;i++){var p=seen[i];if(p.contains(e)||e.contains(p)){var q=p.getBoundingClientRect();if(Math.abs(q.width-r.width)<12&&Math.abs(q.height-r.height)<12)return}} seen.push(e);out.push([Math.round(r.left+sx),Math.round(r.top+sy),Math.round(r.width),Math.round(r.height)])}var a=document.querySelectorAll(sel);for(var i=0;i<a.length&&out.length<1200;i++)add(a[i]);var b=document.querySelectorAll('div,span,li,img,article,section,figure,h2,h3');for(var j=0;j<b.length&&j<1500&&out.length<1500;j++){var e=b[j];if(e.closest(sel))continue;try{if(getComputedStyle(e).cursor==='pointer')add(e)}catch(x){}}return JSON.stringify({sx:sx,sy:sy,vw:vw,vh:vh,dh:document.documentElement.scrollHeight,it:out})})()";
    private volatile float[][] boxes = new float[0][];
    private volatile float scrX = 0, scrY = 0, docH = 0;
    private volatile long boxesAt = 0;
    private boolean collecting = false;

    private void refreshBoxes() {
        if (collecting || atHome()) return;
        collecting = true;
        web.evaluateJavascript(COLLECT_JS, v -> {
            collecting = false;
            try {
                org.json.JSONObject j = new org.json.JSONObject(new org.json.JSONTokener(v).nextValue().toString());
                org.json.JSONArray it = j.getJSONArray("it");
                float[][] b = new float[it.length()][];
                for (int i = 0; i < it.length(); i++) {
                    org.json.JSONArray q = it.getJSONArray(i);
                    b[i] = new float[]{(float) q.getDouble(0), (float) q.getDouble(1), (float) q.getDouble(2), (float) q.getDouble(3)};
                }
                boxes = b; scrX = (float) j.getDouble("sx"); scrY = (float) j.getDouble("sy"); docH = (float) j.getDouble("dh");
                boxesAt = System.currentTimeMillis();
            } catch (Exception ignored) { }
        });
    }

    private final Runnable boxRefresher = new Runnable() {
        @Override public void run() {
            // not while an arrow is held (the page is busy moving)
            if (!atHome() && customView == null && SystemClock.uptimeMillis() - lastKeyAt > 600) refreshBoxes();
            web.postDelayed(this, 2500);
        }
    };
    private boolean refresherOn = false;
    private volatile long lastKeyAt = 0;

    private void snapTo(int code) {
        final float d = getResources().getDisplayMetrics().density;
        if (!refresherOn) { refresherOn = true; web.post(boxRefresher); }
        if (cursor.x < 0) { cursor.x = root.getWidth() / 2f; cursor.y = root.getHeight() / 2f; }
        float[][] bx0 = boxes;
        if (bx0.length == 0 || System.currentTimeMillis() - boxesAt > 8000) { refreshBoxes(); freeMove(code, 0); return; }
        float[][] bx = bx0;
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        float vw = root.getWidth() / d, vh = root.getHeight() / d;
        float cx = scrX + cursor.x / d, cy = scrY + cursor.y / d;   // pointer in page coordinates
        float[] best = null; double bs = Double.MAX_VALUE;
        for (float[] b : bx) {
            float l = b[0], t = b[1], rr = b[0] + b[2], bb = b[1] + b[3];
            if (cx >= l && cx <= rr && cy >= t && cy <= bb) continue;            // the box we are on
            float ex = (l + rr) / 2, ey = (t + bb) / 2;
            double along, perp;
            if (dy != 0) {
                along = dy > 0 ? t - cy : cy - bb;
                if (along < -b[3] / 2 || (dy > 0 ? ey <= cy + 4 : ey >= cy - 4)) continue;
                perp = cx < l ? l - cx : cx > rr ? cx - rr : 0;                   // 0 when the box is straight below/above
            } else {
                along = dx > 0 ? l - cx : cx - rr;
                if (dx > 0 ? ex <= cx + 4 : ex >= cx - 4) continue;
                perp = cy < t ? t - cy : cy > bb ? cy - bb : 0;
            }
            double sc = Math.max(along, 0) + perp * 3;
            boolean onScreen = bb > scrY && t < scrY + vh;
            if (!onScreen) sc += 400;                                            // prefer what is visible
            if (sc < bs) { bs = sc; best = b; }
        }
        if (best == null) {
            boolean atTop = scrY <= 1, atBottom = scrY + vh >= docH - 2;
            if ((dy < 0 && !atTop) || (dy > 0 && !atBottom)) { scrollPage(dy * root.getHeight() * 0.6f); { web.removeCallbacks(boxesLater); web.postDelayed(boxesLater, 350); } }
            else freeMove(code, 0);   // top/bottom of the page or sideways: let the pointer move on its own
            return;
        }
        float ex = best[0] + Math.min(best[2], vw) / 2, ey = best[1] + Math.min(best[3], vh * 0.8f) / 2;
        float targetY = ey - scrY;
        if (targetY < 40 || targetY > vh - 40) {                                 // off screen: bring it to the middle
            float newY = Math.max(0, Math.min(ey - vh / 2, Math.max(0, docH - vh)));
            web.evaluateJavascript("window.scrollTo({left:" + Math.round(scrX) + ",top:" + Math.round(newY) + ",behavior:'instant'})", null);
            scrY = newY;
            targetY = ey - newY;
            { web.removeCallbacks(boxesLater); web.postDelayed(boxesLater, 350); }
        }
        cursor.x = Math.max(4 * d, Math.min((ex - scrX) * d, root.getWidth() - 8 * d));
        cursor.y = Math.max(4 * d, Math.min(targetY * d, root.getHeight() - 8 * d));
        cursor.invalidate();
        hoverAtCursor();
        web.evaluateJavascript("(function(x,y){var e=document.elementFromPoint(x,y);if(window.__shPrev){window.__shPrev.style.outline=window.__shPrevO||'';window.__shPrev=null}" +
            "if(!e)return;window.__shPrev=e;window.__shPrevO=e.style.outline;e.style.outline='3px solid #e50914'})(" +
            Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")", null);
    }

    // holding an arrow sends ~30 presses a second: scrolling, hovering and re-reading the page's boxes are
    // coalesced so a heavy page never gets more than it can do (it used to freeze)
    private final Runnable boxesLater = this::refreshBoxes;
    private float pendingScroll = 0;
    private boolean scrolling = false;
    private long lastHover = 0;

    private void scrollPage(float dy) {
        web.removeCallbacks(boxesLater);
        web.postDelayed(boxesLater, 500);
        // the page itself scrolls natively: instant and smooth while an arrow is held (no JS, no fighting
        // the site's own smooth-scroll animation, which made the screen shake)
        int dir = dy > 0 ? 1 : -1;
        if (dy != 0 && pendingScroll == 0 && !scrolling && web.canScrollVertically(dir)) { web.scrollBy(0, Math.round(dy)); return; }
        pendingScroll += dy;
        if (scrolling) return;
        scrolling = true;
        float d = getResources().getDisplayMetrics().density;
        int css = Math.round(pendingScroll / d);
        pendingScroll = 0;
        // scroll the page and, if it scrolls inside a box, the element under the pointer
        web.evaluateJavascript("(function(){var y=" + css + ",x=" + Math.round(cursor.x / d) + ",yy=" + Math.round(cursor.y / d) + ";" +
            "var el=document.elementFromPoint(x,yy),b=window.scrollY;window.scrollBy({top:y,behavior:'instant'});if(window.scrollY!==b)return;" +
            "while(el&&el!==document.body){var s=getComputedStyle(el).overflowY;if((s=='auto'||s=='scroll')&&el.scrollHeight>el.clientHeight){el.scrollBy({top:y,behavior:'instant'});return}el=el.parentElement}})()",
            v -> { scrolling = false; if (pendingScroll != 0) scrollPage(0); });
    }

    private void hoverAtCursor() {
        long t = SystemClock.uptimeMillis();
        if (t - lastHover < 120) return;
        lastHover = t;
        MotionEvent m = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cursor.x, cursor.y, 0);
        m.setSource(android.view.InputDevice.SOURCE_MOUSE);
        (customView != null ? customView : web).dispatchGenericMotionEvent(m);
        m.recycle();
    }

    private void hoverAtCursorNow() { lastHover = 0; hoverAtCursor(); }

    private volatile long playerTapAt = 0;

    private void tapAtCursor() {
        if (zoomBar != null && zoomBar.getVisibility() == View.VISIBLE) {
            if (onButton(zoomMinus, 3)) { zoomText(-10); return; }
            if (onButton(zoomPlus, 3)) { zoomText(10); return; }
        }
        if (navBar.getVisibility() == View.VISIBLE) {
            if (onButton(backBtn)) { goBackPage(); return; }
            if (onButton(homeBtn)) { web.loadUrl(HOME); return; }
        }
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("(function(x,y){var e=document.elementFromPoint(x,y);for(;e;e=e.parentElement){if(e.tagName==='IFRAME'||e.tagName==='VIDEO')return 'P'}return ''})("
                + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")", v -> {
            long now = System.currentTimeMillis();
            if (v != null && v.contains("P")) {
                if (customView == null && now - lastPlayerTap < 500) { lastPlayerTap = 0; fullscreenAtCursor(); return; } // double OK on the player
                lastPlayerTap = now;
                // the first press on the player goes full screen right away: the ad that shows in the seconds
                // before the player appears only shows outside full screen
                if (customView == null && !stretched && autoFs()) autoFullscreen();
            }
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursor.x, cursor.y, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cursor.x, cursor.y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            View target = customView != null ? customView : web;
            target.dispatchTouchEvent(down);
            target.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            { web.removeCallbacks(boxesLater); web.postDelayed(boxesLater, 800); }
        });
    }

    private static final String FS_JS =
        "(function(x,y){var e=document.elementFromPoint(x,y),p=null;for(;e;e=e.parentElement){if(e.tagName==='IFRAME'||e.tagName==='VIDEO'){p=e;break}}if(!p)return '';" +
        "function css(){if(window.__shFs)return;window.__shFs={el:p,st:p.getAttribute('style')||''};p.setAttribute('style',(window.__shFs.st)+';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;border:0!important;max-width:none!important;max-height:none!important');var a=[];for(var q=p.parentElement;q&&q!==document.documentElement;q=q.parentElement){a.push([q,q.getAttribute('style')]);q.setAttribute('style',(q.getAttribute('style')||'')+';transform:none!important;filter:none!important;backdrop-filter:none!important;contain:none!important;perspective:none!important;will-change:auto!important;overflow:visible!important;z-index:2147483647!important')}window.__shFs.anc=a;document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden'}" +
        "try{var f=p.requestFullscreen||p.webkitRequestFullscreen;var r=f?f.call(p):null;if(r&&r.catch)r.catch(css);else if(!f)css()}catch(z){css()}return 'ok'})";

    /** Double OK on the player: full screen (real full screen, or the player stretched over the whole page). */
    private volatile boolean fsPressed = false;

    /** Double OK on the player: press the player's own ⛶ button (in whatever frame it is); our stretch only if it has none. */
    private void fullscreenAtCursor() {
        fsPressed = false;
        autoFullscreen();
    }

    /** First press on the player: 1) full screen from inside the player frame right now, 2) the player's ⛶ button,
     *  3) our own full screen of the frame — each only if the one before didn't take. */
    private void autoFullscreen() {
        // only the player's OWN ⛶ button (our own full screen doesn't take): pressed as soon as it exists —
        // tried every 0.2 s for 4.5 s (the press on the video gave the page the right to go full screen)
        fsPressed = false;
        final int[] n = {0};
        // 1) a double tap on the player — away from its middle (the play/pause button is there) — which players
        //    answer with their own full screen; two taps leave play/pause as it was
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("(function(x,y){var e=document.elementFromPoint(x,y);for(;e;e=e.parentElement){if(e.tagName==='IFRAME'||e.tagName==='VIDEO'){var r=e.getBoundingClientRect();return [r.left,r.top,r.width,r.height].join(',')}}return ''})("
                + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")", v -> {
            if (v == null || v.length() < 5) return;
            String[] p = v.replace("\"", "").split(",");
            try {
                float l = Float.parseFloat(p[0]), t = Float.parseFloat(p[1]), w = Float.parseFloat(p[2]), h = Float.parseFloat(p[3]);
                final float px = (l + w * 0.18f) * d, py = (t + h * 0.3f) * d;      // upper-left part of the picture
                web.postDelayed(() -> tapAt(px, py), 60);
                web.postDelayed(() -> tapAt(px, py), 200);
            } catch (Exception ignored) { }
        });
        // 2) if that didn't take, the player's ⛶ button as soon as it exists
        ensureStealth(() -> web.postDelayed(new Runnable() {
            @Override public void run() {
                if (fsPressed || customView != null || n[0]++ > 22) return;
                web.evaluateJavascript("window.__sajoFsBtn&&__sajoFsBtn()", null);
                web.postDelayed(this, 200);
            }
        }, 700));
    }

    /** One plain tap at a screen point (no extra logic). */
    private void tapAt(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        View target = customView != null ? customView : web;
        target.dispatchTouchEvent(down);
        target.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void fullscreenAtCursorOwn() {
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript(FS_JS + "(" + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")", v -> {
            if (v != null && v.contains("ok")) { web.postDelayed(() -> { if (customView == null) { setStretched(true); autoCloseAds(); } }, 600); }
        });
    }

    /** Hand a page to another browser installed on the box (the user picks it once, e.g. BrowseHere). */
    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(Intent.createChooser(i, "افتح في…"));
        } catch (Exception e) { Toast.makeText(this, "مفيش متصفح تاني على الجهاز", Toast.LENGTH_SHORT).show(); }
    }

    // ------------------------------------------------------------------ Menu on a website

    /** The servers blocked on this page; picking one lets it through on this site only. */
    private void showBlocked() {
        final String page = pageHost;
        if (page == null) return;
        final java.util.List<String> hosts = new java.util.ArrayList<>(adblock.blockedHosts());
        for (String h : adblock.allowedHostsFor(page)) if (!hosts.contains(h)) hosts.add(h);
        if (hosts.isEmpty()) {
            Toast.makeText(this, adblock.isAllowed(page) ? "المنع مقفول على الموقع ده أصلًا" : "مفيش حاجة اتمنعت في الصفحة دي", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[hosts.size()];
        for (int i = 0; i < items.length; i++) items[i] = (adblock.hostAllowed(page, hosts.get(i)) ? "✅ " : "🛡 ") + hosts.get(i);
        new android.app.AlertDialog.Builder(this)
            .setTitle("اختار سيرفر عشان تسمح بيه هنا (✅ = مسموح)")
            .setItems(items, (d, w) -> {
                adblock.toggleHost(page, hosts.get(w));
                Toast.makeText(this, (adblock.hostAllowed(page, hosts.get(w)) ? "✅ اتسمح بـ " : "🛡 رجع المنع على ") + hosts.get(w), Toast.LENGTH_SHORT).show();
                web.reload();
            })
            .setNegativeButton("رجوع", null).show();
    }

    private static String stubFor(int type, String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?'); if (q > 0) u = u.substring(0, q);
        if (type == FilterEngine.SCRIPT || u.endsWith(".js")) return "noop.js";
        if (type == FilterEngine.IMAGE) return "1x1.gif";
        if (type == FilterEngine.SUBDOCUMENT) return "noop.html";
        if (type == FilterEngine.STYLESHEET || u.endsWith(".css")) return "noop.css";
        if (type == FilterEngine.MEDIA) return u.endsWith(".mp3") ? "noop-0.1s.mp3" : "noop-1s.mp4";
        if (u.endsWith(".xml") || u.contains("vast") || u.contains("vmap")) return "noop-vast4.xml";
        return null;
    }

    private String asset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    private final java.util.ArrayList<String> pageLog = new java.util.ArrayList<>();
    private void logLine(String l) {
        if (l.length() > 220) l = l.substring(0, 220) + "…";
        synchronized (pageLog) { pageLog.add(l); if (pageLog.size() > 80) pageLog.remove(0); }
    }

    /** What went wrong on this page (failed loads, script errors) — to find why a player stays empty. */
    private void showLog() {
        StringBuilder b = new StringBuilder();
        synchronized (pageLog) { for (int i = pageLog.size() - 1; i >= 0; i--) b.append(pageLog.get(i)).append("\n\n"); }
        java.util.List<String> bl = adblock.blockedHosts();
        if (!bl.isEmpty()) b.append("🛡 اتمنع: ").append(android.text.TextUtils.join("، ", bl));
        final String text = b.length() == 0 ? "مفيش أخطاء في الصفحة دي" : b.toString();
        new android.app.AlertDialog.Builder(this).setTitle("🧾 سجل الصفحة").setMessage(text)
            .setPositiveButton("تمام", null)
            .setNeutralButton("📋 انسخ", (d, w) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", text));
            }).show();
    }

    private void showSiteMenu() {
        final String url = web.getUrl();
        final String title = web.getTitle() == null ? "" : web.getTitle();
        if (url == null) return;
        String[] items = {
            "⬅ الصفحة اللي قبلها",
            "🏠 الرئيسية",
            "➕ أضف لمواقعي (الموقع كله أو الصفحة دي بس)",
            "▶ احفظ الصفحة دي في تكملة المشاهدة",
            "📋 انسخ الرابط",
            "📑 اعرض الصفحة اللي اتفتحت في الخلفية",
            "🛡 مانع الإعلانات: " + (adblock.enabled() ? "شغال (اقفله)" : "مقفول (شغّله)"),
            adblock.isAllowed(pageHost) ? "🛡 رجّع المنع على الموقع ده" : "✅ اقفل المنع على الموقع ده بس",
            snapMode() ? "🔲 الأسهم: بتنط من زرار لزرار (خليها ماوس بس)" : "🖱 الأسهم: ماوس بس (خليها تنط من زرار لزرار)",
            "🌐 افتح الصفحة دي في متصفح تاني",
            "ℹ معلومات",
            "🔍 المشغّل مش شغال؟ شوف اللي اتمنع في الصفحة دي",
            "🧾 سجل أخطاء الصفحة",
            "🧹 شيل الإعلان اللي تحت الماوس",
            "🔬 افحص اللي تحت الماوس (صوّرهولي)",
            autoFs() ? "⛶ ملء الشاشة أول ما أدوس على المشغّل: شغال (اقفله)" : "⛶ ملء الشاشة أول ما أدوس على المشغّل: مقفول (شغّله)",
            !isTv ? "ℹ أدوات المواقع شغالة على الموبايل" : fullMode() ? "🧪 أدوات المواقع الجديدة على البوكس: شغالة (اقفلها)" : "🧪 أدوات المواقع الجديدة على البوكس: مقفولة (شغّلها)",
            !isTv ? "ℹ يوتيوب على الموبايل بشكله العادي" : ytTv() ? "📺 يوتيوب بواجهة التليفزيون: شغالة (رجّع الشكل العادي)" : "📺 يوتيوب بواجهة التليفزيون: مقفولة (شغّلها)"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle(Uri.parse(url).getHost())
            .setItems(items, (dlg, which) -> {
                switch (which) {
                    case 0: goBackPage(); break;
                    case 1: web.loadUrl(HOME); break;
                    case 2: {
                        // the whole site (its home page) or exactly this page / tab
                        final String root = Uri.parse(url).getScheme() + "://" + Uri.parse(url).getHost() + "/";
                        new android.app.AlertDialog.Builder(this).setTitle("➕ تضيف إيه لمواقعي؟")
                            .setItems(new String[]{"🏠 الموقع كله (صفحته الرئيسية)", "📌 الصفحة دي بالظبط (القسم/التبويب ده)"}, (d2, w2) -> {
                                String target = w2 == 0 ? root : url;
                                // the site's own icon goes on the tile by itself
                                web.evaluateJavascript(ICON_JS, ic -> {
                                    String icon = "";
                                    try { icon = ic == null || ic.equals("null") ? "" : (String) new org.json.JSONTokener(ic).nextValue(); } catch (Exception ignored) { }
                                    pendingAdd = "quickAdd('site'," + org.json.JSONObject.quote(target) + "," + org.json.JSONObject.quote(title) + "," + org.json.JSONObject.quote(icon) + ")";
                                    web.loadUrl(HOME);
                                });
                            }).show();
                        break;
                    }
                    case 3: {
                        String kind = which == 2 ? "site" : "cont";
                        String target = which == 2 ? Uri.parse(url).getScheme() + "://" + Uri.parse(url).getHost() + "/" : url;
                        pendingAdd = "quickAdd(" + org.json.JSONObject.quote(kind) + "," + org.json.JSONObject.quote(target) + "," + org.json.JSONObject.quote(title) + ")";
                        web.loadUrl(HOME);
                        break;
                    }
                    case 4: {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                        Toast.makeText(this, "📋 اتنسخ: " + url, Toast.LENGTH_LONG).show();
                        break;
                    }
                    case 5:
                        if (bgUrl == null) Toast.makeText(this, "مفيش صفحة اتفتحت في الخلفية", Toast.LENGTH_SHORT).show();
                        else { String go = bgUrl; bgUrl = null; web.loadUrl(go); }
                        break;
                    case 6:
                        adblock.setEnabled(!adblock.enabled());
                        Toast.makeText(this, adblock.enabled() ? "🛡 مانع الإعلانات اشتغل" : "مانع الإعلانات اتقفل", Toast.LENGTH_SHORT).show();
                        web.reload();
                        break;
                    case 7:
                        adblock.toggleAllow(pageHost);
                        Toast.makeText(this, adblock.isAllowed(pageHost) ? "✅ المنع اتقفل على الموقع ده" : "🛡 المنع رجع على الموقع ده", Toast.LENGTH_SHORT).show();
                        web.reload();
                        break;
                    case 8: {
                        boolean sn = !snapMode();
                        getSharedPreferences("adblock", MODE_PRIVATE).edit().putBoolean("snap", sn).apply();
                        Toast.makeText(this, sn ? "🔲 الأسهم بتنط من زرار لزرار — اضغط مطوّل للماوس الحر" : "🖱 الأسهم بقت ماوس بس", Toast.LENGTH_LONG).show();
                        break;
                    }
                    case 9: openExternal(url); break;
                    case 10: {
                        String wv = "?";
                        try { if (Build.VERSION.SDK_INT >= 26) wv = WebView.getCurrentWebViewPackage().versionName; } catch (Exception ignored) { }
                        new android.app.AlertDialog.Builder(this).setTitle("ℹ معلومات")
                            .setMessage("Sajo TV نسخة " + Updater.myCode(this) + "\nأندرويد " + Build.VERSION.RELEASE + "\nWebView " + wv +
                                "\nقواعد منع الإعلانات: " + (adblock.ready ? adblock.rules() + " قاعدة (EasyList + uBlock + القايمة العربية)" : "لسه بتتحمّل…") +
                                "\nالإخفاء جوه المشغّلات: " + (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ? "شغال ✅" : "مش مدعوم ❌ (حدّث Android System WebView)"))
                            .setPositiveButton("تمام", null).show();
                        break;
                    }
                    case 11: showBlocked(); break;
                    case 12: showLog(); break;
                    case 13: zapAtCursor(); break;
                    case 14: probeAtCursor(); break;
                    case 17: {
                        if (!isTv) break;
                        boolean on = !ytTv();
                        getSharedPreferences("adblock", MODE_PRIVATE).edit().putBoolean("yttv", on).apply();
                        if (!on && baseUA != null) web.getSettings().setUserAgentString(baseUA);
                        Toast.makeText(this, on ? "📺 يوتيوب هيفتح بواجهة التليفزيون" : "يوتيوب هيفتح بشكله العادي", Toast.LENGTH_SHORT).show();
                        if (pageHost != null && isYt(pageHost)) web.loadUrl(on ? "https://www.youtube.com/tv#/" : "https://m.youtube.com/");
                        break;
                    }
                    case 16: {
                        if (!isTv) break;
                        boolean on = !fullMode();
                        getSharedPreferences("adblock", MODE_PRIVATE).edit().putBoolean("boxfull", on).commit();
                        Toast.makeText(this, on ? "🧪 اتشغّلت — التطبيق هيفتح من جديد" : "اتقفلت — التطبيق هيفتح من جديد", Toast.LENGTH_SHORT).show();
                        web.postDelayed(this::recreate, 800);
                        break;
                    }
                    case 15: {
                        boolean on = !autoFs();
                        getSharedPreferences("adblock", MODE_PRIVATE).edit().putBoolean("autofs", on).apply();
                        Toast.makeText(this, on ? "⛶ هيملا الشاشة أول ما تدوس على المشغّل" : "ملء الشاشة التلقائي اتقفل", Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
            })
            .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    // ---- box: YouTube's TV interface (youtube.com/tv) — the remote drives it directly, like the YouTube app
    private String baseUA;
    private static final String TV_UA = "Mozilla/5.0 (Linux; Android 9; SMART-TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.17-jit gles Starboard/15, SystemIntegratorName_TV_2025/FirmwareVersion (Brand, Model, Wired)";
    private boolean ytTv() { return isTv && getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("yttv", true); }
    private static boolean isYt(String host) { return host != null && (host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be")); }
    private boolean ytTvPage() { String u = web.getUrl(); return ytTv() && u != null && isYt(Uri.parse(u).getHost()) && Uri.parse(u).getPath() != null && Uri.parse(u).getPath().startsWith("/tv"); }

    /** true = handled (we load the TV interface instead) */
    private boolean ytRoute(WebView v, String url) {
        if (!isTv) return false;
        String host = Uri.parse(url).getHost();
        WebSettings st = v.getSettings();
        if (ytTv() && isYt(host)) {
            if (!TV_UA.equals(st.getUserAgentString())) st.setUserAgentString(TV_UA);
            String path = Uri.parse(url).getPath();
            if (path == null || !path.startsWith("/tv")) {
                String vid = Uri.parse(url).getQueryParameter("v");
                v.loadUrl("https://www.youtube.com/tv" + (vid != null ? "#/watch?v=" + vid : "#/"));
                return true;
            }
            return false;
        }
        if (baseUA != null && !baseUA.equals(st.getUserAgentString())) st.setUserAgentString(baseUA);
        return false;
    }

    private long ytMouseAt = 0;

    // a real mouse for YouTube TV (it answers a mouse, not finger taps): hover, click, and wheel at the screen's edges
    private MotionEvent mouseEv(int action, int buttons, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0; pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x; pc.y = y; pc.pressure = buttons != 0 ? 1 : 0; pc.size = 1;
        return MotionEvent.obtain(t, t, action, 1, new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, buttons, 1, 1, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
    }
    private void mouseHover() {
        MotionEvent m = mouseEv(MotionEvent.ACTION_HOVER_MOVE, 0, cursor.x, cursor.y);
        web.dispatchGenericMotionEvent(m); m.recycle();
    }
    private void mouseClick() {
        float x = cursor.x, y = cursor.y;
        MotionEvent d = mouseEv(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, x, y);
        web.dispatchTouchEvent(d); d.recycle();
        if (Build.VERSION.SDK_INT >= 23) {
            MotionEvent bp = mouseEv(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, x, y);
            web.dispatchGenericMotionEvent(bp); bp.recycle();
            MotionEvent br = mouseEv(MotionEvent.ACTION_BUTTON_RELEASE, 0, x, y);
            web.dispatchGenericMotionEvent(br); br.recycle();
        }
        MotionEvent u = mouseEv(MotionEvent.ACTION_UP, 0, x, y);
        web.dispatchTouchEvent(u); u.recycle();
    }
    private long lastWheel = 0;
    /** pushing the mouse against a screen edge rolls the wheel: the rows and the page move */
    private void ytEdgeScroll(int code) {
        long t = SystemClock.uptimeMillis();
        if (t - lastWheel < 180) return;
        float d = getResources().getDisplayMetrics().density, w = root.getWidth(), h = root.getHeight(), edge = 6 * d;
        float hs = 0, vs = 0;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && cursor.x >= w - edge - 4 * d) hs = 1;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT && cursor.x <= edge) hs = -1;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN && cursor.y >= h - edge - 30 * d) vs = -1;
        else if (code == KeyEvent.KEYCODE_DPAD_UP && cursor.y <= edge + 30 * d) vs = 1;
        if (hs == 0 && vs == 0) return;
        lastWheel = t;
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0; pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = cursor.x; pc.y = cursor.y;
        pc.setAxisValue(MotionEvent.AXIS_HSCROLL, hs);
        pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vs);
        MotionEvent m = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(m); m.recycle();
    }

    private void ytKey(int code) {
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
    }

    // phone + YouTube: the video keeps playing with the phone locked or the app in the background
    private boolean ytBackground() { return !isTv && pageHost != null && (pageHost.endsWith("youtube.com") || pageHost.equals("youtu.be")); }

    @Override protected void onPause() {
        super.onPause();
        if (ytBackground()) {
            PlayService.start(this);   // keep playing: the page is not paused
            // if the page paused itself when the screen went off, play it again
            for (int t : new int[]{400, 1200, 2500, 5000, 9000})
                web.postDelayed(() -> web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){if(v.paused&&v.currentTime>0&&!v.ended){var p=v.play();if(p&&p.catch)p.catch(function(){})}})", null), t);
        }
        else web.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        PlayService.stop(this);
        web.onResume();
        Updater.resume(this); Updater.check(this, false);
        if (!isTv && Build.VERSION.SDK_INT >= 33 && !askedNotif) {
            askedNotif = true;
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        }
    }
    private boolean askedNotif = false;
    @Override protected void onDestroy() { PlayService.stop(this); super.onDestroy(); }

    // phone + YouTube: turn the phone sideways = the video fills the screen (like the YouTube app); upright = back
    private static final String YT_FILL_JS = "(function(){if(window.__shFs)return 'on';var p=document.querySelector('#player-container-id,.html5-video-player,#movie_player,#player');" +
        "if(!p){var v=document.querySelector('video');p=v&&v.parentElement}if(!p)return '';" +
        "var st=p.getAttribute('style')||'';window.__shFs={el:p,st:st,anc:[]};" +
        "p.setAttribute('style',st+';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;margin:0!important;transform:none!important');" +
        "for(var q=p.parentElement;q&&q!==document.documentElement;q=q.parentElement){window.__shFs.anc.push([q,q.getAttribute('style')]);q.setAttribute('style',(q.getAttribute('style')||'')+';transform:none!important;filter:none!important;contain:none!important;overflow:visible!important;z-index:2147483647!important')}" +
        "var vs=p.querySelectorAll('video');for(var i=0;i<vs.length;i++){vs[i].dataset.sajoSt=vs[i].getAttribute('style')||'';vs[i].style.cssText+=';width:100vw!important;height:100vh!important;left:0!important;top:0!important;object-fit:contain!important'}" +
        "document.documentElement.style.overflow='hidden';window.dispatchEvent(new Event('resize'));return 'on'})()";

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (isTv || customView != null || pageHost == null || !isYt(pageHost)) return;
        boolean land = c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (land) {
            web.evaluateJavascript("document.querySelector('video')&&!document.querySelector('video').paused", v -> {
                if (!"true".equals(v)) return;
                web.evaluateJavascript(YT_FILL_JS, null);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                if (zoomBar != null) zoomBar.setVisibility(View.GONE);
                if (phoneMenu != null) phoneMenu.setVisibility(View.GONE);
            });
        } else {
            web.evaluateJavascript("(function(){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){if(vs[i].dataset.sajoSt!==undefined){vs[i].setAttribute('style',vs[i].dataset.sajoSt);delete vs[i].dataset.sajoSt}}})()", null);
            web.evaluateJavascript(UNSTRETCH_JS, null);
            web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            updatePhoneButton();
        }
    }

    private class Bridge {
        /** Hiding rules for a frame of the current page (empty on the launcher). */
        @JavascriptInterface
        public void adClosed() { adClosedInFrame = true; }
        /** a video on the page is at t of d seconds (sent every few seconds while it plays) */
        @JavascriptInterface
        public void progress(double t, double d) {
            final String url = pageUrl, title = pageTitle;
            if (url == null || !(d > 60)) return;
            saveWatch(url, t, d, title, null);
            if (t / d >= 0.97) runOnUiThread(() -> web.evaluateJavascript(NEXT_JS, v -> {
                try { String n = v == null || v.equals("null") ? "" : (String) new org.json.JSONTokener(v).nextValue(); if (!n.isEmpty()) saveWatch(url, t, d, title, n); } catch (Exception ignored) { }
            }));
        }
        /** where to resume this page's video (0 = from the start) */
        @JavascriptInterface
        public double resumeAt() {
            String url = pageUrl;
            if (url == null) return 0;
            org.json.JSONObject o = watchAll().optJSONObject(url);
            if (o == null) return 0;
            double t = o.optDouble("t", 0), d = o.optDouble("d", 0);
            if (t > 30 && d > 0 && t / d < 0.97) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "⏯ كمّلنا من " + hms(t), Toast.LENGTH_SHORT).show());
                return t;
            }
            return 0;
        }
        /** for the launcher: every page's progress */
        @JavascriptInterface
        public String watchList() { return watchAll().toString(); }
        @JavascriptInterface
        public void fsPressed() { fsPressed = true; }
        @JavascriptInterface
        public void boxes(String json) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                float now = (float) (SystemClock.uptimeMillis() % 100000000L);
                synchronized (fsBoxes) {
                    // keep the latest report of every frame: drop boxes older than ~3 s (the frames re-report every 1.5 s)
                    java.util.Iterator<float[]> it = fsBoxes.iterator();
                    while (it.hasNext()) { float[] b = it.next(); if (now - b[4] > 3200 || now < b[4]) it.remove(); }
                    for (int i = 0; i < a.length() && fsBoxes.size() < 300; i++) {
                        org.json.JSONArray q = a.getJSONArray(i);
                        float x = (float) q.getDouble(0), y = (float) q.getDouble(1);
                        java.util.Iterator<float[]> it2 = fsBoxes.iterator();
                        while (it2.hasNext()) { float[] b = it2.next(); if (Math.abs(b[0] - x) < 3 && Math.abs(b[1] - y) < 3) it2.remove(); }
                        fsBoxes.add(new float[]{x, y, (float) q.getDouble(2), (float) q.getDouble(3), now});
                    }
                }
            } catch (Exception ignored) { }
        }
        @JavascriptInterface
        public void probe(String line) { synchronized (probeLines) { if (probeLines.size() < 12) probeLines.add(line); } }
        @JavascriptInterface
        public void fsOn() { runOnUiThread(() -> { setStretched(true); autoCloseAds(); showMediaBar("⛶ ملء الشاشة — Back للخروج"); }); }
        @JavascriptInterface
        public void fsOff() { runOnUiThread(() -> setStretched(false)); }
        @JavascriptInterface
        public void mediaInfo(String cmd, double cur, double dur, boolean paused) {
            runOnUiThread(() -> {
                String t = (paused ? "⏸" : "▶") + "   " + hms(cur) + (dur > 0 ? " / " + hms(dur) : "") + "\n◀ ترجيع   ·   OK " + (paused ? "تشغيل" : "إيقاف") + "   ·   تقديم ▶";
                if (customView != null && mediaToast == null) { /* the full-screen view covers our views: use a toast there */ }
                if (customView != null) {
                    if (mediaToast != null) mediaToast.cancel();
                    mediaToast = Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT);
                    mediaToast.show();
                } else showMediaBar(t);
            });
        }
        @JavascriptInterface
        public void zapped(String r) {
            if (r != null && (r.contains("ok") || r.contains("video"))) { zapReplied = true; zapResult(r); }
        }
        @JavascriptInterface
        public String sl(String host) {
            // the page's frame can ask before the app has noted the new page (opening a site from the home screen):
            // then the asking page itself is the page
            String ph = pageHost;
            if (ph == null && host != null && !host.equals("appassets.androidplatform.net")) ph = host;
            return adblock.stealthConfig(ph, host);
        }
        @JavascriptInterface
        public String css(String host) { return pageHost == null ? "" : adblock.css(host, pageHost); }

        @JavascriptInterface
        public void saveBackup(String json) {
            runOnUiThread(() -> {
                try {
                    String name = "shashti-backup.json";
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                        cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                    } else {
                        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
                            Toast.makeText(MainActivity.this, "اسمح بالتخزين ودوس نسخة احتياطية تاني", Toast.LENGTH_LONG).show();
                            return;
                        }
                        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        dir.mkdirs();
                        try (FileOutputStream os = new FileOutputStream(new File(dir, name))) { os.write(bytes); }
                    }
                    Toast.makeText(MainActivity.this, "اتحفظت في Download/" + name, Toast.LENGTH_LONG).show();
                } catch (Exception ex) {
                    Toast.makeText(MainActivity.this, "تعذّر الحفظ: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void openExternal(String url) { runOnUiThread(() -> MainActivity.this.openExternal(url)); }

        @JavascriptInterface
        public void checkUpdate() { runOnUiThread(() -> Updater.check(MainActivity.this, true)); }

        @JavascriptInterface
        public boolean isPhone() { return !isTv; }
        @JavascriptInterface
        public String appVersion() { return String.valueOf(Updater.myCode(MainActivity.this)); }

        @JavascriptInterface
        public boolean adblockOn() { return adblock.enabled(); }

        @JavascriptInterface
        public void setAdblock(boolean on) { adblock.setEnabled(on); }

        @JavascriptInterface
        public void goHome() { runOnUiThread(() -> web.loadUrl(HOME)); }
    }
}
