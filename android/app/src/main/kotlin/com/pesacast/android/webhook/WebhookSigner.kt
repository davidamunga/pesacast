package com.pesacast.android.webhook

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Request signing for webhook receivers; format defined in docs/protocol.md ("Phone webhook"). */
object WebhookSigner {
    const val VERSION_PREFIX = "v1="

    /** `v1=` followed by the hex HMAC-SHA256 of `"<timestamp>.<body>"` keyed with [secret]. */
    fun sign(secret: String, timestampSeconds: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(timestampSeconds.toString().toByteArray(Charsets.US_ASCII))
        mac.update('.'.code.toByte())
        val digest = mac.doFinal(body)
        return VERSION_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }
}
