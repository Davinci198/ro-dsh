package com.dshmobile.app;

import android.content.Context;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 向 dsh Web UI 注入移动端适配 CSS/JS。 */
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

    /** 页面开始加载时注入老 WebView 兼容 polyfill（须在 dsh 自身脚本运行前）。 */
    public void injectEarlyPolyfill(WebView webView) {
        webView.evaluateJavascript(earlyPolyfillJs, null);
    }

    /**
     * 页面加载完成后注入。幂等，可重复调用。
     *
     * 两代 DOM 分开处理：mobile.css / inject.js 是照 dsh 0.1.0-rc.6 的哈希类名写的，
     * 0.2.x 换了类名（框架变成了 data-sidebar-* 属性驱动的响应式抽屉）。旧样式在新的
     * DOM 上不但没用，还会误判：“侧栏是否收起”靠 _collapsed 类判断，新 DOM 没有这个类，
     * 于是永远判成展开，body 常驻 dsh-mobile-drawer，遮罩盖在全屏上 → 点哪都没反应。
     * 新 DOM 自带响应式（实测 443px 宽时网格是 0 / 1fr / 0），只需要极小量的兜底样式。
     */
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
        // 覆盖入口（开发用）：把同名文件放到外部目录即可替换内置样式/脚本，
        // 不用重打 APK。dsh 升级后 DOM 类名一变就得调 CSS，这个入口把
        // "改一行 → CI 构建 3 分钟 → adb install" 缩成 "adb push → 重启 Activity"。
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

    /** 外部覆盖文件：<外部 files 目录>/ui/<name>，存在且非空时优先于内置 asset。 */
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
