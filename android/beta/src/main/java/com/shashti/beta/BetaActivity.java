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
            // the real uBlock Origin, shipped inside the app (assets/ubo)
            runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/ubo/", "uBlock0@raymondhill.net")
                .accept(ext -> { }, err -> runOnUiThread(() -> Toast.makeText(this, "uBlock: " + err, Toast.LENGTH_LONG).show()));
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

        session.open(runtime);
        view = new GeckoView(this);
        view.setSession(session);
        setContentView(view);
        session.loadUri(HOME);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
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
