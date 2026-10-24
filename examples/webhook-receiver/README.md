# Reference webhook receiver

A small receiver for the PesaCast phone and desktop webhooks. Python 3.10+, **standard library only** (SQLite for storage). Use it as a starting point or to see how the pieces fit; it is not a hardened service.

```bash
export PESACAST_SECRET=...   # same signing secret as in the phone app (optional)
export PESACAST_TOKEN=...    # same bearer token as in the app (optional, also used by the desktop)
python3 receiver.py --port 8080 --db receiver.sqlite3
```

It refuses to start without a secret or token unless you pass `--insecure-no-auth` (testing only). The phone requires **HTTPS**, so put a TLS-terminating reverse proxy or a tunnel in front of it (see [docs/webhooks.md](../../docs/webhooks.md)).

## What it does

| Request | Result |
|---|---|
| Valid signature, or valid bearer token | Accepted |
| Wrong or missing credentials, or a stale timestamp | `401` (the phone pauses and keeps its queue) |
| Test ping (`"test": true`) | `204`, nothing stored |
| Transaction already stored (a retry, or the same one from phone and desktop) | `200`, stored once |
| Unknown `schema_version` | `200`, raw event kept, not parsed (never rejected, so nothing is lost) |
| Body that can never be processed | `400` (the phone drops that item) |

Storage: `events` keeps every accepted delivery as received (for audit and replay); `transactions` has `PRIMARY KEY (ref, time)`, so duplicates are impossible by construction.

Authentication is "a valid signature **or** the bearer token": the phone can sign, while the desktop sends only the bearer token.

## Tests

```bash
python3 -m unittest -v test_receiver
```

The tests include the HMAC vector that the Android tests also assert, so the two implementations cannot drift apart.

## Before using this for real

Run it behind HTTPS, add rate limiting, run it as an unprivileged user, back up the database, and remember that an SMS is not proof of payment: confirm money-moving events through Safaricom's Daraja callbacks.
