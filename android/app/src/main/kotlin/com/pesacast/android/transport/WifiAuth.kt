package com.pesacast.android.transport

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** Which peers the WiFi server talks to at all. */
object PrivateIp {
    /**
     * Accepts loopback, RFC-1918 IPv4, and IPv6 link-local / unique-local (fc00::/7). Rejects
     * every other address, including public IPv6, 169.254/16 and CGNAT (100.64/10).
     */
    fun isAllowed(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        val b = address.address
        if (b.size == 16) return (b[0].toInt() and 0xFE) == 0xFC
        if (b.size != 4) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 10 ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168)
    }
}

/** Per-IP failed-attempt limiting. */
class AuthGuard(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun isBlocked(ip: String): Boolean {
        val (count, since) = failures[ip] ?: return false
        if (now() - since > windowMs) {
            failures.remove(ip)
            return false
        }
        return count >= maxFailures
    }

    fun recordFailure(ip: String) {
        val t = now()
        failures.merge(ip, 1 to t) { old, _ ->
            if (t - old.second > windowMs) 1 to t else (old.first + 1) to old.second
        }
    }
}
