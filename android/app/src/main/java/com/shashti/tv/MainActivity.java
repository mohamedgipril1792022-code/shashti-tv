package com.shashti.tv;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.Environment;
import android.provider.MediaStore;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.ScriptHandler;
import java.util.Collections;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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
    private int repeat = 0;
    private boolean longPressed = false;
    private boolean backLong = false;
    private volatile boolean frameClosed = false;
    private ScriptHandler cleaner;
    private String cleanJs = "";
    private volatile String currentSite = null;
    private long pressStart = 0;

    /** Page cleaner (ad overlays, pop-ups) runs in every frame, the video player's frame included. */
    private void applyCleaner() {
        if (cleanJs.isEmpty()) {
            try (java.io.InputStream in = getAssets().open("clean.js")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                cleanJs = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) { return; }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        if (cleaner != null) { cleaner.remove(); cleaner = null; }
        if (adblock.enabled()) cleaner = WebViewCompat.addDocumentStartJavaScript(web, cleanerScript(), Collections.singleton("*"));
    }

    private String cleanerScript() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String x : adblock.allowed()) a.put(x);
        String hide = getSharedPreferences("adblock", MODE_PRIVATE).getString("hide", "{}");
        return "window.__SHASHTI_ALLOW=" + a + ";window.__SHASHTI_HIDE=" + hide + ";" + cleanJs;
    }

    private boolean blocking() { return adblock.enabled() && !adblock.isAllowed(currentSite); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
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
        s.setSupportMultipleWindows(true); // popups go through onCreateWindow below
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse a = assets.shouldInterceptRequest(r.getUrl());
                if (a != null) return a;
                if (adblock.isAd(r.getUrl().getHost()) && !adblock.isAllowed(currentSite)) {
                    adblock.blocked++;
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                currentSite = (u == null || u.startsWith("https://appassets.androidplatform.net/")) ? null : Uri.parse(u).getHost();
            }
            @Override
            public void onPageFinished(WebView v, String u) {
                // older WebViews without document-start scripts: clean the main page at least
                if (cleaner == null && blocking() && !atHome() && !cleanJs.isEmpty()) v.evaluateJavascript(cleanerScript(), null);
                if (pendingAdd != null && atHome()) {
                    final String js = pendingAdd;
                    pendingAdd = null;
                    v.postDelayed(() -> v.evaluateJavascript(js, null), 300);
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                if (!"http".equals(sc) && !"https".equals(sc)) return true; // ignore intent://, market:// ...
                String host = r.getUrl().getHost();
                if (atHome()) return false;
                if (r.isForMainFrame() && System.currentTimeMillis() - playerTapAt < 2500) {
                    String cur0 = Uri.parse(web.getUrl()).getHost();
                    if (!(AdBlock.sameSite(cur0, host) && !r.isRedirect() && r.getUrl().toString().equals(web.getUrl()))) {
                        lastBlocked = r.getUrl().toString(); sendAway(lastBlocked); return true;
                    }
                }
                if (!blocking()) return false;
                if (adblock.isAd(host)) { lastBlocked = r.getUrl().toString(); sendAway(lastBlocked); return true; }
                // a jump to another site from inside a site goes to the silent background tab (Menu can show it)
                if (r.isForMainFrame() && Build.VERSION.SDK_INT >= 24 && !r.isRedirect()) {
                    String cur = Uri.parse(web.getUrl()).getHost();
                    if (cur != null && (cur.contains("google.") || cur.contains("bing.") || cur.contains("duckduckgo."))) return false;
                    if (!AdBlock.sameSite(cur, host)) { lastBlocked = r.getUrl().toString(); sendAway(lastBlocked); return true; }
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
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
                // catch the popup's address, then decide: open it here or drop it
                WebView tmp = new WebView(MainActivity.this);
                final boolean[] done = {false};
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView t, WebResourceRequest r) {
                        if (!done[0]) { done[0] = true; openPopup(r.getUrl().toString(), gesture); }
                        return true;
                    }
                    @Override
                    public void onPageStarted(WebView t, String u, android.graphics.Bitmap f) {
                        // some WebView versions load the first address without asking above
                        if (!done[0] && u != null && u.startsWith("http")) { done[0] = true; t.stopLoading(); openPopup(u, gesture); }
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
                try {
                    startActivityForResult(p.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "مفيش مدير ملفات على الجهاز", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        android.content.SharedPreferences ap0 = getSharedPreferences("adblock", MODE_PRIVATE);
        if (!ap0.getBoolean("hideCleared", false)) ap0.edit().putString("hide", "{}").putBoolean("hideCleared", true).apply();
        applyCleaner();
        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
        web.postDelayed(() -> Updater.check(this, false), 4000);
    }

    private void openPopup(String url, boolean gesture) {
        String host = Uri.parse(url).getHost();
        if (!adblock.isAd(host)) lastBlocked = url;
        if (atHome()) { // links from the launcher itself
            web.loadUrl(url);
            return;
        }
        String cur = Uri.parse(web.getUrl()).getHost();
        // a new window on the same site (episode page) opens right here — unless it came from a click on the player
        if (System.currentTimeMillis() - playerTapAt > 2500 && AdBlock.sameSite(cur, host) && !adblock.isAd(host)) { web.loadUrl(url); return; }
        // anything else (usually the ad that opens with the first click) goes to a silent background tab,
        // so the page you are watching stays on screen; Menu can still show it if it was wanted
        sendAway(url);
    }

    private long lastToast = 0;
    private long lastAway = 0;

    private boolean adsOutside() { return getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("outside", true); }

    /** Ads the user does not want here: open them in the device browser and come straight back. */
    private WebView bgTab;
    private String bgUrl = null;

    /** Open a pop-up/ad quietly in a hidden tab: nothing changes on screen. */
    private void sendAway(String url) {
        boolean retrying = retapAfterAd();
        long now = System.currentTimeMillis();
        bgUrl = url;
        if (!adsOutside() || adblock.isAd(Uri.parse(url).getHost()) || now - lastAway < 1500) { if (!retrying) blockedToast(); else adblock.blocked++; return; }
        lastAway = now;
        if (bgTab == null) {
            bgTab = new WebView(this);
            WebSettings bs = bgTab.getSettings();
            bs.setJavaScriptEnabled(true);
            bs.setDomStorageEnabled(true);
            bs.setMediaPlaybackRequiresUserGesture(true); // no sound from the hidden tab
            bs.setSupportMultipleWindows(true);           // and no further pop-ups from it
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
        long t = System.currentTimeMillis();
        if (!retrying && t - lastToast > 4000) { lastToast = t; Toast.makeText(this, "📑 صفحة اتفتحت في الخلفية (Menu لو عايز تشوفها)", Toast.LENGTH_SHORT).show(); }
    }
    private String lastBlocked = null;
    private void blockedToast() {
        retapAfterAd();
        adblock.blocked++;
        long now = System.currentTimeMillis();
        if (now - lastToast > 4000) {
            lastToast = now;
            runOnUiThread(() -> Toast.makeText(this, "🛡 اتمنع إعلان", Toast.LENGTH_SHORT).show());
        }
    }

    /** Menu key on a website: save it to the launcher without typing the address. */
    private void showSiteMenu() {
        final String url = web.getUrl();
        final String title = web.getTitle() == null ? "" : web.getTitle();
        String[] items = {"🏠 الرئيسية", "➕ أضف الموقع ده لمواقعي", "▶ احفظ الصفحة دي في تكملة المشاهدة", "📋 انسخ الرابط", "📑 اعرض الصفحة اللي اتفتحت في الخلفية", "🛡 مانع الإعلانات: " + (adblock.enabled() ? "شغال (اقفله)" : "مقفول (شغّله)"), adblock.isAllowed(currentSite) ? "🛡 رجّع المنع على الموقع ده" : "✅ اسمح للموقع ده (لو بيبوظ مع المنع)", adsOutside() ? "📑 الصفحات المنبثقة: بتفتح في الخلفية بصمت (اقفلها خالص)" : "🚫 الصفحات المنبثقة: بتتقفل (افتحها في الخلفية)", "🧹 شيل الصندوق اللي تحت الماوس (ويفضل متشال)", "↩ رجّع الصناديق اللي شلتها من الموقع ده", snapMode() ? "🔲 الأسهم: بتنط من زرار لزرار (خليها ماوس بس)" : "🖱 الأسهم: ماوس بس (خليها تنط من زرار لزرار)", (getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("autoallow", false) ? "🔧 لو ظهر صندوق قافل: بشغّل الموقع من غير منع (خليه يقفل الصندوق بس)" : "🔧 لو ظهر صندوق قافل: بقفله والمنع شغال (خليه يشغّل الموقع من غير منع)"), "ℹ معلومات الجهاز"};
        final String[] shown = new String[items.length - 2];
        for (int i = 0, j = 0; i < items.length; i++) if (i != 8 && i != 9) shown[j++] = items[i];
        new android.app.AlertDialog.Builder(this)
            .setTitle(Uri.parse(url).getHost())
            .setItems(shown, (dlg, pos) -> {
                final int which = pos >= 8 ? pos + 2 : pos;
                if (which == 0) { web.loadUrl(HOME); return; }
                if (which == 4 && bgUrl != null) { String go = bgUrl; bgUrl = null; web.loadUrl(go); return; }
                if (which == 4) {
                    if (lastBlocked == null) { Toast.makeText(this, "مفيش صفحة اتمنعت", Toast.LENGTH_SHORT).show(); return; }
                    String go = lastBlocked; lastBlocked = null;
                    adblock.setEnabled(false); web.loadUrl(go); adblock.setEnabled(true);
                    return;
                }
                if (which == 5) {
                    adblock.setEnabled(!adblock.enabled());
                    applyCleaner();
                    Toast.makeText(this, adblock.enabled() ? "🛡 مانع الإعلانات اشتغل" : "مانع الإعلانات اتقفل", Toast.LENGTH_SHORT).show();
                    web.reload();
                    return;
                }
                if (which == 8) { zapUnderCursor(); return; }
                if (which == 11) {
                    android.content.SharedPreferences ap = getSharedPreferences("adblock", MODE_PRIVATE);
                    boolean aa = !ap.getBoolean("autoallow", false);
                    ap.edit().putBoolean("autoallow", aa).apply();
                    Toast.makeText(this, aa ? "🔧 هشغّل الموقع من غير منع لو اتقفل المشغّل" : "🔧 هقفل الصندوق بس والمنع يفضل شغال", Toast.LENGTH_LONG).show();
                    return;
                }
                if (which == 12) {
                    String wv = "?";
                    try { if (Build.VERSION.SDK_INT >= 26) wv = WebView.getCurrentWebViewPackage().versionName; } catch (Exception ignored) { }
                    boolean frames = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
                    new android.app.AlertDialog.Builder(this).setTitle("ℹ معلومات الجهاز")
                        .setMessage("Sajo TV نسخة " + Updater.myCode(this) + "\nأندرويد " + Build.VERSION.RELEASE + "\nWebView " + wv +
                            "\nالتنظيف جوه المشغّل: " + (frames ? "شغال ✅" : "مش مدعوم على الجهاز ده ❌ (حدّث Android System WebView)"))
                        .setPositiveButton("تمام", null).show();
                    return;
                }
                if (which == 10) {
                    boolean sn = !snapMode();
                    getSharedPreferences("adblock", MODE_PRIVATE).edit().putBoolean("snap", sn).apply();
                    Toast.makeText(this, sn ? "🔲 الأسهم بتنط من زرار لزرار — اضغط مطوّل للماوس الحر" : "🖱 الأسهم بقت ماوس بس", Toast.LENGTH_LONG).show();
                    return;
                }
                if (which == 9) {
                    try {
                        android.content.SharedPreferences p = getSharedPreferences("adblock", MODE_PRIVATE);
                        org.json.JSONObject h = new org.json.JSONObject(p.getString("hide", "{}"));
                        h.remove(AdBlock.base(currentSite));
                        p.edit().putString("hide", h.toString()).apply();
                    } catch (Exception ignored) { }
                    applyCleaner(); web.reload();
                    return;
                }
                if (which == 7) {
                    boolean o = !adsOutside();
                    getSharedPreferences("adblock", MODE_PRIVATE).edit().putBoolean("outside", o).apply();
                    Toast.makeText(this, o ? "📑 هتفتح في الخلفية بصمت" : "🚫 هتتقفل", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (which == 6) {
                    adblock.toggleAllow(currentSite);
                    applyCleaner();
                    Toast.makeText(this, adblock.isAllowed(currentSite) ? "✅ الموقع ده مسموح — مفيش منع عليه" : "🛡 المنع رجع على الموقع ده", Toast.LENGTH_SHORT).show();
                    web.reload();
                    return;
                }
                if (which == 3) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                    Toast.makeText(this, "📋 اتنسخ: " + url, Toast.LENGTH_LONG).show();
                    return;
                }
                String kind = which == 1 ? "site" : "cont";
                String target = which == 1 ? Uri.parse(url).getScheme() + "://" + Uri.parse(url).getHost() + "/" : url;
                pendingAdd = "quickAdd(" + org.json.JSONObject.quote(kind) + "," + org.json.JSONObject.quote(target) + "," + org.json.JSONObject.quote(title) + ")";
                web.loadUrl(HOME);
            })
            .show();
    }
    private String pendingAdd = null;

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

    /** Remote control on websites: arrows move a pointer, OK taps, edges scroll. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Back: short press = one step back; holding it = home screen from anywhere
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) backLong = false;
                else if (!backLong && e.getRepeatCount() >= 6) {
                    backLong = true;
                    exitFullscreen();
                    if (!atHome()) { web.loadUrl(HOME); Toast.makeText(this, "🏠 الرئيسية", Toast.LENGTH_SHORT).show(); }
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!backLong) onKeyDown(KeyEvent.KEYCODE_BACK, e);
                backLong = false;
            }
            return true;
        }
        boolean pointer = customView == null && !atHome();
        cursor.setVisibility(pointer ? View.VISIBLE : View.GONE);
        if (!pointer) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        float d = getResources().getDisplayMetrics().density;
        int w = root.getWidth(), h = root.getHeight();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (e.getAction() == KeyEvent.ACTION_UP) { repeat = 0; return true; }
                if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (e.getRepeatCount() == 0) pressStart = SystemClock.uptimeMillis();
                // a single press jumps to the next box (link/button) in that direction; holding = free mouse
                if (e.getRepeatCount() == 0 && snapMode()) { snapTo(code); return true; }
                freeMove(code, e.getRepeatCount());
                return true;
            }
            default: break;
        }
        return dispatchRest(e);
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
                    if (cursor.y - step < margin) scrollPage(-step * 2.5f); else cursor.y -= step;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (cursor.y + step > h - margin) scrollPage(step * 2.5f); else cursor.y += step;
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    cursor.x = Math.max(0, cursor.x - step);
                } else {
                    cursor.x = Math.min(w - 4 * d, cursor.x + step);
                }
                cursor.invalidate();
                hoverAtCursor();
    }

    private boolean snapMode() { return getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("snap", true); }

    private static final String SNAP_JS =
        "(function(dx,dy,cx,cy){var sel='a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,[role=button],[role=link],[role=tab],[onclick],[tabindex]:not([tabindex=\"-1\"])';" +
        "var vw=innerWidth,vh=innerHeight,best=null,bs=1e9;" +
        "[].slice.call(document.querySelectorAll(sel)).forEach(function(e){var r=e.getBoundingClientRect();if(r.width<6||r.height<6||r.bottom<0||r.top>vh||r.right<0||r.left>vw)return;" +
        "var st=getComputedStyle(e);if(st.visibility==='hidden'||parseFloat(st.opacity)<0.1)return;" +
        "var x=Math.min(Math.max(cx,r.left+4),r.right-4),y=Math.min(Math.max(cy,r.top+4),r.bottom-4);if(Math.abs(x-cx)<2&&Math.abs(y-cy)<2)return;" +
        "var ex=r.left+r.width/2,ey=r.top+r.height/2,ax=(dx?ex:x)-cx,ay=(dy?ey:y)-cy,along=ax*dx+ay*dy;if(along<8)return;" +
        "var perp=Math.abs(ax*dy)+Math.abs(ay*dx);var sc=along+perp*2.2;if(sc<bs){bs=sc;best=[e,ex,ey]}});" +
        "if(window.__shPrev){window.__shPrev.style.outline=window.__shPrevO||'';window.__shPrev=null}" +
        "if(!best)return '';var e=best[0];window.__shPrev=e;window.__shPrevO=e.style.outline;e.style.outline='3px solid #e50914';e.style.outlineOffset='2px';" +
        "return Math.round(best[1])+','+Math.round(best[2])})";

    private void snapTo(int code) {
        final float d = getResources().getDisplayMetrics().density;
        if (cursor.x < 0) { cursor.x = root.getWidth() / 2f; cursor.y = root.getHeight() / 2f; }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        String js = SNAP_JS + "(" + dx + "," + dy + "," + Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")";
        web.evaluateJavascript(js, v -> {
            String xy = v == null ? "" : v.replace("\"", "");
            if (xy.contains(",")) {
                String[] p = xy.split(",");
                cursor.x = Float.parseFloat(p[0]) * d;
                cursor.y = Float.parseFloat(p[1]) * d;
                cursor.invalidate();
                hoverAtCursor();
            } else if (dy != 0) {
                scrollPage(dy * root.getHeight() * 0.45f); // nothing more in that direction on screen: scroll
            } else {
                freeMove(code, 0);
            }
        });
    }

    private boolean dispatchRest(KeyEvent e) {
        int code = e.getKeyCode();
        int h = root.getHeight();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) longPressed = false;
                    else if (!longPressed && e.getRepeatCount() >= 8) { longPressed = true; showSiteMenu(); }
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (!longPressed) tapAtCursor();
                    longPressed = false;
                }
                return true;
            }
            case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (e.getAction() == KeyEvent.ACTION_DOWN) scrollPage(h * 0.8f);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_CHANNEL_UP:
                if (e.getAction() == KeyEvent.ACTION_DOWN) scrollPage(-h * 0.8f);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void scrollPage(float dy) {
        float d = getResources().getDisplayMetrics().density;
        int css = Math.round(dy / d);
        // scroll the page and, if it scrolls inside a box, the element under the pointer
        web.evaluateJavascript("(function(){var y=" + css + ",x=" + Math.round(cursor.x / d) + ",yy=" + Math.round(cursor.y / d) + ";" +
            "var el=document.elementFromPoint(x,yy),b=window.scrollY;window.scrollBy(0,y);if(window.scrollY!==b)return;" +
            "while(el&&el!==document.body){var s=getComputedStyle(el).overflowY;if((s=='auto'||s=='scroll')&&el.scrollHeight>el.clientHeight){el.scrollBy(0,y);return}el=el.parentElement}})()", null);
    }

    /** Remove the box under the pointer (and its dark backdrop), and remember it for this site. */
    private void zapUnderCursor() {
        float d = getResources().getDisplayMetrics().density;
        String js = "(function(x,y){var el=document.elementFromPoint(x,y);if(!el||el===document.body||el===document.documentElement)return '';" +
            "function fx(e){for(;e&&e!==document.body;e=e.parentElement){if(getComputedStyle(e).position==='fixed')return true}return false}" +
            "function plf(f){var a=(f.getAttribute('allow')||'')+' '+(f.hasAttribute('allowfullscreen')?'fullscreen':'');if(/fullscreen/i.test(a)||/embed|player|video|watch|stream|play/i.test(f.src||''))return true;var r=f.getBoundingClientRect();return !(r.width>=innerWidth*0.9&&r.height>=innerHeight*0.9)}" + "function pl(f){return plf(f)}" + // full-screen iframes without player traits are ad overlays
            "function media(e){if(e.tagName==='VIDEO'||e.querySelector('video'))return true;if(e.tagName==='IFRAME')return pl(e);return [].slice.call(e.querySelectorAll('iframe')).some(pl)}" +
            "if(media(el))return 'PLAYER';" +
            "var t=el,best=null;while(t&&t!==document.body){if(media(t))break;var s=getComputedStyle(t);if(s.position==='fixed'||s.position==='absolute'&&(parseInt(s.zIndex)||0)>=10)best=t;t=t.parentElement}" +
            "var box=best||el;if(media(box))return 'PLAYER';" +
            "function sel(e){if(e.id&&!/\\d{3,}/.test(e.id))return '#'+CSS.escape(e.id);var c=[].slice.call(e.classList).filter(function(k){return !/\\d{3,}/.test(k)}).slice(0,3);return e.tagName.toLowerCase()+(c.length?'.'+c.map(CSS.escape).join('.'):'')}" +
            "var out=[sel(box)];box.remove();" +
            "[].slice.call(document.querySelectorAll('body *')).forEach(function(e){var s=getComputedStyle(e);if(s.position!=='fixed')return;var q=e.getBoundingClientRect();" +
            "if(q.width*q.height>innerWidth*innerHeight*0.6&&!media(e)&&!e.innerText.trim()){out.push(sel(e));e.remove()}});" +
            "document.documentElement.style.overflow='';document.body.style.overflow='';return JSON.stringify(out)})(" +
            Math.round(cursor.x / d) + "," + Math.round(cursor.y / d) + ")";
        web.evaluateJavascript(js, v -> {
            try {
                if (v == null || v.equals("null") || v.equals("\"\"")) { Toast.makeText(this, "مفيش صندوق تحت الماوس", Toast.LENGTH_SHORT).show(); return; }
                if (v.contains("PLAYER")) { Toast.makeText(this, "ده المشغّل نفسه — مش هشيله. لو عليه صندوق قافله، التطبيق بيشغّل الموقع من غير منع لوحده", Toast.LENGTH_LONG).show(); return; }
                org.json.JSONArray got = new org.json.JSONArray(new org.json.JSONTokener(v).nextValue().toString());
                android.content.SharedPreferences p = getSharedPreferences("adblock", MODE_PRIVATE);
                org.json.JSONObject h = new org.json.JSONObject(p.getString("hide", "{}"));
                String site = AdBlock.base(currentSite);
                org.json.JSONArray list = h.optJSONArray(site);
                if (list == null) list = new org.json.JSONArray();
                for (int i = 0; i < got.length(); i++) {
                    String sel = got.getString(i);
                    if (sel.equals("div") || sel.equals("a") || sel.equals("span") || sel.equals("section")) continue; // too general to remember
                    list.put(sel);
                }
                h.put(site, list);
                p.edit().putString("hide", h.toString()).apply();
                applyCleaner();
                Toast.makeText(this, "🧹 اتشال — ومش هيظهر تاني على الموقع ده", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "🧹 اتشال", Toast.LENGTH_SHORT).show(); }
        });
    }

    // ---- auto-fix: you pressed play but a blocking box came up instead of the player ----
    private static final String PLAYER_CHECK =
        "(function(){var vw=innerWidth,vh=innerHeight;function big(r,f){return r.width*r.height>vw*vh*f}" + "function plf(f){var a=(f.getAttribute('allow')||'')+' '+(f.hasAttribute('allowfullscreen')?'fullscreen':'');if(/fullscreen/i.test(a)||/embed|player|video|watch|stream|play/i.test(f.src||''))return true;var r=f.getBoundingClientRect();return !(r.width>=innerWidth*0.9&&r.height>=innerHeight*0.9)}" +
        "var player=[].slice.call(document.querySelectorAll('video,iframe')).some(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.bottom>0&&r.top<vh&&big(r,0.12)});" +
        "var wall=[].slice.call(document.querySelectorAll('body *')).some(function(e){var s=getComputedStyle(e);if(s.position!=='fixed'&&s.position!=='absolute')return false;" +
        "if((parseInt(s.zIndex)||0)<100||s.visibility==='hidden'||s.display==='none'||parseFloat(s.opacity)<0.3)return false;var r=e.getBoundingClientRect();if(!big(r,0.35))return false;" +
        "if(e.querySelector('video'))return false;var fr=e.tagName==='IFRAME'?[e]:[].slice.call(e.querySelectorAll('iframe'));if(fr.length)return !fr.some(plf);return !!(e.innerText.trim()||e.querySelector('img,canvas,svg'))});" +
        "return player&&!wall?'player':(wall?'wall':'none')})()";
    private String checkedPage = null;

    private static final String CLOSE_OVERLAY =
        "(function(){var vw=innerWidth,vh=innerHeight;" +
        "function fx(e){for(;e&&e!==document.body;e=e.parentElement){if(getComputedStyle(e).position==='fixed')return true}return false}" +
        "function plf(f){var a=(f.getAttribute('allow')||'')+' '+(f.hasAttribute('allowfullscreen')?'fullscreen':'');if(/fullscreen/i.test(a)||/embed|player|video|watch|stream|play/i.test(f.src||''))return true;var r=f.getBoundingClientRect();return !(r.width>=innerWidth*0.9&&r.height>=innerHeight*0.9)}" + "function player(e){return e.tagName==='VIDEO'||!!e.querySelector('video')||(e.tagName==='IFRAME'&&plf(e))||[].slice.call(e.querySelectorAll('iframe')).some(plf)}" +
        "var cands=[].slice.call(document.querySelectorAll('body *')).filter(function(e){var s=getComputedStyle(e);if(s.position!=='fixed'&&s.position!=='absolute')return false;" +
        "if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)<0.3)return false;var r=e.getBoundingClientRect();if(r.width*r.height<vw*vh*0.25)return false;" +
        "var t=(e.innerText||'');if(t.length<800&&/vpn|robot|روبوت|تحقق|verify|ad.?block|مانع/i.test(t)&&!e.querySelector('video'))return true;" + // lock/scam boxes: always closable
        "return (parseInt(s.zIndex)||0)>=1&&!player(e)});" +
        "if(!cands.length)return 'none';cands.sort(function(a,b){return (parseInt(getComputedStyle(b).zIndex)||0)-(parseInt(getComputedStyle(a).zIndex)||0)});var o=cands[0];" +
        "var btns=[].slice.call(o.querySelectorAll('button,a,span,div,i,svg,[role=button]')).filter(function(b){var t=(b.innerText||b.getAttribute('aria-label')||b.getAttribute('title')||'').trim().toLowerCase();" +
        "var c=((b.className&&b.className.baseVal!==undefined)?b.className.baseVal:(b.className||''))+' '+(b.id||'');var r=b.getBoundingClientRect();" +
        "return r.width>0&&r.width<90&&r.height<90&&(/^(×|✕|✖|x|close|إغلاق|اغلاق|skip|تخطي)$/i.test(t)||/close|dismiss|modal-x|btn-x/i.test(c))});" +
        "if(btns.length){btns[0].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));setTimeout(function(){if(o.isConnected&&getComputedStyle(o).display!=='none')o.remove()},400);return 'closed'}" +
        "o.remove();document.documentElement.style.overflow='';document.body.style.overflow='';return 'closed'})()";

    private void watchForPlayer() {
        if (atHome() || customView != null || !blocking()) return;
        final String page = web.getUrl();
        {
            web.postDelayed(() -> {
                if (customView != null || web.getUrl() == null || !web.getUrl().equals(page) || !blocking()) return;
                web.evaluateJavascript(PLAYER_CHECK, after -> {
                    if (after == null || !after.contains("wall")) return;
                    autoAllow(page);
                });
            }, 5000);
        }
    }

    /** The site put a box where the player should be: run this site without blocking and reload. */
    private int wallCloses = 0;
    private String wallPage = null;

    private void autoAllow(String page) {
                    if (atHome() || !blocking() || page == null) return;
                    if (!getSharedPreferences("adblock", MODE_PRIVATE).getBoolean("autoallow", false)) {
                        // default: keep blocking, just close the box (page first, then inside the player)
                        if (!page.equals(wallPage)) { wallPage = page; wallCloses = 0; }
                        if (++wallCloses > 4) return;
                        web.evaluateJavascript(CLOSE_OVERLAY, v -> {
                            if (v == null || !v.contains("closed"))
                                web.evaluateJavascript("[].forEach.call(document.querySelectorAll('iframe'),function(f){try{f.contentWindow.postMessage({shashti:'close'},'*')}catch(e){}})", null);
                            Toast.makeText(this, "✕ قفلت صندوق كان مغطي المشغّل", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    if (page.equals(checkedPage)) return;
                    checkedPage = page;
                    // the site answered the blocker with a wall: let this site run unblocked and try again
                    adblock.toggleAllow(currentSite);
                    try { // also forget boxes removed on this site, they may be part of the player
                        android.content.SharedPreferences p = getSharedPreferences("adblock", MODE_PRIVATE);
                        org.json.JSONObject h = new org.json.JSONObject(p.getString("hide", "{}"));
                        h.remove(AdBlock.base(currentSite));
                        p.edit().putString("hide", h.toString()).apply();
                    } catch (Exception ignored) { }
                    applyCleaner();
                    Toast.makeText(this, "🔧 المشغّل ماظهرش — شغّلت الموقع ده من غير منع وبعيد التحميل", Toast.LENGTH_LONG).show();
                    web.reload();
                    web.postDelayed(() -> web.evaluateJavascript(PLAYER_CHECK, v -> {
                        if (v != null && v.contains("wall"))
                            Toast.makeText(this, "الموقع ده لسه قافل المشغّل — جرّب موقع تاني", Toast.LENGTH_LONG).show();
                    }), 9000);
    }

    private void hoverAtCursor() {
        long t = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cursor.x, cursor.y, 0);
        m.setSource(android.view.InputDevice.SOURCE_MOUSE);
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    private volatile long playerTapAt = 0;

    private void tapAtCursor() {
        float dd = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("(function(x,y){var e=document.elementFromPoint(x,y);for(;e;e=e.parentElement){if(e.tagName==='IFRAME'||e.tagName==='VIDEO')return 'P'}return ''})("
                + Math.round(cursor.x / dd) + "," + Math.round(cursor.y / dd) + ")", v -> {
            if (v != null && v.contains("P")) playerTapAt = System.currentTimeMillis();
            doTap();
        });
    }

    private long lastTapAt = 0;
    private int retaps = 3;

    /** An ad grabbed the click: press again at the same spot (up to 3 times) so the click does what you meant. */
    private boolean retapAfterAd() {
        if (retaps >= 3 || System.currentTimeMillis() - lastTapAt > 2500 || atHome()) return false;
        retaps++;
        final float x = cursor.x, y = cursor.y;
        web.postDelayed(() -> { if (!atHome() && cursor.x == x && cursor.y == y) { lastTapAt = System.currentTimeMillis(); doTap(false); } }, 450);
        return true;
    }

    private void doTap() { doTap(true); }

    private void doTap(boolean user) {
        if (user) { lastTapAt = System.currentTimeMillis(); retaps = 0; }
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursor.x, cursor.y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cursor.x, cursor.y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        watchForPlayer();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (customView != null) { exitFullscreen(); return true; }
            if (!atHome()) {
                // a box covering the page? Back presses its ✕ (or removes it) instead of leaving the page
                web.evaluateJavascript(CLOSE_OVERLAY, v -> {
                    if (v != null && v.contains("closed")) { Toast.makeText(this, "✕ اتقفل", Toast.LENGTH_SHORT).show(); return; }
                    // nothing on the page itself: ask the frames (the player) to close their box
                    frameClosed = false;
                    web.evaluateJavascript("[].forEach.call(document.querySelectorAll('iframe'),function(f){try{f.contentWindow.postMessage({shashti:'close'},'*')}catch(e){}})", null);
                    web.postDelayed(() -> {
                        if (frameClosed) { Toast.makeText(this, "✕ اتقفل", Toast.LENGTH_SHORT).show(); return; }
                        if (web.canGoBack()) web.goBack(); else web.loadUrl(HOME);
                    }, 450);
                });
                return true;
            }
            // on the launcher page: let the page close its own dialogs first
            web.evaluateJavascript(
                "(function(){var m=document.getElementById('modal'),p=document.getElementById('push');" +
                "if(m&&m.classList.contains('open')){closeModal();return 1}" +
                "if(p&&p.classList.contains('open')){p.classList.remove('open');return 1}return 0})()",
                v -> { if (!"1".equals(v)) finish(); });
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_GUIDE) {
            if (atHome()) { web.evaluateJavascript("window.tileMenuFocused&&tileMenuFocused()", null); return true; }
            showSiteMenu();
            return true;
        }
        return super.onKeyDown(code, e);
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
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    private class Bridge {
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
        public void closedInFrame() { frameClosed = true; }

        @JavascriptInterface
        public void reportWall(String frameUrl) { runOnUiThread(() -> autoAllow(web.getUrl())); }

        @JavascriptInterface
        public void checkUpdate() { runOnUiThread(() -> Updater.check(MainActivity.this, true)); }

        @JavascriptInterface
        public String appVersion() { return String.valueOf(Updater.myCode(MainActivity.this)); }

        @JavascriptInterface
        public boolean adblockOn() { return adblock.enabled(); }

        @JavascriptInterface
        public void setAdblock(boolean on) { adblock.setEnabled(on); runOnUiThread(MainActivity.this::applyCleaner); }

        @JavascriptInterface
        public int blockedCount() { return adblock.blocked; }

        @JavascriptInterface
        public void goHome() { runOnUiThread(() -> web.loadUrl(HOME)); }
    }
}
