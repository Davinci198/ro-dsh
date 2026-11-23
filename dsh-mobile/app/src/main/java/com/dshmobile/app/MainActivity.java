package com.dshmobile.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import android.net.Uri;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Main screen: a full-screen WebView for the dsh web UI, with a startup screen until it is ready. */
public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout splash;
    private TextView splashStatus;
    private MobileUiInjector injector;
    private Handler handler;
    private volatile boolean stopPolling;
    private volatile boolean pageFailed;
    private int loadAttempts;
    private int port;
    private static final int REQ_FILE_CHOOSER = 42;
    /** dsh web requires a token, and the token changes on every harness restart, so the log is re-read before each load. */
    private static final Pattern PAGE_TOKEN =
            Pattern.compile("[?&]token=([A-Za-z0-9._~-]+)");
    private static final long TOKEN_SCAN_BYTES = 256 * 1024;
    private boolean loadedWithToken;
    private int authRetries;
    private int httpErrorRetries;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Prefs prefs = Prefs.of(this);
        // The setup flag alone cannot be trusted after an interrupted reset: the container may be
        // gone while the flag still says ready. Starting the harness in that state used to end in
        // ForegroundServiceDidNotStartInTimeException, which looks like the app crashing on open.
        if (!prefs.isSetupDone()
                || !new File(ProotRunner.rootfsDir(this), "bin/bash").isFile()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        port = prefs.getPort();
        handler = new Handler(Looper.getMainLooper());
        injector = new MobileUiInjector(this);

        // Automation hook: `adb shell am start -n com.dshmobile.app/.MainActivity --es screen
        // terminal` opens the container terminal directly. The terminal activity is not
        // exported (it runs a root shell), so this is the only way to reach it from a script
        // without tapping the screen. "reset" does the same with the container reset that the
        // settings screen offers, which is how a rootfs upgrade is performed on a live install.
        String screen = getIntent().getStringExtra("screen");
        if ("terminal".equals(screen)) {
            startActivity(new Intent(this, TerminalActivity.class));
        } else if ("reset".equals(screen)) {
            resetContainer();
        } else if ("setup".equals(screen)) {
            // Resumes the installer without wiping anything: an interrupted install picks up at
            // the first stage that is not already done.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
        }

        // WebView remote debugging: the injected CSS/JS depends on dsh's DOM class
        // names, which carry a build hash and change with every dsh upgrade (that is how
        // the drawer once got stuck open). With this on, the real DOM can be read over
        // `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` + CDP
        // instead of guessed. Only the local 127.0.0.1 page is exposed.
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // Permissions: up to Android 12 storage is requested (/sdcard/dsh-shared is the
        // fallback mapping). From Android 13 (16 included) the foreground-service
        // notification is requested explicitly, because targetSdk 28 only shows the system
        // fallback prompt at a moment nobody controls.
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 33) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), 1);
        }

        buildUi();
        warnIfWebViewTooOld();
        checkUpdate();
        HarnessService.startService(this);
        waitForServerAndLoad();
    }

    // ---------- update check ----------

    /** Checks the latest GitHub release on launch and offers it. Fails silently. */
    private void checkUpdate() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(
                        "https://api.github.com/repos/Ajwyunsx/deepseek-harness-mobile/releases/latest");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "dsh-mobile/" + currentVersion());
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                in.close();
                c.disconnect();
                org.json.JSONObject j = new org.json.JSONObject(buf.toString("UTF-8"));
                String tag = j.optString("tag_name", "");
                String notes = j.optString("body", "");
                handler.post(() -> maybeShowUpdate(tag, notes));
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: skipped silently, never blocking.
            }
        }, "dsh-update-check").start();
    }

    private void maybeShowUpdate(String tag, String notes) {
        if (isFinishing() || tag.isEmpty()) return;
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        if (compareVersion(latest, currentVersion()) <= 0) return;
        String body = notes == null ? "" : notes.trim();
        if (body.length() > 500) body = body.substring(0, 500) + "…";
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_title, tag))
                .setMessage(body.isEmpty()
                        ? getString(R.string.update_message, currentVersion(), tag)
                        : body)
                .setPositiveButton(R.string.update_now, (d, w) -> {
                    try {
                        // Direct link to the fixed asset name; the browser downloads it.
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Ajwyunsx/deepseek-harness-mobile/releases/latest/download/dsh-mobile.apk")));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, R.string.update_open_failed, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    /** Reads this app's own versionName at runtime (AGP 9 no longer generates BuildConfig). */
    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Semantic version compare: positive when a>b, negative when a<b, 0 when equal. */
    private static int compareVersion(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** An old WebView (Chrome below 116, common on EMUI and old ROMs) lacks a pile of
     *  modern APIs and the dsh UI throws or crashes its renderer. The polyfill covers what
     *  it can; updating the engine is the actual fix, so say so. */
    private void warnIfWebViewTooOld() {
        try {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            String v = pi == null ? null : pi.versionName;
            if (v == null) return;
            int dot = v.indexOf('.');
            int major = Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
            if (major < 116) {
                android.widget.Toast.makeText(this,
                        getString(R.string.webview_old, v),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
            // An unparsable version is not worth a warning and must not block start.
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFailed = false;
                injector.injectEarlyPolyfill(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageFailed) return;
                injector.inject(view);
                // The 401 page loads like any other page, so "this is the 401, not the UI"
                // has to be recognised from its text: once the harness restarts the old token
                // is dead, and the page is reloaded with a fresh one.
                view.evaluateJavascript(
                        "(document.body&&document.body.innerText||'').slice(0,200)",
                        text -> {
                            boolean authPage = text != null
                                    && text.toLowerCase().contains("authentication required");
                            if (authPage && authRetries < 5) {
                                authRetries++;
                                loadAttempts = 0;
                                showSplash(getString(R.string.token_expired));
                                handler.postDelayed(() -> {
                                    if (!isFinishing()) loadMainUrl();
                                }, 1500);
                                return;
                            }
                            authRetries = 0;
                            httpErrorRetries = 0;
                            dismissSplash();
                        });
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (!request.isForMainFrame()) return;
                int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
                // Neither of these answers a readable page: a 401 means the token in the URL
                // died with the previous harness process, a 5xx means dsh web is up but broken.
                // The WebView then renders net::ERR_HTTP_RESPONSE_CODE_FAILURE, which carries no
                // text to inspect, so the status code is the only signal to act on.
                if (code == 401 || code == 403 || code >= 500) {
                    pageFailed = true;
                    recoverFromHttpError(code);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only the main frame matters: a failed subresource leaves the page usable.
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    scheduleReload();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            /**
             * A page-side <input type="file"> (dsh attachments and its upload plugin both
             * use it) is swallowed silently by a plain Android WebView. The system picker has
             * to be started here, or tapping upload does nothing at all.
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    // createIntent() already carries the accept types and the multi-select flag.
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    // Nothing can handle the picker: hand the callback back so the page cannot hang.
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return true;
                }
            }
        });
        webView.addJavascriptInterface(new DshBridge(), "DshNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildFabs());

        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /**
     * Page to native bridge, called by inject.js after it intercepts a page button.
     * Note: @JavascriptInterface methods run on a WebView background thread, so any UI
     * work has to be posted back to the main thread.
     */
    private final class DshBridge {
        /** The settings dialog's "open the config file": xdg-open is absent in the container, so this is native. */
        @android.webkit.JavascriptInterface
        public void openConfig() {
            handler.post(() -> {
                if (!isFinishing()) {
                    startActivity(new Intent(MainActivity.this, ConfigEditorActivity.class));
                }
            });
        }

        /** The plugin page's "add plugin": runs dsh plugin --profile web add off the main thread. */
        @android.webkit.JavascriptInterface
        public void installPlugin(final String spec) {
            new Thread(() -> {
                final PluginInstaller.Result r = PluginInstaller.install(MainActivity.this, spec);
                final String json = "{\"ok\":" + r.ok + ",\"output\":"
                        + org.json.JSONObject.quote(r.output) + "}";
                handler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.__dshOnPluginInstallResult && window.__dshOnPluginInstallResult(" + json + ")",
                                null);
                    }
                });
            }, "dsh-plugin-install").start();
        }
    }

    /** Right-edge floating buttons: file manager above, container terminal below. */
    private View buildFabs() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(fab("📁", v ->
                startActivity(new Intent(this, FileManagerActivity.class))));
        View term = fab(">_", v ->
                startActivity(new Intent(this, TerminalActivity.class)));
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) term.getLayoutParams();
        tlp.topMargin = Ui.dp(this, 12);
        col.addView(term, tlp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.rightMargin = Ui.dp(this, 6);
        col.setLayoutParams(lp);
        return col;
    }

    private View fab(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.PRIMARY);
        tv.setBackground(g);
        tv.setAlpha(0.85f);
        int size = Ui.dp(this, 44);
        tv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        tv.setOnClickListener(click);
        return tv;
    }

    private FrameLayout buildSplash() {
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Ui.bg(this));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 36);
        box.setPadding(pad, pad, pad, pad);

        // The official DeepSeek Harness whale mark; the icon carries the brand on its own.
        // The vector is filled black, so it takes the text colour in dark mode; a black whale on black is invisible.
        ImageView logo = new ImageView(this);
        Drawable d = getResources().getDrawable(R.drawable.ic_dsh_brand, null);
        logo.setImageDrawable(d);
        logo.setImageTintList(ColorStateList.valueOf(Ui.text(this)));
        int logoSize = Ui.dp(this, 96);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        box.addView(logo, llp);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.PRIMARY));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 32);
        box.addView(pb, plp);

        splashStatus = Ui.hint(this, getString(R.string.starting_container));
        splashStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 16);
        box.addView(splashStatus, stlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 40);

        Button settings = Ui.outlineButton(this, getString(R.string.settings));
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btns.addView(settings);

        // The terminal stays reachable after a failed start, which is what a dead web service needs.
        Button term = Ui.outlineButton(this, getString(R.string.terminal));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        tlp.leftMargin = Ui.dp(this, 16);
        term.setLayoutParams(tlp);
        term.setOnClickListener(v ->
                startActivity(new Intent(this, TerminalActivity.class)));
        btns.addView(term);

        Button reinstall = Ui.primaryButton(this, getString(R.string.reinstall));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        rlp.leftMargin = Ui.dp(this, 16);
        reinstall.setLayoutParams(rlp);
        reinstall.setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));
        btns.addView(reinstall);

        box.addView(btns, blp);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER;
        fl.addView(box, flp);
        return fl;
    }

    /** Polls the web service with real HTTP requests and loads the page when it answers. */
    private void waitForServerAndLoad() {
        stopPolling = false;
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 120_000;
            boolean up = false;
            while (!stopPolling && System.currentTimeMillis() < deadline) {
                if (httpReady(port)) {
                    up = true;
                    break;
                }
                final long left = (deadline - System.currentTimeMillis()) / 1000;
                handler.post(() -> splashStatus.setText(
                        HarnessService.isRunning()
                                ? getString(R.string.container_started_waiting, left)
                                : getString(R.string.starting_container_seconds, left)));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            final boolean ok = up;
            handler.post(() -> {
                if (isFinishing()) return;
                if (ok) {
                    splashStatus.setText(R.string.loading_ui);
                    loadMainUrl();
                } else {
                    splashStatus.setText(R.string.wait_timeout);
                }
            });
        }, "dsh-port-poll").start();
    }

    private void loadMainUrl() {
        loadAttempts++;
        String token = harnessPageToken();
        loadedWithToken = token != null;
        // dsh web listens first and prints its tokenised URL afterwards. Opening the bare
        // path in between yields the 401 "authentication required" page, which is a normal
        // response, so onPageFinished would drop the splash and strand the user on that dead
        // page. The token is therefore awaited first; only when it never appears is the bare
        // path used (older dsh web needs no token).
        if (token == null && loadAttempts <= 60) {
            showSplash(getString(R.string.waiting_token));
            handler.postDelayed(() -> {
                if (!isFinishing()) loadMainUrl();
            }, 1000);
            return;
        }
        // Without a token the bare root is used: older dsh web needs none and it still opens.
        webView.loadUrl("http://127.0.0.1:" + port + "/"
                + (token == null ? "" : "?token=" + token));
    }

    /**
     * Reads the access token dsh web printed last from the harness log.
     *
     * `dsh web` prints `dsh web: http://127.0.0.1:3080/?token=...` on start, and opening
     * `http://127.0.0.1:3080/` without it only yields "authentication required; reopen the
     * URL printed by dsh web.". The token changes on every restart, so it cannot be cached
     * once: it is re-read from the log tail every time, and only the tail is scanned so a
     * growing log never has to be read whole.
     */
    private String harnessPageToken() {
        File log = new File(ProotRunner.logDir(this), "dsh-web.log");
        if (!log.isFile()) return null;
        String tail;
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - TOKEN_SCAN_BYTES);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        Matcher m = PAGE_TOKEN.matcher(tail);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /**
     * A non-2xx answer for the main frame. The token rotates on every harness restart, so the
     * first attempts just re-read the log tail and reload; only a server that keeps failing is
     * restarted, and a restart is what mints a new token anyway.
     */
    private void recoverFromHttpError(int code) {
        if (isFinishing()) return;
        httpErrorRetries++;
        loadAttempts = 0;
        if (httpErrorRetries <= 2) {
            showSplash(getString(R.string.token_expired));
            handler.postDelayed(() -> {
                if (!isFinishing()) loadMainUrl();
            }, 1200);
            return;
        }
        if (httpErrorRetries <= 4) {
            showSplash(getString(R.string.service_retry));
            restartHarness();
            return;
        }
        httpErrorRetries = 0;
        showSplash(getString(R.string.load_failed));
    }

    /** Stops dsh web and starts it again, which also prints a fresh access token. */
    private void restartHarness() {
        HarnessService.stopService(this);
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            HarnessService.startService(this);
            handler.postDelayed(() -> {
                if (!isFinishing()) loadMainUrl();
            }, 3000);
        }, 2000);
    }

    /** Main frame failed: show the splash again and retry a bounded number of times. */
    private void scheduleReload() {
        handler.post(() -> {
            if (isFinishing()) return;
            if (loadAttempts >= 90) {
                showSplash(getString(R.string.load_failed));
                return;
            }
            showSplash(getString(R.string.service_retry));
            handler.postDelayed(() -> {
                if (!isFinishing()) loadMainUrl();
            }, 2000);
        });
    }

    private void showSplash(String text) {
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        splashStatus.setText(text);
    }

    private void dismissSplash() {
        if (splash.getVisibility() != View.VISIBLE) return;
        splash.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            splash.setVisibility(View.GONE);
            splash.setAlpha(1f);
        }).start();
    }

    /** A listening TCP port is not a working HTTP service: during warm-up PRoot holds the
     *  port without answering, and a loadUrl there is just a blank page. A real response is
     *  what counts as ready. */
    private static boolean httpReady(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(1500);
            conn.setInstanceFollowRedirects(false);
            conn.getResponseCode();
            return true; // Any HTTP response, 404/302 included, proves the service answers.
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Wipes the container and hands over to the first-run installer. A stored 22.04 rootfs URL
     * would reinstall the release being replaced, so it is dropped in favour of the current
     * default; a custom mirror URL is kept as it is.
     */
    private void resetContainer() {
        Prefs prefs = Prefs.of(this);
        HarnessService.stopService(this);
        new Thread(() -> {
            if (prefs.getRootfsUrl().contains("22.04")) {
                prefs.setRootfsUrl(Prefs.DEFAULT_ROOTFS_URL);
            }
            BootstrapInstaller.resetToFreshContainer(this);
            runOnUiThread(() -> {
                startActivity(new Intent(this, SetupActivity.class));
                finish();
            });
        }, "dsh-reset").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        // The last load went out without a token, so the page sits on "authentication
        // required": reload once with a token on resume. The flag is set after a successful
        // tokenised load, so this never loops and never loses the chat position.
        if (webView != null && !loadedWithToken && splash.getVisibility() != View.VISIBLE) {
            loadMainUrl();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Never loaded successfully and the splash is still up: start polling again.
        if (webView != null && splash.getVisibility() == View.VISIBLE
                && (webView.getUrl() == null || pageFailed)) {
            loadAttempts = 0;
            waitForServerAndLoad();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling = true;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    // Multiple picks arrive in clipData, a single one in data.
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
