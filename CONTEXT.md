# Context

Shared vocabulary. Use these terms exactly in code, docs and commits.

| Term | Meaning |
|---|---|
| **Transaction** | A parsed M-PESA SMS (`MpesaTransaction`): type, direction, amount, currency, from, ref, time, balance, optional transaction cost. Identity is `(ref, time)`. |
| **Direction** | One of `received`, `sent`, `paid`, `withdrawn`, `fuliza`. |
| **Mirror / mirroring** | Relaying transactions from the phone to the desktop. Toggled by the user on Android (`mirroringEnabled`). |
| **Transport** | A way to carry transactions from phone to desktop. Currently **BLE** and **WiFi**. Transports are independent and may run at once. |
| **Mirror service** | The Android foreground service (`MirrorService`) that keeps transports alive in the background. |
| **Peripheral / central** | BLE roles. The phone is the GATT *peripheral* (server); the desktop is the *central* (client). |
| **WiFi transport** | The phone hosts a WebSocket server on the LAN; the desktop connects as a client. |
| **Pairing token** | Random secret generated on the phone that the desktop sends to authenticate a WiFi connection. |
| **Phone webhook** | Optional Android feature that POSTs each transaction from the phone to a user-configured HTTPS URL, independent of any desktop. Not the same as the desktop's webhook push or the local broadcast. |
| **Setup checklist** | The Android screen (Connect tab, then Setup checklist) that shows what a phone still needs before it reliably forwards payments (SMS permission, battery settings, the phone maker's auto-start menu, a destination) and runs an end-to-end test. See ADR-0015. |
| **Local broadcast** | Optional desktop WebSocket server on `127.0.0.1:7878` that re-publishes every ingested transaction to local tools. Not related to the WiFi transport. |
| **Ingest** | The desktop pipeline every transaction passes through: parse, dedupe, notify, emit, broadcast. |
| **Changeset** | A pending version bump in `.changeset/`. |
