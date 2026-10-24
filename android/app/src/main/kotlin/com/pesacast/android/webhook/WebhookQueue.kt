package com.pesacast.android.webhook

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** One transaction waiting for delivery. [json] is the transaction object; [key] is its idempotency key. */
data class QueuedWebhook(
    val id: String,
    val key: String,
    val json: String,
    val enqueuedAt: Long,
    val attempts: Int = 0,
)

/**
 * A small persisted FIFO. Holds financial data at rest, so items are deleted on delivery and the
 * queue is bounded by count and age (ADR-0013). The whole file is rewritten atomically on each
 * change; with at most [maxItems] short entries that is cheap and keeps the format trivial.
 */
class WebhookQueue(
    private val file: File,
    private val maxItems: Int = 500,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<QueuedWebhook>>() {}.type
    private val items: MutableList<QueuedWebhook> = load()

    @Synchronized fun size(): Int = items.size

    @Synchronized fun peek(): QueuedWebhook? = items.firstOrNull()

    @Synchronized fun add(item: QueuedWebhook) {
        if (items.any { it.key == item.key }) return // already waiting; retries of the same SMS
        while (items.size >= maxItems) items.removeAt(0)
        items.add(item)
        save()
    }

    @Synchronized fun remove(id: String) {
        if (items.removeAll { it.id == id }) save()
    }

    @Synchronized fun recordAttempt(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            items[index] = items[index].copy(attempts = items[index].attempts + 1)
            save()
        }
    }

    /** Drops items older than the retention period. Returns how many were removed. */
    @Synchronized fun purgeExpired(): Int {
        val cutoff = now() - maxAgeMs
        val before = items.size
        items.removeAll { it.enqueuedAt < cutoff }
        if (items.size != before) save()
        return before - items.size
    }

    @Synchronized fun clear() {
        items.clear()
        save()
    }

    private fun load(): MutableList<QueuedWebhook> = try {
        if (file.exists()) gson.fromJson<MutableList<QueuedWebhook>>(file.readText(), type) ?: mutableListOf()
        else mutableListOf()
    } catch (_: Exception) {
        mutableListOf() // unreadable file: start clean rather than crash the SMS path
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gson.toJson(items, type))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
