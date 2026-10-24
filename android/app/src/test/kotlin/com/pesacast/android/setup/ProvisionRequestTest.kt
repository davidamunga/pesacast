package com.pesacast.android.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionRequestTest {

    private fun valid(url: String?, token: String? = null, secret: String? = null) =
        ProvisionRequest.parse(url, token, secret) as ProvisionRequest.Valid

    private fun invalid(url: String?, token: String? = null, secret: String? = null) =
        (ProvisionRequest.parse(url, token, secret) as ProvisionRequest.Invalid).reason

    @Test
    fun `a plain https url is accepted and its host is shown`() {
        val r = valid("https://hooks.example.com/pesacast", "tok-123", "s3cret")
        assertEquals("https://hooks.example.com/pesacast", r.url)
        assertEquals("hooks.example.com", r.displayHost)
        assertEquals("tok-123", r.token)
        assertEquals("s3cret", r.secret)
    }

    @Test
    fun `a non default port is shown, the default is not`() {
        assertEquals("example.com:8443", valid("https://example.com:8443/h").displayHost)
        assertEquals("example.com", valid("https://example.com:443/h").displayHost)
    }

    @Test
    fun `surrounding whitespace is trimmed and blank credentials become null`() {
        val r = valid("  https://example.com/h  ", " ", "")
        assertEquals("https://example.com/h", r.url)
        assertNull(r.token)
        assertNull(r.secret)
    }

    @Test
    fun `a host name with non-ascii characters is refused so look-alikes cannot hide`() {
        // The first letter is Cyrillic "а", not Latin "a".
        assertTrue(invalid("https://\u0430pple.com/hook").contains("non-ASCII"))
        assertTrue(invalid("https://example.com/p\u00e4th").contains("non-ASCII"))
    }

    @Test
    fun `an internationalised host in its ascii form is accepted but flagged`() {
        val r = valid("https://xn--pple-43d.com/hook")
        assertEquals("xn--pple-43d.com", r.displayHost)
        assertTrue(r.isPunycode)
        assertTrue(!valid("https://example.com/h").isPunycode)
    }

    @Test
    fun `plain http, missing, odd or credential-bearing urls are refused`() {
        assertTrue(invalid("http://example.com/h").contains("https"))
        assertTrue(invalid(null).contains("No webhook URL"))
        assertTrue(invalid("   ").contains("No webhook URL"))
        assertTrue(invalid("ftp://example.com/h").contains("https"))
        assertTrue(invalid("https://user:pw@example.com/h").isNotEmpty())
        assertTrue(invalid("https://").isNotEmpty())
        assertTrue(invalid("javascript:alert(1)").isNotEmpty())
        assertTrue(invalid("https://example.com/" + "a".repeat(3000)).isNotEmpty())
    }

    @Test
    fun `a token that could inject a header is refused`() {
        assertTrue(invalid("https://example.com/h", token = "abc\r\nX-Evil: 1").contains("token"))
        assertTrue(invalid("https://example.com/h", token = "abc def").contains("token"))
        assertTrue(invalid("https://example.com/h", token = "tök").contains("token"))
        assertTrue(invalid("https://example.com/h", token = "a".repeat(513)).contains("token"))
    }

    @Test
    fun `a signing secret may contain spaces and unicode but not control characters`() {
        assertEquals("pass phrase ✓", valid("https://example.com/h", secret = "pass phrase ✓").secret)
        assertTrue(invalid("https://example.com/h", secret = "bad\u0000secret").contains("secret"))
        assertTrue(invalid("https://example.com/h", secret = "a".repeat(513)).contains("secret"))
    }

    @Test
    fun `an invalid request never carries values the screen could apply`() {
        val r = ProvisionRequest.parse("http://example.com/h", "tok", "sec")
        assertTrue(r is ProvisionRequest.Invalid)
    }
}
