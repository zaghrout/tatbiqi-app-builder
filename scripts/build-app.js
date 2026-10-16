const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2).reduce((acc, arg) => {
  const [key, value] = arg.replace('--', '').split('=');
  acc[key] = value || '';
  return acc;
}, {});

const config = {
  url: args.url || 'https://salla.sa',
  name: args.name || 'Tatbiqi App',
  color: args.color || '#0F766E',
  logo: args.logo || '',
  whatsapp: args.whatsapp || '',
  api: args.api || 'https://app.tatbiqii.com',
};

console.log('Building app with config:', JSON.stringify(config, null, 2));

const srcDir = path.join(__dirname, '..', 'src');
let indexTemplate = fs.readFileSync(path.join(srcDir, 'index.html'), 'utf8');
let cssTemplate = fs.readFileSync(path.join(srcDir, 'styles.css'), 'utf8');
let jsTemplate = fs.readFileSync(path.join(srcDir, 'app.js'), 'utf8');

let html = indexTemplate
  .replace(/__APP_NAME__/g, config.name)
  .replace(/__APP_URL__/g, config.url)
  .replace(/__PRIMARY_COLOR__/g, config.color)
  .replace(/__API_URL__/g, config.api)
  .replace(/__WHATSAPP_URL__/g, `https://wa.me/${config.whatsapp.replace(/[^0-9]/g, '')}`)
  .replace(/__WHATSAPP_DISPLAY__/g, config.whatsapp ? 'flex' : 'none');

if (config.logo) {
  html = html.replace(/__LOGO_HTML__/g, `<img src="${config.logo}" alt="logo">`);
} else {
  const firstLetter = config.name.charAt(0).toUpperCase();
  html = html.replace(/__LOGO_HTML__/g, `<div class="default-logo">${firstLetter}</div>`);
}

let css = cssTemplate.replace(/__PRIMARY_COLOR__/g, config.color);
let js = jsTemplate
  .replace(/__APP_URL__/g, config.url)
  .replace(/__API_URL__/g, config.api);

fs.writeFileSync(path.join(srcDir, 'index.html'), html);
fs.writeFileSync(path.join(srcDir, 'styles.css'), css);
fs.writeFileSync(path.join(srcDir, 'app.js'), js);

// Update capacitor.config.json
const configPath = path.join(__dirname, '..', 'capacitor.config.json');
let configContent = JSON.parse(fs.readFileSync(configPath, 'utf8'));
configContent.appName = config.name;
configContent.plugins.SplashScreen.backgroundColor = config.color;
configContent.plugins.SplashScreen.android.backgroundColor = config.color;
configContent.plugins.StatusBar.backgroundColor = config.color;
fs.writeFileSync(configPath, JSON.stringify(configContent, null, 2));

console.log('App customized successfully!');
