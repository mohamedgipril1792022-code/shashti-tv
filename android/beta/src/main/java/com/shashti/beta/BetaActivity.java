package com.shashti.beta;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;

/**
 * Sajo Beta — a test of Firefox's engine (GeckoView) with the real uBlock Origin inside.
 * Two things to try: ads on the user's sites, and YouTube playing with the phone locked.
 * Home is the same Sajo TV launcher page (its web version).
 */
public class BetaActivity extends Activity {
    private static final String HOME = "https://mohamedgipril1792022-code.github.io/shashti-tv/";
    private static GeckoRuntime runtime;

    private GeckoView view;
    private GeckoSession session;
    private boolean canGoBack = false, fullScreen = false, playing = false;
    private String url = HOME;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (runtime == null) {
            runtime = GeckoRuntime.create(this, new GeckoRuntimeSettings.Builder().consoleOutput(false).build());
            // the real uBlock Origin from Firefox's add-on store (signed), installed once; a message says whether it runs
            final org.mozilla.geckoview.WebExtensionController wc = runtime.getWebExtensionController();
            wc.setPromptDelegate(new org.mozilla.geckoview.WebExtensionController.PromptDelegate() {
                @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onInstallPrompt(org.mozilla.geckoview.WebExtension ext) {
                    return GeckoResult.allow();
                }
            });
            wc.list().accept(list -> {
                org.mozilla.geckoview.WebExtension found = null;
                if (list != null) for (org.mozilla.geckoview.WebExtension e : list)
                    if ("uBlock0@raymondhill.net".equals(e.id) && !e.isBuiltIn) found = e;
                if (found != null) {
                    String v = found.metaData != null ? found.metaData.version : "";
                    boolean on = found.metaData == null || found.metaData.enabled;
                    say(on ? "✅ uBlock Origin شغال " + v : "⚠ uBlock Origin متركّب بس مقفول");
                    if (!on) wc.enable(found, org.mozilla.geckoview.WebExtensionController.EnableSource.USER);
                    return;
                }
                say("⏳ بيتركّب uBlock Origin…");
                wc.install("https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi")
                    .accept(ext -> say("✅ uBlock Origin اتركّب وشغال" + (ext != null && ext.metaData != null ? " " + ext.metaData.version : "")),
                            err -> say("❌ uBlock Origin متركّبش: " + err));
            }, err -> say("❌ " + err));
        }
        session = new GeckoSession(new GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build());

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean can) { canGoBack = can; }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                // a link that opens a new window: open it right here (ad pop-ups are stopped by uBlock)
                session.loadUri(uri);
                return null;
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String u) { url = u; }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onFullScreen(GeckoSession s, boolean full) {
                fullScreen = full;
                if (full) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }
            }
        });
        session.setMediaSessionDelegate(new MediaSession.Delegate() {
            @Override public void onPlay(GeckoSession s, MediaSession m) { playing = true; }
            @Override public void onPause(GeckoSession s, MediaSession m) { playing = false; }
            @Override public void onStop(GeckoSession s, MediaSession m) { playing = false; }
        });

        // the page's dialogs: choosing a file (restore a backup), alerts, yes/no questions, text questions
        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
                GeckoResult<PromptResponse> res = new GeckoResult<>();
                pendingFile = prompt; pendingFileResult = res;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "اختار الملف"), 41); }
                catch (Exception e) { res.complete(prompt.dismiss()); pendingFile = null; }
                return res;
            }
            @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt prompt) {
                GeckoResult<PromptResponse> res = new GeckoResult<>();
                new AlertDialog.Builder(BetaActivity.this).setMessage(prompt.message)
                    .setPositiveButton("تمام", (d, w) -> res.complete(prompt.dismiss()))
                    .setOnCancelListener(d -> res.complete(prompt.dismiss())).show();
                return res;
            }
            @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt prompt) {
                GeckoResult<PromptResponse> res = new GeckoResult<>();
                new AlertDialog.Builder(BetaActivity.this).setMessage(prompt.message)
                    .setPositiveButton("أيوه", (d, w) -> res.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                    .setNegativeButton("لأ", (d, w) -> res.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                    .setOnCancelListener(d -> res.complete(prompt.dismiss())).show();
                return res;
            }
            @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt prompt) {
                GeckoResult<PromptResponse> res = new GeckoResult<>();
                EditText et = new EditText(BetaActivity.this);
                et.setText(prompt.defaultValue);
                new AlertDialog.Builder(BetaActivity.this).setMessage(prompt.message).setView(et)
                    .setPositiveButton("تمام", (d, w) -> res.complete(prompt.confirm(et.getText().toString())))
                    .setNegativeButton("إلغاء", (d, w) -> res.complete(prompt.dismiss()))
                    .setOnCancelListener(d -> res.complete(prompt.dismiss())).show();
                return res;
            }
        });

        session.open(runtime);
        view = new GeckoView(this);
        view.setSession(session);
        setContentView(view);
        session.loadUri(HOME);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
    }

    private void say(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    private GeckoSession.PromptDelegate.FilePrompt pendingFile;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFileResult;

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != 41 || pendingFile == null) return;
        Uri u = data == null ? null : data.getData();
        pendingFileResult.complete(u != null ? pendingFile.confirm(this, u) : pendingFile.dismiss());
        pendingFile = null; pendingFileResult = null;
    }

    @Override
    public void onBackPressed() {
        if (fullScreen) { session.exitFullScreen(); return; }
        if (canGoBack) { session.goBack(); return; }
        if (url != null && !url.startsWith(HOME)) { session.loadUri(HOME); return; }
        super.onBackPressed();
    }

    // the phone locked / the app left while something plays: it keeps playing (the page stays "active")
    @Override
    protected void onPause() {
        super.onPause();
        if (playing) { PlayService.start(this); session.setActive(true); }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playing) session.setActive(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayService.stop(this);
        session.setActive(true);
    }

    @Override
    protected void onDestroy() {
        PlayService.stop(this);
        super.onDestroy();
    }
}
