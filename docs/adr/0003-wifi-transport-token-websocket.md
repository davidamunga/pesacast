# ADR-0003: Add a token-authenticated WebSocket WiFi transport

- **Status:** Accepted (implemented in PR #12)
- **Date:** 2026-10-24

## Context

BLE is unreliable on some devices and ranges are short. Many users have phone and desktop on the same LAN. We still want no cloud dependency.

## Decision

When enabled, the Android app hosts a WebSocket server (default port 7877) and advertises it over mDNS. The desktop connects as a client. The first client message must be `{"auth":"<token>"}`; the server replies `{"status":"ok"}`, then pushes transaction JSON. The token is 32 random bytes (URL-safe base64), generated on the phone and entered on the desktop. The server accepts only loopback, private IPv4, and link-local/unique-local IPv6 peers, drops unauthenticated sockets after 5 s, compares tokens in constant time, and blocks an IP after 5 failed attempts in 60 s.

## Alternatives considered

- **TLS with a self-signed certificate** — needs certificate pinning/trust UX on both sides; deferred.
- **Phone as client, desktop as server** — requires the desktop to be reachable and open a listening port.
- **Cloud relay** — see ADR-0002.

## Consequences

Traffic is **unencrypted** (`ws://`). The token and transactions can be read by anyone on the same network segment, so the feature is off by default and the README warns about it. Revisit with TLS or an encrypted application-layer handshake. mDNS is registered but the desktop does not browse for it yet; users type the IP.
