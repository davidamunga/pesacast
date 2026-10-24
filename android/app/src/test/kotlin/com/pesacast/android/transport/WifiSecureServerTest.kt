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

    private fun startServer(allowLegacy: Boolean = true, guard: AuthGuard = AuthGuard(token)) {
        server = PesaCastWsServer(
            port = 0,
            token = token,
            allowLegacy = { allowLegacy },
            authenticatedClients = authenticated,
            onClientAuthenticated = { authCount.set(it) },
            onClientDisconnected = { authCount.set(it) },
            onServerError = { throw it },
            guard = guard,
        )
        server.start()
        eventually("bound") { server.port != 0 }
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
        startServer(guard = AuthGuard(token, maxFailures = 1))
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
    fun `legacy client is refused when legacy is disabled and not counted as a failure`() {
        startServer(allowLegacy = false)
        val legacy = SecureClient(server.port, token).open()
        legacy.send("""{"auth":"$token"}""")
        assertTrue(legacy.awaitClose())
        assertEquals(1008, legacy.closeCode)
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `legacy and encrypted clients can be connected at once and each get the right format`() {
        startServer()
        val legacy = SecureClient(server.port, token).open()
        legacy.send("""{"auth":"$token"}""")
        assertEquals("""{"status":"ok"}""", legacy.texts.poll(3, TimeUnit.SECONDS))
        val secure = SecureClient(server.port, token).open().handshake()
        secure.sendRecord("""{"ready":true}""")
        secure.nextRecord()
        eventually("two clients") { authenticated.size == 2 }

        server.sendToAuthenticated("""{"n":1}""")
        assertEquals("""{"n":1}""", legacy.texts.poll(3, TimeUnit.SECONDS))
        assertEquals("""{"n":1}""", secure.nextRecord())
        legacy.close()
        secure.close()
    }
}
