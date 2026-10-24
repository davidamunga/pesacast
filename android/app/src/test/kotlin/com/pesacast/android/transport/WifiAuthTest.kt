package com.pesacast.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PrivateIpTest {

    private fun allowed(ip: String) = PrivateIp.isAllowed(InetAddress.getByName(ip))

    @Test
    fun `rfc1918 ipv4 is allowed`() {
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.14", "192.168.43.1")
            .forEach { assertTrue(it, allowed(it)) }
    }

    @Test
    fun `loopback is allowed`() {
        assertTrue(allowed("127.0.0.1"))
        assertTrue(allowed("::1"))
    }

    @Test
    fun `public ipv4 is rejected`() {
        listOf("8.8.8.8", "1.1.1.1", "41.90.1.1", "172.15.255.255", "172.32.0.1", "192.167.1.1", "192.169.1.1", "11.0.0.1")
            .forEach { assertFalse(it, allowed(it)) }
    }

    @Test
    fun `cgnat and zeroconf ranges are rejected`() {
        // 100.64.0.0/10 is shared with VPNs such as Tailscale; not treated as a trusted LAN.
        assertFalse(allowed("100.64.0.1"))
        assertFalse(allowed("100.127.255.254"))
        // IPv4 link-local 169.254/16 is also rejected (IPv6 link-local is allowed, see below).
        assertTrue(InetAddress.getByName("169.254.1.1").isLinkLocalAddress == allowed("169.254.1.1"))
    }

    @Test
    fun `ipv6 link-local and unique-local are allowed`() {
        assertTrue(allowed("fe80::1"))
        assertTrue(allowed("fc00::1"))
        assertTrue(allowed("fd12:3456:789a::1"))
    }

    @Test
    fun `public ipv6 is rejected`() {
        assertFalse(allowed("2001:4860:4860::8888"))
        assertFalse(allowed("2a00:1450:4001::200e"))
        assertFalse(allowed("ff02::1"))
    }
}

class AuthGuardTest {

    @Test
    fun `exact token is valid`() {
        assertTrue(AuthGuard("secret").isValid("secret"))
    }

    @Test
    fun `wrong null empty prefix and longer tokens are invalid`() {
        val guard = AuthGuard("secret")
        assertFalse(guard.isValid(null))
        assertFalse(guard.isValid(""))
        assertFalse(guard.isValid("secre"))
        assertFalse(guard.isValid("secret "))
        assertFalse(guard.isValid("SECRET"))
    }

    @Test
    fun `generated tokens are url safe and unique`() {
        val a = WifiTransport.generateToken()
        val b = WifiTransport.generateToken()
        assertTrue(a != b)
        assertEquals(43, a.length) // 32 bytes, base64url without padding
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `ip is blocked after the maximum number of failures`() {
        val guard = AuthGuard("t", maxFailures = 3)
        repeat(2) { guard.recordFailure("192.168.0.2") }
        assertFalse(guard.isBlocked("192.168.0.2"))
        guard.recordFailure("192.168.0.2")
        assertTrue(guard.isBlocked("192.168.0.2"))
    }

    @Test
    fun `blocking is per ip`() {
        val guard = AuthGuard("t", maxFailures = 1)
        guard.recordFailure("192.168.0.2")
        assertTrue(guard.isBlocked("192.168.0.2"))
        assertFalse(guard.isBlocked("192.168.0.3"))
    }

    @Test
    fun `block expires after the window`() {
        var now = 0L
        val guard = AuthGuard("t", maxFailures = 1, windowMs = 1_000, now = { now })
        guard.recordFailure("192.168.0.2")
        assertTrue(guard.isBlocked("192.168.0.2"))
        now = 1_001
        assertFalse(guard.isBlocked("192.168.0.2"))
    }

    @Test
    fun `failures outside the window do not accumulate`() {
        var now = 0L
        val guard = AuthGuard("t", maxFailures = 2, windowMs = 1_000, now = { now })
        guard.recordFailure("192.168.0.2")
        now = 2_000
        guard.recordFailure("192.168.0.2")
        assertFalse(guard.isBlocked("192.168.0.2"))
    }
}
