import { readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');

const pkg = JSON.parse(readFileSync(resolve(root, 'desktop/package.json'), 'utf8'));
const version = pkg.version;

const [major, minor, patch] = version.split('.').map(Number);
const versionCode = major * 10000 + minor * 100 + patch;

// Sync → android/app/build.gradle.kts
const gradlePath = resolve(root, 'android/app/build.gradle.kts');
let gradle = readFileSync(gradlePath, 'utf8');
gradle = gradle.replace(/versionCode\s*=\s*\d+/, `versionCode = ${versionCode}`);
gradle = gradle.replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${version}"`);
writeFileSync(gradlePath, gradle);
console.log(`Synced version ${version} (versionCode: ${versionCode}) → android/app/build.gradle.kts`);

// Sync → desktop/src-tauri/Cargo.toml
// Tauri v2 reads the app version exclusively from Cargo.toml, so this must stay in sync.
const cargoPath = resolve(root, 'desktop/src-tauri/Cargo.toml');
let cargo = readFileSync(cargoPath, 'utf8');
cargo = cargo.replace(/^version\s*=\s*"[^"]+"/m, `version = "${version}"`);
writeFileSync(cargoPath, cargo);
console.log(`Synced version ${version} → desktop/src-tauri/Cargo.toml`);
