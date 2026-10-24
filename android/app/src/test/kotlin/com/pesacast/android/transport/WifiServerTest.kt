package com.pesacast.android.transport

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real WebSocket server on loopback and talks to it with a real client. */
class WifiServerTest {

    private val token = "test-token"
    private val authenticated = CopyOnWriteArraySet<org.java_websocket.WebSocket>()
    private val authCount = AtomicInteger()
    private lateinit var server: PesaCastWsServer

    private class TestClient(port: Int) : WebSocketClient(URI("ws://127.0.0.1:$port")) {
        val messages = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        @Volatile var closeCode = -1
        private val opened = CountDownLatch(1)

        override fun onOpen(handshakedata: ServerHandshake?) = opened.countDown()
        override fun onMessage(message: String) { messages.add(message) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            closeCode = code
            closed.countDown()
        }
        override fun onError(ex: Exception?) {}

        fun open(): TestClient {
            assertTrue("connect", connectBlocking(3, TimeUnit.SECONDS))
            return this
        }
        fun next(): String? = messages.poll(3, TimeUnit.SECONDS)
        fun awaitClose() = closed.await(3, TimeUnit.SECONDS)
    }

    private fun startServer(authTimeoutMs: Long = 5_000, guard: AuthGuard = AuthGuard(token)) {
        server = PesaCastWsServer(
            port = 0,
            token = token,
            allowLegacy = { true },
            authenticatedClients = authenticated,
            onClientAuthenticated = { authCount.set(it) },
            onClientDisconnected = { authCount.set(it) },
            onServerError = { throw it },
            authTimeoutMs = authTimeoutMs,
            guard = guard,
        )
        server.start()
        eventually("server bound") { server.port != 0 }
    }

    /** The server replies before it updates its callbacks, so assertions on them must wait. */
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.shutdownTimers()
            server.stop(200)
        }
    }

    @Test
    fun `valid token is acknowledged and registered`() {
        startServer()
        val client = TestClient(server.port).open()
        client.send("""{"auth":"$token"}""")
        assertEquals("""{"status":"ok"}""", client.next())
        assertEquals(1, authenticated.size)
        eventually("auth callback") { authCount.get() == 1 }
        client.close()
    }

    @Test
    fun `wrong token closes the connection without registering`() {
        startServer()
        val client = TestClient(server.port).open()
        client.send("""{"auth":"nope"}""")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `malformed first message closes the connection`() {
        startServer()
        val client = TestClient(server.port).open()
        client.send("this is not json")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `missing auth field closes the connection`() {
        startServer()
        val client = TestClient(server.port).open()
        client.send("""{"hello":"world"}""")
        assertTrue(client.awaitClose())
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `client that never authenticates is dropped after the timeout`() {
        startServer(authTimeoutMs = 300)
        val client = TestClient(server.port).open()
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
    }

    @Test
    fun `authenticated client is not dropped by the auth timeout`() {
        startServer(authTimeoutMs = 300)
        val client = TestClient(server.port).open()
        client.send("""{"auth":"$token"}""")
        assertEquals("""{"status":"ok"}""", client.next())
        Thread.sleep(600)
        assertTrue(client.isOpen)
        client.close()
    }

    @Test
    fun `ip is refused after repeated bad tokens`() {
        startServer(guard = AuthGuard(token, maxFailures = 2))
        repeat(2) {
            val bad = TestClient(server.port).open()
            bad.send("""{"auth":"nope"}""")
            assertTrue(bad.awaitClose())
        }
        // Third attempt: the server closes the socket during the handshake, before any auth
        // message can be sent, so only assert that it is closed and nothing was registered.
        val blocked = TestClient(server.port)
        blocked.connectBlocking(3, TimeUnit.SECONDS)
        assertTrue(blocked.awaitClose())
        assertEquals(1008, blocked.closeCode)
        assertFalse(authenticated.any { it.isOpen })
    }

    @Test
    fun `disconnect of an authenticated client updates the count`() {
        startServer()
        val client = TestClient(server.port).open()
        client.send("""{"auth":"$token"}""")
        client.next()
        client.closeBlocking()
        eventually("client removed") { authenticated.isEmpty() }
        eventually("disconnect callback") { authCount.get() == 0 }
    }
}
