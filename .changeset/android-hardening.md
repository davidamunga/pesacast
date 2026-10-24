---
"pesacast": patch
---

Harden the Android app: disable cloud backup and device transfer, stop writing SMS text to the log, store the WiFi pairing token encrypted (existing tokens are migrated), remove unused permissions, and make the SMS and webhook features keep working when Bluetooth permission is denied. After a denial the app now explains what is needed and links to Settings.
