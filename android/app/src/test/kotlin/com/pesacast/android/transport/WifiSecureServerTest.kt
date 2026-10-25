package com.pesacast.android.transport

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the v2 encrypted handshake end to end against the real server on loopback. */
class WifiSecureServerTest {

    private val token = "secure-test-token"
    private val authenticated = CopyOnWriteArraySet<org.java_websocket.WebSocket>()
    private val authCount = AtomicInteger()
    private lateinit var server: PesaCastWsServer

    private class SecureClient(port: Int, private val token: String) :
        WebSocketClient(URI("ws://127.0.0.1:$port")) {
        val texts = LinkedBlockingQueue<String>()
        val binaries = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)
        @Volatile var closeCode = -1

        private val keyPair = WifiCrypto.generateKeyPair()
        private val pub = WifiCrypto.encodePublic(keyPair.public as ECPublicKey)
        private val nonce = WifiCrypto.randomNonce()
        private var sendKey = ByteArray(0)
        private var receiveKey = ByteArray(0)
        private var sendCounter = 0L
        private var receiveCounter = 0L

        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String) { texts.add(message) }
        override fun onMessage(bytes: ByteBuffer) { binaries.add(ByteArray(bytes.remaining()).also(bytes::get)) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            closeCode = code
            closed.countDown()
        }
        override fun onError(ex: Exception?) {}

        fun open() = apply { assertTrue(connectBlocking(3, TimeUnit.SECONDS)) }

        fun hello(): String {
            val enc = Base64.getEncoder()
            send("""{"v":2,"hello":{"pub":"${enc.encodeToString(pub)}","nonce":"${enc.encodeToString(nonce)}"}}""")
            return texts.poll(3, TimeUnit.SECONDS) ?: error("no server hello")
        }

        /** Completes the handshake: derive keys from the server hello and send `ready`. */
        fun handshake(): SecureClient {
            val hello = com.google.gson.JsonParser.parseString(hello()).asJsonObject.getAsJsonObject("hello")
            val dec = Base64.getDecoder()
            val serverPub = dec.decode(hello["pub"].asString)
            val serverNonce = dec.decode(hello["nonce"].asString)
            val shared = WifiCrypto.sharedSecret(keyPair.private, WifiCrypto.decodePublic(serverPub))
            val (c2s, s2c) = WifiCrypto.deriveKeys(shared, token, pub, serverPub, nonce, serverNonce)
            sendKey = c2s
            receiveKey = s2c
            return this
        }

        fun sendRecord(json: String) {
            send(WifiCrypto.seal(sendKey, WifiCrypto.DIR_CLIENT_TO_SERVER, sendCounter++, json.toByteArray()))
        }

        fun nextRecord(): String? {
            val bytes = binaries.poll(3, TimeUnit.SECONDS) ?: return null
            val plain = WifiCrypto.open(receiveKey, WifiCrypto.DIR_SERVER_TO_CLIENT, receiveCounter, bytes) ?: return null
            receiveCounter++
            return String(plain)
        }

        fun awaitClose() = closed.await(3, TimeUnit.SECONDS)
    }

    private fun startServer(authTimeoutMs: Long = 5_000, guard: AuthGuard = AuthGuard()) {
        server = PesaCastWsServer(
            port = 0,
            token = token,
            authenticatedClients = authenticated,
            onClientAuthenticated = { authCount.set(it) },
            onClientDisconnected = { authCount.set(it) },
            onServerError = { throw it },
            authTimeoutMs = authTimeoutMs,
            guard = guard,
        )
        server.start()
        // Until the socket is bound, getPort() reports -1 (not 0), so "!= 0" passes too early and the
        // first client connects to a port that is not listening yet.
        eventually("bound") { server.port > 0 }
    }

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
    fun `encrypted client authenticates and receives transactions as encrypted records`() {
        startServer()
        val client = SecureClient(server.port, token).open().handshake()
        client.sendRecord("""{"ready":true}""")
        assertEquals("""{"status":"ok"}""", client.nextRecord())
        eventually("registered") { authenticated.size == 1 && authCount.get() == 1 }

        server.sendToAuthenticated("""{"type":"mpesa_txn","amount":500}""")
        assertEquals("""{"type":"mpesa_txn","amount":500}""", client.nextRecord())
        // Nothing readable was sent as text after the hello.
        assertTrue(client.texts.isEmpty())
        client.close()
    }

    @Test
    fun `wrong token fails authentication and counts as a failed attempt`() {
        startServer(guard = AuthGuard(maxFailures = 1))
        val client = SecureClient(server.port, "not-the-token").open().handshake()
        client.sendRecord("""{"ready":true}""")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
        assertTrue(authenticated.isEmpty())

        // The single allowed failure is used up, so the IP is now refused at connect time.
        val blocked = SecureClient(server.port, token)
        blocked.connectBlocking(3, TimeUnit.SECONDS)
        assertTrue(blocked.awaitClose())
        assertEquals(1008, blocked.closeCode)
    }

    @Test
    fun `a record that is not ready is rejected`() {
        startServer()
        val client = SecureClient(server.port, token).open().handshake()
        client.sendRecord("""{"hello":"world"}""")
        assertTrue(client.awaitClose())
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `client records after ready are ignored`() {
        startServer()
        val client = SecureClient(server.port, token).open().handshake()
        client.sendRecord("""{"ready":true}""")
        client.nextRecord()
        client.sendRecord("""{"anything":1}""")
        Thread.sleep(200)
        assertTrue(client.isOpen)
        assertFalse(authenticated.isEmpty())
        client.close()
    }

    @Test
    fun `binary frame before a hello closes the connection`() {
        startServer()
        val client = SecureClient(server.port, token).open()
        client.send(ByteArray(40))
        assertTrue(client.awaitClose())
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `hello with an invalid public key is refused`() {
        startServer()
        val client = SecureClient(server.port, token).open()
        val enc = Base64.getEncoder()
        client.send("""{"v":2,"hello":{"pub":"${enc.encodeToString(ByteArray(65))}","nonce":"${enc.encodeToString(ByteArray(16))}"}}""")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
    }

    @Test
    fun `unsupported protocol version is refused`() {
        startServer()
        val client = SecureClient(server.port, token).open()
        client.send("""{"v":3,"hello":{"pub":"AA==","nonce":"AA=="}}""")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
    }

    @Test
    fun `v1 plaintext auth is refused as a bad request and not registered`() {
        startServer()
        val client = SecureClient(server.port, token).open()
        client.send("""{"auth":"$token"}""")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
        assertTrue(authenticated.isEmpty())
        assertTrue("no plaintext ack", client.texts.isEmpty())
    }

    @Test
    fun `malformed first message closes the connection`() {
        startServer()
        val client = SecureClient(server.port, token).open()
        client.send("this is not json")
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `first message without a version closes the connection`() {
        startServer()
        val client = SecureClient(server.port, token).open()
        client.send("""{"hello":"world"}""")
        assertTrue(client.awaitClose())
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `client that never authenticates is dropped after the timeout`() {
        startServer(authTimeoutMs = 300)
        val client = SecureClient(server.port, token).open()
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
    }

    @Test
    fun `client that stalls after the hello is dropped after the timeout`() {
        startServer(authTimeoutMs = 300)
        val client = SecureClient(server.port, token).open().handshake()
        assertTrue(client.awaitClose())
        assertEquals(1008, client.closeCode)
    }

    @Test
    fun `authenticated client is not dropped by the auth timeout`() {
        startServer(authTimeoutMs = 300)
        val client = SecureClient(server.port, token).open().handshake()
        client.sendRecord("""{"ready":true}""")
        assertEquals("""{"status":"ok"}""", client.nextRecord())
        Thread.sleep(600)
        assertTrue(client.isOpen)
        client.close()
    }

    @Test
    fun `ip is refused after repeated failed attempts`() {
        startServer(guard = AuthGuard(maxFailures = 2))
        repeat(2) {
            val bad = SecureClient(server.port, "wrong").open().handshake()
            bad.sendRecord("""{"ready":true}""")
            assertTrue(bad.awaitClose())
        }
        val blocked = SecureClient(server.port, token)
        blocked.connectBlocking(3, TimeUnit.SECONDS)
        assertTrue(blocked.awaitClose())
        assertEquals(1008, blocked.closeCode)
        assertFalse(authenticated.any { it.isOpen })
    }

    @Test
    fun `disconnect of an authenticated client updates the count`() {
        startServer()
        val client = SecureClient(server.port, token).open().handshake()
        client.sendRecord("""{"ready":true}""")
        client.nextRecord()
        eventually("registered") { authCount.get() == 1 }
        client.closeBlocking()
        eventually("client removed") { authenticated.isEmpty() }
        eventually("disconnect callback") { authCount.get() == 0 }
    }

    @Test
    fun `two encrypted clients each receive their own encrypted copy`() {
        startServer()
        val a = SecureClient(server.port, token).open().handshake()
        a.sendRecord("""{"ready":true}""")
        a.nextRecord()
        val b = SecureClient(server.port, token).open().handshake()
        b.sendRecord("""{"ready":true}""")
        b.nextRecord()
        eventually("two clients") { authenticated.size == 2 }

        server.sendToAuthenticated("""{"n":1}""")
        assertEquals("""{"n":1}""", a.nextRecord())
        assertEquals("""{"n":1}""", b.nextRecord())
        a.close()
        b.close()
    }
}
