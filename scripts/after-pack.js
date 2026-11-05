// Ad-hoc code signature for macOS builds, so the app launches on Apple Silicon.
// On a Mac this uses Apple's built-in `codesign`; elsewhere it uses `rcodesign` if installed.
const { execFileSync } = require('child_process');
const path = require('path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const appPath = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  try {
    if (process.platform === 'darwin') {
      execFileSync('codesign', ['--force', '--deep', '--sign', '-', appPath], { stdio: 'inherit' });
    } else {
      execFileSync(process.env.RCODESIGN || 'rcodesign', ['sign', appPath], { stdio: 'inherit' });
    }
    console.log(`  • ad-hoc signed ${path.basename(appPath)}`);
  } catch (e) {
    console.warn(`  • could not ad-hoc sign the macOS app (${e.message}). It may need "codesign --force --deep --sign -" on the Mac.`);
  }
};
