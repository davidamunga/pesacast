# pesacast

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
