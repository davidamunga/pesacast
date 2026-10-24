# ADR-0010: Discover the phone over mDNS on the desktop

- **Status:** Accepted
- **Date:** 2026-10-24
- **Relates to:** [ADR-0003](0003-wifi-transport-token-websocket.md)

## Context

ADR-0003 registered `_pesacast._tcp` on the phone but the desktop never browsed for it, so users had to find and type the phone's IP.

## Decision

The desktop has a Rust command, `discover_wifi_devices`, that browses `_pesacast._tcp.local.` with the `mdns-sd` crate for 3 seconds and returns `{name, ip, port}` entries, IPv4 only (the phone's server accepts only private IPv4/loopback peers in practice). The WiFi card shows a "Find my phone" button; picking a result fills the IP and port. The token is still entered by the user and is never advertised. On macOS the app declares `NSLocalNetworkUsageDescription` and `NSBonjourServices` in `Info.plist`.

## Alternatives considered

- **Browse continuously in the background** — extra multicast traffic and a standing permission prompt on macOS for a one-time action.
- **Put the token in a TXT record** — would hand the secret to anyone on the network.
- **QR code pairing** — better UX and could carry IP and token together; a larger change, still a candidate.

## Consequences

Discovery depends on the network allowing multicast. Guest networks, some routers and AP isolation block mDNS, so manual entry stays. macOS shows a local-network permission prompt on first scan.
