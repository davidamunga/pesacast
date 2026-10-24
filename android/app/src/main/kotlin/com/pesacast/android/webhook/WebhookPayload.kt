package com.pesacast.android.webhook

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** Request bodies; shape defined in docs/protocol.md ("Phone webhook"). Same envelope as the desktop push. */
object WebhookPayload {

    /** Incremented only for incompatible changes (docs/webhook-payload.schema.json). */
    const val SCHEMA_VERSION = 1

    fun transaction(txnJson: String, deviceId: String, now: Instant = Instant.now()): ByteArray {
        val txn = JsonParser.parseString(txnJson)
        val body = envelope(deviceId, now).apply {
            add("transactions", JsonArray().apply { add(txn) })
            addProperty("count", 1)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** A connectivity check that carries no transaction, so a receiver never records a fake one. */
    fun test(deviceId: String, now: Instant = Instant.now()): ByteArray {
        val body = envelope(deviceId, now).apply {
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

    private fun envelope(deviceId: String, now: Instant) = JsonObject().apply {
        addProperty("schema_version", SCHEMA_VERSION)
        addProperty("source", "android")
        addProperty("device_id", deviceId)
        addProperty("exported_at", now.toString())
    }
}
