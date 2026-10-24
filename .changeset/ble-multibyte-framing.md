---
"pesacast": patch
---

Fix BLE transactions being corrupted when a name contains accented characters or emoji that fall on a chunk boundary, and when a chunk happens to start with digits followed by a colon (for example inside a timestamp).
