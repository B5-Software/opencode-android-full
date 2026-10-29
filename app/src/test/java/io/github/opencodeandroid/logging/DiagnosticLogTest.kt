package io.github.opencodeandroid.logging

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class DiagnosticLogTest {
    private class Fixture(capacity: Int = 48 * 1024) {
        val tasks = ConcurrentLinkedQueue<() -> Unit>()
        val published = mutableListOf<DiagnosticLog.Snapshot>()
        val log = DiagnosticLog(capacity, { published.add(it) }, { tasks.add(it); Unit })
        fun flush() { tasks.remove().invoke() }
    }

    @Test fun tenThousandLinesKeepOnlyBoundedWholeLinesAndOnePendingPublication() {
        val fixture = Fixture()
        repeat(10_000) { fixture.log.append("row-$it-完成🔧") }
        assertEquals(1, fixture.tasks.size); assertTrue(fixture.published.isEmpty())
        val immediate = fixture.log.snapshot()
        assertEquals(10_000L, immediate.revision)
        assertTrue(immediate.text.length <= 48 * 1024)
        assertEquals(512, immediate.text.lineSequence().count { it.isNotEmpty() })
        assertTrue(immediate.text.endsWith("row-9999-完成🔧\n"))
        assertFalse(immediate.text.contains("row-0-"))
        fixture.flush(); assertEquals(listOf(immediate), fixture.published)
        fixture.log.append("last"); assertEquals(1, fixture.tasks.size)
        fixture.flush(); assertTrue(fixture.published.last().text.endsWith("last\n"))
    }

    @Test fun concurrentSourcesNeverCorruptLinesOrQueueOneTimerPerLine() {
        val fixture = Fixture(12000)
        val workers = (1..8).map { worker -> thread { repeat(500) { fixture.log.append("worker=$worker item=$it 🔧") } } }
        workers.forEach { it.join() }
        val result = fixture.log.snapshot()
        assertEquals(4000L, result.revision); assertEquals(1, fixture.tasks.size)
        assertTrue(result.text.length <= 12000)
        result.text.lineSequence().filter { it.isNotEmpty() }.forEach { assertTrue(Regex("worker=[1-8] item=[0-9]+ 🔧").matches(it)) }
    }

    @Test fun generatedCredentialsStayMaskedUntilTheLastLeaseCloses() {
        val secret = "host-fixture-private-value-12345"
        val first = DiagnosticSecrets.register(secret, secret.take(8))
        val second = DiagnosticSecrets.register(secret)
        try {
            assertEquals("[redacted]", DiagnosticSecrets.redact(secret))
            first.close(); first.close()
            assertEquals("[redacted]", DiagnosticSecrets.redact(secret))
            second.close()
            assertEquals(secret, DiagnosticSecrets.redact(secret))
        } finally { first.close(); second.close() }
    }

    @Test fun nativeHeadersQueriesJsonEnvironmentAndKnownKeyShapesAreRedacted() {
        val cases = listOf(
            "Authorization: Basic Zml4dHVyZTpwYXNzd29yZA==" to "Zml4dHVyZTpwYXNzd29yZA==",
            "Proxy-Authorization: Bearer fixture-access-value" to "fixture-access-value",
            "Cookie: session=fixture-cookie; another=private-cookie" to "private-cookie",
            "Set-Cookie: session=fixture-cookie; HttpOnly" to "fixture-cookie",
            "{\"apiKey\":\"fixture-json-value\",\"port\":49374}" to "fixture-json-value",
            "ANTHROPIC_API_KEY='fixture env value'" to "fixture env value",
            "{\"clientSecret\":\"fixture\\\"escaped-secret\"}" to "escaped-secret",
            "https://example.test/?access_token=fixture-query&port=49374" to "fixture-query",
            "http://127.0.0.1:49374/auth/connect/fixture-pair-code" to "fixture-pair-code",
            "https://fixture-user:fixture-password@example.test/path" to "fixture-password",
            "failure Bearer fixture-token-value" to "fixture-token-value",
            "failure sk-ant-api03-fixturesecret1234567890" to "fixturesecret1234567890",
            "failure github_pat_fixturesecret1234567890" to "fixturesecret1234567890",
        )
        cases.forEach { (input, hidden) ->
            val fixture = Fixture(); fixture.log.append(input)
            val result = fixture.log.snapshot().text
            assertFalse("Credential pattern was retained", result.contains(hidden))
            assertTrue(result.contains("[redacted]"))
        }
        val fixture = Fixture(); fixture.log.append("OpenCode 2.1.4 ready; http://127.0.0.1:49374/; exit=7")
        assertTrue(fixture.log.snapshot().text.contains("exit=7"))
        assertTrue(fixture.log.snapshot().text.contains("2.1.4"))
    }

    @Test fun ansiControlsAndPrivateKeyBodiesCannotEnterSnapshots() {
        val fixture = Fixture()
        fixture.log.append("\u001b[31mpass\u001b[0mword=fixture-ansi-value")
        fixture.log.append("\u001b]0;fixture-window-title\u0007safe\u0000-text")
        fixture.log.append("-----BEGIN RSA PRIVATE KEY-----")
        fixture.log.append("fixture-private-key-body")
        fixture.log.append("-----END RSA PRIVATE KEY-----")
        fixture.log.append("next operation exit=7")
        val result = fixture.log.snapshot().text
        for (hidden in listOf("fixture-ansi-value", "fixture-window-title", "fixture-private-key-body", "\u001b", "\u0000")) assertFalse(result.contains(hidden))
        assertTrue(result.contains("[private key omitted]")); assertTrue(result.contains("safe-text")); assertTrue(result.contains("exit=7"))
    }

    @Test fun oversizedStreamIsDrainedWithoutRetainingAnyPrefixAndStillCloses() {
        val bytes = 2 * 1024 * 1024
        var remaining = bytes
        var suffixPosition = 0
        var closed = false
        var checkpoints = 0
        val suffix = "\r\n恢复正常🔧\rprogress\nend".toByteArray(Charsets.UTF_8)
        val input = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining > 0) { val n = minOf(length, remaining); buffer.fill(120, offset, offset + n); remaining -= n; return n }
                if (suffixPosition == suffix.size) return -1
                val n = minOf(length, suffix.size - suffixPosition)
                suffix.copyInto(buffer, offset, suffixPosition, suffixPosition + n); suffixPosition += n; return n
            }
            override fun close() { closed = true }
        }
        val result = mutableListOf<String>()
        DiagnosticReader.read(input, { result.add(it) }, { checkpoints++ })
        assertEquals(listOf(DiagnosticLog.OMITTED_LINE, "恢复正常🔧", "progress", "end"), result)
        assertEquals(0, remaining); assertTrue(closed); assertTrue(checkpoints > 100)
        val fixture = Fixture(); fixture.log.append("password=" + "p".repeat(DiagnosticLog.MAX_LINE))
        assertEquals(DiagnosticLog.OMITTED_LINE + "\n", fixture.log.snapshot().text)
    }

    @Test fun utf8SurvivesSingleByteReadsAndCarriageReturnBoundaries() {
        val data = "中文🔧\r\nemoji🧪\nfinal".toByteArray(Charsets.UTF_8)
        val input = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(1, length))
        }
        val result = mutableListOf<String>(); DiagnosticReader.read(input, { result.add(it) })
        assertEquals(listOf("中文🔧", "emoji🧪", "final"), result)
    }

    @Test(timeout = 5000) fun longOrdinaryNamesDoNotCauseRepeatedUnboundedPrefixScans() {
        val fixture = Fixture()
        val line = "x".repeat(DiagnosticLog.MAX_LINE - 1) + ":"
        repeat(200) { fixture.log.append(line) }
        assertTrue(fixture.log.snapshot().text.endsWith(line + "\n"))
        assertEquals(1, fixture.tasks.size)
    }
}
