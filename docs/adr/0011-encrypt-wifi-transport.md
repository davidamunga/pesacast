# ADR-0011: Encrypt the WiFi transport with a PSK-authenticated key exchange

- **Status:** Proposed
- **Date:** 2026-10-24
- **Supersedes (on acceptance):** the plaintext handshake and consequences of [ADR-0003](0003-wifi-transport-token-websocket.md)

## Context

The WiFi transport ([ADR-0003](0003-wifi-transport-token-websocket.md)) sends the pairing token and every transaction over plaintext `ws://`. Anyone on the same network segment (shared WiFi, hotel, office, a compromised IoT device) can read balances and counterparties, learn the token, and then connect as the desktop. The private-IP filter and rate limiting do not help against a passive listener. The feature is off by default and the README warns about it, but a financial-data transport should not rely on a warning.

Constraints that shape the options:

- The desktop client currently lives in the **webview** (`useWifi.ts`, browser `WebSocket`). A browser WebSocket cannot trust a self-signed certificate or pin one.
- The phone already shares a **256-bit random secret** (the pairing token) with the desktop through a channel the user controls (typed in or copied from the pairing screen). That is a pre-shared key we can build on, without certificates.
- The apps update independently, so old and new versions will meet.
- No new network service, no cloud, no certificate authority.

## Decision

Keep the WebSocket, and add an application-layer secure channel authenticated by the pairing token, built only from standard primitives available on both sides:

- **Key agreement:** ephemeral ECDH on P-256, one key pair per connection on each side (forward secrecy).
- **Authentication:** the pairing token is mixed into key derivation. A party without the token cannot derive the session keys, so a man-in-the-middle cannot join. The token is **never sent**.
- **Key derivation:** HKDF-SHA256 with `salt = token`, `ikm = ECDH shared secret`, and `info` binding the protocol version, both public keys and both nonces. Two keys are derived, one per direction.
- **Records:** AES-256-GCM. The 96-bit nonce is a per-direction counter; the receiver rejects any counter that does not increase. The direction label and counter are authenticated as associated data.

Handshake sketch (JSON text frames, then binary records):

1. Client → `{"v":2,"hello":{"pub":"<b64>","nonce":"<b64 16B>"}}`
2. Server → `{"v":2,"hello":{"pub":"<b64>","nonce":"<b64 16B>"}}`
3. Both derive keys. Server → first encrypted record `{"status":"ok"}`. A wrong token yields a GCM failure on this record, so the client closes and reports "wrong pairing token". The server counts a failed attempt when the client's first record fails to authenticate.
4. Afterwards every transaction is one encrypted record. The existing 5 s handshake timeout, per-IP failure limit and private-IP filter stay.

Compatibility:

- The server inspects the first message. `{"v":2,...}` takes the encrypted path. `{"auth":...}` is the legacy plaintext path.
- **Release N (this change):** legacy stays enabled by default so existing desktops keep working, with a setting to disable it. The desktop prefers v2 and, if the phone only speaks legacy, asks the user before connecting unencrypted and then shows an "unencrypted" badge. It never silently downgrades.
- **Release N+1:** legacy is disabled by default on the phone.
- **Release N+2:** legacy code is removed and this ADR is updated.

## Alternatives considered

- **TLS (`wss://`) with a self-signed phone certificate and pinning.** The most conventional answer, and the right one if the client moves out of the webview. Not chosen now because the browser `WebSocket` cannot pin or accept it, so the client would have to move to Rust (rustls with a custom verifier) *and* the phone would need certificate provisioning plus fingerprint exchange in the pairing flow. Bigger rewrite for the same security result. Revisit if the client moves to Rust for other reasons.
- **Noise (`Noise_NNpsk0` or `XXpsk`).** A well-reviewed framework designed for exactly this shape. Not chosen because there is no maintained library we would trust on both Android and in the webview; adopting it would also pull the client into Rust. If it does move, this is a strong replacement for the hand-assembled handshake above.
- **Static PSK AES-GCM without key exchange.** Simpler, but no forward secrecy: a leaked token would decrypt anything ever recorded.
- **Keep plaintext and document it.** The status quo; leaves the token and financial data exposed on shared networks.
- **VPN / WireGuard or an SSH tunnel.** Pushes setup onto every user and is out of scope for an app that should work after typing an IP and a token.

## Consequences

- Confidentiality, integrity, replay protection within a session, forward secrecy, and mutual authentication, with no new user steps. Pairing UX is unchanged; regenerating the token revokes access as before.
- Because it composes standard primitives rather than using an off-the-shelf protocol, the design carries the risk of subtle mistakes. Mitigations: only vetted primitives (WebCrypto, `javax.crypto`), fixed known-answer test vectors checked by both the Kotlin and TypeScript implementations, negative tests (wrong token, replayed or reordered record, truncated frame), and an external review of the handshake before the legacy path is disabled.
- A passive attacker can still see that the connection exists, its timing, and message sizes. Metadata is out of scope.
- An attacker who learns the token can still impersonate either side (as with any PSK scheme). Token handling rules in `AGENTS.md` stay in force.
- The downgrade window exists until legacy is disabled; the consent prompt and badge limit it, and the timeline above bounds it.
- `docs/protocol.md` and `docs/architecture.md` change when this is implemented. ADR-0003 is marked superseded when this ADR is accepted.

## Open questions

- Is one release of overlap enough, or should N+1 wait for a measured update rate?
- Do we want the client to move to Rust anyway (background reconnect without webview throttling), which would let us choose TLS or Noise instead?
- Binary WebSocket frames for records, or base64 text frames for easier debugging?
