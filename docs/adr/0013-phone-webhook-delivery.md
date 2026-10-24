# ADR-0013: Deliver transactions to a webhook directly from the phone

- **Status:** Proposed
- **Date:** 2026-10-24

## Context

The desktop app can POST each transaction to a user-configured URL (`webhook-push.tsx`, `autoPushTransaction`). That only works while a desktop is running and connected. Users who want the data in their own system, or who run the phone on its own as an always-on SMS receiver, need the phone to deliver it itself. We also want to keep dependencies down.

Facts about the current Android app that shape the design:

- Every parsed transaction already flows through `SmsReceiver` to `TransportManager.sendTransaction`, which fans out to BLE and Wi-Fi.
- `MirrorService` is a foreground service that keeps transports alive; a broadcast receiver itself only has about 10 seconds and must not do network work inline.
- `INTERNET` is already declared. OkHttp is declared in `build.gradle.kts` but nothing uses it.
- `res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="true"` for all hosts, while its comment says LAN only. Plaintext Wi-Fi was removed in [ADR-0012](0012-remove-wifi-v1-plaintext.md), so the phone no longer needs cleartext at all.
- Android's network security config can allow cleartext per domain name, not per IP range, so "plain HTTP only on the LAN" cannot be expressed in it.
- Transactions are financial data. Sending them to a remote host is a trust-boundary change.

## Decision

Add an **opt-in webhook sink** on the phone, off by default, as a third destination alongside BLE and Wi-Fi.

**Delivery**
- The SMS path enqueues the transaction; a sender running in `MirrorService` delivers it. `onReceive` does no network I/O.
- The queue is persisted in a small file in app storage so a killed process or lack of signal does not lose transactions. It holds at most 500 items or 7 days, dropping the oldest first.
- Retries use exponential backoff (5 s doubling to 15 min). The queue is also drained when a `ConnectivityManager.NetworkCallback` reports a validated network. No WorkManager.
- Result handling: `2xx` removes the item. `408`, `429` and `5xx` and network errors retry. `401` and `403` pause delivery and keep the queue (the credential is wrong; do not discard data) and show the error. Other `4xx` drop that item and record it as the last error.
- The webhook is independent of the desktop: it works when mirroring to a desktop is off, and `MirrorService` runs whenever either the webhook or a transport is enabled.

**Request**
- `POST` to the user's URL, `Content-Type: application/json`, same body as the desktop push so existing receivers keep working: `{"transactions":[<txn>],"count":1,"exported_at":"<ISO-8601>"}`, plus an additive `"source":"android"`. The desktop will add `"source":"desktop"` later; receivers must ignore unknown fields.
- `Idempotency-Key: <ref>@<time>` (the transaction identity). Delivery is **at-least-once**, so receivers should dedupe on it.
- Optional `Authorization: Bearer <token>`, for compatibility with the desktop push.
- Optional signing secret: `X-PesaCast-Timestamp: <unix seconds>` and `X-PesaCast-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>`. Receivers should reject old timestamps. This uses only `javax.crypto`.

**Safety**
- **HTTPS only.** `http://` URLs are refused. Redirects are not followed. Connect and read timeouts are 10 s. The Android network security config is changed to `cleartextTrafficPermitted="false"`.
- The bearer token and signing secret are stored encrypted with an Android Keystore AES-GCM key via the JDK crypto APIs (no new dependency), not in plain `SharedPreferences`.
- The settings screen states plainly that transaction data (amounts, names, balances) leaves the phone. Nothing is sent until the user enables it and a "send test" succeeds. Tokens and bodies are never logged.

**Dependencies**
- Use `HttpURLConnection`. **Remove the unused OkHttp dependency.** Net dependencies go down by one.
- Unit tests use the JDK's `com.sun.net.httpserver`; no mock-server library.

## Alternatives considered

- **Desktop only (status quo).** Needs a running desktop; does not serve a phone running alone.
- **WorkManager for retries.** The standard Android answer, but adds an AndroidX dependency for behaviour a foreground service plus a network callback already gives us.
- **OkHttp.** Nicer API, but we need one `POST` with timeouts; the platform client is enough and lets us drop a dependency.
- **Allow plain HTTP to private addresses.** Convenient for self-hosted receivers, but Android cannot scope cleartext by IP range, so it would mean allowing cleartext everywhere. Users with HTTP-only LAN receivers can put a TLS-terminating reverse proxy or tunnel in front. Revisit if that proves a common blocker.
- **Bearer token only.** Matches the desktop but the secret travels with every request; the HMAC option is stronger for receivers that can verify it.
- **A push or relay service run by the project.** Adds infrastructure and sends financial data through a third party.

## Consequences

- Users can run a phone alone and still get data into their own systems. The phone is now a networked sender, so its release notes, README and `docs/protocol.md` must say so, and the new section in `docs/protocol.md` becomes a compatibility contract.
- Receivers see duplicates in two cases: retries (at-least-once), and a user enabling both the phone and desktop push to the same URL. The idempotency key and the `source` field exist for this; the settings text should warn about the second case.
- A compromised receiver or a leaked secret exposes transactions. The secret can be rotated in the app; signed timestamps limit replay but do not hide the body, which TLS does.
- Persisted queues contain financial data at rest on the phone for up to 7 days. App storage is private to the app, but a rooted device can read it; document this, and delete items on success.
- `SmsReceiver` and `MirrorService` change behaviour: the `mirroringEnabled` early return must not block the webhook, and the service lifecycle follows "any sink enabled". Needs a real-device check on cheap phones with aggressive battery management.
- Cleartext is no longer permitted anywhere in the app. No current feature needs it.
- A future per-endpoint HMAC format change needs a version in the signature prefix; `v1=` is that hook.

## Open questions

- Is 500 items / 7 days the right queue bound?
- Should a failing endpoint raise a visible notification after N hours, or only show in the settings screen?
- Does the project want a documented receiver example (Node, Python) under `docs/` for the signature check?
