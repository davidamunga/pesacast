package com.pesacast.android.transport

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WiFi v2 secure channel primitives (see docs/protocol.md, "WiFi v2"): ephemeral P-256 ECDH,
 * HKDF-SHA256 keyed by the pairing token, AES-256-GCM records with counter nonces.
 *
 * Pure JVM (no android.* classes) so the same code is covered by unit tests against the shared
 * known-answer vectors in docs/wifi-crypto-vectors.json.
 */
object WifiCrypto {
    const val POINT_SIZE = 65
    const val NONCE_SIZE = 16
    const val TAG_BITS = 128
    const val DIR_CLIENT_TO_SERVER: Byte = 0x01
    const val DIR_SERVER_TO_CLIENT: Byte = 0x02

    private const val INFO_PREFIX = "pesacast-v2"
    private val random = SecureRandom()

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE).also(random::nextBytes)

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** 65-byte uncompressed SEC1 point: 0x04 || X || Y. */
    fun encodePublic(key: ECPublicKey): ByteArray {
        val out = ByteArray(POINT_SIZE)
        out[0] = 0x04
        fixedBytes(key.w.affineX).copyInto(out, 1)
        fixedBytes(key.w.affineY).copyInto(out, 33)
        return out
    }

    /** Parses and validates a peer public key. Throws [IllegalArgumentException] if invalid. */
    fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == POINT_SIZE && bytes[0] == 0x04.toByte()) { "bad point encoding" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        require(isOnCurve(x, y)) { "point not on curve" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    /** Builds a private key from a raw scalar; used with fixed known-answer vectors in tests. */
    internal fun privateKeyFromScalar(d: ByteArray): java.security.PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), curve))

    fun sharedSecret(own: java.security.PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }

    /** Returns 64 bytes: key client-to-server followed by key server-to-client. */
    fun deriveKeys(
        shared: ByteArray,
        token: String,
        clientPub: ByteArray,
        serverPub: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val info = INFO_PREFIX.toByteArray(Charsets.US_ASCII) + clientPub + serverPub + clientNonce + serverNonce
        val okm = hkdfSha256(ikm = shared, salt = token.toByteArray(Charsets.UTF_8), info = info, length = 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    fun seal(key: ByteArray, direction: Byte, counter: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(counter)))
        cipher.updateAAD(aad(direction, counter))
        val ct = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(8 + ct.size).putLong(counter).put(ct).array()
    }

    /** Returns null if the record is malformed or fails authentication. */
    fun open(key: ByteArray, direction: Byte, expectedCounter: Long, record: ByteArray): ByteArray? {
        if (record.size < 8 + TAG_BITS / 8) return null
        val counter = ByteBuffer.wrap(record, 0, 8).long
        if (counter != expectedCounter) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(counter)))
            cipher.updateAAD(aad(direction, counter))
            cipher.doFinal(record, 8, record.size - 8)
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    private fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    private fun aad(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(9).put(direction).putLong(counter).array()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            // An empty HMAC key is invalid for SecretKeySpec; HKDF treats it as a zero block.
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
            doFinal(data)
        }

    private fun fixedBytes(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        raw.copyInto(out, 32 - n, raw.size - n, raw.size)
        return out
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (curve.curve.field as java.security.spec.ECFieldFp).p
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
        return lhs == rhs
    }
}
