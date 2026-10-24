---
"pesacast": patch
---

Webhook payload additions for integrators: every push now carries `schema_version`, `source` and a per-install `device_id` (also as an `X-PesaCast-Device-Id` header), and the desktop push uses the same envelope and idempotency key as the phone so a receiver can de-duplicate across both. The format is published as a JSON Schema and an OpenAPI description, with a reference receiver that stores events in a database.
