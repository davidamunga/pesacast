package com.pesacast.android.setup

import com.pesacast.android.webhook.TestResult
import com.pesacast.android.webhook.WebhookConfig

/** Where a provisioned webhook configuration is stored. Android-backed in the app, faked in tests. */
interface ProvisionStore {
    fun setEnabled(enabled: Boolean)
    fun save(url: String, token: String, secret: String)
    fun markVerified()
    fun wakeSender()
}

sealed class ProvisionOutcome {
    /** Saved, tested successfully, and switched on. */
    data class Enabled(val httpCode: Int) : ProvisionOutcome()

    /** Saved, but the test failed, so the webhook stays off until the user fixes it and tests again. */
    data class SavedNotEnabled(val message: String) : ProvisionOutcome()
}

/**
 * Applies a request the user has already confirmed on screen. The order is the safety property
 * (ADR-0013, ADR-0015): the webhook is switched off first, the new values are stored, a test is
 * sent to the new destination, and only a successful test marks the values verified and turns the
 * webhook on. A failed test never leaves the phone forwarding to an unverified server.
 */
object Provisioner {
    fun apply(
        request: ProvisionRequest.Valid,
        store: ProvisionStore,
        test: (WebhookConfig) -> TestResult,
    ): ProvisionOutcome {
        store.setEnabled(false)
        store.save(request.url, request.token.orEmpty(), request.secret.orEmpty())
        return when (val result = test(WebhookConfig(true, request.url, request.token, request.secret))) {
            is TestResult.Success -> {
                store.markVerified()
                store.setEnabled(true)
                store.wakeSender()
                ProvisionOutcome.Enabled(result.httpCode)
            }
            is TestResult.Failure -> ProvisionOutcome.SavedNotEnabled(result.message)
        }
    }
}
