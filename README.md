# Tatbiqi App Builder

Builds a signed Android APK for a Salla store. No Gradle, no Capacitor: the APK is a
single WebView built with `javac` + `d8` + `aapt2` + `apksigner` and comes out around 12 KB
(a little more with a logo icon). A build takes about a minute, most of it runner setup.

## How it works

1. The dashboard (`POST /api/build/trigger` in `zaghrout/tatbiqi`) sends a
   `repository_dispatch` event of type `build-app` with the store's details.
2. `.github/workflows/build-apk.yml` runs `builder/build_apk.py`.
3. The APK is uploaded to R2 at `apks/<order number>/<version code>.apk`.
4. `builder/callback.py` reports `building`, `ready` or `failed` to
   `/api/build/callback`, signed with HMAC-SHA256.
5. The dashboard shows a download button that hands out a short-lived R2 link.

Store values (URL, color, WhatsApp) are written to `assets/tatbiqi.json` and read by
`builder/template/MainActivity.java` at runtime, so no user input is ever compiled as code.
Each store gets its own package name (`sa.tatbiqi.<slug>`), so several store apps can sit
on one phone, and each rebuild gets a higher version code so it installs as an update.

## Setup (one time)

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 tatbiqi-release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password (same as keystore password if unsure) |
| `R2_ACCOUNT_ID` | Cloudflare account ID |
| `R2_ACCESS_KEY_ID` | R2 API token access key |
| `R2_SECRET_ACCESS_KEY` | R2 API token secret |
| `R2_BUCKET` | `tatbiqi` |
| `BUILD_CALLBACK_SECRET` | random string, same value as on Vercel |

Optional variable `TATBIQI_CALLBACK_URL` (default `https://app.tatbiqii.com/api/build/callback`).

Create the release keystore once and keep a backup somewhere safe. If it is lost, apps
already installed can never be updated:

```bash
keytool -genkeypair -v -keystore tatbiqi-release.keystore -alias tatbiqi \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Manual test build

Actions → Build Android APK → Run workflow. Without a build id the callback is ignored by
the dashboard; download the APK from the run's artifacts.

## Local build

Needs JDK 17, an Android SDK with `platforms;android-34` and `build-tools;34.0.0`, and Pillow.

```bash
export ANDROID_HOME=~/Android/Sdk
python3 builder/build_apk.py --store-url https://mystore.salla.sa --app-name "متجري" \
  --package-name sa.tatbiqi.mystore --primary-color 0F766E --whatsapp 966555555555 \
  --output out/app.apk --throwaway-key
```

`--throwaway-key` signs with a temporary key for testing. Set `KEYSTORE_PATH`,
`KEYSTORE_PASSWORD` and `KEY_ALIAS` to sign with the real key.

## Installing on Realme / Xiaomi

Turn off Play Protect scanning and allow "Install unknown apps" for the browser or file
manager used to open the APK.

## iOS

`builder/build_ios.py` writes an Xcode project for one store (shared Swift sources in
`builder/ios/Sources`, plus the store's `tatbiqi.json`, app icon and launch color), and
`.github/workflows/build-ios.yml` builds it with XcodeGen + xcodebuild on a macOS runner.
The app is a full-screen `WKWebView` with the same rules as the Android shell (store and
payment pages stay in the app, WhatsApp and other links open outside, WhatsApp button
injected on store pages), plus pull to refresh, swipe back and an offline screen.

Today the workflow builds unsigned for iPhone and for the simulator, then launches the app
in a simulator as a smoke test. Signing and App Store upload come with the first client's
Apple Developer account (Team ID + App Store Connect API key). The bundle id is the Android
package name with `_` replaced by `-`.
