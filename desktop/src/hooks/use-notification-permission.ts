import {
  isPermissionGranted,
  requestPermission,
} from "@tauri-apps/plugin-notification";

export async function ensureNotificationPermission() {
  let granted = await isPermissionGranted();
  if (!granted) {
    const perm = await requestPermission();
    granted = perm === "granted";
  }
  if (!granted) console.warn("Notification permission not granted");
}
