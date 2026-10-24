# Security policy

PesaCast handles M-PESA transaction messages (amounts, sender names, references, balances), so we take security reports seriously.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub: open the repository's **Security** tab, choose **Report a vulnerability**, and describe what you found. That creates a private advisory that only the maintainers can see.

Helpful to include:

- What you found and what an attacker could do with it.
- The app version (shown at the bottom of the Android Connect tab) and the platform.
- Steps to reproduce, or a proof of concept.
- Whether you plan to publish, and any deadline you are working to.

**Please do not include real transaction messages, real pairing tokens or webhook secrets in a report.** Use made-up data; the repository's test vectors show how.

## What to expect

This is a small, volunteer-run project, so these are intentions, not guarantees:

- We aim to acknowledge a report within about a week.
- We will keep you updated, agree a disclosure date with you, and credit you in the advisory and release notes if you want that.
- Fixes ship in a new release; critical ones as fast as we can.

## Supported versions

Only the **latest release** of the Android and desktop apps receives security fixes. The two apps are versioned and released together, and WiFi only works between matching major protocol versions, so please update both.

## Scope

In scope:

- The Android app (SMS handling, the WiFi server and its encrypted channel, the webhook sender, local storage of secrets).
- The desktop app (WiFi and BLE clients, notifications, local database, the optional local broadcast server and the webhook push).
- The wire formats in [docs/protocol.md](docs/protocol.md), including the WiFi secure channel in [ADR-0011](docs/adr/0011-encrypt-wifi-transport.md).

Out of scope:

- Anything that requires physical access to an unlocked phone or computer, or a rooted or already-compromised device.
- A receiver you run yourself (the webhook endpoint is your system). Guidance for building one safely is in [docs/webhooks.md](docs/webhooks.md).
- SMS spoofing on the mobile network. PesaCast mirrors message text and is [not proof of payment](docs/webhooks.md#sms-is-not-proof-of-payment).
- Denial of service by someone already on your local network.
- Findings that need a user to turn on an optional, off-by-default feature and then point it at a hostile server.

## Reviewing the WiFi handshake

The WiFi secure channel is a composition of standard primitives and has not had an independent review yet. If you have cryptography experience and want to look, [docs/security/wifi-handshake-review-request.md](docs/security/wifi-handshake-review-request.md) has the specification, test vectors, threat model and the questions we most want answered.
