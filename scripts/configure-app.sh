#!/usr/bin/env bash
# configure-app.sh
# Replaces placeholders in the Capacitor app with the actual store data
# This runs INSIDE the GitHub Actions runner before capacitor sync

set -euo pipefail

echo "=== Configuring Tatbiqi App ==="

STORE_URL="${STORE_URL:-https://example.salla.sa}"
APP_NAME="${APP_NAME:-متجري}"
PRIMARY_COLOR="${PRIMARY_COLOR:-0F766E}"
LOGO_URL="${LOGO_URL:-}"
WHATSAPP_NUMBER="${WHATSAPP_NUMBER:-}"
ORDER_ID="${ORDER_ID:-TEST-001}"

# Ensure PRIMARY_COLOR has no leading #
PRIMARY_COLOR="${PRIMARY_COLOR#\#}"

echo "Order: $ORDER_ID"
echo "Store: $STORE_URL"
echo "App Name: $APP_NAME"
echo "Color: #$PRIMARY_COLOR"
echo "Logo: ${LOGO_URL:-<none>}"
echo "WhatsApp: ${WHATSAPP_NUMBER:-<none>}"

# ============ 1. Update capacitor.config.json (app name) ============
if [ -f capacitor.config.json ]; then
  echo "→ Updating capacitor.config.json"
  # Use node to safely edit JSON
  node -e "
    const fs = require('fs');
    const config = JSON.parse(fs.readFileSync('capacitor.config.json', 'utf8'));
    config.appName = '${APP_NAME}';
    config.appId = 'com.tatbiqi.app';
    if (config.plugins && config.plugins.SplashScreen) {
      config.plugins.SplashScreen.backgroundColor = '#${PRIMARY_COLOR}';
    }
    fs.writeFileSync('capacitor.config.json', JSON.stringify(config, null, 2));
    console.log('✅ capacitor.config.json updated');
  "
fi

# ============ 2. Update src/index.html ============
if [ -f src/index.html ]; then
  echo "→ Updating src/index.html"
  
  # Create the WebView HTML page
  cat > src/index.html <<HTML_EOF
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
    <meta name="theme-color" content="#${PRIMARY_COLOR}">
    <title>${APP_NAME}</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body {
            width: 100%;
            height: 100%;
            overflow: hidden;
            background: #${PRIMARY_COLOR};
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Tahoma', sans-serif;
        }
        #splash {
            position: fixed;
            inset: 0;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            background: #${PRIMARY_COLOR};
            color: white;
            z-index: 100;
            transition: opacity 0.4s ease;
        }
        #splash.hide { opacity: 0; pointer-events: none; }
        #splash img {
            width: 120px;
            height: 120px;
            object-fit: contain;
            border-radius: 24px;
            margin-bottom: 24px;
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
        }
        #splash .default-logo {
            width: 120px;
            height: 120px;
            background: white;
            border-radius: 24px;
            margin-bottom: 24px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 60px;
            font-weight: bold;
            color: #${PRIMARY_COLOR};
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
        }
        #splash .app-name {
            font-size: 28px;
            font-weight: 700;
            margin-bottom: 8px;
        }
        #splash .loader {
            width: 32px;
            height: 32px;
            border: 3px solid rgba(255,255,255,0.3);
            border-top-color: white;
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
            margin-top: 16px;
        }
        @keyframes spin { to { transform: rotate(360deg); } }

        #webview-frame {
            position: fixed;
            inset: 0;
            width: 100%;
            height: 100%;
            border: none;
            background: white;
        }

        /* Bottom Navigation */
        #bottom-nav {
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            height: 64px;
            background: white;
            display: flex;
            justify-content: space-around;
            align-items: center;
            box-shadow: 0 -2px 12px rgba(0,0,0,0.08);
            z-index: 50;
            padding-bottom: env(safe-area-inset-bottom, 0);
        }
        #bottom-nav button {
            background: none;
            border: none;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 2px;
            color: #999;
            font-size: 10px;
            font-weight: 500;
            padding: 8px 16px;
            cursor: pointer;
            transition: color 0.2s;
        }
        #bottom-nav button.active { color: #${PRIMARY_COLOR}; }
        #bottom-nav button svg { width: 22px; height: 22px; }

        /* WhatsApp Floating Button */
        #whatsapp-btn {
            position: fixed;
            bottom: 80px;
            left: 16px;
            width: 56px;
            height: 56px;
            background: #25D366;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 16px rgba(37, 211, 102, 0.4);
            z-index: 60;
            cursor: pointer;
            border: none;
        }
        #whatsapp-btn svg { width: 28px; height: 28px; fill: white; }

        #webview-container {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 64px;
            overflow: hidden;
        }

        /* Error page */
        #error-page {
            position: fixed;
            inset: 0;
            background: #f8f9fa;
            display: none;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 32px;
            text-align: center;
            z-index: 200;
        }
        #error-page.show { display: flex; }
        #error-page h2 { color: #${PRIMARY_COLOR}; margin-bottom: 12px; font-size: 22px; }
        #error-page p { color: #666; margin-bottom: 24px; line-height: 1.6; }
        #error-page button {
            background: #${PRIMARY_COLOR};
            color: white;
            border: none;
            padding: 12px 32px;
            border-radius: 8px;
            font-size: 16px;
            cursor: pointer;
        }
    </style>
