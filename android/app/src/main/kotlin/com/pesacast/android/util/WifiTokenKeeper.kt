package com.pesacast.android.util

/**
 * Hands out the WiFi pairing token and remembers when it had to be replaced because the stored one
 * could no longer be decrypted. A replaced token silently breaks the desktop pairing, so the app
 * shows a one-time notice ([consumeResetNotice]).
 */
class WifiTokenKeeper(
    private val secret: MigratingSecret,
    private val getFlag: () -> Boolean,
    private val setFlag: (Boolean) -> Unit,
) {
    fun current(): String? = secret.read()

    /** The stored token, or a new one from [generate] (noting the reset if the old one was lost). */
    fun tokenOrRegenerate(generate: () -> String): String {
        secret.read()?.let { return it }
        val wasLost = secret.hasUnreadableValue()
        val fresh = generate()
        secret.write(fresh)
        if (wasLost) setFlag(true)
        return fresh
    }

    fun write(value: String?) = secret.write(value)

    /** True once after a token was replaced because the old one was unreadable. */
    fun consumeResetNotice(): Boolean {
        if (!getFlag()) return false
        setFlag(false)
        return true
    }
}
