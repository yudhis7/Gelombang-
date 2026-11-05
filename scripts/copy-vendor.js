// Copies hls.js into the renderer so the packaged app has no runtime node_modules.
const fs = require('fs');
const path = require('path');
const src = path.join(__dirname, '..', 'node_modules', 'hls.js', 'dist', 'hls.min.js');
const destDir = path.join(__dirname, '..', 'src', 'renderer', 'vendor');
if (!fs.existsSync(src)) { console.log('hls.js not installed yet; skipping vendor copy'); process.exit(0); }
fs.mkdirSync(destDir, { recursive: true });
fs.copyFileSync(src, path.join(destDir, 'hls.min.js'));
console.log('Copied hls.min.js to src/renderer/vendor');
