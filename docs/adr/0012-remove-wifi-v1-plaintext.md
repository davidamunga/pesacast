# ADR-0012: Remove the unencrypted WiFi v1 protocol

- **Status:** Accepted
- **Date:** 2026-10-24
- **Supersedes:** the compatibility timeline in [ADR-0011](0011-encrypt-wifi-transport.md) (the encrypted design itself stands)

## Context

ADR-0011 added an encrypted channel and kept the plaintext v1 handshake for a transition: first behind a setting that defaulted on, then (changed during implementation) defaulted off, with removal planned for a later release. While v1 exists, a phone can be talked into plaintext by anyone who connects with `{"auth":"<token>"}`, which also puts the token on the wire for a user who turns the setting on. The user base is small and the feature was released only days earlier, so the cost of a long overlap is higher than the cost of making users update.

## Decision

Remove the v1 protocol and the "Allow unencrypted connections" setting from both apps now. The Android server accepts only the v2 hello. A client that sends anything else is closed with `1008 Bad request`. The desktop no longer has a plaintext mode or a "connect without encryption" fallback; if a phone hangs up on the hello it tells the user to update PesaCast on the phone.

## Alternatives considered

- **Keep the switch, default off (state after ADR-0011 was implemented)** — leaves a downgrade path and extra code to keep tested.
- **Keep v1 for one more release** — extends a known weak path for a few users who can update.

## Consequences

- Old and new versions of the apps no longer interoperate over WiFi. Users must update the phone and the desktop together (BLE is unaffected). This goes in the release notes.
- Less code: no legacy branch in the server, no mode flag in `useWifi`, one set of tests.
- A downgrade attack is no longer possible because there is nothing to downgrade to.
- If a future change needs another incompatible protocol version, add a version negotiation then; `v` in the hello is the hook.
