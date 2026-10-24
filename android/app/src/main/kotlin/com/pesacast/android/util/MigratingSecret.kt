package com.pesacast.android.util

import com.pesacast.android.webhook.SecretCipher

/**
 * One secret string stored encrypted, optionally with a one-time upgrade from a plaintext value
 * written by an older version of the app.
 *
 * - [read] returns the decrypted value. If only the old plaintext key exists, it is encrypted into
 *   the new key and the plaintext is removed in the same call. If the encrypted value can no longer
 *   be decrypted (the Keystore key was invalidated), it returns null instead of falling back, so a
 *   stale plaintext copy is never resurrected.
 * - [write] stores the value encrypted and always clears any plaintext leftover. Null or blank
 *   clears the secret.
 *
 * [get] and [put] are the underlying key/value store; a null value removes the key.
 */
class MigratingSecret(
    private val cipher: SecretCipher,
    private val get: (key: String) -> String?,
    private val put: (key: String, value: String?) -> Unit,
    private val encryptedKey: String,
    private val legacyPlaintextKey: String? = null,
) {
    fun read(): String? {
        get(encryptedKey)?.let { return cipher.decrypt(it) }
        val legacy = legacyPlaintextKey?.let(get) ?: return null
        put(encryptedKey, cipher.encrypt(legacy))
        put(legacyPlaintextKey, null)
        return legacy
    }

    /**
     * True if an encrypted value is stored but cannot be decrypted, for example because Android
     * reset the Keystore key. No side effects, and it can become false again if the key returns.
     */
    fun hasUnreadableValue(): Boolean = get(encryptedKey)?.let { cipher.decrypt(it) == null } ?: false

    fun write(value: String?) {
        legacyPlaintextKey?.let { put(it, null) }
        put(encryptedKey, value?.takeIf { it.isNotBlank() }?.let(cipher::encrypt))
    }
}
