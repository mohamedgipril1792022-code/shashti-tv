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
import android.view.KeyEvent;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
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
                if (adblock.isAd(r.getUrl().getHost())) {
                    adblock.blocked++;
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                if (!"http".equals(sc) && !"https".equals(sc)) return true; // ignore intent://, market:// ...
                String host = r.getUrl().getHost();
                if (adblock.isAd(host)) { blockedToast(); return true; }
                if (adblock.enabled() && r.isForMainFrame() && !atHome() && Build.VERSION.SDK_INT >= 24
                        && !r.isRedirect() && !r.hasGesture()) {
                    String cur = Uri.parse(web.getUrl()).getHost();
                    if (!AdBlock.sameSite(cur, host)) { blockedToast(); return true; } // silent jump to another site
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
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView t, WebResourceRequest r) {
                        openPopup(r.getUrl().toString(), gesture);
                        t.destroy();
                        return true;
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

        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
    }

    private void openPopup(String url, boolean gesture) {
        String host = Uri.parse(url).getHost();
        if (atHome()) { // links from the launcher itself
            if (host != null && host.contains("google.") && url.contains("/search")) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    Toast.makeText(this, "انسخ رابط الموقع من المتصفح وارجع للبرنامج", Toast.LENGTH_LONG).show();
                    return;
                } catch (Exception noBrowser) { /* no browser on the box: open inside the app */ }
            }
            web.loadUrl(url);
            return;
        }
        if (!adblock.enabled()) { web.loadUrl(url); return; }
        String cur = Uri.parse(web.getUrl()).getHost();
        if (gesture && !adblock.isAd(host) && AdBlock.sameSite(cur, host)) { web.loadUrl(url); return; }
        blockedToast();
    }

    private long lastToast = 0;
    private void blockedToast() {
        adblock.blocked++;
        long now = System.currentTimeMillis();
        if (now - lastToast > 4000) {
            lastToast = now;
            runOnUiThread(() -> Toast.makeText(this, "🛡 اتمنع إعلان", Toast.LENGTH_SHORT).show());
        }
    }

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
            web.loadUrl(HOME);
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
        public void setAdblock(boolean on) { adblock.setEnabled(on); }

        @JavascriptInterface
        public int blockedCount() { return adblock.blocked; }

        @JavascriptInterface
        public void goHome() { runOnUiThread(() -> web.loadUrl(HOME)); }
    }
}
