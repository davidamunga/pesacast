# ADR-0006: Local broadcast server rejects browser origins

- **Status:** Accepted (implemented in PR #12)
- **Date:** 2026-10-24

## Context

The optional local broadcast (`ws://127.0.0.1:7878`) re-publishes every transaction to local tools. Any web page open in the user's browser can open a WebSocket to `127.0.0.1`, so a malicious site could read financial data (cross-site WebSocket hijacking). WebSockets are not subject to the same-origin policy.

## Decision

Reject any handshake that includes an `Origin` header with `403`. Browsers always send `Origin`; native clients (scripts, `websocat`) normally do not. The server stays off by default and bound to loopback.

## Alternatives considered

- **Origin allow-list** — we have no legitimate browser client to list.
- **Shared secret** — better long term but adds setup for local scripts.

## Consequences

Browser-based tools cannot connect. A local malicious *process* can still connect; that is outside the threat model while the user's session is compromised anyway. Add a token if that changes.
