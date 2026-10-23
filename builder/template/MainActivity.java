package com.tatbiqi.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tatbiqi store app shell.
 *
 * This file is identical for every store. All per-store values (URL, color,
 * WhatsApp) come from assets/tatbiqi.json, which build_apk.py writes. Keeping
 * store data out of the Java source means no user input is ever compiled as code.
 *
 * Add-ons the merchant changes on the dashboard (WhatsApp button, offers popup)
 * are read at every launch from configUrl, so they apply without a new build.
 * The last answer is kept, so they also work offline; until the first answer
 * arrives the values from tatbiqi.json are used.
 *
 * Store notifications come from NotificationJob, which checks the dashboard in
 * the background. Tapping one opens this activity with its link.
 *
 * Design: the splash image (assets/splash.jpg) is drawn behind the WebView,
 * which stays hidden until the first page loads. The secondary color paints
 * the bottom navigation bar, the accent color the offer popup's button.
 *
 * Rules learned on Realme/Xiaomi (see LESSONS-LEARNED.md):
 *  - The WebView is the only view. No native buttons or overlays on top of it.
 *  - The WhatsApp button is injected into the page with JavaScript.
 *
 * Targets Android 16 (API 36), which Google Play requires. On Android 15+ the app
 * is always drawn edge to edge, so the WebView is padded by the system bar insets
 * and the store color shows behind the bars. On Android 13+ back is handled with
 * OnBackInvokedCallback, because onBackPressed is no longer called at API 36.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private String storeUrl = "https://salla.sa";
    private String storeHost = "";
    private String primaryColor = "#0F766E";
    /** Bottom navigation bar; "" keeps the primary color. */
    private String secondaryColor = "";
    /** Offer popup button; "" uses the primary color. */
    private String accentColor = "";
    private String splashAsset = "";
    private ShellBackground background;
    private boolean splashShowing = false;
    private String whatsappNumber = "";
    private String whatsappMessage = "";
    private String configUrl = "";
    /** Offers popup from the dashboard, or null when off. */
    private JSONObject offer = null;
    /** The popup shows once per launch. */
    private boolean offerShown = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();
        if (savedInstanceState != null) {
            offerShown = savedInstanceState.getBoolean("offerShown", false);
        }
        applyRemoteConfig(getSharedPreferences("tatbiqi", MODE_PRIVATE).getString("remoteConfig", null));

        int barColor = Color.parseColor("#0F766E");
        try {
            barColor = Color.parseColor(primaryColor);
        } catch (Exception ignored) {
        }

        int navColor = parseColor(secondaryColor, barColor);

        FrameLayout root = new FrameLayout(this);
        background = new ShellBackground(barColor, navColor);
        root.setBackground(background);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setUpSystemBars(root, barColor, navColor);
        if (savedInstanceState == null) {
            showSplash();
        }

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

        // Always added: the dashboard can turn the WhatsApp button on after install.
        webView.addJavascriptInterface(new WhatsAppBridge(), "TatbiqiApp");

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
                hideSplash();
                injectWhatsAppButton(view, url);
                maybeShowOffer(view, url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateBackCallback();
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            String link = notificationLink(getIntent());
            webView.loadUrl(link != null ? link : storeUrl);
        }
        fetchRemoteConfig();
        NotificationJob.schedule(this, configUrl);
        askNotificationPermission();
    }

    /** A tapped notification while the app is open: go to its page. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String link = notificationLink(intent);
        if (link != null && webView != null) {
            webView.loadUrl(link);
        }
    }

    private static String notificationLink(Intent intent) {
        String link = intent == null ? null : intent.getStringExtra(NotificationJob.EXTRA_URL);
        return link != null && link.startsWith("https://") ? link : null;
    }

    /** Android 13+ needs the customer's OK to show notifications. Asked once, on the first launch. */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || !configUrl.startsWith("https://")) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("tatbiqi", MODE_PRIVATE);
        if (prefs.getBoolean("notifAsked", false)
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        prefs.edit().putBoolean("notifAsked", true).apply();
        try {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        } catch (Exception ignored) {
        }
    }

    /**
     * Store color behind the status bar and the secondary color behind the
     * navigation bar, with icons that stay readable on them.
     */
    @SuppressWarnings("deprecation")
    private void setUpSystemBars(View root, int barColor, int navColor) {
        boolean lightBar = luminance(barColor) > 0.6;
        boolean lightNav = luminance(navColor) > 0.6;
        boolean customNav = secondaryColor.length() > 0;
        Window window = getWindow();
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                // Edge to edge is forced: keep the page clear of the bars and the keyboard.
                root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        Insets bars = insets.getInsets(
                                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                        Insets ime = insets.getInsets(WindowInsets.Type.ime());
                        v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                        background.setBottomHeight(bars.bottom);
                        return WindowInsets.CONSUMED;
                    }
                });
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(barColor);
                if (customNav) {
                    window.setNavigationBarColor(navColor);
                    if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 30 && lightNav) {
                        View decor = window.getDecorView();
                        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                int value = lightBar ? mask : 0;
                if (Build.VERSION.SDK_INT >= 35 || customNav) {
                    mask |= WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    if (lightNav) {
                        value |= WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    }
                }
                window.getInsetsController().setSystemBarsAppearance(value, mask);
            }
        } catch (Exception ignored) {
        }
    }

    private static int parseColor(String hex, int fallback) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) {
            return fallback;
        }
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Splash image behind the hidden WebView until the first page loads (6 seconds at most). */
    private void showSplash() {
        if (splashAsset.length() == 0) {
            return;
        }
        try {
            InputStream in = getAssets().open(splashAsset);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (bmp == null) {
                return;
            }
            background.setSplash(bmp);
            webView.setVisibility(View.INVISIBLE);
            splashShowing = true;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideSplash();
                }
            }, 6000);
        } catch (Exception ignored) {
        }
    }

    private void hideSplash() {
        if (!splashShowing) {
            return;
        }
        splashShowing = false;
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
        }
        background.setSplash(null);
    }

    /**
     * The window background: the store color, the navigation bar color along the
     * bottom inset (Android 15+, where the app draws behind the bars), and the
     * splash image while it shows, scaled to fill the screen.
     */
    private static class ShellBackground extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int topColor;
        private final int bottomColor;
        private int bottomHeight = 0;
        private Bitmap splash;

        ShellBackground(int topColor, int bottomColor) {
            this.topColor = topColor;
            this.bottomColor = bottomColor;
        }

        void setBottomHeight(int h) {
            bottomHeight = h;
            invalidateSelf();
        }

        void setSplash(Bitmap bmp) {
            splash = bmp;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawColor(topColor);
            if (splash != null) {
                float scale = Math.max(b.width() / (float) splash.getWidth(), b.height() / (float) splash.getHeight());
                float w = splash.getWidth() * scale;
                float h = splash.getHeight() * scale;
                float left = b.left + (b.width() - w) / 2f;
                float top = b.top + (b.height() - h) / 2f;
                canvas.drawBitmap(splash, null, new RectF(left, top, left + w, top + h), paint);
                return;
            }
            if (bottomHeight > 0) {
                paint.setColor(bottomColor);
                canvas.drawRect(b.left, b.bottom - bottomHeight, b.right, b.bottom, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    private static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
    }

    /**
     * Android 13+: back goes to the previous store page while there is one.
     * The callback is only registered then, so the system back-to-home animation
     * still plays on the first page.
     */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || webView == null) {
            return;
        }
        boolean want = webView.canGoBack();
        if (want == backCallbackRegistered) {
            return;
        }
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (backCallback == null) {
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        finish();
                    }
                }
            };
        }
        if (want) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else {
            dispatcher.unregisterOnBackInvokedCallback(backCallback);
        }
        backCallbackRegistered = want;
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
            secondaryColor = cfg.optString("secondaryColor", "");
            accentColor = cfg.optString("accentColor", "");
            splashAsset = "splash.jpg".equals(cfg.optString("splash", "")) ? "splash.jpg" : "";
            whatsappNumber = cfg.optString("whatsappNumber", "");
            whatsappMessage = cfg.optString("whatsappMessage", "");
            configUrl = cfg.optString("configUrl", "");
        } catch (Exception ignored) {
        }
        try {
            String host = Uri.parse(storeUrl).getHost();
            storeHost = host == null ? "" : host;
        } catch (Exception ignored) {
        }
    }

    /** Reads the dashboard's add-ons in the background, keeps them, and applies them. */
    private void fetchRemoteConfig() {
        if (!configUrl.startsWith("https://")) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(configUrl).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("Accept", "application/json");
                    if (c.getResponseCode() != 200) {
                        return;
                    }
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) {
                        out.write(buf, 0, n);
                    }
                    in.close();
                    final String body = out.toString("UTF-8");
                    new JSONObject(body); // only keep valid JSON
                    getSharedPreferences("tatbiqi", MODE_PRIVATE).edit().putString("remoteConfig", body).apply();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            applyRemoteConfig(body);
                            if (webView != null) {
                                String url = webView.getUrl();
                                refreshWhatsAppButton(webView, url);
                                maybeShowOffer(webView, url);
                            }
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * {"whatsapp": {"number", "message"} | null, "offer": {...} | null}.
     * A missing key leaves the value as it was; null turns the add-on off.
     */
    private void applyRemoteConfig(String json) {
        if (json == null) {
            return;
        }
        try {
            JSONObject cfg = new JSONObject(json);
            if (cfg.has("whatsapp")) {
                JSONObject wa = cfg.optJSONObject("whatsapp");
                String number = wa == null ? "" : wa.optString("number", "").replaceAll("\\D", "");
                whatsappNumber = number.length() >= 8 && number.length() <= 15 ? number : "";
                whatsappMessage = wa == null ? "" : wa.optString("message", "");
            }
            if (cfg.has("offer")) {
                offer = cfg.optJSONObject("offer");
            }
            // Colors changed on the dashboard after the build; null keeps the primary color.
            JSONObject design = cfg.optJSONObject("design");
            if (design != null) {
                String sec = design.isNull("secondaryColor") ? "" : design.optString("secondaryColor", "");
                String acc = design.isNull("accentColor") ? "" : design.optString("accentColor", "");
                secondaryColor = sec.matches("#[0-9A-Fa-f]{6}") ? sec : "";
                accentColor = acc.matches("#[0-9A-Fa-f]{6}") ? acc : "";
            }
        } catch (Exception ignored) {
        }
    }

    private boolean onStorePage(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host != null && isStoreHost(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** Shows the offers popup once per launch, on a store page. Text is set with textContent, never as HTML. */
    private void maybeShowOffer(WebView view, String url) {
        if (offerShown || offer == null || url == null || !onStorePage(url)) {
            return;
        }
        offerShown = true;
        JSONObject o = new JSONObject();
        try {
            o.put("title", offer.optString("title", ""));
            o.put("text", offer.optString("text", ""));
            o.put("image", offer.optString("imageUrl", ""));
            o.put("link", offer.optString("linkUrl", ""));
            o.put("button", offer.optString("buttonText", ""));
            o.put("color", accentColor.matches("#[0-9A-Fa-f]{6}") ? accentColor : primaryColor);
        } catch (Exception e) {
            return;
        }
        String js = "(function(o){"
                + "if(document.getElementById('tatbiqi-offer'))return;"
                + "var ov=document.createElement('div');ov.id='tatbiqi-offer';ov.dir='auto';"
                + "ov.style.cssText='position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:2147483647;"
                + "display:flex;align-items:center;justify-content:center;padding:24px;';"
                + "var c=document.createElement('div');"
                + "c.style.cssText='position:relative;background:#fff;color:#111;border-radius:18px;overflow:hidden;"
                + "width:100%;max-width:360px;box-shadow:0 10px 40px rgba(0,0,0,.35);font-family:inherit;text-align:center;';"
                + "var x=document.createElement('div');x.textContent='\u00D7';x.setAttribute('role','button');"
                + "x.setAttribute('aria-label','close');"
                + "x.style.cssText='position:absolute;top:8px;left:8px;width:32px;height:32px;border-radius:50%;"
                + "background:rgba(0,0,0,.45);color:#fff;font-size:22px;line-height:32px;cursor:pointer;z-index:1;';"
                + "function close(){ov.remove();}"
                + "x.onclick=close;ov.onclick=function(e){if(e.target===ov)close();};c.appendChild(x);"
                + "if(/^https:\\/\\//.test(o.image)){var i=document.createElement('img');i.src=o.image;i.alt='';"
                + "i.style.cssText='display:block;width:100%;max-height:50vh;object-fit:cover;';c.appendChild(i);}"
                + "var b=document.createElement('div');b.style.cssText='padding:18px 18px 20px;';"
                + "if(o.title){var t=document.createElement('div');t.textContent=o.title;"
                + "t.style.cssText='font-size:19px;font-weight:700;margin-bottom:6px;';b.appendChild(t);}"
                + "if(o.text){var p=document.createElement('div');p.textContent=o.text;"
                + "p.style.cssText='font-size:15px;line-height:1.6;color:#444;white-space:pre-wrap;';b.appendChild(p);}"
                + "if(/^https:\\/\\//.test(o.link)){var a=document.createElement('div');a.setAttribute('role','button');"
                + "a.textContent=o.button||'\u062A\u0633\u0648\u0651\u0642 \u0627\u0644\u062D\u064A\u0646';"
                + "a.style.cssText='margin-top:14px;padding:12px;border-radius:12px;color:#fff;font-weight:700;"
                + "font-size:16px;cursor:pointer;background:'+o.color+';';"
                + "a.onclick=function(){close();location.href=o.link;};b.appendChild(a);}"
                + "c.appendChild(b);ov.appendChild(c);(document.body||document.documentElement).appendChild(ov);"
                + "})(" + o.toString() + ");";
        view.evaluateJavascript(js, null);
    }

    /** After the dashboard changes the WhatsApp button: drop the old one and add the new one. */
    private void refreshWhatsAppButton(WebView view, String url) {
        view.evaluateJavascript(
                "(function(){var b=document.getElementById('tatbiqi-wa');if(b)b.remove();})();", null);
        if (url != null) {
            injectWhatsAppButton(view, url);
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
            if (host == null || !isStoreHost(host)) {
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
                + "b.style.cssText='position:fixed;bottom:96px;left:16px;width:56px;height:56px;"
                + "border-radius:50%;background:#25D366;display:flex;align-items:center;"
                + "justify-content:center;z-index:2147483646;cursor:pointer;"
                + "box-shadow:0 4px 12px rgba(0,0,0,.3);';"
                + "b.onclick=function(){TatbiqiApp.openWhatsApp();};"
                + "(document.body||document.documentElement).appendChild(b);"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    /** Same site as the store, ignoring "www." and subdomains (e.g. shop.x.com vs x.com). */
    private boolean isStoreHost(String host) {
        String a = stripWww(host.toLowerCase());
        String b = stripWww(storeHost.toLowerCase());
        if (b.length() == 0) {
            return false;
        }
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private static String stripWww(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private class WhatsAppBridge {
        @JavascriptInterface
        public void openWhatsApp() {
            if (whatsappNumber.length() == 0) {
                return;
            }
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
        outState.putBoolean("offerShown", offerShown);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    /** Android 12 and older. */
    @Override
    @SuppressWarnings("deprecation")
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
