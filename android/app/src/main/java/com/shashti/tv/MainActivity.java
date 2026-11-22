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
    private boolean longPressed = false, backLong = false;
    private long lastPlayerTap = 0, pressStart = 0, lastToast = 0;
    private String pendingAdd = null;
    private WebView bgTab;
    private String bgUrl = null;

    // the hiding rules of the lists, applied in every frame (players included) as soon as a document starts
    private static final String CSS_JS =
        "(function(){try{if(!window.Android||!Android.css)return;var c=Android.css(location.hostname);if(!c)return;" +
        "var s=document.createElement('style');s.textContent=c;function put(){(document.head||document.documentElement).appendChild(s)}" +
        "if(document.documentElement)put();document.addEventListener('DOMContentLoaded',put)}catch(e){}})()";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        freshStartOnce();
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        buildNavButtons();
        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
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
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true);            // new windows go through onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", ""));

        // don't announce the app's package in every request (some video hosts refuse in-app browsers)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.<String>emptySet());

        web.addJavascriptInterface(new Bridge(), "Android");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, CSS_JS, Collections.singleton("*"));
            String st = asset("stealth.js");
            if (st != null) WebViewCompat.addDocumentStartJavaScript(web, st, Collections.singleton("*"));
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
                WebResourceResponse rep = adblock.resource(m);
                if (rep == null) rep = adblock.resource(stubFor(typeOf(r), r.getUrl().toString()));
                if (rep == null) rep = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                java.util.Map<String, String> hd = new java.util.HashMap<>();
                String origin = r.getRequestHeaders() == null ? null : r.getRequestHeaders().get("Origin");
                hd.put("Access-Control-Allow-Origin", origin != null ? origin : "*");
                if (origin != null) hd.put("Access-Control-Allow-Credentials", "true");
                hd.put("Cache-Control", "no-store");
                rep.setResponseHeaders(hd);
                return rep;
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
                boxes = new float[0][];
                adblock.clearLog();
                synchronized (pageLog) { pageLog.clear(); }
            }
            @Override
            public void onPageFinished(WebView v, String u) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && pageHost != null) v.evaluateJavascript(CSS_JS, null);
                v.postDelayed(MainActivity.this::refreshBoxes, 600);
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

    private boolean onButton(View b) {
        int[] p = new int[2], q = new int[2];
        b.getLocationOnScreen(p); root.getLocationOnScreen(q);
        float x = p[0] - q[0], y = p[1] - q[1], pad = 14 * getResources().getDisplayMetrics().density;
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
        boolean pointer = customView == null && !atHome();
        cursor.setVisibility(pointer ? View.VISIBLE : View.GONE);
        navBar.setVisibility(pointer ? View.VISIBLE : View.GONE);
        if (!pointer) return super.dispatchKeyEvent(e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getAction() != KeyEvent.ACTION_DOWN) { hoverAtCursorNow(); return true; }
                lastKeyAt = SystemClock.uptimeMillis();
                if (e.getRepeatCount() == 0) pressStart = SystemClock.uptimeMillis();
                // one press jumps to the next box (link/button); holding the arrow = free mouse
                if (e.getRepeatCount() == 0 && snapMode()) snapTo(code); else freeMove(code, e.getRepeatCount());
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
    private static final String BACK_JS = "(function(){var f=window.__shFs;if(f){f.el.setAttribute('style',f.st);window.__shFs=null;document.documentElement.style.overflow='';return 'fs'}if(window.__sajoCloseAd){if(__sajoCloseAd())return 'ad';var n=0;for(var i=0;i<frames.length;i++){try{frames[i].postMessage({sajoCloseAd:1},'*');n++}catch(e){}}return n?'ask':''}var vw=innerWidth,vh=innerHeight,va=vw*vh,fs=document.fullscreenElement||document.webkitFullscreenElement;function isPlayer(e){if(e.tagName==='VIDEO'||e.querySelector('video'))return true;var fr=e.tagName==='IFRAME'?[e]:e.querySelectorAll('iframe');for(var i=0;i<fr.length;i++){var x=fr[i];if(x.hasAttribute('allowfullscreen')||x.hasAttribute('webkitallowfullscreen')||/fullscreen/i.test(x.getAttribute('allow')||'')||/embed|player|video|stream/i.test(x.src||''))return true}return false}function cands(e){var l=[];for(var n=e;n&&n!==document.body&&n!==document.documentElement;n=n.parentElement){var st=getComputedStyle(n),p=st.position;if(p!=='fixed'&&!(p==='absolute'&&(parseInt(st.zIndex)||0)>=1000))continue;var r=n.getBoundingClientRect();var a=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0))*Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));if(a<va*0.04)continue;if(r.top<=4&&r.height<vh*0.2&&r.width>vw*0.8)continue;l.unshift(n)}return l}var pts=[[.5,.5],[.3,.45],[.7,.45],[.5,.75],[.3,.7],[.7,.7],[.5,.92],[.5,.3]];for(var i=0;i<pts.length;i++){var x=vw*pts[i][0],y=vh*pts[i][1];var st=document.elementsFromPoint?document.elementsFromPoint(x,y):[document.elementFromPoint(x,y)];var top=st[0];if(!top)continue;var l=cands(top);for(var j=0;j<l.length;j++){var c=l[j];if(fs&&(c.contains(fs)||fs.contains(c)))continue;if(isPlayer(c))continue;c.remove();document.documentElement.style.overflow='';if(document.body)document.body.style.overflow='';return 'ad'}}return ''})()";

    private volatile boolean adClosedInFrame = false;

    private void onBack() {
        if (customView != null) { exitFullscreen(); return; }
        if (!atHome()) {
            // the player stretched with a double OK comes back first
            // 1) the player stretched with a double OK comes back, 2) an ad laid over the page closes, 3) previous page
            web.evaluateJavascript(BACK_JS, v -> {
                if (v != null && v.contains("ask")) {
                    // the ad may be inside the player's frame: the frames answer through Android.adClosed()
                    adClosedInFrame = false;
                    web.postDelayed(() -> {
                        if (adClosedInFrame) Toast.makeText(this, "🛡 اتقفل إعلان", Toast.LENGTH_SHORT).show();
                        else goBackPage();
                    }, 350);
                }
                else if (v != null && v.contains("ad")) Toast.makeText(this, "🛡 اتقفل إعلان", Toast.LENGTH_SHORT).show();
                else if (v == null || !v.contains("fs")) goBackPage();
            });
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
                float step = d * (repeatCount == 0 ? 34 : Math.min(30 + held / 8f, 140));
                if (cursor.x < 0) { cursor.x = w / 2f; cursor.y = h / 2f; }
                float margin = 30 * d;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
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
        float[][] bx = java.util.Arrays.copyOf(bx0, bx0.length + 2);
        int k = bx0.length;
        for (View nb : new View[]{backBtn, homeBtn}) {
            int[] p = new int[2], q = new int[2];
            nb.getLocationOnScreen(p); root.getLocationOnScreen(q);
            bx[k++] = new float[]{scrX + (p[0] - q[0]) / d, scrY + (p[1] - q[1]) / d, nb.getWidth() / d, nb.getHeight() / d};
        }
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
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    private void hoverAtCursorNow() { lastHover = 0; hoverAtCursor(); }

    private volatile long playerTapAt = 0;

    private void tapAtCursor() {
        if (navBar.getVisibility() == View.VISIBLE) {
            if (onButton(backBtn)) { goBackPage(); return; }
            if (onButton(homeBtn)) { web.loadUrl(HOME); return; }
        }
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("(function(x,y){var e=document.elementFromPoint(x,y);for(;e;e=e.parentElement){if(e.tagName==='IFRAME'||e.tagName==='VIDEO')return 'P'}return ''})("
                + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")", v -> {
            long now = System.currentTimeMillis();
            if (v != null && v.contains("P")) {
                if (now - lastPlayerTap < 500) { lastPlayerTap = 0; fullscreenAtCursor(); return; } // double OK on the player
                lastPlayerTap = now;
            }
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursor.x, cursor.y, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cursor.x, cursor.y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(down);
            web.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            { web.removeCallbacks(boxesLater); web.postDelayed(boxesLater, 800); }
        });
    }

    private static final String FS_JS =
        "(function(x,y){var e=document.elementFromPoint(x,y),p=null;for(;e;e=e.parentElement){if(e.tagName==='IFRAME'||e.tagName==='VIDEO'){p=e;break}}if(!p)return '';" +
        "function css(){if(window.__shFs)return;window.__shFs={el:p,st:p.getAttribute('style')||''};p.setAttribute('style',(window.__shFs.st)+';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;border:0!important;max-width:none!important;max-height:none!important');document.documentElement.style.overflow='hidden'}" +
        "try{var f=p.requestFullscreen||p.webkitRequestFullscreen;var r=f?f.call(p):null;if(r&&r.catch)r.catch(css);else if(!f)css()}catch(z){css()}return 'ok'})";

    /** Double OK on the player: full screen (real full screen, or the player stretched over the whole page). */
    private void fullscreenAtCursor() {
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript(FS_JS + "(" + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")", v -> {
            if (v != null && v.contains("ok")) Toast.makeText(this, "⛶ ملء الشاشة — Back للخروج", Toast.LENGTH_SHORT).show();
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
            "➕ أضف الموقع ده لمواقعي",
            "▶ احفظ الصفحة دي في تكملة المشاهدة",
            "📋 انسخ الرابط",
            "📑 اعرض الصفحة اللي اتفتحت في الخلفية",
            "🛡 مانع الإعلانات: " + (adblock.enabled() ? "شغال (اقفله)" : "مقفول (شغّله)"),
            adblock.isAllowed(pageHost) ? "🛡 رجّع المنع على الموقع ده" : "✅ اقفل المنع على الموقع ده بس",
            snapMode() ? "🔲 الأسهم: بتنط من زرار لزرار (خليها ماوس بس)" : "🖱 الأسهم: ماوس بس (خليها تنط من زرار لزرار)",
            "🌐 افتح الصفحة دي في متصفح تاني",
            "ℹ معلومات",
            "🔍 المشغّل مش شغال؟ شوف اللي اتمنع في الصفحة دي",
            "🧾 سجل أخطاء الصفحة"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle(Uri.parse(url).getHost())
            .setItems(items, (dlg, which) -> {
                switch (which) {
                    case 0: goBackPage(); break;
                    case 1: web.loadUrl(HOME); break;
                    case 2: case 3: {
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
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); Updater.resume(this); Updater.check(this, false); }

    private class Bridge {
        /** Hiding rules for a frame of the current page (empty on the launcher). */
        @JavascriptInterface
        public void adClosed() { adClosedInFrame = true; }
        @JavascriptInterface
        public String sl(String host) { return adblock.stealthConfig(pageHost, host); }
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
        public String appVersion() { return String.valueOf(Updater.myCode(MainActivity.this)); }

        @JavascriptInterface
        public boolean adblockOn() { return adblock.enabled(); }

        @JavascriptInterface
        public void setAdblock(boolean on) { adblock.setEnabled(on); }

        @JavascriptInterface
        public void goHome() { runOnUiThread(() -> web.loadUrl(HOME)); }
    }
}
