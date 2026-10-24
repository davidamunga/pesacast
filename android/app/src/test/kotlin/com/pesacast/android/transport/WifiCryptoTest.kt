package com.pesacast.android.transport

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** Checks the implementation against the shared vectors in docs/wifi-crypto-vectors.json. */
class WifiCryptoTest {

    private object V {
        private val json = JsonParser.parseString(vectorsFile().readText()).asJsonObject
        fun str(name: String) = json.get(name).asString
        fun bytes(name: String) = hex(str(name))
        val records = json.getAsJsonArray("records").map { it.asJsonObject }

        private fun vectorsFile(): File {
            // Gradle runs unit tests with the module directory (android/app) as working dir.
            val candidates = listOf("../../docs/wifi-crypto-vectors.json", "../docs/wifi-crypto-vectors.json")
            return candidates.map(::File).firstOrNull { it.exists() }
                ?: error("wifi-crypto-vectors.json not found from ${File(".").absolutePath}")
        }
    }

    @Test
    fun `ecdh and hkdf reproduce the vectors`() {
        val clientPriv = WifiCrypto.privateKeyFromScalar(V.bytes("clientPrivateKey"))
        val serverPub = WifiCrypto.decodePublic(V.bytes("serverPublicKey"))
        val shared = WifiCrypto.sharedSecret(clientPriv, serverPub)
        assertArrayEquals(V.bytes("sharedSecret"), shared)

        val (c2s, s2c) = WifiCrypto.deriveKeys(
            shared, V.str("token"), V.bytes("clientPublicKey"), V.bytes("serverPublicKey"),
            V.bytes("clientNonce"), V.bytes("serverNonce"),
        )
        assertArrayEquals(V.bytes("keyClientToServer"), c2s)
        assertArrayEquals(V.bytes("keyServerToClient"), s2c)
    }

    @Test
    fun `server and client sides derive the same shared secret`() {
        val serverPriv = WifiCrypto.privateKeyFromScalar(V.bytes("serverPrivateKey"))
        val clientPub = WifiCrypto.decodePublic(V.bytes("clientPublicKey"))
        assertArrayEquals(V.bytes("sharedSecret"), WifiCrypto.sharedSecret(serverPriv, clientPub))
    }

    @Test
    fun `sealed records match the vectors and open again`() {
        for (r in V.records) {
            val clientToServer = r["direction"].asString == "client-to-server"
            val key = V.bytes(if (clientToServer) "keyClientToServer" else "keyServerToClient")
            val dir = if (clientToServer) WifiCrypto.DIR_CLIENT_TO_SERVER else WifiCrypto.DIR_SERVER_TO_CLIENT
            val counter = r["counter"].asLong
            val plaintext = r["plaintext"].asString.toByteArray()
            val expected = hex(r["record"].asString)

            assertArrayEquals(expected, WifiCrypto.seal(key, dir, counter, plaintext))
            assertArrayEquals(plaintext, WifiCrypto.open(key, dir, counter, expected))
        }
    }

    @Test
    fun `open rejects tampering, wrong counter and wrong direction`() {
        val key = V.bytes("keyServerToClient")
        val record = hex(V.records[1]["record"].asString) // server-to-client, counter 0

        val flipped = record.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(WifiCrypto.open(key, WifiCrypto.DIR_SERVER_TO_CLIENT, 0, flipped))
        assertNull("replay at wrong counter", WifiCrypto.open(key, WifiCrypto.DIR_SERVER_TO_CLIENT, 1, record))
        assertNull("reflected direction", WifiCrypto.open(key, WifiCrypto.DIR_CLIENT_TO_SERVER, 0, record))
        assertNull("wrong key", WifiCrypto.open(V.bytes("keyClientToServer"), WifiCrypto.DIR_SERVER_TO_CLIENT, 0, record))
        assertNull("truncated", WifiCrypto.open(key, WifiCrypto.DIR_SERVER_TO_CLIENT, 0, record.copyOf(10)))
    }

    @Test
    fun `different token derives different keys`() {
        val shared = V.bytes("sharedSecret")
        val (c2s, _) = WifiCrypto.deriveKeys(
            shared, "another-token", V.bytes("clientPublicKey"), V.bytes("serverPublicKey"),
            V.bytes("clientNonce"), V.bytes("serverNonce"),
        )
        assertTrue(!c2s.contentEquals(V.bytes("keyClientToServer")))
    }

    @Test
    fun `decodePublic rejects bad encodings and off-curve points`() {
        val good = V.bytes("serverPublicKey")
        assertNotNull(WifiCrypto.decodePublic(good))

        fun rejects(bytes: ByteArray, message: String) = try {
            WifiCrypto.decodePublic(bytes)
            fail(message)
        } catch (_: IllegalArgumentException) {
        }

        rejects(good.copyOf(64), "short")
        rejects(good.copyOf().also { it[0] = 0x02 }, "compressed prefix")
        rejects(good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }, "off curve")
        rejects(ByteArray(65).also { it[0] = 0x04 }, "zero point")
    }

    @Test
    fun `session accepts a hello and agrees on keys with the client`() {
        val clientKey = WifiCrypto.generateKeyPair()
        val clientPub = WifiCrypto.encodePublic(clientKey.public as java.security.interfaces.ECPublicKey)
        val clientNonce = WifiCrypto.randomNonce()
        val enc = java.util.Base64.getEncoder()
        val accepted = WifiSession.accept(enc.encodeToString(clientPub), enc.encodeToString(clientNonce), "tok")!!

        val hello = JsonParser.parseString(accepted.serverHello).asJsonObject.getAsJsonObject("hello")
        val dec = java.util.Base64.getDecoder()
        val serverPub = dec.decode(hello["pub"].asString)
        val serverNonce = dec.decode(hello["nonce"].asString)
        val shared = WifiCrypto.sharedSecret(clientKey.private, WifiCrypto.decodePublic(serverPub))
        val (c2s, s2c) = WifiCrypto.deriveKeys(shared, "tok", clientPub, serverPub, clientNonce, serverNonce)

        // Client -> server record opens on the session; server -> client record opens with s2c.
        val ready = WifiCrypto.seal(c2s, WifiCrypto.DIR_CLIENT_TO_SERVER, 0, """{"ready":true}""".toByteArray())
        assertEquals("""{"ready":true}""", String(accepted.session.open(ready)!!))
        val reply = accepted.session.seal("hi".toByteArray())
        assertEquals("hi", String(WifiCrypto.open(s2c, WifiCrypto.DIR_SERVER_TO_CLIENT, 0, reply)!!))
    }

    @Test
    fun `session rejects malformed hellos`() {
        val enc = java.util.Base64.getEncoder()
        val good = enc.encodeToString(hex(V.str("clientPublicKey")))
        assertNull(WifiSession.accept("not base64!!", enc.encodeToString(ByteArray(16)), "t"))
        assertNull(WifiSession.accept(good, enc.encodeToString(ByteArray(8)), "t"))
        assertNull(WifiSession.accept(enc.encodeToString(ByteArray(65)), enc.encodeToString(ByteArray(16)), "t"))
    }

    @Test
    fun `hkdf matches rfc 5869 test case 1`() {
        val okm = WifiCrypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }
}

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { BigInteger(s.substring(it * 2, it * 2 + 2), 16).toByte() }
