package com.pesacast.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramingTest {

    private fun reassemble(chunks: List<ByteArray>): String =
        // The desktop decodes every notification on its own, so decode per chunk, not once.
        chunks.joinToString("") { String(it, Charsets.UTF_8) }

    @Test
    fun `frame prefixes the json with its length in utf-16 code units`() {
        assertEquals("2:{}", BleFraming.frame("{}"))
    }

    @Test
    fun `prefix counts characters not bytes`() {
        // "é" is 1 UTF-16 unit but 2 UTF-8 bytes. The desktop compares against String.length,
        // so the prefix must stay in UTF-16 units to remain compatible.
        val json = """{"from":"José"}"""
        assertEquals("${json.length}:$json", BleFraming.frame(json))
        assertTrue(json.toByteArray(Charsets.UTF_8).size > json.length)
    }

    @Test
    fun `emoji counts as two utf-16 units`() {
        val json = "\"😀\""
        assertEquals(4, json.length)
        assertEquals("4:$json", BleFraming.frame(json))
    }

    @Test
    fun `short payload is a single chunk`() {
        val framed = BleFraming.frame("""{"a":1}""")
        val chunks = BleFraming.chunk(framed, 20)
        assertEquals(1, chunks.size)
        assertEquals(framed, reassemble(chunks))
    }

    @Test
    fun `ascii payload splits into chunks no larger than the limit and reassembles`() {
        val json = """{"type":"mpesa_txn","direction":"received","amount":500.0,"from":"JOHN DOE","ref":"ABC123XYZ0"}"""
        val framed = BleFraming.frame(json)
        val chunks = BleFraming.chunk(framed, 20)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= 20 })
        assertEquals(framed, reassemble(chunks))
    }

    @Test
    fun `exact multiple of the chunk size has no empty trailing chunk`() {
        val framed = "x".repeat(40)
        val chunks = BleFraming.chunk(framed, 20)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.isNotEmpty() })
    }

    @Test
    fun `empty input yields no chunks`() {
        assertEquals(0, BleFraming.chunk("", 20).size)
    }

    @Test
    fun `multibyte characters are never split across chunks`() {
        // Each chunk is decoded on its own by the desktop. A UTF-8 sequence cut in half decodes to
        // replacement characters and corrupts the message.
        val json = """{"from":"José Ñandú 😀 Åsa","note":"ééééééééééééééééééé"}"""
        val framed = BleFraming.frame(json)
        for (size in 4..24) {
            val chunks = BleFraming.chunk(framed, size)
            assertTrue("size $size", chunks.all { it.size <= size })
            assertEquals("size $size", framed, reassemble(chunks))
        }
    }
}
