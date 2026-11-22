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
        return "window.__SHASHTI_ALLOW=" + a + ";" + cleanJs;
    }

    private boolean blocking() { return adblock.enabled() && !adblock.isAllowed(currentSite); }
    }

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
                if (!blocking() || atHome()) return false;
                if (adblock.isAd(host)) { lastBlocked = r.getUrl().toString(); blockedToast(); return true; }
                // a jump to another site that you did not click is an ad redirect; clicks are always allowed
                if (r.isForMainFrame() && Build.VERSION.SDK_INT >= 24 && !r.isRedirect() && !r.hasGesture()) {
                    String cur = Uri.parse(web.getUrl()).getHost();
                    if (!AdBlock.sameSite(cur, host)) { lastBlocked = r.getUrl().toString(); blockedToast(); return true; }
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

        applyCleaner();
        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
    }

    private void openPopup(String url, boolean gesture) {
        String host = Uri.parse(url).getHost();
        if (!adblock.isAd(host)) lastBlocked = url;
        if (atHome()) { // links from the launcher itself
            web.loadUrl(url);
            return;
        }
        if (!blocking()) { web.loadUrl(url); return; }
        String cur = Uri.parse(web.getUrl()).getHost();
        if (gesture && !adblock.isAd(host) && AdBlock.sameSite(cur, host)) { web.loadUrl(url); return; }
        blockedToast();
    }

    private long lastToast = 0;
    private String lastBlocked = null;
    private void blockedToast() {
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
        String[] items = {"🏠 الرئيسية", "➕ أضف الموقع ده لمواقعي", "▶ احفظ الصفحة دي في تكملة المشاهدة", "📋 انسخ الرابط", "↪ افتح آخر صفحة اتمنعت", "🛡 مانع الإعلانات: " + (adblock.enabled() ? "شغال (اقفله)" : "مقفول (شغّله)"), adblock.isAllowed(currentSite) ? "🛡 رجّع المنع على الموقع ده" : "✅ اسمح للموقع ده (لو بيبوظ مع المنع)"};
        new android.app.AlertDialog.Builder(this)
            .setTitle(Uri.parse(url).getHost())
            .setItems(items, (dlg, which) -> {
                if (which == 0) { web.loadUrl(HOME); return; }
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
                long held = SystemClock.uptimeMillis() - pressStart;
                // short tap = fine move; holding speeds up smoothly
                float step = d * (e.getRepeatCount() == 0 ? 22 : Math.min(14 + held / 18f, 85));
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
                return true;
            }
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

    private void hoverAtCursor() {
        long t = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cursor.x, cursor.y, 0);
        m.setSource(android.view.InputDevice.SOURCE_MOUSE);
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    private void tapAtCursor() {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursor.x, cursor.y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cursor.x, cursor.y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (customView != null) { exitFullscreen(); return true; }
            if (!atHome()) {
                if (web.canGoBack()) web.goBack(); else web.loadUrl(HOME);
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
        public boolean adblockOn() { return adblock.enabled(); }

        @JavascriptInterface
        public void setAdblock(boolean on) { adblock.setEnabled(on); runOnUiThread(MainActivity.this::applyCleaner); }

        @JavascriptInterface
        public int blockedCount() { return adblock.blocked; }

        @JavascriptInterface
        public void goHome() { runOnUiThread(() -> web.loadUrl(HOME)); }
    }
}
