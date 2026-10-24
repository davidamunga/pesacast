# ADR-0002: Use BLE GATT notify as the primary transport

- **Status:** Accepted
- **Date:** 2026-10-24 (records an earlier decision)

## Context

The phone must push transactions to a nearby desktop with no account, no cloud service and no shared network setup. Classic Bluetooth support was previously used and then removed.

## Decision

The Android app is a BLE GATT peripheral advertising a PesaCast service (UUID in [protocol.md](../protocol.md)). The desktop is the central: it scans, connects, and subscribes to a notify characteristic. Each transaction is sent as length-prefixed UTF-8 JSON, split into chunks of `negotiated MTU − 3` bytes.

## Alternatives considered

- **Classic Bluetooth** — an earlier transport that paired with a chosen Mac in the Android app. It was removed before this repository's history begins (the initial commit already carries only leftover preference keys and a comment saying it was removed), and the reason was not recorded. Nothing here depends on it; this ADR does not claim why it was dropped.
- **Cloud relay** — requires accounts and sends financial data off-device.

## Consequences

Works offline and needs no pairing screen beyond OS Bluetooth. Throughput is low, so framing and reassembly are required on the desktop. Behaviour varies by phone BLE stack, which makes on-device testing important.
