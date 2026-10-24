package com.pesacast.android.webhook

import java.net.URI

/** Webhook destinations must be HTTPS (ADR-0013); Android cannot scope cleartext to a LAN. */
object WebhookUrl {
    private const val MAX_LENGTH = 2048

    /** Returns a user-facing problem, or null if [raw] is an acceptable webhook URL. */
    fun validate(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "Enter a webhook URL."
        if (text.length > MAX_LENGTH) return "That URL is too long."
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return "That doesn't look like a valid URL."
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return "The URL must start with https://."
        if (uri.host.isNullOrBlank()) return "The URL needs a host name."
        if (uri.userInfo != null) return "Put credentials in the token or secret fields, not in the URL."
        return null
    }
}
