package com.pesacast.android.setup

import com.pesacast.android.webhook.WebhookUrl
import java.net.URI

/**
 * A webhook configuration offered from outside the app (an `adb` command or another app),
 * validated before anything is shown or stored. Pure Kotlin so every rule is unit tested.
 *
 * Nothing here applies a request: the confirmation screen does, after the user has seen
 * [Valid.displayHost] (ADR-0015).
 */
sealed class ProvisionRequest {

    data class Valid(
        val url: String,
        /** The host as it is connected to. Always ASCII, so a look-alike name cannot hide. */
        val displayHost: String,
        /** True for an internationalised name (`xn--...`), which the screen flags as unusual. */
        val isPunycode: Boolean,
        val token: String?,
        val secret: String?,
    ) : ProvisionRequest()

    data class Invalid(val reason: String) : ProvisionRequest()

    companion object {
        const val MAX_CREDENTIAL_LENGTH = 512

        fun parse(url: String?, token: String?, secret: String?): ProvisionRequest {
            if (url.isNullOrBlank()) return Invalid("No webhook URL was provided.")
            // Non-ASCII characters in a host name are how look-alike domains are made. Accept only
            // the ASCII form (punycode), which is what is really connected to.
            if (url.any { it.code > 127 }) {
                return Invalid("The URL contains non-ASCII characters. Use the ASCII (xn--) form of the host name.")
            }
            WebhookUrl.validate(url)?.let { return Invalid(it) }

            val trimmed = url.trim()
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return Invalid("That doesn't look like a valid URL.")
            }
            val host = uri.host ?: return Invalid("The URL needs a host name.")
            val cleanToken = token?.trim()?.takeIf { it.isNotEmpty() }
            val cleanSecret = secret?.takeIf { it.isNotBlank() }
            credentialProblem("token", cleanToken, headerSafe = true)?.let { return Invalid(it) }
            credentialProblem("secret", cleanSecret, headerSafe = false)?.let { return Invalid(it) }

            val port = uri.port
            val shown = if (port > 0 && port != 443) "$host:$port" else host
            return Valid(
                url = trimmed,
                displayHost = shown,
                isPunycode = host.split('.').any { it.startsWith("xn--", ignoreCase = true) },
                token = cleanToken,
                secret = cleanSecret,
            )
        }

        /**
         * The token is sent in an HTTP header, so it must be plain printable ASCII: a newline in it
         * would let a request inject headers. The signing secret is only ever a key for HMAC, so any
         * text is fine, but control characters are still refused.
         */
        private fun credentialProblem(name: String, value: String?, headerSafe: Boolean): String? {
            if (value.isNullOrEmpty()) return null
            if (value.length > MAX_CREDENTIAL_LENGTH) return "The $name is too long."
            val ok = value.all { c ->
                if (headerSafe) c.code in 0x21..0x7E else !c.isISOControl()
            }
            return if (ok) null else "The $name contains characters that are not allowed."
        }
    }
}
