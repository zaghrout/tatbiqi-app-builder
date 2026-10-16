package com.tatbiqi.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Tatbiqi store app shell.
 *
 * This file is identical for every store. All per-store values (URL, color,
 * WhatsApp) come from assets/tatbiqi.json, which build_apk.py writes. Keeping
 * store data out of the Java source means no user input is ever compiled as code.
 *
 * Rules learned on Realme/Xiaomi (see LESSONS-LEARNED.md):
 *  - The WebView is the only view. No native buttons or overlays on top of it.
 *  - The WhatsApp button is injected into the page with JavaScript.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private String storeUrl = "https://salla.sa";
    private String storeHost = "";
    private String primaryColor = "#0F766E";
    private String whatsappNumber = "";
    private String whatsappMessage = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();

        try {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor(primaryColor));
        } catch (Exception ignored) {
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDefaultTextEncodingName("UTF-8");
        s.setSupportZoom(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        if (whatsappNumber.length() > 0) {
            webView.addJavascriptInterface(new WhatsAppBridge(), "TatbiqiApp");
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectWhatsAppButton(view, url);
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(storeUrl);
        }
    }

    /** Reads assets/tatbiqi.json. Falls back to defaults if anything is off. */
    private void loadConfig() {
        try {
            InputStream in = getAssets().open("tatbiqi.json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            JSONObject cfg = new JSONObject(out.toString("UTF-8"));
            storeUrl = cfg.optString("storeUrl", storeUrl);
            primaryColor = cfg.optString("primaryColor", primaryColor);
            whatsappNumber = cfg.optString("whatsappNumber", "");
            whatsappMessage = cfg.optString("whatsappMessage", "");
        } catch (Exception ignored) {
        }
        try {
            String host = Uri.parse(storeUrl).getHost();
            storeHost = host == null ? "" : host;
        } catch (Exception ignored) {
        }
    }

    /**
     * Web links stay inside the app (checkout and payment pages need that).
     * Everything else (tel:, mailto:, whatsapp:, intent:) opens the matching app.
     */
    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

        if (host.equals("wa.me") || host.endsWith("whatsapp.com")) {
            openExternal(uri);
            return true;
        }
        if (scheme.equals("http") || scheme.equals("https")) {
            return false;
        }
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        } catch (Exception ignored) {
        }
    }

    /** Adds the floating WhatsApp button, only on the store's own pages. */
    private void injectWhatsAppButton(WebView view, String url) {
        if (whatsappNumber.length() == 0) {
            return;
        }
        try {
            String host = Uri.parse(url).getHost();
            if (host == null || storeHost.length() == 0 || !host.equalsIgnoreCase(storeHost)) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        String js = "(function(){"
                + "if(document.getElementById('tatbiqi-wa'))return;"
                + "var b=document.createElement('div');"
                + "b.id='tatbiqi-wa';"
                + "b.setAttribute('role','button');"
                + "b.setAttribute('aria-label','WhatsApp');"
                + "b.innerHTML='<svg viewBox=\"0 0 32 32\" width=\"28\" height=\"28\" fill=\"#fff\">"
                + "<path d=\"M16 3C9 3 3.3 8.6 3.3 15.6c0 2.5.7 4.8 1.9 6.8L3 29l6.8-2.1c1.9 1 4 1.6 6.2 1.6 "
                + "7 0 12.7-5.6 12.7-12.6S23 3 16 3zm0 23.1c-2 0-3.9-.6-5.5-1.6l-.4-.2-4 1.2 1.3-3.9-.3-.4"
                + "a10.4 10.4 0 0 1-1.7-5.6C5.4 9.8 10.2 5.1 16 5.1s10.6 4.7 10.6 10.5S21.8 26.1 16 26.1zm5.8-7.8"
                + "c-.3-.2-1.9-.9-2.2-1-.3-.1-.5-.2-.7.2-.2.3-.8 1-1 1.2-.2.2-.4.2-.7.1-.3-.2-1.3-.5-2.5-1.5"
                + "-.9-.8-1.6-1.8-1.7-2.1-.2-.3 0-.5.1-.6l.5-.6c.2-.2.2-.3.3-.5.1-.2 0-.4 0-.5l-1-2.4c-.3-.6"
                + "-.5-.5-.7-.5h-.6c-.2 0-.5.1-.8.4-.3.3-1 1-1 2.5s1.1 2.9 1.2 3.1c.2.2 2.1 3.2 5.1 4.5 "
                + "2.5 1 3 .8 3.6.7.6-.1 1.9-.8 2.2-1.5.3-.7.3-1.4.2-1.5-.1-.1-.3-.2-.6-.3z\"/></svg>';"
                + "b.style.cssText='position:fixed;bottom:20px;left:16px;width:56px;height:56px;"
                + "border-radius:50%;background:#25D366;display:flex;align-items:center;"
                + "justify-content:center;z-index:2147483646;cursor:pointer;"
                + "box-shadow:0 4px 12px rgba(0,0,0,.3);';"
                + "b.onclick=function(){TatbiqiApp.openWhatsApp();};"
                + "(document.body||document.documentElement).appendChild(b);"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    private class WhatsAppBridge {
        @JavascriptInterface
        public void openWhatsApp() {
            String link = "https://wa.me/" + whatsappNumber;
            if (whatsappMessage.length() > 0) {
                link += "?text=" + Uri.encode(whatsappMessage);
            }
            openExternal(Uri.parse(link));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
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
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
