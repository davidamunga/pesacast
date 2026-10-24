package com.pesacast.android.webhook

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the bodies the app produces against docs/webhook-payload.schema.json, so the published
 * contract cannot drift from the code. A small structural check (required keys, known keys,
 * enums, constants) rather than a full JSON Schema validator, to avoid a new dependency.
 */
class WebhookSchemaTest {

    private val schema: JsonObject by lazy {
        val file = listOf("../docs/webhook-payload.schema.json", "../../docs/webhook-payload.schema.json")
            .map(::File).firstOrNull { it.exists() } ?: error("webhook-payload.schema.json not found")
        JsonParser.parseString(file.readText()).asJsonObject
    }

    private val txn = """{"type":"mpesa_txn","direction":"received","amount":500.0,"currency":"KES",
        "from":"JANE","ref":"ABC123","time":"2026-10-24T10:00:00+03:00","balance":1500.0,"transaction_cost":0.0}"""

    private fun names(array: JsonArray) = array.map { it.asString }.toSet()

    private fun checkAgainst(body: JsonObject, def: JsonObject, label: String) {
        val properties = def.getAsJsonObject("properties")
        for (required in names(def.getAsJsonArray("required"))) {
            assertTrue("$label is missing required \"$required\"", body.has(required))
        }
        for (key in body.keySet()) {
            assertTrue("$label has \"$key\", which the schema does not describe", properties.has(key))
        }
    }

    private fun check(bodyBytes: ByteArray) {
        val body = JsonParser.parseString(String(bodyBytes)).asJsonObject
        checkAgainst(body, schema, "payload")
        val props = schema.getAsJsonObject("properties")
        assertEquals(props.getAsJsonObject("schema_version")["const"].asInt, body["schema_version"].asInt)
        assertTrue(body["source"].asString in names(props.getAsJsonObject("source").getAsJsonArray("enum")))
        assertTrue(body["device_id"].asString.length in 1..props.getAsJsonObject("device_id")["maxLength"].asInt)
        assertEquals(body.getAsJsonArray("transactions").size(), body["count"].asInt)

        val txnDef = schema.getAsJsonObject("\$defs").getAsJsonObject("transaction")
        for (item in body.getAsJsonArray("transactions")) {
            checkAgainst(item.asJsonObject, txnDef, "transaction")
            val direction = item.asJsonObject["direction"].asString
            assertTrue(direction in names(txnDef.getAsJsonObject("properties").getAsJsonObject("direction").getAsJsonArray("enum")))
        }
    }

    @Test
    fun `a transaction body conforms to the schema`() = check(WebhookPayload.transaction(txn, "5b0f6c1e-6a9a-4f4f-9e0e-2c5d7c0a9d11"))

    @Test
    fun `a test body conforms to the schema`() = check(WebhookPayload.test("5b0f6c1e-6a9a-4f4f-9e0e-2c5d7c0a9d11"))

    @Test
    fun `the schema's own constants match the code`() {
        val props = schema.getAsJsonObject("properties")
        assertEquals(WebhookPayload.SCHEMA_VERSION, props.getAsJsonObject("schema_version")["const"].asInt)
    }

    @Test
    fun `every direction the parser can produce is allowed by the schema`() {
        val source = File("src/main/kotlin/com/pesacast/android/sms/MpesaParser.kt").takeIf { it.exists() }
            ?: File("app/src/main/kotlin/com/pesacast/android/sms/MpesaParser.kt")
        val used = Regex("""(?:build\([^,]+,\s*|direction\s*=\s*)"(\w+)"""").findAll(source.readText()).map { it.groupValues[1] }.toSet()
        val allowed = names(
            schema.getAsJsonObject("\$defs").getAsJsonObject("transaction").getAsJsonObject("properties")
                .getAsJsonObject("direction").getAsJsonArray("enum")
        )
        assertTrue("Parser directions $used must be within $allowed", allowed.containsAll(used))
        assertTrue(used.isNotEmpty())
    }
}
