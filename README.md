# Tatbiqi App Builder

GitHub Actions workflow that builds Android APK for Tatbiqi SaaS platform stores.

## How It Works

1. Main app (tatbiqi.com) triggers `repository_dispatch` with store data
2. Workflow runs `scripts/configure-app.sh` to inject store name, URL, colors, logo
3. Capacitor syncs the web assets to native Android
4. Gradle builds the debug APK (with auto-generated debug keystore)
5. APK uploaded as artifact (30 days retention)

## Trigger Build

### From Tatbiqi Dashboard
POST to `/api/build/trigger` from the app-management page.

### Manual (for testing)
Run workflow manually from GitHub Actions tab with inputs:
- `store_url`: e.g. `https://example.salla.sa`
- `app_name`: e.g. `متجري`
- `primary_color`: e.g. `0F766E` (without #)
- `logo_url`: optional
- `whatsapp_number`: optional
- `order_id`: e.g. `TEST-001`

## Output
- APK artifact: `tatbiqi-apk-{order_id}`
- Available in GitHub Actions artifacts section
- Retained for 30 days
