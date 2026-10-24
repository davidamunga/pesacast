# Protocol

The Android and desktop apps are released and updated independently. Treat everything here as a compatibility contract and prefer additive changes.

## Transaction JSON

One transaction per message. Field names are fixed.

| Field | Type | Notes |
|---|---|---|
| `type` | string | Always `"mpesa_txn"` from Android today |
| `direction` | string | `received` \| `sent` \| `paid` \| `withdrawn` \| `airtime` \| `fuliza`; new values may be added |
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

Transport: WebSocket on `<phone-ip>:<port>`, default port `7877`, with the encrypted channel below ([ADR-0011](adr/0011-encrypt-wifi-transport.md)).

Common rules:

- Transactions generated while no client is authenticated are queued (max 50, in memory) and delivered on the next authenticated connection.
- Peers outside loopback, private IPv4 (10/8, 172.16/12, 192.168/16), IPv6 link-local and IPv6 ULA are refused. 5 failed attempts in 60 s blocks the source IP.
- An unauthenticated socket is closed (`1008`) after 5 s.
- Discovery: the phone registers mDNS service `_pesacast._tcp.` (service name `PesaCast`, port = the WebSocket port). The desktop browses for it with the `discover_wifi_devices` command and fills in IP and port. The advertisement carries no secret; the pairing token is always entered by the user.

### Secure channel (v2)

Preferred. The pairing token is the pre-shared key; it is never sent.

**Primitives:** ECDH on P-256, HKDF-SHA256, AES-256-GCM. Binary values in JSON are standard base64 with padding.

1. **Client hello** (text): `{"v":2,"hello":{"pub":"<b64>","nonce":"<b64>"}}`
   - `pub`: client ephemeral public key, 65-byte uncompressed point (`0x04 || X || Y`).
   - `nonce`: 16 random bytes.
2. **Server hello** (text): same shape with the server's ephemeral key and nonce. The server must validate that `pub` is a point on the curve.
3. **Key derivation** (both sides):
   - `shared` = ECDH(own private, peer public), the 32-byte X coordinate.
   - `okm` = HKDF-SHA256(`ikm` = `shared`, `salt` = UTF-8 bytes of the token string, `info` = `"pesacast-v2"` (ASCII) `|| clientPub || serverPub || clientNonce || serverNonce`, length 64).
   - `key_c2s` = `okm[0..32]`, `key_s2c` = `okm[32..64]`.
4. **Record** (binary frame): `counter (8 bytes, big-endian) || ciphertext || tag (16 bytes)`.
   - Nonce (12 bytes): `0x00000000 || counter`.
   - AAD (9 bytes): direction byte (`0x01` client-to-server, `0x02` server-to-client) `|| counter`.
   - Each direction has its own counter starting at 0 and increasing by 1 per record. A receiver rejects a record whose counter is not exactly the expected next value, or that fails authentication, and closes the connection.
   - Plaintext is UTF-8 JSON.
5. **Authentication:** the client sends its first record, `{"ready":true}`. If the server cannot decrypt it, the token is wrong: the server records a failed attempt for the IP and closes (`1008`). Otherwise the server replies with the record `{"status":"ok"}`.
6. After that the server sends one record per transaction (the transaction JSON above). Client records after `ready` are ignored.

Test vectors: [wifi-crypto-vectors.json](wifi-crypto-vectors.json).

### Older clients

Earlier releases used an unencrypted protocol (first message `{"auth":"<token>"}`). It is no longer supported ([ADR-0012](adr/0012-remove-wifi-v1-plaintext.md)): the server closes such a connection with `1008 Bad request`, and the desktop reports that the phone must be updated.

## Phone webhook (Android, optional)

[ADR-0013](adr/0013-phone-webhook-delivery.md). When enabled, the phone `POST`s each parsed transaction to a user-configured **HTTPS** URL, independent of any desktop. Redirects are not followed. Plain `http://` is refused.

**Request**

```
POST <url>
Content-Type: application/json
User-Agent: PesaCast-Android
Idempotency-Key: <ref>@<time>
X-PesaCast-Timestamp: <unix seconds>
X-PesaCast-Signature: v1=<hex>        (only if a signing secret is set)
Authorization: Bearer <token>         (only if a token is set)
```

Body: the same envelope the desktop push uses, plus `source`:

```json
{
  "exported_at": "2026-10-24T10:00:01.234Z",
  "source": "android",
  "transactions": [ { "type": "mpesa_txn", "ref": "ABC123", "time": "...", "amount": 500.0, "...": "..." } ],
  "count": 1
}
```

The "Save & send test" button sends the same envelope with `"test": true`, an empty `transactions` array and `"count": 0`. Receivers must not record it as a transaction. Receivers must ignore unknown fields.

**Signature.** `v1=` plus the lowercase hex HMAC-SHA256 of the ASCII string `<timestamp>.` followed by the raw request body bytes, keyed with the signing secret (UTF-8). Verify with a constant-time comparison and reject timestamps older than a few minutes. See [webhooks.md](webhooks.md) for examples.

**Delivery** is at-least-once and in order. The queue is persisted on the phone (max 500 items, 7 days, oldest dropped first).

| Response | Behaviour |
|---|---|
| `2xx` | Delivered; item removed |
| `408`, `429`, `5xx`, network error | Retried with backoff (5 s, doubling, max 15 min); the same `Idempotency-Key` |
| `401`, `403` | Delivery pauses and the queue is kept until settings change or the user retries |
| `3xx`, other `4xx` | That item is dropped and the error shown in the app |

## Local broadcast (desktop, optional)

`ws://127.0.0.1:7878` (configurable, off by default). Server-to-client only: every ingested transaction as the JSON above. Handshakes with an `Origin` header are rejected with `403`.
