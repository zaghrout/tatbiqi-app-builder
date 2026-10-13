# Tatbiqi App Builder

تطبيق موبايل (WebView wrapper) بيتولّد تلقائياً لكل متجر.

## كيف يعمل؟

```
1. التاجر يطلب تطبيق في tatbiqii.com
   ↓
2. النظام يبعت طلب لـ GitHub Actions
   ↓
3. GitHub Actions يبني APK حقيقي
   ↓
4. APK بيتخزّن كـ artifact
   ↓
5. التاجر ينزّله من الـ Dashboard
```

## بنية المشروع

```
├── .github/workflows/
│   └── build-apk.yml          # GitHub Actions pipeline
├── src/
│   ├── index.html              # HTML template (مع placeholders)
│   ├── styles.css              # CSS template
│   └── app.js                  # App logic (WebView + FCM)
├── scripts/
│   └── build-app.js            # Build script (يستبدل الـ placeholders)
├── capacitor.config.ts         # Capacitor config
└── package.json
```

## تخصيص التطبيق

التطبيق بيتخصص عن طريق استبدال الـ placeholders:

| Placeholder | الوصف |
|---|---|
| `__APP_NAME__` | اسم التطبيق |
| `__APP_URL__` | رابط المتجر |
| `__PRIMARY_COLOR__` | اللون الأساسي |
| `__LOGO_HTML__` | الشعار |
| `__WHATSAPP_URL__` | رابط واتساب |
| `__API_URL__` | رابط الـ API |

## بناء يدوي

```bash
# تخصيص التطبيق
node scripts/build-app.js \
  --url=https://store.salla.sa \
  --name="متجر الأناقة" \
  --color=#0F766E \
  --logo=https://... \
  --whatsapp=+9665XXXXXXXX

# بناء APK
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
```

## النتيجة

```
android/app/build/outputs/apk/debug/app-debug.apk
```
