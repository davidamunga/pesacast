# pesacast

## 0.9.0

### Minor Changes

- 20d4c30: Add an optional webhook on the Android app: send each M-PESA transaction from the phone straight to your HTTPS endpoint, without a desktop. Deliveries are queued on the phone and retried, carry an idempotency key, and can be signed with an HMAC secret. Cleartext network traffic is now disallowed in the app.
- 5e50952: Add a provisioning screen so the webhook can be configured from a computer with `adb` instead of typing on the phone. It shows the destination host and needs one tap to confirm, accepts only https addresses, and switches the webhook on only after a successful test. Releases now also publish a SHA-256 checksum next to the Android APK.
- 49b8f57: Remove the unencrypted WiFi protocol. Phone and desktop must both be updated: older versions can no longer connect over WiFi (Bluetooth is unaffected). The "Allow unencrypted connections" setting and the desktop's "Connect without encryption" fallback are gone.
- 6bdbcfc: Add a setup checklist to the Android app (Connect tab, then Setup checklist). It shows what a phone still needs before it reliably forwards payments (SMS permission and Android's restricted-settings step, battery optimisation, the phone maker's auto-start menu, a destination), opens the right system screen for each, and runs an end-to-end test through every enabled destination.

### Patch Changes

- a9334f6: Android fixes: phones without Bluetooth can now run the app (webhook and WiFi), copying the WiFi token hides it from the clipboard preview, the boot receiver is no longer exported, and the app now tells you when Android reset its secure storage and a saved token or webhook secret has to be entered again. The WiFi token shown in the app now updates the first time WiFi is enabled.
- c6f9a25: Harden the Android app: disable cloud backup and device transfer, stop writing SMS text to the log, store the WiFi pairing token encrypted (existing tokens are migrated), remove unused permissions, and make the SMS and webhook features keep working when Bluetooth permission is denied. After a denial the app now explains what is needed and links to Settings.
- 6f18561: Webhook payload additions for integrators: every push now carries `schema_version`, `source` and a per-install `device_id` (also as an `X-PesaCast-Device-Id` header), and the desktop push uses the same envelope and idempotency key as the phone so a receiver can de-duplicate across both. The format is published as a JSON Schema and an OpenAPI description, with a reference receiver that stores events in a database.

## 0.8.0

### Minor Changes

- 8d1f142: Encrypt the WiFi transport. The desktop and phone now agree on session keys with an ephemeral key exchange authenticated by the pairing token, and every transaction is sent as an encrypted record. The token is no longer sent over the network. The phone refuses older, unencrypted desktop versions by default: update the desktop app, or turn on "Allow unencrypted connections" in the Android app as a stopgap. The desktop shows when a connection is not encrypted and asks before falling back.

## 0.7.1

### Patch Changes

- b9cba89: Fix BLE transactions being corrupted when a name contains accented characters or emoji that fall on a chunk boundary, and when a chunk happens to start with digits followed by a colon (for example inside a timestamp).
- 2f794d1: Fix Paybill payments to a numeric account number (for example KPLC prepaid) being shown as "sent" instead of "paid".
- ce67fd8: Cap the Android BLE pending-transaction queue and make it thread-safe, and stop `MirrorService` from stacking duplicate notification collectors.

## 0.7.0

### Minor Changes

- 4aed464: Add "Find my phone" to the WiFi card: the desktop discovers the phone over mDNS and fills in its IP and port. The pairing token is still entered manually.

## 0.6.0

### Minor Changes

- e13e3a3: Add WiFi transport: the Android app can serve transactions over a token-authenticated WebSocket on the local network, and the desktop app can connect to it as an alternative to Bluetooth LE. Includes a reworked Android UI with Transactions and Connect tabs.

## 0.5.1

### Patch Changes

- fix: ble transport reliability

## 0.5.0

### Minor Changes

- feat: add websocket broadcast option

## 0.4.0

### Minor Changes

- 79d018d: feat: add auto export to api option
- 79d018d: add fuliza attribution
- 79d018d: feat: add transaction cost column and exports
- 79d018d: feat: add db(sqlite) persistence option

### Patch Changes

- 79d018d: fix: webhook push tauri permissions

## 0.3.0

### Minor Changes

- 146064e: feat: add export transactions
- 146064e: feat: add webhook push

## 0.2.6

### Patch Changes

- 2a8340a: fix: release updater json

## 0.2.5

### Patch Changes

- 4688dbb: fix: generate updater json

## 0.2.4

### Patch Changes

- 742bd39: fix: update banner

## 0.2.3

### Patch Changes

- 1c8afbc: fix: release workflow

## 0.2.2

### Patch Changes

- fix: app versioning

## 0.2.1

### Patch Changes

- defb3ad: fix: update banner state

## 0.2.0

### Minor Changes

- 2115b9f: feat: add multi device connect broadcast

## 0.1.2

### Patch Changes

- 1c61518: feat: initial release

## 0.1.1

### Patch Changes

- 5d5fc51: feat: initial release
