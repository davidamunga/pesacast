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

Transport: WebSocket on `<phone-ip>:<port>`, default port `7877`. Two protocol versions exist during the migration described in [ADR-0011](adr/0011-encrypt-wifi-transport.md). The server chooses by the first text message.

Common to both versions:

- Transactions generated while no client is authenticated are queued (max 50, in memory) and delivered on the next authenticated connection.
- Peers outside loopback, private IPv4 (10/8, 172.16/12, 192.168/16), IPv6 link-local and IPv6 ULA are refused. 5 failed attempts in 60 s blocks the source IP.
- An unauthenticated socket is closed (`1008`) after 5 s.
- Discovery: the phone registers mDNS service `_pesacast._tcp.` (service name `PesaCast`, port = the WebSocket port). The desktop browses for it with the `discover_wifi_devices` command and fills in IP and port. The advertisement carries no secret; the pairing token is always entered by the user.

### WiFi v2 (encrypted)

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

### WiFi v1 (legacy, unencrypted)

`ws://`, plaintext. The first client message is `{"auth":"<token>"}`; the server replies `{"status":"ok"}`, then sends one text message per transaction. Accepted only while "Allow unencrypted connections" is enabled on the phone. It is **off by default**; the user must turn it on to use an older desktop (see ADR-0011 for the removal timeline). A v2 phone that sees this message treats the client as legacy.

## Local broadcast (desktop, optional)

`ws://127.0.0.1:7878` (configurable, off by default). Server-to-client only: every ingested transaction as the JSON above. Handshakes with an `Origin` header are rejected with `403`.
