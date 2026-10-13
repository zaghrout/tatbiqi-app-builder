/**
 * Tatbiqi App - WebView Controller
 *
 * Handles:
 * - Splash screen auto-hide
 * - Bottom navigation
 * - WebView communication
 * - Push notifications (FCM)
 * - Deep links
 */

// ============================================
// SPLASH SCREEN
// ============================================

window.addEventListener('load', () => {
  // Hide splash after 2 seconds or when iframe loads
  const splash = document.getElementById('splash');
  const iframe = document.getElementById('webview');

  let splashHidden = false;

  const hideSplash = () => {
    if (splashHidden) return;
    splashHidden = true;
    splash.classList.add('hidden');
    setTimeout(() => {
      splash.style.display = 'none';
    }, 500);
  };

  // Hide after 2s regardless
  setTimeout(hideSplash, 2000);

  // Hide when iframe loads
  if (iframe) {
    iframe.addEventListener('load', hideSplash);
  }
});

// ============================================
// BOTTOM NAVIGATION
// ============================================

const navItems = document.querySelectorAll('.nav-item');
const iframe = document.getElementById('webview');

const baseStoreUrl = '__APP_URL__';

navItems.forEach(item => {
  item.addEventListener('click', () => {
    // Update active state
    navItems.forEach(n => n.classList.remove('active'));
    item.classList.add('active');

    // Navigate iframe
    const action = item.dataset.action;
    if (!action || !iframe) return;

    let targetUrl = baseStoreUrl;

    switch (action) {
      case 'home':
        targetUrl = baseStoreUrl;
        break;
      case 'cart':
        targetUrl = baseStoreUrl + '/cart';
        break;
      case 'search':
        targetUrl = baseStoreUrl + '/search';
        break;
      case 'account':
        targetUrl = baseStoreUrl + '/account';
        break;
    }

    iframe.src = targetUrl;
  });
});

// ============================================
// BACK BUTTON (Android)
// ============================================

let canExit = false;

document.addEventListener('backbutton', (e) => {
  e.preventDefault();

  if (!canExit) {
    canExit = true;
    showToast('اضغط مرة أخرى للخروج');

    setTimeout(() => {
      canExit = false;
    }, 2000);
  } else {
    navigator.app.exitApp();
  }
});

// ============================================
// PUSH NOTIFICATIONS (FCM)
// ============================================

// Check if Capacitor is available
if (typeof window.Capacitor !== 'undefined') {
  const { PushNotifications } = window.Capacitor.Plugins;

  if (PushNotifications) {
    // Request permission
    PushNotifications.requestPermissions().then(result => {
      if (result.receive === 'granted') {
        PushNotifications.register();
      }
    });

    // Listen for registration
    PushNotifications.addListener('registration', (token) => {
      console.log('FCM Token:', token.value);
      // Send token to Tatbiqi server
      fetch('__API_URL__/api/notifications/register-token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          token: token.value,
          platform: 'android',
        }),
      }).catch(err => console.error('Token registration failed:', err));
    });

    // Listen for notifications
    PushNotifications.addListener('pushNotificationReceived', (notification) => {
      console.log('Notification received:', notification);
    });

    // Listen for notification clicks
    PushNotifications.addListener('pushNotificationActionPerformed', (action) => {
      console.log('Notification action:', action);
      const data = action.notification.data;
      if (data.deepLink && iframe) {
        iframe.src = baseStoreUrl + data.deepLink;
      }
    });
  }
}

// ============================================
// UTILITY
// ============================================

function showToast(message) {
  const toast = document.createElement('div');
  toast.style.cssText = `
    position: fixed;
    bottom: 80px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(0, 0, 0, 0.8);
    color: white;
    padding: 8px 20px;
    border-radius: 20px;
    font-size: 14px;
    z-index: 1000;
    animation: fadeIn 0.3s;
  `;
  toast.textContent = message;
  document.body.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transition = 'opacity 0.3s';
    setTimeout(() => toast.remove(), 300);
  }, 2000);
}
