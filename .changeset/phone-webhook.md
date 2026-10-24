---
"pesacast": minor
---

Add an optional webhook on the Android app: send each M-PESA transaction from the phone straight to your HTTPS endpoint, without a desktop. Deliveries are queued on the phone and retried, carry an idempotency key, and can be signed with an HMAC secret. Cleartext network traffic is now disallowed in the app.
