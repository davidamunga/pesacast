# Receiving PesaCast webhooks

How to accept transactions that the Android app (or the desktop app) pushes to your URL. The wire format is in [protocol.md](protocol.md#phone-webhook-android-optional).

## SMS is not proof of payment

PesaCast forwards the text of an M-PESA SMS. That is useful for notifications, dashboards and reconciliation, but it is not authoritative: the sender name on an SMS can be spoofed, and a message can be delayed, duplicated or missing if the phone was off or out of signal. If your system releases goods or credits an account, confirm the payment with Safaricom's official Daraja callbacks (C2B or STK push) and treat PesaCast events as a convenience alongside them.

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

## Trying it before you build a receiver

`scripts/webhook-test-server.mjs` is a dependency-free receiver for testing the phone. It checks the token and signature you give it, ignores test pings, flags duplicates, and lets you change its answer while it runs by typing a status code (`500`, `429`, `401`, `400`, `302`), `slow` or `ok`.

```bash
node scripts/webhook-test-server.mjs --port 3919 --token mytoken --secret mysecret
```

The phone needs an **HTTPS** URL, so expose it with a tunnel:

```bash
ngrok http 3919
# or
cloudflared tunnel --url http://localhost:3919
```

Put `https://<tunnel-host>/hook` in the app's Webhook card, enter the same token and secret, press **Save & send test**, then turn the switch on.

Troubleshooting:

- **HTTP 403 on the test.** The request reached something that is not the test server. Check the port (`curl -i` the tunnel URL; a healthy test server answers `401` when a token is set). A tunnel to a port used by another dev server returns that server's own error page, which the app shows after the status code.
- **HTTP 401.** The token or secret in the app differs from the server's `--token` or `--secret`.
- **"The URL must start with https://".** Use the tunnel's `https://` address, not `http://localhost`.
- A quick tunnel is reachable by anyone who has its address while it runs. Use test data and stop the tunnel afterwards.
