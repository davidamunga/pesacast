package com.pesacast.android.webhook

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP status and a short, sanitised piece of the response text. The snippet is only for
 * showing the user why a request failed (for example a proxy saying "host not allowed"); it is
 * never logged or stored.
 */
data class HttpResponse(val code: Int, val snippet: String = "")

/** Sends one POST. Throws [IOException] on network errors. */
fun interface HttpPoster {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse
}

/** Platform HTTP client: bounded timeouts, no redirects (ADR-0013). */
private const val SNIPPET_BYTES = 512
private const val SNIPPET_CHARS = 100

/** First line of the response as plain text: control characters removed, truncated. */
internal fun snippetOf(bytes: ByteArray): String {
    val text = String(bytes, Charsets.UTF_8)
        .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        .filter { !it.isISOControl() }
    return if (text.length > SNIPPET_CHARS) text.take(SNIPPET_CHARS - 1) + "…" else text
}

class UrlConnectionPoster(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : HttpPoster {

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
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
            // Read a bounded amount of the response so the connection closes cleanly; keep the start
            // of it as a hint for error messages.
            val head = java.io.ByteArrayOutputStream()
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { stream ->
                val buffer = ByteArray(1024)
                var total = 0
                while (total < 16 * 1024) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (head.size() < SNIPPET_BYTES) head.write(buffer, 0, minOf(n, SNIPPET_BYTES - head.size()))
                    total += n
                }
            }
            return HttpResponse(code, if (code in 200..299) "" else snippetOf(head.toByteArray()))
        } finally {
            conn.disconnect()
        }
    }
}
