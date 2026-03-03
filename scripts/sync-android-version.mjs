import { readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');

const pkg = JSON.parse(readFileSync(resolve(root, 'desktop/package.json'), 'utf8'));
const version = pkg.version;

const [major, minor, patch] = version.split('.').map(Number);
const versionCode = major * 10000 + minor * 100 + patch;

const gradlePath = resolve(root, 'android/app/build.gradle.kts');
let gradle = readFileSync(gradlePath, 'utf8');

gradle = gradle.replace(/versionCode\s*=\s*\d+/, `versionCode = ${versionCode}`);
gradle = gradle.replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${version}"`);

writeFileSync(gradlePath, gradle);
console.log(`Synced version ${version} (versionCode: ${versionCode}) → android/app/build.gradle.kts`);
