package com.pesacast.android.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guards for the rules in AGENTS.md: never log SMS bodies, tokens, secrets or payloads.
 * These scan the Kotlin sources, so they fail the build on a regression without needing a device.
 */
class SourceHygieneTest {

    private fun mainSources(): List<File> {
        val roots = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).filter { it.exists() }
        check(roots.isNotEmpty()) { "Kotlin sources not found from ${File(".").absolutePath}" }
        return roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
    }

    /** Words that must not be interpolated into a log line (their length is fine). */
    private val sensitive = Regex("""\b(body|fullBody|txnJson|json|token|secret|payload|pairing|bearer)\b""", RegexOption.IGNORE_CASE)

    @Test
    fun `log calls never include sensitive values`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            file.readLines().forEachIndexed { i, line ->
                if (!Regex("""\bLog\.[a-z]\(""").containsMatchIn(line)) return@forEachIndexed
                // The value's size is not sensitive, only its content.
                val scrubbed = line.replace(Regex("""\b\w+\.length\b"""), "").replace(Regex("""\b\w+\.size\b"""), "")
                // String literal text inside quotes is a message, not a value, unless interpolated.
                val interpolated = Regex("""\$\{?([A-Za-z_][\w.]*)""").findAll(scrubbed).map { it.groupValues[1] }
                if (interpolated.any { sensitive.containsMatchIn(it) }) {
                    offenders += "${file.name}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("Sensitive values in log calls:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the guard itself catches an offending line`() {
        val line = """Log.d(TAG, "M-PESA SMS: ${'$'}{fullBody.take(60)}")"""
        val interpolated = Regex("""\$\{?([A-Za-z_][\w.]*)""").findAll(line).map { it.groupValues[1] }
        assertTrue(interpolated.any { sensitive.containsMatchIn(it) })
    }
}
