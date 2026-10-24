// A tiny receiver for testing the PesaCast phone webhook (docs/webhooks.md). No dependencies.
//
//   node scripts/webhook-test-server.mjs [--port 3000] [--secret S] [--token T]
//
// It verifies the bearer token and HMAC signature when you pass them, ignores test pings, flags
// duplicates by Idempotency-Key, and lets you change its answer while it runs by typing a status
// code and pressing Enter ("500", "429", "401", "400", "302", "200"), or "slow" / "ok".
// The phone needs an HTTPS URL, so put a tunnel in front (see docs/webhooks.md).
import http from "node:http";
import crypto from "node:crypto";
import readline from "node:readline";

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const PORT = Number(opt("port", process.env.PORT ?? 3000));
const SECRET = opt("secret", process.env.PESACAST_SECRET);
const TOKEN = opt("token", process.env.PESACAST_TOKEN);

let forced = null; // status code forced by the operator, or "slow"
const seen = new Set();
let count = 0;

const stamp = () => new Date().toISOString().slice(11, 23);
const log = (...a) => console.log(`[${stamp()}]`, ...a);

const server = http
  .createServer((req, res) => {
    if (req.method === "GET") {
      res.writeHead(200, { "content-type": "text/plain" });
      return res.end("pesacast webhook test server\n");
    }
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", async () => {
      const body = Buffer.concat(chunks);
      const n = ++count;
      const reply = (code, why) => {
        log(`#${n} -> ${code}${why ? ` (${why})` : ""}`);
        res.writeHead(code);
        res.end();
      };

      if (TOKEN && req.headers.authorization !== `Bearer ${TOKEN}`) return reply(401, "bad or missing bearer token");

      if (SECRET) {
        const ts = req.headers["x-pesacast-timestamp"] ?? "";
        const sig = String(req.headers["x-pesacast-signature"] ?? "");
        const expected = "v1=" + crypto.createHmac("sha256", SECRET).update(`${ts}.`).update(body).digest("hex");
        const good = sig.length === expected.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected));
        if (!good) return reply(401, "signature does not match");
        if (Math.abs(Date.now() / 1000 - Number(ts)) > 300) return reply(401, "timestamp too old");
      }

      let payload;
      try {
        payload = JSON.parse(body.toString("utf8"));
      } catch {
        return reply(400, "body is not JSON");
      }

      if (payload.test) {
        log(`#${n} TEST PING (source=${payload.source}), nothing recorded`);
        return reply(forced && forced !== "slow" ? forced : 200, forced ? `forced ${forced}` : "");
      }

      const key = req.headers["idempotency-key"];
      const duplicate = key && seen.has(key);
      const txns = payload.transactions ?? [];
      log(`#${n} ${duplicate ? "DUPLICATE " : ""}${txns.length} txn(s) key=${key} source=${payload.source}`);
      for (const t of txns) log(`     ${t.ref}  ${t.direction}  ${t.currency} ${t.amount}  ${t.from}`);

      if (forced === "slow") await new Promise((r) => setTimeout(r, 15_000)); // longer than the phone's 10 s timeout
      if (forced && forced !== "slow") return reply(forced, `forced ${forced}`); // not recorded: the phone will retry
      if (key) seen.add(key);
      reply(200);
    });
  })
  .listen(PORT, () => {
    log(`listening on http://localhost:${PORT}/hook`);
    log(`token check: ${TOKEN ? "on" : "off"}   signature check: ${SECRET ? "on" : "off"}`);
    log(`type a status code (500, 429, 401, 400, 302), "slow", or "ok" + Enter to change the answer`);
  });

server.on("error", (e) => {
  if (e.code === "EADDRINUSE") {
    console.error(`Port ${PORT} is already in use by another program. Try: --port ${PORT + 1}`);
  } else {
    console.error(e.message);
  }
  process.exit(1);
});

readline.createInterface({ input: process.stdin }).on("line", (line) => {
  const v = line.trim().toLowerCase();
  if (v === "ok" || v === "200" || v === "") forced = null;
  else if (v === "slow") forced = "slow";
  else if (/^\d{3}$/.test(v)) forced = Number(v);
  else return log(`unknown command "${v}"`);
  log(`now answering: ${forced ?? "normally (200)"}`);
});
