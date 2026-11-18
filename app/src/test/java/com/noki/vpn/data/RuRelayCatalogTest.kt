package com.noki.vpn.data

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuRelayCatalogTest {
    @Test fun selectionUsesOnlyDynamicWeightsAndNeverInventsTcpOnWireGuardNodes() {
        val json = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":null,
            "relays":[{"id":"22222222-2222-4222-8222-222222222222","name":"RU","country_code":"RU",
                "available":true,"pair_ready":true,"weight":999,"selection_weight":4,
                "metrics_at":"2026-11-18T00:00:00Z","probe_ip":"203.0.113.4","probe_host":null,"probe_port":null}],
            "foreign_nodes":[{"id":"11111111-1111-4111-8111-111111111111","name":"NL","country_code":"NL","weight":999}]}""")
        val catalog = RuRelayCatalogJsonParser.parse(json, null)
        assertEquals(4, catalog.relays.single().selectionWeight)
        assertEquals("203.0.113.4", catalog.relays.single().probeIp)
        assertEquals(null, catalog.relays.single().probePort)
        assertEquals(1, catalog.foreignNodes.single().selectionWeight)
        json.getJSONArray("relays").getJSONObject(0).remove("selection_weight")
        assertEquals(1, RuRelayCatalogJsonParser.parse(json, null).relays.single().selectionWeight)
    }
    private val exitId = "11111111-1111-4111-8111-111111111111"
    private val readyId = "22222222-2222-4222-8222-222222222222"
    private val offlineId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun discoversEntryRelaysAndRealForeignParticipantsWithoutSelectedExit() = runBlocking {
        var observed: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"route_version":1,"entry_node_id":null,"exit_node_id":null,"relays":[
                    {"id":"$readyId","name":"Moscow","country_code":"RU","available":true,"pair_ready":true},
                    {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false,"pair_ready":false,"unavailable_reason":"entry_unready"}],
                    "foreign_nodes":[{"id":"$exitId","name":"NL overlay","country_code":"NL"}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example").ruRelayCatalog("access", readyId, "proof")
        assertEquals(listOf(RuForeignNode(exitId, "NL overlay", "NL")), result.foreignNodes)
        assertEquals(listOf(readyId, offlineId), result.relays.map { it.id })
        assertEquals(listOf(true, false), result.relays.map { it.available && it.pairReady })
        assertEquals("entry_unready", result.relays[1].unavailableReason)
        assertEquals(null, observed!!.url.queryParameter("exit_node_id"))
        assertEquals(readyId, observed!!.header("X-Device-Id"))
        assertEquals("proof", observed!!.header("X-Device-Key"))
        assertEquals(null, result.exitNodeId)
    }

    @Test
    fun discoveryRejectsSelectedRouteAndDuplicateOrRussianParticipants() {
        fun discovery() = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":null,
            "relays":[],"foreign_nodes":[{"id":"$exitId","name":"NL overlay","country_code":"NL"}]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(discovery().put("exit_node_id", exitId), null)
        }
        val russian = discovery().apply { getJSONArray("foreign_nodes").getJSONObject(0).put("country_code", "RU") }
        assertThrows(IllegalArgumentException::class.java) { RuRelayCatalogJsonParser.parse(russian, null) }
        val duplicate = discovery().apply { getJSONArray("foreign_nodes").put(getJSONArray("foreign_nodes").getJSONObject(0)) }
        assertThrows(IllegalArgumentException::class.java) { RuRelayCatalogJsonParser.parse(duplicate, null) }
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(discovery().apply { remove("foreign_nodes") }, null)
        }
    }

    @Test
    fun routeDiscoveryUsesTheCommonSamplerWithoutInventedPortsOrAnswers() = runBlocking {
        val targets = listOf(
            RuRelayCatalogEntry(readyId, "Ready", "RU", true, null, "192.0.2.2", 25001, pairReady = true),
            RuRelayCatalogEntry(offlineId, "Unknown", "RU", true, null, pairReady = true),
        )
        val sampler = ClientLatencySampler(tcpConnect = { host, port, _ ->
            assertEquals("192.0.2.2", host); assertEquals(25001, port); 28
        }, icmpPing = { _, _, _ -> error("No unbound fallback") }, tcpAttempts = 1)
        assertEquals(mapOf(readyId to 28), sampler.measureNodes(targets,
            { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }))
        assertEquals(emptyMap<String, Int>(), ClientLatencySampler(tcpConnect = { _, _, _ -> null })
            .measureNodes(targets, { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }))
    }

    @Test
    fun acceptsOnlyReadyValidRelayProbePorts() {
        for ((port, expected) in listOf(30001 to 30001, 0 to null, 65536 to null)) {
            val json = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[
                {"id":"$readyId","name":"Moscow","country_code":"RU","available":true,"pair_ready":true,
                 "probe_host":"8.8.8.8","probe_port":$port}
            ]}""")
            assertEquals(expected, RuRelayCatalogJsonParser.parse(json, exitId).relays.single().probePort)
            json.getJSONArray("relays").getJSONObject(0).put("probe_host", JSONObject.NULL)
            assertEquals(null, RuRelayCatalogJsonParser.parse(json, exitId).relays.single().probePort)
        }
    }

    @Test
    fun parsesAvailableAndUnavailableRuRelaysWithoutInventingAvailability() {
        val catalog = RuRelayCatalogJsonParser.parse(JSONObject("""{
            "route_version":1,"entry_node_id":null,
            "exit_node_id":"$exitId",
            "relays":[
                {"id":"$readyId","name":"Moscow","country_code":"RU","available":true,"pair_ready":true,"unavailable_reason":null,"probe_host":"8.8.8.8"},
                {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false,"unavailable_reason":"offline","probe_host":"1.1.1.1"}
            ]
        }"""), exitId)
        assertEquals(listOf(true, false), catalog.relays.map { it.available })
        assertEquals("offline", catalog.relays[1].unavailableReason)
        assertEquals("8.8.8.8", catalog.relays[0].probeHost)
        assertEquals(null, catalog.relays[1].probeHost)
    }

    @Test
    fun rejectsWrongExitAndMissingUnavailableReason() {
        val json = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(json, offlineId)
        }
        val missingReason = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[
            {"id":"$offlineId","name":"Kazan","country_code":"RU","available":false}
        ]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(missingReason, exitId)
        }
        val overlapping = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[
            {"id":"$exitId","name":"Kazan","country_code":"RU","available":true}
        ]}""")
        assertThrows(IllegalArgumentException::class.java) {
            RuRelayCatalogJsonParser.parse(overlapping, exitId)
        }
    }

    @Test
    fun apiScopesCatalogToAuthenticatedDeviceAndExactExit() = runBlocking {
        var observed: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BackendApiClient(client, "https://api.example")
            .ruRelayCatalog("access", readyId, "proof", exitId)
        assertEquals(exitId, result.exitNodeId)
        assertEquals("/v1/vpn/ru-relays", observed!!.url.encodedPath)
        assertEquals(exitId, observed!!.url.queryParameter("exit_node_id"))
        assertEquals("1", observed!!.url.queryParameter("route_version"))
        assertEquals(null, observed!!.url.queryParameter("entry_node_id"))
        assertEquals(readyId, observed!!.url.queryParameter("device_id"))
        assertEquals("Bearer access", observed!!.header("Authorization"))
        assertEquals(readyId, observed!!.header("X-Device-Id"))
        assertEquals("proof", observed!!.header("X-Device-Key"))
    }

    @Test
    fun apiScopesExplicitForeignEntryAndRejectsAResponseForTheShorterRoute() = runBlocking {
        val entryId = "44444444-4444-4444-8444-444444444444"
        var responseEntry: Any = entryId
        val observed = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed += chain.request()
            val payload = JSONObject().put("route_version", 1).put("entry_node_id", responseEntry)
                .put("exit_node_id", exitId).put("relays", org.json.JSONArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = BackendApiClient(client, "https://api.example")
        val result = api.ruRelayCatalog("access", readyId, "proof", exitId, entryId)
        assertEquals(entryId, result.entryNodeId)
        assertEquals(1, result.routeVersion)
        assertEquals(entryId, observed.single().url.queryParameter("entry_node_id"))
        responseEntry = JSONObject.NULL
        val failure = runCatching { api.ruRelayCatalog("access", readyId, "proof", exitId, entryId) }
        org.junit.Assert.assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, observed.size)
        Unit
    }

    @Test
    fun catalogRequiresVersionAndExplicitNullableEntryForTheExactTuple() {
        fun response() = JSONObject("""{"route_version":1,"entry_node_id":null,"exit_node_id":"$exitId","relays":[]}""")
        for (field in listOf("route_version", "entry_node_id")) {
            assertThrows(field, IllegalArgumentException::class.java) {
                RuRelayCatalogJsonParser.parse(response().apply { remove(field) }, exitId)
            }
        }
        for ((field, value) in listOf("route_version" to 2, "route_version" to "1", "entry_node_id" to readyId)) {
            assertThrows(field, IllegalArgumentException::class.java) {
                RuRelayCatalogJsonParser.parse(response().put(field, value), exitId)
            }
        }
    }
}
