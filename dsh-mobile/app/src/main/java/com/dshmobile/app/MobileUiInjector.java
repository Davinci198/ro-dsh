package com.dshmobile.app;

import android.content.Context;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Injects the mobile adaptation CSS/JS into the dsh web UI. */
public final class MobileUiInjector {

    private final String css;
    private final String js;
    private final String earlyPolyfillJs;
    private final String cssNg;
    private final String jsNg;

    public MobileUiInjector(Context ctx) {
        css = readAsset(ctx, "mobile.css");
        js = readAsset(ctx, "inject.js");
        earlyPolyfillJs = readAsset(ctx, "early-polyfill.js");
        cssNg = readAsset(ctx, "mobile-ng.css");
        jsNg = readAsset(ctx, "inject-ng.js");
    }

    /** Old-WebView polyfill, injected before the page loads (it must run before dsh's own scripts). */
    public void injectEarlyPolyfill(WebView webView) {
        webView.evaluateJavascript(earlyPolyfillJs, null);
    }

    /**
     * Injected after the page loads. Idempotent, so it can run repeatedly.
     *
     * The two DOM generations are handled separately: mobile.css / inject.js were written
     * against dsh 0.1.0-rc.6's hashed class names, and 0.2.x renamed them (the frame became a
     * responsive drawer driven by data-sidebar-* attributes). The old styles are not merely
     * useless on the new DOM, they misjudge it: "is the sidebar collapsed" was decided from
     * the _collapsed class, which the new DOM never emits, so it was always read as open,
     * body kept dsh-mobile-drawer and the mask covered the whole screen — every tap landed on
     * the mask. The new DOM is responsive by itself (443px wide measured a 0 / 1fr / 0 grid),
     * so it only needs a tiny amount of fallback styling.
    public void inject(WebView webView) {
        webView.evaluateJavascript(
                "(function(){try{return !!document.querySelector("
                        + "'[data-sidebar-drawer],[data-sidebar-mask]');}catch(e){return false;}})()",
                value -> injectFor(webView, value != null && value.contains("true")));
    }

    private void injectFor(WebView webView, boolean modern) {
        String cssJson = toJsString(modern ? cssNg : css);
        String script =
                "(function(){" +
                "if(!document.getElementById('dsh-mobile-style')){" +
                "var st=document.createElement('style');st.id='dsh-mobile-style';" +
                "st.textContent=" + cssJson + ";document.head.appendChild(st);}" +
                "var v=document.querySelector('meta[name=viewport]');" +
                "if(v){v.setAttribute('content','width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content');}" +
                "else{var m=document.createElement('meta');m.name='viewport';" +
                "m.content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';" +
                "document.head.appendChild(m);}" +
                "})();";
        webView.evaluateJavascript(script, null);
        String extra = modern ? jsNg : js;
        if (extra != null && !extra.isEmpty()) {
            webView.evaluateJavascript(extra, null);
        }
    }

    private static String readAsset(Context ctx, String name) {
        // Override hook (for development): dropping a file of the same name into the
        // external directory replaces the bundled style/script without rebuilding the APK.
        // A dsh upgrade changes the DOM class names and the CSS has to follow; this turns
        // "edit a line → 3 minute CI build → adb install" into "adb push → restart activity".
        String override = readOverride(ctx, name);
        if (override != null) return override;
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    /** External override: <external files dir>/ui/<name>, used when it exists and is not empty. */
    private static String readOverride(Context ctx, String name) {
        java.io.File dir = ctx.getExternalFilesDir("ui");
        if (dir == null) return null;
        java.io.File f = new java.io.File(dir, name);
        if (!f.isFile() || f.length() == 0) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return null;
        }
    }

    private static String toJsString(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
