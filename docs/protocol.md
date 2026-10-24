# Protocol

The Android and desktop apps are released and updated independently. Treat everything here as a compatibility contract and prefer additive changes.

## Transaction JSON

One transaction per message. Field names are fixed.

| Field | Type | Notes |
|---|---|---|
| `type` | string | Always `"mpesa_txn"` from Android today |
| `direction` | string | `received` \| `sent` \| `paid` \| `withdrawn` \| `fuliza` |
| `amount` | number | |
| `currency` | string | `"KES"` |
| `from` | string | Sender name or merchant |
| `ref` | string | M-PESA reference code |
| `time` | string | ISO-8601 instant |
| `balance` | number | Balance after the transaction |
| `transaction_cost` | number, optional | Omitted when absent |

Identity of a transaction is `(ref, time)`. Unknown extra fields are ignored by the Rust side; keep it that way so newer phones work with older desktops.

Defined in: `android/.../model/MpesaTransaction.kt`, `desktop/src-tauri/src/model.rs`, `desktop/src/domains/transactions/types.ts`.

## BLE

- Phone is the GATT peripheral, desktop the central.
- Service UUID `E7A12345-B09E-4B3C-83A6-00112233AABB`; transaction characteristic `E7A12346-B09E-4B3C-83A6-00112233AABB` (notify). Defined in `model/` on Android and `desktop/src/domains/connection/constants.ts`.
- **Framing:** payload is `"<length>:<json>"` in UTF-8, split into chunks of `negotiated MTU − 3` bytes (20 bytes before MTU negotiation). The central reassembles until `<length>` is reached. The desktop also accepts an unframed single JSON chunk.
- Android computes `<length>` from `json.length` (UTF-16 code units), while chunking operates on UTF-8 bytes. Non-ASCII payloads may therefore differ in length; confirm the desktop's comparison if you change either side. (`TODO`: verify and fix or document.)

## WiFi (PR #12)

- Transport: WebSocket, `ws://<phone-ip>:<port>`, default port `7877`. Unencrypted.
- Handshake: client sends `{"auth":"<token>"}` as its first text message. Server replies `{"status":"ok"}`; any other first message, a wrong token, or silence for 5 s closes the socket (`1008`).
- After `ok`, the server sends one text message per transaction (the JSON above). Client messages after auth are ignored.
- Transactions generated while no client is connected are queued (max 50, in memory) and delivered on the next authenticated connection.
- Peers outside loopback, private IPv4 (10/8, 172.16/12, 192.168/16), IPv4/IPv6 link-local and IPv6 ULA are refused. 5 failed attempts in 60 s blocks the source IP.
- Discovery: the phone registers mDNS service `_pesacast._tcp.` but clients currently require a manually entered IP.

## Local broadcast (desktop, optional)

`ws://127.0.0.1:7878` (configurable, off by default). Server-to-client only: every ingested transaction as the JSON above. Handshakes with an `Origin` header are rejected with `403`.
