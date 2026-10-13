import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor Configuration
 *
 * This config is dynamically updated per-merchant by the build script.
 * The build script replaces:
 * - APP_URL: the merchant's store URL
 * - APP_NAME: the merchant's app name
 * - PRIMARY_COLOR: the merchant's brand color
 * - LOGO_URL: the merchant's logo URL
 */

const config: CapacitorConfig = {
  appId: 'sa.tatbiqi.app',
  appName: 'Tatbiqi App',
  webDir: 'src',
  android: {
    allowMixedContent: true,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 2000,
      launchAutoHide: true,
      backgroundColor: '#0F766E',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
      android: {
        backgroundColor: '#0F766E',
      },
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#0F766E',
    },
  },
};

export default config;