</head>
<body>
    <!-- Splash Screen -->
    <div id="splash">
        LOGO_PLACEHOLDER
        <div class="app-name">${APP_NAME}</div>
        <div class="loader"></div>
    </div>

    <!-- Main App Container -->
    <div id="webview-container">
        <iframe id="webview-frame" src="${STORE_URL}" 
            sandbox="allow-same-origin allow-scripts allow-forms allow-popups allow-popups-to-escape-sandbox allow-top-navigation-by-user-activation">
        </iframe>
    </div>

    <!-- Bottom Navigation -->
    <nav id="bottom-nav">
        <button class="active" data-action="home">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>
            <span>الرئيسية</span>
        </button>
        <button data-action="categories">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 4h7v7H4zm9 0h7v7h-7zm-9 9h7v7H4zm9 0h7v7h-7z"/></svg>
            <span>الأقسام</span>
        </button>
        <button data-action="cart">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 18c-1.1 0-1.99.9-1.99 2S5.9 22 7 22s2-.9 2-2-.9-2-2-2zM1 2v2h2l3.6 7.59-1.35 2.45c-.16.28-.25.61-.25.96 0 1.1.9 2 2 2h12v-2H7.42c-.14 0-.25-.11-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49c.08-.14.12-.31.12-.48 0-.55-.45-1-1-1H5.21l-.94-2H1zm16 16c-1.1 0-1.99.9-1.99 2s.89 2 1.99 2 2-.9 2-2-.9-2-2-2z"/></svg>
            <span>السلة</span>
        </button>
        <button data-action="account">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>
            <span>حسابي</span>
        </button>
    </nav>

    <!-- WhatsApp Button -->
    WHATSAPP_PLACEHOLDER

    <!-- Error Page -->
    <div id="error-page">
        <h2>تعذّر الاتصال</h2>
        <p>تأكد من اتصالك بالإنترنت وحاول مرة أخرى</p>
        <button onclick="location.reload()">إعادة المحاولة</button>
    </div>

    <script>
        // Hide splash after iframe loads (or after 3s timeout)
        const splash = document.getElementById('splash');
        const iframe = document.getElementById('webview-frame');
        let loaded = false;
        
        function hideSplash() {
            if (loaded) return;
            loaded = true;
            splash.classList.add('hide');
            setTimeout(() => splash.style.display = 'none', 400);
        }
        
        iframe.addEventListener('load', hideSplash);
        setTimeout(hideSplash, 3000); // Fallback after 3s

        // Bottom navigation
        const navButtons = document.querySelectorAll('#bottom-nav button');
        navButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                navButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                const action = btn.dataset.action;
                const base = '${STORE_URL}'.replace(/\/$/, '');
                let url = base;
                if (action === 'categories') url = base + '/categories';
                else if (action === 'cart') url = base + '/cart';
                else if (action === 'account') url = base + '/account';
                iframe.src = url;
            });
        });

        // Network error detection
        window.addEventListener('offline', () => {
            document.getElementById('error-page').classList.add('show');
        });
        window.addEventListener('online', () => {
            document.getElementById('error-page').classList.remove('show');
            location.reload();
        });
    </script>
