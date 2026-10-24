package com.pesacast.android.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebhookSignerTest {
    @Test
    fun `signature matches an independently computed hmac`() {
        // node: createHmac("sha256","s3cret").update('1700000000.{"a":1}').digest("hex")
        assertEquals(
            "v1=1698a50bc74d1ff1db85c4e0a5297c2ad9fdba245d5737cdb789e4cc6e098940",
            WebhookSigner.sign("s3cret", 1_700_000_000L, """{"a":1}""".toByteArray()),
        )
    }

    @Test
    fun `different secret timestamp or body changes the signature`() {
        val base = WebhookSigner.sign("s3cret", 1L, "x".toByteArray())
        assertTrue(base != WebhookSigner.sign("other", 1L, "x".toByteArray()))
        assertTrue(base != WebhookSigner.sign("s3cret", 2L, "x".toByteArray()))
        assertTrue(base != WebhookSigner.sign("s3cret", 1L, "y".toByteArray()))
    }
}

class WebhookUrlTest {
    @Test
    fun `https urls are accepted`() {
        assertNull(WebhookUrl.validate("https://example.com/hook"))
        assertNull(WebhookUrl.validate("  https://example.com:8443/a?b=c  "))
    }

    @Test
    fun `plain http, blanks, other schemes and credentials are refused`() {
        assertNotNull(WebhookUrl.validate("http://example.com/hook"))
        assertNotNull(WebhookUrl.validate("http://192.168.0.5/hook"))
        assertNotNull(WebhookUrl.validate(""))
        assertNotNull(WebhookUrl.validate("   "))
        assertNotNull(WebhookUrl.validate("ftp://example.com"))
        assertNotNull(WebhookUrl.validate("https://"))
        assertNotNull(WebhookUrl.validate("https://user:pw@example.com/hook"))
        assertNotNull(WebhookUrl.validate("not a url"))
        assertNotNull(WebhookUrl.validate("https://example.com/" + "a".repeat(3000)))
    }
}

class WebhookPayloadTest {
    private val txn = """{"type":"mpesa_txn","ref":"ABC123","time":"2026-10-24T10:00:00Z","amount":500.0}"""

    @Test
    fun `transaction body matches the desktop envelope plus source`() {
        val body = com.google.gson.JsonParser.parseString(String(WebhookPayload.transaction(txn, "dev-1"))).asJsonObject
        assertEquals(1, body["count"].asInt)
        assertEquals("android", body["source"].asString)
        assertEquals(1, body["schema_version"].asInt)
        assertEquals("dev-1", body["device_id"].asString)
        assertEquals("ABC123", body.getAsJsonArray("transactions")[0].asJsonObject["ref"].asString)
        assertTrue(body.has("exported_at"))
        assertTrue(!body.has("test"))
    }

    @Test
    fun `test body carries no transactions`() {
        val body = com.google.gson.JsonParser.parseString(String(WebhookPayload.test("dev-1"))).asJsonObject
        assertTrue(body["test"].asBoolean)
        assertEquals(0, body["count"].asInt)
        assertEquals(0, body.getAsJsonArray("transactions").size())
    }

    @Test
    fun `idempotency key is ref at time, or null when either is missing`() {
        assertEquals("ABC123@2026-10-24T10:00:00Z", WebhookPayload.idempotencyKey(txn))
        assertNull(WebhookPayload.idempotencyKey("""{"ref":"ABC"}"""))
        assertNull(WebhookPayload.idempotencyKey("not json"))
    }
}

class WebhookQueueTest {
    @get:Rule val folder = TemporaryFolder()

    private fun item(n: Int, at: Long = 1_000L) =
        QueuedWebhook("id$n", "key$n", """{"n":$n}""", at)

    private fun queue(file: File = File(folder.root, "q.json"), max: Int = 500, now: () -> Long = { 1_000L }) =
        WebhookQueue(file, maxItems = max, maxAgeMs = 100, now = now)

    @Test
    fun `items come out in order and survive a restart`() {
        val file = File(folder.root, "q.json")
        queue(file).apply { add(item(1)); add(item(2)) }

        val reloaded = queue(file)
        assertEquals(2, reloaded.size())
        assertEquals("id1", reloaded.peek()!!.id)
        reloaded.remove("id1")
        assertEquals("id2", queue(file).peek()!!.id)
    }

    @Test
    fun `the oldest item is dropped when full`() {
        val q = queue(max = 3)
        (1..5).forEach { q.add(item(it)) }
        assertEquals(3, q.size())
        assertEquals("id3", q.peek()!!.id)
    }

    @Test
    fun `an item with the same key is not queued twice`() {
        val q = queue()
        q.add(item(1))
        q.add(item(1).copy(id = "other"))
        assertEquals(1, q.size())
    }

    @Test
    fun `expired items are purged`() {
        var now = 1_000L
        val q = queue(now = { now })
        q.add(item(1, at = 1_000L))
        now = 1_050L
        q.add(item(2, at = 1_050L))
        now = 1_120L // item 1 is 120 ms old (> 100), item 2 is 70 ms old
        assertEquals(1, q.purgeExpired())
        assertEquals("id2", q.peek()!!.id)
    }

    @Test
    fun `attempts are counted and persisted`() {
        val file = File(folder.root, "q.json")
        queue(file).apply { add(item(1)); recordAttempt("id1"); recordAttempt("id1") }
        assertEquals(2, queue(file).peek()!!.attempts)
    }

    @Test
    fun `a corrupt file starts an empty queue instead of crashing`() {
        val file = File(folder.root, "q.json").apply { writeText("{not json") }
        assertEquals(0, queue(file).size())
    }
}

class WebhookSnippetTest {
    @Test
    fun `takes the first non-empty line`() {
        assertEquals("Blocked request", snippetOf("\n\n  Blocked request  \nsecond".toByteArray()))
    }

    @Test
    fun `truncates long text and removes control characters`() {
        val long = snippetOf(("a".repeat(300)).toByteArray())
        assertEquals(100, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("abc", snippetOf("a\u0000b\u001bc".toByteArray()))
    }

    @Test
    fun `empty or binary-ish input gives an empty hint`() {
        assertEquals("", snippetOf(ByteArray(0)))
        assertEquals("", snippetOf("\n \n".toByteArray()))
    }
}

class WebhookBackoffTest {
    @Test
    fun `backoff doubles from 5s and caps at 15 minutes`() {
        assertEquals(5_000L, defaultBackoffMs(1))
        assertEquals(10_000L, defaultBackoffMs(2))
        assertEquals(20_000L, defaultBackoffMs(3))
        assertEquals(15 * 60 * 1000L, defaultBackoffMs(30))
        assertEquals(5_000L, defaultBackoffMs(0))
    }
}
