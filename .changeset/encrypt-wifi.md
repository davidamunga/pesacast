---
"pesacast": minor
---

Encrypt the WiFi transport. The desktop and phone now agree on session keys with an ephemeral key exchange authenticated by the pairing token, and every transaction is sent as an encrypted record. The token is no longer sent over the network. The phone refuses older, unencrypted desktop versions by default: update the desktop app, or turn on "Allow unencrypted connections" in the Android app as a stopgap. The desktop shows when a connection is not encrypted and asks before falling back.
