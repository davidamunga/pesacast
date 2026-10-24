package com.pesacast.android.util

import com.pesacast.android.webhook.SecretCipher

/**
 * One secret string stored encrypted, with a one-time upgrade from a plaintext value written by an
 * older version of the app.
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
    private val legacyPlaintextKey: String,
) {
    fun read(): String? {
        get(encryptedKey)?.let { return cipher.decrypt(it) }
        val legacy = get(legacyPlaintextKey) ?: return null
        put(encryptedKey, cipher.encrypt(legacy))
        put(legacyPlaintextKey, null)
        return legacy
    }

    fun write(value: String?) {
        put(legacyPlaintextKey, null)
        put(encryptedKey, value?.takeIf { it.isNotBlank() }?.let(cipher::encrypt))
    }
}
