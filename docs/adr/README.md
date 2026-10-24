# Architecture decision records

Why the code is the way it is. See [the template](0000-template.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md#architecture-decision-records).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-ble-primary-transport.md) | Use BLE GATT notify as the primary transport | Accepted |
| [0003](0003-wifi-transport-token-websocket.md) | Add a token-authenticated WebSocket WiFi transport | Proposed (PR #12) |
| [0004](0004-dedupe-in-desktop-ingest.md) | Deduplicate transactions in the desktop ingest pipeline | Proposed (PR #12) |
| [0005](0005-foreground-service-connected-device.md) | Run transports in a `connectedDevice` foreground service | Proposed (PR #12) |
| [0006](0006-local-broadcast-rejects-browser-origins.md) | Local broadcast server rejects browser origins | Proposed (PR #12) |
| [0007](0007-vendor-btleplug.md) | Vendor and patch `btleplug` | Accepted, rationale to confirm |
| [0008](0008-changesets-and-version-sync.md) | Version with Changesets and sync to Android/Cargo | Accepted |
| [0009](0009-conventional-commits-and-lint-gates.md) | Enforce Conventional Commits and lint gates | Accepted |
| [0010](0010-mdns-discovery-on-desktop.md) | Discover the phone over mDNS on the desktop | Accepted |
