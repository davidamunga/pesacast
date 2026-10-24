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

    /**
     * Splits [framed] into UTF-8 chunks of at most [maxBytes] bytes each, never cutting through a
     * character. The desktop decodes each notification independently, so a multi-byte sequence
     * split across two chunks would turn into replacement characters.
     *
     * [maxBytes] is raised to 4 (the longest UTF-8 sequence) so every character fits in a chunk.
     */
    fun chunk(framed: String, maxBytes: Int): List<ByteArray> {
        val limit = maxBytes.coerceAtLeast(MAX_UTF8_BYTES_PER_CHAR)
        val chunks = mutableListOf<ByteArray>()
        val current = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < framed.length) {
            val cp = framed.codePointAt(i)
            val charBytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (current.size() + charBytes.size > limit) {
                chunks += current.toByteArray()
                current.reset()
            }
            current.write(charBytes)
            i += Character.charCount(cp)
        }
        if (current.size() > 0) chunks += current.toByteArray()
        return chunks
    }

    private const val MAX_UTF8_BYTES_PER_CHAR = 4
}
