# ADR-0005: Run transports in a `connectedDevice` foreground service

- **Status:** Accepted (implemented in PR #12)
- **Date:** 2026-10-24

## Context

BLE and the WiFi server must survive the app being backgrounded. Android 14+ requires a foreground service type. We first added `dataSync` for the WiFi server, but on Android 15 `dataSync` services are time-limited (about 6 hours) and stopped by the system.

## Decision

Declare only `foregroundServiceType="connectedDevice"` (permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`). That type covers Bluetooth and network connections to external devices.

## Alternatives considered

- **`dataSync`** — timeout on Android 15.
- **`specialUse`** — needs Play Store justification and is not required here.

## Consequences

One service type, no timeout handling. If a store review disputes the type for the WiFi use, revisit.