</body>
</html>
HTML_EOF

  # Replace LOGO_PLACEHOLDER with actual logo or default
  if [ -n "${LOGO_URL}" ]; then
    echo "→ Injecting logo URL: $LOGO_URL"
    sed -i.bak "s|LOGO_PLACEHOLDER|<img src=\"${LOGO_URL}\" alt=\"${APP_NAME}\" onerror=\"this.style.display='none';this.nextElementSibling.style.display='flex';\"/><div class=\"default-logo\" style=\"display:none;\">${APP_NAME:0:1}</div>|" src/index.html
  else
    echo "→ Using default logo (first letter)"
    sed -i.bak "s|LOGO_PLACEHOLDER|<div class=\"default-logo\">${APP_NAME:0:1}</div>|" src/index.html
  fi
  rm -f src/index.html.bak

  # Replace WHATSAPP_PLACEHOLDER
  if [ -n "${WHATSAPP_NUMBER}" ]; then
    echo "→ Adding WhatsApp button: $WHATSAPP_NUMBER"
    WHATSAPP_BTN="<button id=\"whatsapp-btn\" onclick=\"window.open('https://wa.me/${WHATSAPP_NUMBER}?text=' + encodeURIComponent('مرحباً، عندي استفسار'), '_blank')\"><svg viewBox='0 0 24 24'><path d='M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51l-.57-.01c-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 01-5.031-1.378l-.361-.214-3.741.982.998-3.648-.235-.374a9.86 9.86 0 01-1.51-5.26c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03 6.988 2.898a9.825 9.825 0 012.893 6.994c-.003 5.45-4.437 9.884-9.885 9.884m8.413-18.297A11.815 11.815 0 0012.05 0C5.495 0 .16 5.335.157 11.892c0 2.096.547 4.142 1.588 5.945L.057 24l6.305-1.654a11.882 11.882 0 005.683 1.448h.005c6.554 0 11.89-5.335 11.893-11.893a11.821 11.821 0 00-3.48-8.413Z'/></svg></button>"
    sed -i.bak "s|WHATSAPP_PLACEHOLDER|${WHATSAPP_BTN}|" src/index.html
  else
    sed -i.bak "s|WHATSAPP_PLACEHOLDER||" src/index.html
  fi
  rm -f src/index.html.bak
fi

# ============ 3. Update Android strings.xml (app name) ============
STRINGS_FILE="android/app/src/main/res/values/strings.xml"
if [ -f "$STRINGS_FILE" ]; then
  echo "→ Updating strings.xml"
  mkdir -p $(dirname "$STRINGS_FILE")
  cat > "$STRINGS_FILE" <<XML_EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${APP_NAME}</string>
    <string name="title_activity_main">${APP_NAME}</string>
    <string name="custom_url_scheme">com.tatbiqi.app</string>
</resources>
XML_EOF
fi

# ============ 4. Update Android colors.xml ============
COLORS_FILE="android/app/src/main/res/values/colors.xml"
if [ -f "$COLORS_FILE" ]; then
  echo "→ Updating colors.xml"
  mkdir -p $(dirname "$COLORS_FILE")
  cat > "$COLORS_FILE" <<XML_EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#${PRIMARY_COLOR}</color>
    <color name="primary_color">#${PRIMARY_COLOR}</color>
</resources>
XML_EOF
fi

# ============ 5. Update AndroidManifest.xml ============
MANIFEST_FILE="android/app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST_FILE" ]; then
  echo "→ Updating AndroidManifest.xml"
  cat > "$MANIFEST_FILE" <<'XML_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/AppTheme"
        android:usesCleartextTraffic="true"
        android:extractNativeLibs="true"
        android:networkSecurityConfig="@xml/network_security_config"
        tools:replace="android:appComponentFactory"
        android:appComponentFactory="androidx.core.app.CoreComponentFactory">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:theme="@style/AppTheme.SplashScreen"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
XML_EOF
fi

# ============ 6. Create network security config (allow cleartext) ============
NETSEC_DIR="android/app/src/main/res/xml"
mkdir -p "$NETSEC_DIR"
cat > "$NETSEC_DIR/network_security_config.xml" <<'XML_EOF'
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
XML_EOF

echo ""
echo "=== ✅ Configuration complete ==="
echo "Order ID: $ORDER_ID"
echo "Store URL: $STORE_URL"
echo "App Name: $APP_NAME"

