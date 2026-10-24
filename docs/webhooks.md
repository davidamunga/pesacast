# Receiving PesaCast webhooks

How to accept transactions that the Android app (or the desktop app) pushes to your URL. The wire format is in [protocol.md](protocol.md#phone-webhook-android-optional).

## What to do on every request

1. **Verify the signature** (if you set a signing secret in the app).
2. **Reject old timestamps** (a few minutes) to stop replays.
3. **Ignore test pings**: `"test": true` with an empty `transactions` array.
4. **De-duplicate** on `Idempotency-Key` (`<ref>@<time>`). Delivery is at-least-once, and if you enable the push on both the phone and the desktop you will see each transaction twice.
5. **Answer `2xx` quickly.** Anything else makes the phone retry (`5xx`, `429`, `408`) or pause (`401`, `403`).

## Node (Express)

```js
import crypto from "node:crypto";
import express from "express";

const SECRET = process.env.PESACAST_SECRET;
const seen = new Set(); // use a database or Redis in production

const app = express();
// Keep the raw bytes: the signature covers the body exactly as sent.
app.use(express.raw({ type: "application/json" }));

app.post("/hook", (req, res) => {
  const timestamp = req.get("x-pesacast-timestamp") ?? "";
  const signature = req.get("x-pesacast-signature") ?? "";

  const expected =
    "v1=" + crypto.createHmac("sha256", SECRET).update(`${timestamp}.`).update(req.body).digest("hex");
  const ok =
    signature.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expected));
  const fresh = Math.abs(Date.now() / 1000 - Number(timestamp)) < 300;
  if (!ok || !fresh) return res.sendStatus(401);

  const payload = JSON.parse(req.body.toString("utf8"));
  if (payload.test) return res.sendStatus(204);

  const key = req.get("idempotency-key");
  if (key && seen.has(key)) return res.sendStatus(200);
  if (key) seen.add(key);

  for (const txn of payload.transactions) {
    console.log("received", txn.ref, txn.amount);
  }
  res.sendStatus(200);
});

app.listen(3000);
```

## Python (Flask)

```python
import hashlib, hmac, os, time
from flask import Flask, request, abort

SECRET = os.environ["PESACAST_SECRET"].encode()
seen = set()  # use a database in production
app = Flask(__name__)

@app.post("/hook")
def hook():
    timestamp = request.headers.get("X-PesaCast-Timestamp", "")
    signature = request.headers.get("X-PesaCast-Signature", "")
    body = request.get_data()  # raw bytes

    mac = hmac.new(SECRET, timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(signature, "v1=" + mac):
        abort(401)
    if abs(time.time() - float(timestamp or 0)) > 300:
        abort(401)

    payload = request.get_json()
    if payload.get("test"):
        return "", 204

    key = request.headers.get("Idempotency-Key")
    if key and key in seen:
        return "", 200
    if key:
        seen.add(key)

    for txn in payload["transactions"]:
        print("received", txn["ref"], txn["amount"])
    return "", 200
```

## Notes

- Your URL **must be HTTPS** with a certificate the phone trusts. For a receiver on your own network, put a TLS-terminating reverse proxy or tunnel in front; Android cannot allow plain HTTP for just a LAN.
- The bearer token and the signature are independent; use either or both. The signature is the stronger option because the secret is never sent.
- Keep the signing secret out of logs and source control. Rotate it by changing it in the app and on your receiver, then sending a test.
