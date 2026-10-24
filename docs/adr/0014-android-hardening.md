# ADR-0014: Android hardening: no backup, minimal permissions, secrets encrypted at rest

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

A security review of the Android app (manifest, receivers, service, permission flow, logging, storage) found no exported-component or intent-redirection problems, but several issues around data at rest and permissions:

- `allowBackup="true"` let cloud backup and `adb backup` copy the WiFi pairing token and the webhook queue (unsent financial transactions), and restore them on another device.
- The first 60 characters of every M-PESA SMS, and the first 200 of an unparsed one, were written to logcat, contradicting the rule in `AGENTS.md`.
- Permissions were requested all-or-nothing: denying Bluetooth meant the foreground service never started, which also stopped webhook and WiFi delivery. `BLUETOOTH_SCAN`, `READ_SMS` and `ACCESS_WIFI_STATE` were declared though nothing used them.
- The WiFi pairing token was stored in plain `SharedPreferences`, while the webhook credentials (ADR-0013) were encrypted.
- A foreground service of type `connectedDevice` needs at least one qualifying permission on Android 14+; the app relied on a runtime Bluetooth grant for that.

## Decision

1. **No backup and no device transfer.** `allowBackup="false"`, plus `dataExtractionRules` (Android 12+) and `fullBackupContent` (11 and lower) that exclude every domain. Nothing in the app is worth restoring: the pairing token must be regenerated, and queued transactions should not travel.
2. **No SMS text, tokens, secrets or payloads in logs**, only sizes. A unit test scans the Kotlin sources and fails the build on a log call that interpolates such values.
3. **Permissions per feature.** SMS (`RECEIVE_SMS`) is the only permission required to run the service. Bluetooth (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, Android 12+) is requested for the desktop BLE link but its denial only turns BLE off. Notifications are optional. After a denial the app shows why, and sends the user to Settings when Android will no longer prompt. The BLE transport only starts when its permissions are granted.
4. **Declare only what is used.** Remove `READ_SMS`, `BLUETOOTH_SCAN`, `ACCESS_WIFI_STATE`. Add `CHANGE_WIFI_MULTICAST_STATE`, which mDNS registration uses and which also satisfies the `connectedDevice` foreground service prerequisite without a Bluetooth grant. The unused Classic Bluetooth client (`bluetooth/`) is deleted. A manifest test pins these properties.
5. **Secrets at rest use `KeystoreSecretCipher`.** The WiFi token is now stored encrypted. An older plaintext value is migrated on first read and the plaintext key removed. If the Keystore key is invalidated the token reads as missing and a new one is generated; it is never recovered from a stale plaintext copy.

## Alternatives considered

- **Keep backup on but exclude specific files.** Easier to get wrong as files are added; the data is not worth restoring anyway.
- **Require all permissions up front.** Simple, but makes a webhook-only phone depend on Bluetooth.
- **Encrypt the webhook queue file as well.** Reduces exposure on a rooted device or stolen unlocked phone, at the cost of Keystore calls per write. Left as a follow-up; backup exclusion removes the main exposure and the queue is bounded to 500 items / 7 days.
- **Rely on code review for logging rules.** It already failed once.

## Consequences

- Users who reinstall or move phones must pair again and re-enter webhook settings. That is intentional.
- A user who denies Bluetooth permanently now sees a clear message; they can still use WiFi and webhook.
- Existing installs keep working: the WiFi token is migrated on first launch of the new version.
- Follow-ups not done here: `BootReceiver` is `exported="true"` though the system can deliver boot broadcasts to a non-exported receiver (verify on a device); `EXTRA_IS_SENSITIVE` on the clipboard when copying the token; `uses-feature android.hardware.bluetooth` is still `required="true"`, which excludes phones without Bluetooth that could use the webhook alone.

## Update: follow-ups completed

The follow-ups listed above were done afterwards, without changing the decisions:

- `BootReceiver` is `exported="false"`; the system still delivers boot broadcasts to it. A manifest test now allows only the launcher to be reachable without a permission.
- Copying the WiFi token sets the clipboard "sensitive" flag so it is hidden from previews.
- `uses-feature android.hardware.bluetooth` is optional. `BleTransport` tolerates a missing Bluetooth service, and the permission prompt never asks a Bluetooth-less phone for Bluetooth permissions.
- A saved webhook token, signing secret or WiFi token that can no longer be decrypted (Android reset the Keystore key) is detected. The webhook is held off instead of sending without credentials, and the user is told to re-enter them; a replaced WiFi token produces a one-time notice to pair again.
- The WiFi token field now refreshes when the token is generated or replaced; before, a first-time enable could show an empty token until the app restarted.
