#!/usr/bin/env python3
"""Reference receiver for PesaCast webhooks. Python standard library only (SQLite for storage).

    PESACAST_SECRET=... PESACAST_TOKEN=... python3 receiver.py --port 8080 --db receiver.sqlite3

What it demonstrates (docs/webhooks.md, docs/openapi/pesacast-webhook.yaml):
  * authenticate with the HMAC signature (phone) and/or the bearer token (phone or desktop),
  * reject stale timestamps,
  * answer test pings without recording anything,
  * de-duplicate in the database: PRIMARY KEY (ref, time) means a retry, or the same transaction
    sent by both the phone and the desktop, is stored once,
  * keep the raw event for audit and replay,
  * pick status codes on purpose: 2xx accepted, 401 bad credentials (the phone pauses and keeps its
    queue), 400 only for a body that can never be processed (the phone drops that item).

This is a starting point, not a hardened service: put it behind HTTPS (the phone refuses http://),
add rate limiting, and run it as an unprivileged user.
"""
import argparse
import hashlib
import hmac
import json
import os
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 1 << 20  # 1 MiB; a real payload is a few hundred bytes
MAX_CLOCK_SKEW_SECONDS = 300
SUPPORTED_SCHEMA_VERSION = 1


def sign(secret: str, timestamp: str, body: bytes) -> str:
    """`v1=` plus the hex HMAC-SHA256 of `<timestamp>.` followed by the raw body."""
    mac = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256)
    return "v1=" + mac.hexdigest()


class Config:
    def __init__(self, secret=None, token=None, max_skew=MAX_CLOCK_SKEW_SECONDS, allow_anonymous=False):
        self.secret = secret or None
        self.token = token or None
        self.max_skew = max_skew
        self.allow_anonymous = allow_anonymous  # testing only


def authenticated(headers, body: bytes, config: Config, now: float) -> bool:
    """True if the request proves itself by a fresh valid signature or by the bearer token."""
    if config.allow_anonymous:
        return True
    if config.secret:
        timestamp = headers.get("X-PesaCast-Timestamp", "")
        signature = headers.get("X-PesaCast-Signature", "")
        try:
            fresh = abs(now - float(timestamp)) <= config.max_skew
        except ValueError:
            fresh = False
        if fresh and signature and hmac.compare_digest(signature, sign(config.secret, timestamp, body)):
            return True
    if config.token:
        supplied = headers.get("Authorization", "")
        if hmac.compare_digest(supplied, f"Bearer {config.token}"):
            return True
    return False


class Store:
    def __init__(self, path: str):
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.executescript(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                received_at REAL NOT NULL,
                source TEXT, device_id TEXT, idempotency_key TEXT,
                raw TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS transactions (
                ref TEXT NOT NULL, time TEXT NOT NULL,
                direction TEXT, amount REAL, currency TEXT, counterparty TEXT,
                balance REAL, transaction_cost REAL,
                first_source TEXT, first_device_id TEXT, received_at REAL NOT NULL,
                PRIMARY KEY (ref, time)
            );
            """
        )

    def record(self, raw: str, payload: dict, now: float, idempotency_key=None, parse=True) -> int:
        """Stores the raw event and each new transaction. Returns how many transactions were new."""
        inserted = 0
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO events (received_at, source, device_id, idempotency_key, raw) VALUES (?,?,?,?,?)",
                (now, payload.get("source"), payload.get("device_id"), idempotency_key, raw),
            )
            if parse:
                for t in payload.get("transactions", []):
                    cur = self._db.execute(
                        "INSERT OR IGNORE INTO transactions VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        (
                            t["ref"], t["time"], t.get("direction"), t.get("amount"), t.get("currency"),
                            t.get("from"), t.get("balance"), t.get("transaction_cost"),
                            payload.get("source"), payload.get("device_id"), now,
                        ),
                    )
                    inserted += cur.rowcount
        return inserted

    def count(self, table: str) -> int:
        return self._db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]  # table is internal

    def close(self):
        self._db.close()


def process(headers, body: bytes, store: Store, config: Config, now: float | None = None):
    """The whole decision logic. Returns (http_status, short_message)."""
    now = time.time() if now is None else now

    if not authenticated(headers, body, config, now):
        return 401, "unauthorized"  # the phone pauses and keeps its queue

    try:
        payload = json.loads(body)
        if not isinstance(payload, dict):
            raise ValueError("not an object")
    except ValueError:
        return 400, "body is not a JSON object"  # can never be processed; the phone drops it

    if payload.get("test") is True:
        return 204, "test ping"  # connectivity check: never recorded

    raw = body.decode("utf-8", "replace")
    key = headers.get("Idempotency-Key")

    if payload.get("schema_version") != SUPPORTED_SCHEMA_VERSION:
        # A newer format we do not understand yet: keep it for later instead of rejecting it
        # (a 4xx would make the phone drop the transaction).
        store.record(raw, payload, now, key, parse=False)
        return 200, "stored unparsed (unknown schema_version)"

    try:
        new = store.record(raw, payload, now, key)
    except (KeyError, TypeError, sqlite3.Error):
        # Malformed transaction inside a valid envelope: nothing we can do on a retry.
        return 400, "malformed transaction"
    return 200, f"{new} new transaction(s)"


def make_handler(store: Store, config: Config):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0") or 0)
            if length > MAX_BODY_BYTES:
                self.send_response(413)
                self.end_headers()
                return
            body = self.rfile.read(length)
            status, message = process(self.headers, body, store, config)
            print(f"{self.log_date_time_string()} {status} {message}", flush=True)
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"pesacast reference receiver\n")

        def log_message(self, *_):  # we log one line per request ourselves, without headers or bodies
            pass

    return Handler


def serve(db_path: str, host: str, port: int, config: Config) -> ThreadingHTTPServer:
    store = Store(db_path)
    return ThreadingHTTPServer((host, port), make_handler(store, config))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--db", default="receiver.sqlite3")
    parser.add_argument("--insecure-no-auth", action="store_true", help="accept unauthenticated requests (testing only)")
    args = parser.parse_args(argv)

    config = Config(os.environ.get("PESACAST_SECRET"), os.environ.get("PESACAST_TOKEN"))
    if not (config.secret or config.token) and not args.insecure_no_auth:
        print("Set PESACAST_SECRET and/or PESACAST_TOKEN (or pass --insecure-no-auth for testing).", file=sys.stderr)
        return 2
    if args.insecure_no_auth:
        config = Config(allow_anonymous=True)
        print("WARNING: authentication is off", file=sys.stderr)

    server = serve(args.db, args.host, args.port, config)
    print(f"listening on http://{args.host}:{server.server_address[1]} (put HTTPS in front of it)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
