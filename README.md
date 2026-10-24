<p align="center">
  <img src="desktop/public/logo.png" width="96" alt="PesaCast" />
</p>

<h1 align="center">PesaCast</h1>

<p align="center">
  Real-time M-PESA transaction mirroring from your Android phone to your desktop.<br/>
  PesaCast intercepts incoming M-PESA SMS messages on Android, parses them into structured transaction data, and streams them live to a companion desktop app — over Bluetooth LE or your local WiFi.
</p>

---

## Demo




https://github.com/user-attachments/assets/e52ceab4-d7dd-41d4-b63a-5364eeb25541



---

## How It Works

```mermaid
sequenceDiagram
    participant MPESA as Safaricom / M-PESA
    participant Android as Android App
    participant BLE as Bluetooth LE
    participant Desktop as Desktop App (Tauri + React)

    MPESA->>Android: SMS — "You have received KES 500..."
    Android->>Android: SmsReceiver intercepts broadcast
    Android->>Android: MpesaParser → MpesaTransaction
    Android->>Android: MirrorService (foreground) picks up transaction
    Android->>BLE: GATT characteristic notify (JSON payload)
    BLE->>Desktop: BLE event received
    Desktop->>Desktop: Decode & store transaction
    Desktop->>Desktop: Render live feed + desktop notification
```

---

## Project Structure

```
pesacast/
├── android/        # Android app — SMS interception & broadcasting
└── desktop/        # Desktop app — Tauri (Rust) + React frontend
```

---

## Tech Stack

### Android (`android/`)

| Concern | Technology |
|---|---|
| Language | Kotlin |
| Min SDK | 26 (Android 8.0 Oreo) |
| Architecture | MVVM (ViewModel + ViewBinding) |
| Async | Kotlin Coroutines |
| Bluetooth | Android BLE APIs (GATT server) |
| SMS interception | `BroadcastReceiver` + `MpesaParser` |
| Background | Foreground service + `BootReceiver` (auto-start on boot) |
| Serialization | Gson |

### Desktop (`desktop/`)

| Concern | Technology |
|---|---|
| Framework | Tauri v2 (Rust backend + web frontend) |
| Frontend | React 19 + TypeScript |
| Build tool | Vite 7 |
| Styling | Tailwind CSS v4 |
| UI components | shadcn/ui + Radix UI |
| Icons | Lucide React |
| BLE (desktop) | `@mnlphlp/plugin-blec` + vendored `btleplug` |
| WebSocket server | `tokio-tungstenite` |
| Desktop notifications | `@tauri-apps/plugin-notification` |
| Package manager | pnpm |

---

## Prerequisites

### Android

- Android Studio (Hedgehog or later)
- Android device or emulator running API 26+
- A Safaricom SIM registered for M-PESA

### Desktop

- [Node.js](https://nodejs.org) 20+
- [pnpm](https://pnpm.io) — `npm install -g pnpm`
- [Rust](https://rustup.rs) (stable toolchain)
- Tauri prerequisites for your OS — see [Tauri v2 prerequisites](https://v2.tauri.app/start/prerequisites/)

---

## Getting Started

### 1. Clone the repo

```bash
git clone https://github.com/davidamunga/pesacast.git
cd pesacast
```

### 2. Run the Android app

Open the `android/` folder in Android Studio, connect your device, and run the `app` configuration.

> The app requires the following permissions (granted at runtime on first launch):
> - `RECEIVE_SMS` — to intercept M-PESA messages
> - `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` — for BLE transport
> - `POST_NOTIFICATIONS` — for foreground service notification

### 3. Run the desktop app

```bash
cd desktop
pnpm install
pnpm tauri dev
```

To build a production binary:

```bash
pnpm tauri build
```

---

## Connecting Android to Desktop

PesaCast supports two transports, which can run at the same time. Transactions that arrive over both are de-duplicated on the desktop.

### Bluetooth LE

1. Enable Bluetooth on both devices.
2. Open the desktop app — it will begin scanning automatically.
3. Open the Android app and start the mirror service. The devices will pair and connect.

### WiFi

1. Put both devices on the same network.
2. In the Android app, open the **Connect** tab and turn on **Enable WiFi**.
3. Tap **Share Pairing Info** to get the phone's IP, port and token.
4. In the desktop app, open **Connect → WiFi** and press **Find my phone** (or enter the IP by hand), paste the token, and press **Connect**.

The connection is a token-authenticated WebSocket (default port `7877`) and is only accepted from private-network addresses. Traffic is end-to-end encrypted with a key exchange authenticated by the pairing token (see [ADR-0011](docs/adr/0011-encrypt-wifi-transport.md)); the token itself is never sent. Older versions of the app (before 0.8) used an unencrypted protocol that is no longer supported, so update the phone and desktop apps together. Regenerate the token from the Android app to revoke access.

---

## Setting up a phone

On the phone that receives the M-PESA messages, open **Connect, then Setup checklist**. It lists what is still missing, in the order to do it, and each item has a button that opens the right system screen:

- **SMS permission**, including the "Allow restricted settings" step Android 13+ asks for when an app was installed outside a store;
- **Battery**: exclude PesaCast from battery optimisation so payments are not delayed or lost;
- **Your phone maker's auto-start menu** (Tecno, Infinix, itel, Xiaomi, Oppo, realme, vivo, Samsung, Huawei): it cannot be read by the app, so you confirm it yourself;
- **A destination**: the webhook, WiFi or Bluetooth to the desktop app;
- **An end-to-end test** that sends a clearly labelled test to everything that is on and shows each result. A connected desktop shows a fake test payment; the webhook receives a `"test": true` request with no transaction.

Menu names differ between phones and software versions, so treat the hints as guidance. Design notes: [ADR-0015](docs/adr/0015-setup-checklist-and-provisioning.md).

## Webhook from the phone

The Android app can send each transaction to your own server, without a desktop running. Open the **Connect** tab, fill in the **Webhook** card (HTTPS URL, optional bearer token, optional signing secret), press **Save & send test**, then turn the switch on. Transactions are queued on the phone and retried until your server accepts them, so use the `Idempotency-Key` header to avoid duplicates. Details and receiver examples: [docs/webhooks.md](docs/webhooks.md); decision: [ADR-0013](docs/adr/0013-phone-webhook-delivery.md).

Transaction data (amounts, names, references, balances) leaves the phone when this is on, so only point it at a server you control. An SMS is not proof of payment: confirm money-moving events through Safaricom's Daraja callbacks (see [docs/webhooks.md](docs/webhooks.md#sms-is-not-proof-of-payment)). To try it quickly, use the test receiver in `scripts/webhook-test-server.mjs`.

---

## Parsed Transaction Fields

Each M-PESA SMS is parsed into the following structure:

| Field | Description |
|---|---|
| `type` | Transaction type (e.g. `RECEIVED`, `SENT`, `PAYBILL`, `BUY_GOODS`, `WITHDRAW`) |
| `amount` | Transaction amount |
| `currency` | Currency (e.g. `KES`) |
| `sender` / `recipient` | Counterparty name or number |
| `reference` | M-PESA confirmation code |
| `timestamp` | Date and time of the transaction |
| `balance` | Remaining M-PESA balance after the transaction |

---

## Security

Please report vulnerabilities privately; see [SECURITY.md](SECURITY.md). The design of the encrypted WiFi channel and the reasoning behind other security choices are in [docs/adr](docs/adr/README.md).

---

## License

[MIT](./LICENSE) © 2026 David Amunga
