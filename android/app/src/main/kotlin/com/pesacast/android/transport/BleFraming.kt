package com.pesacast.android.transport

/**
 * Wire framing for BLE notifications. See docs/protocol.md.
 *
 * A transaction is sent as `<length>:<json>` where `<length>` is the number of UTF-16 code units
 * in the JSON (Kotlin `String.length`, equal to JavaScript `String.length` on the desktop), then
 * split into notification-sized chunks.
 */
object BleFraming {

    fun frame(json: String): String = "${json.length}:$json"

    /** Splits [framed] into UTF-8 chunks of at most [maxBytes] bytes each. */
    fun chunk(framed: String, maxBytes: Int): List<ByteArray> {
        val bytes = framed.toByteArray(Charsets.UTF_8)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxBytes, bytes.size)
            chunks += bytes.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }
}
