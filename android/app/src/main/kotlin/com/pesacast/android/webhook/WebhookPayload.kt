package com.pesacast.android.webhook

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** Request bodies; shape defined in docs/protocol.md ("Phone webhook"). Same envelope as the desktop push. */
object WebhookPayload {

    fun transaction(txnJson: String, now: Instant = Instant.now()): ByteArray {
        val txn = JsonParser.parseString(txnJson)
        val body = envelope(now).apply {
            add("transactions", JsonArray().apply { add(txn) })
            addProperty("count", 1)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** A connectivity check that carries no transaction, so a receiver never records a fake one. */
    fun test(now: Instant = Instant.now()): ByteArray {
        val body = envelope(now).apply {
            addProperty("test", true)
            add("transactions", JsonArray())
            addProperty("count", 0)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** Idempotency key for a transaction: its identity, `<ref>@<time>`. Null if either is missing. */
    fun idempotencyKey(txnJson: String): String? = try {
        val obj = JsonParser.parseString(txnJson).asJsonObject
        val ref = obj.get("ref")?.asString
        val time = obj.get("time")?.asString
        if (ref.isNullOrBlank() || time.isNullOrBlank()) null else "$ref@$time"
    } catch (_: Exception) {
        null
    }

    private fun envelope(now: Instant) = JsonObject().apply {
        addProperty("exported_at", now.toString())
        addProperty("source", "android")
    }
}
