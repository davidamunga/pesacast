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
- **Framing:** payload is `"<length>:<json>"` in UTF-8, split into chunks of at most `negotiated MTU − 3` bytes (20 bytes before MTU negotiation). Android implements this in `BleFraming`; the desktop reassembles in `ble-framing.ts` (`FrameReassembler`). Both have unit tests.
- **`<length>` counts UTF-16 code units** (Kotlin `String.length` = JavaScript `string.length`), not bytes. An accented character counts 1, an emoji counts 2. Keep it that way: changing it breaks older desktop/Android pairs.
- **Chunks never split a character.** The desktop decodes every notification on its own, so Android cuts chunks only at character boundaries (a chunk can be a few bytes shorter than the limit).
- **While a frame is in progress every chunk is data**, even if it looks like a header (`12:12:00+03:00` inside a timestamp). A partial frame idle for more than 5 s is abandoned. The desktop also accepts an unframed single JSON chunk.

## WiFi

- Transport: WebSocket, `ws://<phone-ip>:<port>`, default port `7877`. Unencrypted.
- Handshake: client sends `{"auth":"<token>"}` as its first text message. Server replies `{"status":"ok"}`; any other first message, a wrong token, or silence for 5 s closes the socket (`1008`).
- After `ok`, the server sends one text message per transaction (the JSON above). Client messages after auth are ignored.
- Transactions generated while no client is connected are queued (max 50, in memory) and delivered on the next authenticated connection.
- Peers outside loopback, private IPv4 (10/8, 172.16/12, 192.168/16), IPv4/IPv6 link-local and IPv6 ULA are refused. 5 failed attempts in 60 s blocks the source IP.
- Discovery: the phone registers mDNS service `_pesacast._tcp.` (service name `PesaCast`, port = the WebSocket port). The desktop browses for it with the `discover_wifi_devices` command and fills in IP and port. The advertisement carries no secret; the pairing token is always entered by the user.

## Local broadcast (desktop, optional)

`ws://127.0.0.1:7878` (configurable, off by default). Server-to-client only: every ingested transaction as the JSON above. Handshakes with an `Origin` header are rejected with `403`.
