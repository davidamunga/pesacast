package com.pesacast.android.webhook

import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real sender against a real HTTP server on loopback. URL policy is relaxed only here. */
class WebhookDeliveryTest {
    @get:Rule val folder = TemporaryFolder()

    private class Received(val headers: Map<String, String>, val body: ByteArray)

    private val received = CopyOnWriteArrayList<Received>()
    private val responses = ArrayDeque<Int>()
    private val defaultResponse = AtomicInteger(200)
    private lateinit var server: MiniHttpServer
    private lateinit var scope: CoroutineScope
    private lateinit var queue: WebhookQueue
    private var cfg = WebhookConfig(true, "")

    @Before
    fun setUp() {
        server = MiniHttpServer { _, headers, body ->
            received.add(Received(headers, body))
            synchronized(responses) { responses.removeFirstOrNull() } ?: defaultResponse.get()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queue = WebhookQueue(File(folder.root, "q.json"))
        cfg = WebhookConfig(true, "http://127.0.0.1:${server.port}/hook", token = "tok", secret = "sec")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun delivery(backoff: (Int) -> Long = { 50 }, config: () -> WebhookConfig? = { cfg }) =
        WebhookDelivery(queue, config, UrlConnectionPoster(connectTimeoutMs = 1_000, readTimeoutMs = 1_000), scope,
            validateUrl = { null }, backoffMs = backoff)

    private fun respondWith(vararg codes: Int) = synchronized(responses) { codes.forEach { responses.addLast(it) } }

    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }

    private val txn = """{"type":"mpesa_txn","ref":"ABC123","time":"2026-10-24T10:00:00Z","amount":500.0}"""

    @Test
    fun `a transaction is posted with auth, idempotency and a valid signature, then removed`() {
        val d = delivery()
        d.enqueue(txn)
        eventually("delivered") { received.size == 1 && queue.size() == 0 }

        val r = received.single()
        assertEquals("application/json", r.headers["content-type"])
        assertEquals("Bearer tok", r.headers["authorization"])
        assertEquals("ABC123@2026-10-24T10:00:00Z", r.headers["idempotency-key"])
        val ts = r.headers["x-pesacast-timestamp"]!!.toLong()
        assertEquals(WebhookSigner.sign("sec", ts, r.body), r.headers["x-pesacast-signature"])
        val body = JsonParser.parseString(String(r.body)).asJsonObject
        assertEquals("android", body["source"].asString)
        assertEquals("ABC123", body.getAsJsonArray("transactions")[0].asJsonObject["ref"].asString)
        eventually("status") { d.status.value.lastSuccessAt != null && d.status.value.pending == 0 }
    }

    @Test
    fun `no token or secret means no auth or signature headers`() {
        cfg = cfg.copy(token = null, secret = null)
        delivery().enqueue(txn)
        eventually("delivered") { received.size == 1 }
        assertFalse(received.single().headers.containsKey("authorization"))
        assertFalse(received.single().headers.containsKey("x-pesacast-signature"))
    }

    @Test
    fun `server errors are retried until they succeed, keeping the same idempotency key`() {
        respondWith(500, 503)
        delivery().enqueue(txn)
        eventually("eventually delivered") { received.size == 3 && queue.size() == 0 }
        assertEquals(1, received.map { it.headers["idempotency-key"] }.toSet().size)
    }

    @Test
    fun `429 and 408 are retried`() {
        respondWith(429, 408)
        delivery().enqueue(txn)
        eventually("delivered") { received.size == 3 && queue.size() == 0 }
    }

    @Test
    fun `unauthorised pauses delivery, keeps the queue, and a kick resumes it`() {
        respondWith(401)
        val d = delivery()
        d.enqueue(txn)
        eventually("paused") { d.status.value.paused }
        assertEquals(1, queue.size())
        assertTrue(d.status.value.lastError!!.contains("credentials"))
        Thread.sleep(300)
        assertEquals("paused: no automatic retry", 1, received.size)

        d.kick()
        eventually("resumed and delivered") { queue.size() == 0 && received.size == 2 }
        assertFalse(d.status.value.paused)
    }

    @Test
    fun `other client errors drop the item and continue with the next`() {
        respondWith(400)
        val d = delivery()
        d.enqueue(txn)
        d.enqueue(txn.replace("ABC123", "XYZ789"))
        eventually("second delivered") { received.size == 2 && queue.size() == 0 }
        assertTrue(d.status.value.lastError == null || d.status.value.lastError!!.isNotEmpty())
    }

    @Test
    fun `redirects are not followed and the item is dropped`() {
        respondWith(302)
        val d = delivery()
        d.enqueue(txn)
        eventually("dropped") { queue.size() == 0 }
        assertEquals("exactly one request, no follow", 1, received.size)
        assertTrue(d.status.value.lastError!!.contains("redirect"))
    }

    @Test
    fun `items are delivered in order`() {
        val d = delivery()
        listOf("A1", "B2", "C3").forEach { d.enqueue(txn.replace("ABC123", it)) }
        eventually("all delivered") { received.size == 3 }
        assertEquals(
            listOf("A1", "B2", "C3"),
            received.map { JsonParser.parseString(String(it.body)).asJsonObject.getAsJsonArray("transactions")[0].asJsonObject["ref"].asString },
        )
    }

    @Test
    fun `nothing is queued or sent while the webhook is disabled`() {
        val d = delivery { cfg.copy(enabled = false) }
        d.enqueue(txn)
        Thread.sleep(200)
        assertEquals(0, queue.size())
        assertTrue(received.isEmpty())
    }

    @Test
    fun `queued items survive a restart and are sent by a new sender`() {
        defaultResponse.set(500)
        val first = delivery(backoff = { 60_000 })
        first.enqueue(txn)
        eventually("attempted once") { received.size >= 1 }
        scope.cancel()
        assertEquals(1, WebhookQueue(File(folder.root, "q.json")).size())

        defaultResponse.set(200)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val second = WebhookDelivery(
            WebhookQueue(File(folder.root, "q.json")), { cfg }, UrlConnectionPoster(), scope, validateUrl = { null },
        )
        second.kick()
        eventually("sent after restart") { WebhookQueue(File(folder.root, "q.json")).size() == 0 }
    }

    @Test
    fun `an unreachable server is retried, not dropped`() {
        server.stop()
        val d = delivery(backoff = { 60_000 })
        d.enqueue(txn)
        eventually("error recorded") { d.status.value.lastError != null }
        assertEquals(1, queue.size())
        assertFalse(d.status.value.paused)
    }

    @Test
    fun `a disallowed url pauses delivery without sending anything`() {
        val d = WebhookDelivery(queue, { cfg.copy(url = "http://example.com/hook") }, UrlConnectionPoster(), scope)
        d.enqueue(txn)
        eventually("paused") { d.status.value.paused }
        assertEquals(1, queue.size())
        assertTrue(received.isEmpty())
    }

    @Test
    fun `send test posts an empty test payload and does not touch the queue`() {
        val d = delivery()
        val result = d.sendTest(cfg)
        assertTrue(result is TestResult.Success)
        val body = JsonParser.parseString(String(received.single().body)).asJsonObject
        assertTrue(body["test"].asBoolean)
        assertEquals(0, queue.size())
    }

    @Test
    fun `send test reports a rejected credential`() {
        respondWith(401)
        val result = delivery().sendTest(cfg)
        assertTrue(result is TestResult.Failure)
        assertTrue((result as TestResult.Failure).message.contains("credentials"))
    }

    @Test
    fun `send test refuses a non https url with the real policy`() {
        val d = WebhookDelivery(queue, { cfg }, UrlConnectionPoster(), scope)
        val result = d.sendTest(cfg)
        assertTrue(result is TestResult.Failure)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `status starts with the persisted pending count`() {
        queue.add(QueuedWebhook("i", "k", txn, 0))
        assertEquals(1, delivery().status.value.pending)
        assertNull(delivery().status.value.lastError)
    }
}
