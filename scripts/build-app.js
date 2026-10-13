/**
 * Build Script
 *
 * This script takes merchant configuration and generates a customized
 * version of the app (replaces placeholders in index.html and styles.css)
 *
 * Usage: node scripts/build-app.js --url=https://store.salla.sa --name="متجر الأناقة" --color=#0F766E --logo=https://... --whatsapp=+9665XXXXXXXX --api=https://app.tatbiqii.com
 *
 * Output: customized files in src/ directory
 */

const fs = require('fs');
const path = require('path');

// Parse command line arguments
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

console.log('🔧 Building app with config:', JSON.stringify(config, null, 2));

// Read template files
const srcDir = path.join(__dirname, '..', 'src');
const indexTemplate = fs.readFileSync(path.join(srcDir, 'index.html'), 'utf8');
const cssTemplate = fs.readFileSync(path.join(srcDir, 'styles.css'), 'utf8');
const jsTemplate = fs.readFileSync(path.join(srcDir, 'app.js'), 'utf8');

// Replace placeholders in HTML
let html = indexTemplate
  .replace(/__APP_NAME__/g, config.name)
  .replace(/__APP_URL__/g, config.url)
  .replace(/__PRIMARY_COLOR__/g, config.color)
  .replace(/__API_URL__/g, config.api)
  .replace(/__WHATSAPP_URL__/g, `https://wa.me/${config.whatsapp.replace(/[^0-9]/g, '')}`)
  .replace(/__WHATSAPP_DISPLAY__/g, config.whatsapp ? 'flex' : 'none');

// Replace logo
if (config.logo) {
  html = html.replace(/__LOGO_HTML__/g, `<img src="${config.logo}" alt="logo">`);
} else {
  const firstLetter = config.name.charAt(0).toUpperCase();
  html = html.replace(/__LOGO_HTML__/g, `<div class="default-logo">${firstLetter}</div>`);
}

// Replace placeholders in CSS
let css = cssTemplate.replace(/__PRIMARY_COLOR__/g, config.color);

// Replace placeholders in JS
let js = jsTemplate
  .replace(/__APP_URL__/g, config.url)
  .replace(/__API_URL__/g, config.api);

// Write customized files
fs.writeFileSync(path.join(srcDir, 'index.html'), html);
fs.writeFileSync(path.join(srcDir, 'styles.css'), css);
fs.writeFileSync(path.join(srcDir, 'app.js'), js);

// Update capacitor.config.ts
const configPath = path.join(__dirname, '..', 'capacitor.config.ts');
let configContent = fs.readFileSync(configPath, 'utf8');
configContent = configContent
  .replace(/appName: '.*'/, `appName: '${config.name}'`)
  .replace(/'#0F766E'/g, `'${config.color}'`);
fs.writeFileSync(configPath, configContent);

console.log('✅ App customized successfully!');
console.log(`   Name: ${config.name}`);
console.log(`   URL: ${config.url}`);
console.log(`   Color: ${config.color}`);
console.log(`   Logo: ${config.logo || 'default'}`);
console.log(`   WhatsApp: ${config.whatsapp || 'disabled'}`);
