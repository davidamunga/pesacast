package com.pesacast.android.transport

import java.security.interfaces.ECPublicKey
import java.security.PrivateKey

/**
 * Server-side state for one WiFi v2 connection. Created when the client hello arrives and
 * discarded when the socket closes.
 */
class WifiSession private constructor(
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    /** Set once the client's `{"ready":true}` record has authenticated. */
    @Volatile var authenticated = false

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray =
        WifiCrypto.seal(sendKey, WifiCrypto.DIR_SERVER_TO_CLIENT, sendCounter, plaintext).also { sendCounter++ }

    /** Returns the plaintext, or null if the record is out of order or fails authentication. */
    @Synchronized
    fun open(record: ByteArray): ByteArray? =
        WifiCrypto.open(receiveKey, WifiCrypto.DIR_CLIENT_TO_SERVER, receiveCounter, record)
            ?.also { receiveCounter++ }

    companion object {
        /** Result of handling a client hello: the server hello to send back plus the new session. */
        class Accepted(val serverHello: String, val session: WifiSession)

        /**
         * Handles `{"v":2,"hello":{"pub","nonce"}}`. Returns null if the hello is malformed or
         * carries an invalid public key.
         */
        fun accept(
            clientPubB64: String,
            clientNonceB64: String,
            token: String,
            serverKey: Pair<PrivateKey, ECPublicKey> = WifiCrypto.generateKeyPair().let { it.private to (it.public as ECPublicKey) },
            serverNonce: ByteArray = WifiCrypto.randomNonce(),
        ): Accepted? {
            val b64 = java.util.Base64.getDecoder()
            return try {
                val clientPub = b64.decode(clientPubB64)
                val clientNonce = b64.decode(clientNonceB64)
                if (clientNonce.size != WifiCrypto.NONCE_SIZE) return null
                val peer = WifiCrypto.decodePublic(clientPub)
                val serverPub = WifiCrypto.encodePublic(serverKey.second)
                val shared = WifiCrypto.sharedSecret(serverKey.first, peer)
                val (c2s, s2c) = WifiCrypto.deriveKeys(shared, token, clientPub, serverPub, clientNonce, serverNonce)
                val enc = java.util.Base64.getEncoder()
                val hello = """{"v":2,"hello":{"pub":"${enc.encodeToString(serverPub)}","nonce":"${enc.encodeToString(serverNonce)}"}}"""
                Accepted(hello, WifiSession(sendKey = s2c, receiveKey = c2s))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: java.security.GeneralSecurityException) {
                null
            }
        }
    }
}
