package com.pesacast.android.webhook

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sends one POST and returns the HTTP status code. Throws [IOException] on network errors. */
fun interface HttpPoster {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: ByteArray): Int
}

/** Platform HTTP client: bounded timeouts, no redirects (ADR-0013). */
class UrlConnectionPoster(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : HttpPoster {

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            // Drain a bounded amount of the response so the connection can be reused/closed cleanly.
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { stream ->
                val buffer = ByteArray(1024)
                var total = 0
                while (total < 16 * 1024) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                }
            }
            return code
        } finally {
            conn.disconnect()
        }
    }
}
