package com.pesacast.android.webhook

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

private const val TAG = "WebhookDelivery"

/** What the sender needs to know to deliver. [token] and [secret] are optional. */
data class WebhookConfig(
    val enabled: Boolean,
    val url: String,
    val token: String? = null,
    val secret: String? = null,
)

data class WebhookStatus(
    val pending: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    /** True while delivery is stopped waiting for the user to fix something (bad credential or URL). */
    val paused: Boolean = false,
)

sealed class TestResult {
    data class Success(val httpCode: Int) : TestResult()
    data class Failure(val message: String) : TestResult()
}

/**
 * Delivers queued transactions to the webhook, one at a time and in order (ADR-0013).
 *
 * Semantics: at-least-once. 2xx removes the item. 408/429/5xx and network errors retry with
 * exponential backoff. 401/403 or a disallowed URL pause delivery and keep the queue. Redirects
 * and other 4xx drop that item and record the error. A [kick] retries immediately.
 */
class WebhookDelivery(
    private val queue: WebhookQueue,
    private val config: () -> WebhookConfig?,
    private val poster: HttpPoster,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val validateUrl: (String) -> String? = WebhookUrl::validate,
    private val backoffMs: (attempts: Int) -> Long = ::defaultBackoffMs,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow(WebhookStatus(pending = queue.size()))
    val status: StateFlow<WebhookStatus> = _status.asStateFlow()
    private var worker: kotlinx.coroutines.Job? = null

    private sealed class Outcome {
        data object Delivered : Outcome()
        data class Retry(val reason: String) : Outcome()
        data class Pause(val reason: String) : Outcome()
        data class Drop(val reason: String) : Outcome()
    }

    /** Persists [txnJson] and wakes the worker. No-op while the webhook is disabled. */
    fun enqueue(txnJson: String) {
        val cfg = config()
        if (cfg == null || !cfg.enabled) return
        val key = WebhookPayload.idempotencyKey(txnJson) ?: UUID.randomUUID().toString()
        queue.add(QueuedWebhook(UUID.randomUUID().toString(), key, txnJson, clock()))
        publish()
        kick()
    }

    /** Starts the worker if needed and makes it retry now (new item, network back, settings changed). */
    @Synchronized fun kick() {
        // Signal first: a freshly started worker discards wake-ups that predate its first attempt,
        // so signalling after launch could leave a stale one behind and cause an immediate second
        // attempt after a pause or retry wait.
        wake.trySend(Unit)
        if (worker?.isActive != true) worker = scope.launch { run() }
    }

    /** Sends a payload with no transaction to check the URL, token and secret. Does not touch the queue. */
    fun sendTest(cfg: WebhookConfig): TestResult {
        validateUrl(cfg.url)?.let { return TestResult.Failure(it) }
        val body = WebhookPayload.test()
        return try {
            val response = poster.post(cfg.url.trim(), headers(cfg, "test-${clock()}", body), body)
            if (response.code in 200..299) TestResult.Success(response.code)
            else TestResult.Failure(describe(response))
        } catch (e: IOException) {
            TestResult.Failure("Could not reach the server: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun run() {
        while (scope.isActive) {
            val cfg = config()
            if (cfg == null || !cfg.enabled) {
                publish(paused = false)
                wake.receive()
                continue
            }
            queue.purgeExpired()
            val item = queue.peek()
            if (item == null) {
                publish()
                wake.receive()
                continue
            }
            // Discard wake-ups that arrived before this attempt; one that arrives *during* it still
            // cuts the next wait short.
            wake.tryReceive()
            when (val outcome = attempt(item, cfg)) {
                Outcome.Delivered -> {
                    queue.remove(item.id)
                    _status.value = _status.value.copy(
                        pending = queue.size(), lastSuccessAt = clock(), lastError = null, paused = false,
                    )
                }
                is Outcome.Drop -> {
                    Log.w(TAG, "Dropping a transaction: ${outcome.reason}")
                    queue.remove(item.id)
                    _status.value = _status.value.copy(pending = queue.size(), lastError = outcome.reason, paused = false)
                }
                is Outcome.Pause -> {
                    _status.value = _status.value.copy(pending = queue.size(), lastError = outcome.reason, paused = true)
                    wake.receive() // until the user changes settings or kicks
                }
                is Outcome.Retry -> {
                    queue.recordAttempt(item.id)
                    _status.value = _status.value.copy(pending = queue.size(), lastError = outcome.reason, paused = false)
                    // A kick (network back, settings changed) cuts the wait short.
                    withTimeoutOrNull(backoffMs(item.attempts + 1)) { wake.receive() }
                }
            }
        }
    }

    private fun attempt(item: QueuedWebhook, cfg: WebhookConfig): Outcome {
        val url = cfg.url.trim()
        validateUrl(url)?.let { return Outcome.Pause(it) }
        val body = try {
            WebhookPayload.transaction(item.json)
        } catch (e: Exception) {
            return Outcome.Drop("Unreadable queued transaction")
        }
        return try {
            val response = poster.post(url, headers(cfg, item.key, body), body)
            when (val code = response.code) {
                in 200..299 -> Outcome.Delivered
                401, 403 -> Outcome.Pause(describe(response))
                408, 429, in 500..599 -> Outcome.Retry(describe(response))
                in 300..399 -> Outcome.Drop("The server redirected (HTTP $code); redirects are not followed")
                else -> Outcome.Drop(describe(response))
            }
        } catch (e: IOException) {
            Outcome.Retry("Could not reach the server: ${e.javaClass.simpleName}")
        }
    }

    private fun headers(cfg: WebhookConfig, key: String, body: ByteArray): Map<String, String> {
        val timestamp = clock() / 1000
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "PesaCast-Android",
            "Idempotency-Key" to key,
            "X-PesaCast-Timestamp" to timestamp.toString(),
        )
        cfg.token?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }
        cfg.secret?.takeIf { it.isNotBlank() }?.let {
            headers["X-PesaCast-Signature"] = WebhookSigner.sign(it, timestamp, body)
        }
        return headers
    }

    /** What to tell the user. 401 means bad credentials; 403 is a refusal, which may come from a proxy. */
    private fun describe(response: HttpResponse): String {
        val base = when (response.code) {
            401 -> "The server rejected the credentials (HTTP 401)"
            403 -> "The server refused the request (HTTP 403)"
            else -> "The server answered HTTP ${response.code}"
        }
        return if (response.snippet.isBlank()) base else "$base: ${response.snippet}"
    }

    private fun publish(paused: Boolean = _status.value.paused) {
        _status.value = _status.value.copy(pending = queue.size(), paused = paused)
    }
}

/** 5 s, doubling per failed attempt, capped at 15 minutes. */
fun defaultBackoffMs(attempts: Int): Long {
    val exponent = (attempts - 1).coerceIn(0, 10)
    return minOf(5_000L shl exponent, 15 * 60 * 1000L)
}
