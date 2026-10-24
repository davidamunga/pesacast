---
"pesacast": minor
---

Encrypt the WiFi transport. The desktop and phone now agree on session keys with an ephemeral key exchange authenticated by the pairing token, and every transaction is sent as an encrypted record. The token is no longer sent over the network. Older versions still connect unencrypted while "Allow unencrypted connections" is on in the Android app; the desktop shows when a connection is not encrypted and asks before falling back.
