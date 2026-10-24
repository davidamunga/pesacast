import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from receiver import Config, Store, authenticated, process, serve, sign

NOW = 1_700_000_000.0
SECRET = "s3cret"
TOKEN = "tok"


def txn(ref="ABC123", time="2026-10-24T10:00:00+03:00", **extra):
    return {"type": "mpesa_txn", "direction": "received", "amount": 500.0, "currency": "KES",
            "from": "JANE", "ref": ref, "time": time, "balance": 1500.0, **extra}


def body(transactions, source="android", device="dev-1", **extra):
    payload = {"schema_version": 1, "source": source, "device_id": device,
               "exported_at": "2026-10-24T10:00:01Z", "transactions": transactions,
               "count": len(transactions), **extra}
    return json.dumps(payload).encode()


def phone_headers(b, secret=SECRET, now=NOW, key=None):
    ts = str(int(now))
    return {"X-PesaCast-Timestamp": ts, "X-PesaCast-Signature": sign(secret, ts, b),
            "Idempotency-Key": key or "ABC123@2026-10-24T10:00:00+03:00"}


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "r.sqlite3")
        self.store = Store(self.path)
        self.config = Config(secret=SECRET, token=TOKEN)

    def tearDown(self):
        self.store.close()
        self.dir.cleanup()

    def post(self, headers, b, now=NOW):
        return process(headers, b, self.store, self.config, now)

    def test_signature_matches_the_android_implementation(self):
        # The same vector is asserted in the Kotlin tests: HMAC-SHA256("s3cret", '1700000000.{"a":1}').
        self.assertEqual(
            "v1=1698a50bc74d1ff1db85c4e0a5297c2ad9fdba245d5737cdb789e4cc6e098940",
            sign("s3cret", "1700000000", b'{"a":1}'),
        )

    def test_a_signed_transaction_is_stored(self):
        b = body([txn()])
        self.assertEqual(200, self.post(phone_headers(b), b)[0])
        self.assertEqual(1, self.store.count("transactions"))
        self.assertEqual(1, self.store.count("events"))

    def test_a_retry_is_acknowledged_but_stored_once(self):
        b = body([txn()])
        self.post(phone_headers(b), b)
        self.assertEqual(200, self.post(phone_headers(b), b)[0])
        self.assertEqual(1, self.store.count("transactions"))
        self.assertEqual(2, self.store.count("events"), "the raw delivery is still kept for audit")

    def test_the_same_transaction_from_phone_and_desktop_is_stored_once(self):
        phone = body([txn()], source="android", device="phone-1")
        desktop = body([txn()], source="desktop", device="desktop-1")
        self.post(phone_headers(phone), phone)
        self.assertEqual(200, self.post({"Authorization": f"Bearer {TOKEN}"}, desktop)[0])
        self.assertEqual(1, self.store.count("transactions"))

    def test_a_batch_stores_each_transaction(self):
        b = body([txn("A"), txn("B"), txn("A")], source="desktop")
        self.assertEqual(200, self.post({"Authorization": f"Bearer {TOKEN}"}, b)[0])
        self.assertEqual(2, self.store.count("transactions"))

    def test_a_bad_signature_is_rejected_and_stores_nothing(self):
        b = body([txn()])
        h = phone_headers(b)
        h["X-PesaCast-Signature"] = "v1=" + "0" * 64
        self.assertEqual(401, self.post(h, b)[0])
        self.assertEqual(0, self.store.count("events"))

    def test_a_signature_for_a_different_body_is_rejected(self):
        b = body([txn()])
        other = body([txn("OTHER")])
        self.assertEqual(401, self.post(phone_headers(b), other)[0])

    def test_a_stale_timestamp_is_rejected(self):
        b = body([txn()])
        self.assertEqual(401, self.post(phone_headers(b, now=NOW - 3600), b)[0])
        self.assertEqual(401, self.post(phone_headers(b, now=NOW + 3600), b)[0])

    def test_a_missing_or_garbled_timestamp_is_rejected(self):
        b = body([txn()])
        h = phone_headers(b)
        h["X-PesaCast-Timestamp"] = "soon"
        self.assertEqual(401, self.post(h, b)[0])
        self.assertEqual(401, self.post({"X-PesaCast-Signature": h["X-PesaCast-Signature"]}, b)[0])

    def test_the_wrong_secret_is_rejected(self):
        b = body([txn()])
        self.assertEqual(401, self.post(phone_headers(b, secret="other"), b)[0])

    def test_bearer_token_alone_authenticates_and_a_wrong_one_does_not(self):
        b = body([txn()], source="desktop")
        self.assertEqual(200, self.post({"Authorization": f"Bearer {TOKEN}"}, b)[0])
        self.assertEqual(401, self.post({"Authorization": "Bearer nope"}, b)[0])
        self.assertEqual(401, self.post({}, b)[0])

    def test_with_only_a_token_configured_a_signature_is_not_enough(self):
        self.config = Config(token=TOKEN)
        b = body([txn()])
        self.assertEqual(401, self.post(phone_headers(b), b)[0])

    def test_a_test_ping_is_acknowledged_and_never_recorded(self):
        b = body([], test=True)
        self.assertEqual(204, self.post(phone_headers(b, key="test-1"), b)[0])
        self.assertEqual(0, self.store.count("events"))
        self.assertEqual(0, self.store.count("transactions"))

    def test_an_unknown_schema_version_is_kept_unparsed_and_acknowledged(self):
        b = json.dumps({"schema_version": 2, "source": "android", "device_id": "d", "transactions": [{"new": "shape"}]}).encode()
        self.assertEqual(200, self.post(phone_headers(b), b)[0])
        self.assertEqual(1, self.store.count("events"))
        self.assertEqual(0, self.store.count("transactions"))

    def test_a_body_that_is_not_json_is_a_400(self):
        b = b"not json"
        self.assertEqual(400, self.post(phone_headers(b), b)[0])
        b = b"[1,2]"
        self.assertEqual(400, self.post(phone_headers(b), b)[0])

    def test_a_transaction_missing_its_identity_is_a_400_and_leaves_nothing_behind(self):
        b = body([{"amount": 1.0}])
        self.assertEqual(400, self.post(phone_headers(b), b)[0])
        self.assertEqual(0, self.store.count("events"), "the event insert is rolled back with it")

    def test_data_survives_a_restart(self):
        b = body([txn()])
        self.post(phone_headers(b), b)
        self.store.close()
        self.store = Store(self.path)
        self.assertEqual(1, self.store.count("transactions"))
        self.assertEqual(200, self.post(phone_headers(b), b)[0])
        self.assertEqual(1, self.store.count("transactions"))

    def test_anonymous_mode_is_only_on_when_asked_for(self):
        self.assertFalse(authenticated({}, b"", Config(), NOW))
        self.assertTrue(authenticated({}, b"", Config(allow_anonymous=True), NOW))


class ServerTest(unittest.TestCase):
    """One round trip over real HTTP, to make sure the handler wiring and status codes are right."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.server = serve(os.path.join(self.dir.name, "r.sqlite3"), "127.0.0.1", 0, Config(secret=SECRET))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/pesacast/webhook"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.dir.cleanup()

    def send(self, b, headers):
        req = urllib.request.Request(self.url, data=b, headers={"Content-Type": "application/json", **headers}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status
        except urllib.error.HTTPError as e:
            e.close()
            return e.code

    def test_status_codes_over_http(self):
        import time
        b = body([txn()])
        now = time.time()
        self.assertEqual(200, self.send(b, phone_headers(b, now=now)))
        self.assertEqual(401, self.send(b, {}))
        ping = body([], test=True)
        self.assertEqual(204, self.send(ping, phone_headers(ping, now=now, key="test-1")))


if __name__ == "__main__":
    unittest.main()
