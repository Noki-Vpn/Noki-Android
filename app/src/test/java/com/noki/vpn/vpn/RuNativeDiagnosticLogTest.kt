package com.noki.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuNativeDiagnosticLogTest {
    private val received = "noki_ru_diag_v1 event=udp_rx seq=1 ms=9 utc_ms=1794767096351 len=96 type=4 receiver=012345abcdef counter=0"

    @Test fun `safe packet metadata survives with a distinct build and attempt marker`() {
        val log = RuNativeDiagnosticLog("1794767096000_1")
        log.append(received)

        val exported = log.drain().joinToString("\n")
        assertTrue(exported.contains("revision=ru_rx_v1_20261115; attempt=1794767096000_1"))
        assertTrue(exported.contains("event=udp_rx seq=1 ms=9 utc_ms=1794767096351 len=96 type=4 receiver=012345abcdef counter=0"))
        assertTrue(log.drain().isEmpty())
    }

    @Test fun `raw native text unknown fields and unsafe values never enter the export`() {
        val log = RuNativeDiagnosticLog("1_1")
        for (line in listOf(
            "outbound connection to private.example:443",
            received.replace("noki_ru_diag_v1", "noki_ru_diag_v2"),
            "$received private_key=secret-value",
            "$received host=private.example",
            "$received reason=unreviewed_reason",
            "$received ip=192.0.2.1",
            "$received ok=2",
            "$received checksum_ok=2",
            "$received\nprivate_key=secret-value",
            received.replace("receiver=012345abcdef", "receiver=private-key"),
            received.replace("counter=0", "counter=18446744073709551616"),
            received.replace("seq=1 ", ""),
            "$received event=close",
        )) log.append(line)

        val exported = log.drain().joinToString("\n")
        assertFalse(exported.contains("event="))
        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("private.example"))
    }

    @Test fun `selector checksum result is retained as a boolean without packet content`() {
        val log = RuNativeDiagnosticLog("1_3")
        log.append("noki_ru_diag_v1 event=inner_rx seq=2 ms=10 utc_ms=1794767096352 ip=4 proto=6 flags=18 tcp_seq=123 tcp_ack=456 checksum_ok=1")
        assertTrue(log.drain().joinToString("\n").contains("checksum_ok=1"))
    }

    @Test fun `buffer is bounded and every exported block keeps its attempt owner`() {
        val log = RuNativeDiagnosticLog("1_2")
        repeat(400) { log.append(received.replace("seq=1 ", "seq=${it + 1} ")) }

        val blocks = log.drain(close = true)
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.all { it.length <= 1800 && it.startsWith("revision=ru_rx_v1_20261115; attempt=1_2") })
        val exported = blocks.joinToString("\n")
        assertEquals(144, Regex("event=udp_rx").findAll(exported).count())
        assertTrue(exported.contains("discarded=256"))
    }

    @Test fun `a closed core rejects delayed callbacks while a replacement keeps its own events`() {
        val old = RuNativeDiagnosticLog("1_1")
        old.append(received)
        assertTrue(old.drain(close = true).joinToString("\n").contains("event=udp_rx"))
        old.append(received.replace("seq=1", "seq=2"))
        assertTrue(old.drain().isEmpty())

        val replacement = RuNativeDiagnosticLog("1_2")
        replacement.append(received)
        val exported = replacement.drain(close = true).joinToString("\n")
        assertTrue(exported.contains("attempt=1_2"))
        assertFalse(exported.contains("attempt=1_1"))
    }
}
