package com.noki.vpn

import com.noki.vpn.data.clientLatencyTargetKey
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.RuForeignNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientLatencyRequestTargetTest {
    @Test fun changedPhysicalProbeAddressCannotReuseAnotherRuPing() {
        val before = RuForeignNode("node", "Exit", "NL", probeIp = "203.0.113.1")
        val after = before.copy(probeIp = "203.0.113.2")
        assertNotEquals(clientLatencyRequestTarget(emptyList(), true, listOf(before)),
            clientLatencyRequestTarget(emptyList(), true, listOf(after)))
        val state = BackendSyncCoordinator.withCachedClientLatencies(AppUiState(ruForeignNodes = listOf(after)),
            mapOf(clientLatencyTargetKey(before) to 20))
        assertNull(state.ruForeignNodes.single().latencyMs)
    }
    @Test
    fun `direct and relay requests cannot reuse the same latency job`() {
        val locations = listOf(location("lv", "lv.example"))
        assertNotEquals(clientLatencyRequestTarget(locations), clientLatencyRequestTarget(locations, true,
            foreignNodes = listOf(RuForeignNode("overlay", "LV overlay", "LV"))))
    }

    @Test
    fun `relay latency belongs to distinct available exits`() {
        val nodes = listOf(RuForeignNode("exit-b", "Other", "NL"), RuForeignNode("exit-a", "Exit", "LV"))
        assertEquals(nodes.map(::clientLatencyTargetKey).sorted(),
            clientLatencyRequestTarget(emptyList(), true, nodes + nodes.first()))
        assertTrue(clientLatencyRequestTarget(emptyList(), true).isEmpty())
    }

    @Test
    fun `latency target ignores order but changes with endpoint host`() {
        val pl = location("pl", "pl.example")
        val lv = location("lv", "lv.example")

        assertEquals(
            clientLatencyRequestTarget(listOf(pl, lv)),
            clientLatencyRequestTarget(listOf(lv, pl)),
        )
        assertNotEquals(
            clientLatencyRequestTarget(listOf(pl)),
            clientLatencyRequestTarget(listOf(location("pl", "new-pl.example"))),
        )
    }

    @Test
    fun `latency cache belongs to exact code and host`() {
        val oldHost = location("pl", "old-pl.example")
        val newHost = location("pl", "new-pl.example")
        val cache = mapOf(requireNotNull(clientLatencyTargetKey(oldHost)) to 20)

        val sameHostState = BackendSyncCoordinator.withCachedClientLatencies(
            state = AppUiState(locations = listOf(oldHost)),
            clientLatencyByTarget = cache,
        )
        val newHostState = BackendSyncCoordinator.withCachedClientLatencies(
            state = AppUiState(locations = listOf(newHost)),
            clientLatencyByTarget = cache,
        )

        assertEquals(20, sameHostState.locations.single().latencyMs)
        assertFalse(BackendSyncCoordinator.hasMissingClientLatency(sameHostState.locations, cache))
        assertNull(newHostState.locations.single().latencyMs)
        assertTrue(BackendSyncCoordinator.hasMissingClientLatency(newHostState.locations, cache))
    }

    @Test
    fun `overlay latency presentation does not require a direct location`() {
        val node = RuForeignNode("overlay", "NL overlay", "NL")
        val state = BackendSyncCoordinator.withCachedClientLatencies(AppUiState(ruForeignNodes = listOf(node)),
            mapOf(clientLatencyTargetKey(node) to 34))
        assertEquals(34, state.ruForeignNodes.single().latencyMs)
        assertTrue(state.locations.isEmpty())
        assertNull(BackendSyncCoordinator.withCachedClientLatencies(state, emptyMap()).ruForeignNodes.single().latencyMs)
    }

    private fun location(code: String, host: String) = ServerLocation(
        code = code,
        country = code.uppercase(),
        city = code.uppercase(),
        host = host,
        isOnline = true,
    )
}
