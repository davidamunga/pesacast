# ADR-0002: Use BLE GATT notify as the primary transport

- **Status:** Accepted
- **Date:** 2026-10-24 (records an earlier decision)

## Context

The phone must push transactions to a nearby desktop with no account, no cloud service and no shared network setup. Classic Bluetooth support was previously used and then removed.

## Decision

The Android app is a BLE GATT peripheral advertising a PesaCast service (UUID in [protocol.md](../protocol.md)). The desktop is the central: it scans, connects, and subscribes to a notify characteristic. Each transaction is sent as length-prefixed UTF-8 JSON, split into chunks of `negotiated MTU − 3` bytes.

## Alternatives considered

- **Classic Bluetooth** — used earlier and removed from the Android app; the reason was not recorded (`TODO(maintainer)`: confirm).
- **Cloud relay** — requires accounts and sends financial data off-device.

## Consequences

Works offline and needs no pairing screen beyond OS Bluetooth. Throughput is low, so framing and reassembly are required on the desktop. Behaviour varies by phone BLE stack, which makes on-device testing important.
