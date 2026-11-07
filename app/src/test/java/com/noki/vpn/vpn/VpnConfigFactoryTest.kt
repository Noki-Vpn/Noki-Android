package com.noki.vpn.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.BackendVpnSessionJsonParser
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.contractFixture
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConfigFactoryTest {
    @Test fun naiveUsesNativeCronetAndAuthenticatedUotWithoutVlessOptions() {
        val profile = VlessProfile(proxyType = "naive", transport = "naive", security = "tls",
            host = "node.example", port = "443", serverName = "node.example",
            vpnUsername = "test-user", uuid = "test-password")
        val settings = AdvancedSettings(protocol = com.noki.vpn.data.VpnProtocol.NAIVE)
        assertTrue(com.noki.vpn.data.VpnProfileValidator.isUsable(profile, settings))
        val outbound = JSONObject(VpnConfigFactory.buildProbe(profile)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", outbound.getString("type"))
        assertEquals("test-user", outbound.getString("username"))
        assertEquals("test-password", outbound.getString("password"))
        assertFalse(outbound.getBoolean("quic"))
        assertTrue(outbound.getJSONObject("udp_over_tcp").getBoolean("enabled"))
        assertEquals(2, outbound.getJSONObject("udp_over_tcp").getInt("version"))
        assertEquals(setOf("enabled", "server_name"), outbound.getJSONObject("tls").keys().asSequence().toSet())
        assertFalse(outbound.has("uuid"))
        assertFalse(com.noki.vpn.data.VpnProfileValidator.isUsable(profile.copy(vpnUsername = ""), settings))
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.buildProbe(profile.copy(alpn = "h2"))
        }
        assertEquals("", com.noki.vpn.data.RuntimeProfilePolicy.profileAfterServerSelection(profile).vpnUsername)
    }
    @Test fun connectionLogSettingTakesPriorityOverErrorLogs() {
        for ((connection, error, level) in listOf(
            Triple(true, true, "info"),
            Triple(false, true, "error"),
            Triple(false, false, "warn"),
        )) {
            val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
                connectionLogsEnabled = connection, errorLogsEnabled = error,
            ), geosite))
            assertEquals(level, config.getJSONObject("log").getString("level"))
        }
    }

    @Test fun retiredIpTunnelDescriptorDoesNotChangeTheCandidate() {
        val value = JSONObject().put("code", "nv").put("entry_host", "node.example")
            .put("proxy_type", "nokinv").put("transport", "nokinv")
        val candidate = BackendVpnSessionJsonParser.parseEndpointCandidate(value)
        value.put("ip_tunnel", true)
        assertEquals(candidate, BackendVpnSessionJsonParser.parseEndpointCandidate(value))
    }

    @Test fun nokinvUsesDeviceCredentialAndOnlyQuicTransport() {
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val profile = VlessProfile(proxyType = "nokinv", transport = "nokinv", security = "tls",
            host = "node.example", port = "10443", serverName = "node.example", uuid = "",
            nokinvToken = token, nokinvCredentialGeneration = 3)
        val settings = AdvancedSettings(protocol = com.noki.vpn.data.VpnProtocol.NOKINV)
        assertTrue(com.noki.vpn.data.VpnProfileValidator.isUsable(profile, settings))
        val outbound = JSONObject(VpnConfigFactory.buildProbe(profile)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("nokinv", outbound.getString("type"))
        assertEquals(token, outbound.getString("token"))
        assertEquals(3, outbound.getInt("credential_generation"))
        assertEquals("quic", outbound.getString("transport"))
        assertFalse(outbound.has("quic_version"))
        assertEquals("balanced", outbound.getString("mode"))
        assertFalse(outbound.has("tcp_exchange"))
        assertEquals("off", outbound.getString("shaping"))
        assertEquals("coherent", outbound.getString("web_profile"))
        assertEquals("/tunnel", outbound.getString("path"))
        assertEquals("/custom", JSONObject(VpnConfigFactory.buildProbe(profile.copy(path = "/custom")))
            .getJSONArray("outbounds").getJSONObject(0).getString("path"))
        assertFalse(outbound.has("uuid"))
        assertFalse(outbound.has("ip_tunnel"))
        val youtubeConfig = JSONObject(VpnConfigFactory.build(profile, settings.copy(youtubeNoAdsEnabled = true), geosite))
        val sniff = youtubeConfig.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("sniff", sniff.getString("action"))
        assertEquals("[\"tun\"]", sniff.getJSONArray("inbound").toString())
        assertFalse(sniff.has("rules"))
        assertTrue(youtubeConfig.getJSONObject("dns").getBoolean("reverse_mapping"))
        for (mode in listOf("", "quic")) {
            val selected = profile.copy(transportMode = mode)
            for (config in listOf(VpnConfigFactory.buildProbe(selected),
                VpnConfigFactory.build(selected, settings, geosite))) {
                val configured = JSONObject(config).getJSONArray("outbounds").getJSONObject(0)
                assertFalse(configured.has("quic_version"))
                assertEquals("quic", configured.getString("transport"))
            }
        }
        for (retired in listOf("tcp", "auto")) {
            assertThrows(IllegalArgumentException::class.java) {
                VpnConfigFactory.buildProbe(profile.copy(transportMode = retired))
            }
        }
        assertFalse(com.noki.vpn.data.VpnProfileValidator.isUsable(profile.copy(nokinvToken = ""), settings))
        assertTrue(com.noki.vpn.data.VpnProfileValidator.isUsable(profile, AdvancedSettings(ruRelayEnabled = false)))
    }
    private val geosite = GeositeAssetTest.asset(
        "CATEGORY-RU" to listOf(GeositeAssetTest.domain(2, "russian.example")),
        "SAMPLE" to listOf(GeositeAssetTest.domain(3, "sample.example")),
        "YOUTUBE" to listOf(GeositeAssetTest.domain(2, "youtube.com"),
            GeositeAssetTest.domain(2, "googlevideo.com"), GeositeAssetTest.domain(3, "youtubei.googleapis.com")),
    )

    @Test
    fun gamingUsesOnlyNokiNvAndPassesTheNativeGamingMode() {
        val profile = VlessProfile(proxyType = "nokinv", transport = "nokinv", security = "tls",
            host = "node.example", port = "10443", serverName = "node.example",
            nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            nokinvCredentialGeneration = 1)
        val settings = AdvancedSettings(gamingModeEnabled = true)
        val outbound = JSONObject(VpnConfigFactory.build(profile, settings, geosite))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("nokinv", outbound.getString("type"))
        assertEquals("gaming", outbound.getString("mode"))
        assertFalse(outbound.has("quic_version"))
        for (mode in listOf("", "quic")) {
            val cached = JSONObject(VpnConfigFactory.build(profile.copy(transportMode = mode), settings, geosite))
                .getJSONArray("outbounds").getJSONObject(0)
            assertEquals("quic", cached.getString("transport"))
            assertEquals("gaming", cached.getString("mode"))
            assertFalse(cached.has("quic_version"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.build(profile(), settings, geosite)
        }
    }

    @Test
    fun nokinvQuicAndGamingUseStandardSessionRoutingWithAndWithoutYoutube() {
        val profile = VlessProfile(proxyType = "nokinv", transport = "nokinv", security = "tls",
            host = "node.example", port = "10443", serverName = "node.example",
            nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            nokinvCredentialGeneration = 1)
        for (transport in listOf("", "quic")) for (gaming in listOf(false, true)) {
            for (selection in EndpointSelectionMode.entries) for (youtube in listOf(false, true)) {
                val settings = AdvancedSettings(protocol = com.noki.vpn.data.VpnProtocol.NOKINV,
                    gamingModeEnabled = gaming, endpointSelectionMode = selection, youtubeNoAdsEnabled = youtube,
                    bypassDomains = listOf("geosite:youtube", "domain:local.example"))
                val youtubeConnection = if (youtube) youtubeLocal() else null
                val config = JSONObject(VpnConfigFactory.build(profile.copy(transportMode = transport),
                    settings, geosite, youtubeConnection))
                val standard = JSONObject(VpnConfigFactory.build(profile(), settings.copy(
                    protocol = com.noki.vpn.data.VpnProtocol.AUTO, gamingModeEnabled = false), geosite, youtubeConnection))
                assertEquals(standard.getJSONObject("route").toString(), config.getJSONObject("route").toString())
                assertEquals(standard.getJSONObject("dns").toString(), config.getJSONObject("dns").toString())
                if (youtube) {
                    assertYoutubeRoute(config)
                    assertYoutubeTransport(config)
                    val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
                    assertEquals("noki-node-dns", dnsRules.getJSONObject(0).getString("server"))
                    assertEquals("bootstrap", (1 until dnsRules.length()).map(dnsRules::getJSONObject)
                        .single { it.toString().contains("googlevideo.com") }.getString("server"))
                }
                val outbound = config.getJSONArray("outbounds").getJSONObject(0)
                assertFalse(outbound.has("ip_tunnel"))
                assertEquals("quic", outbound.getString("transport"))
                assertEquals(if (gaming) "gaming" else "balanced", outbound.getString("mode"))
                assertEquals("bootstrap", outbound.getJSONObject("domain_resolver").getString("server"))
                val dns = config.getJSONObject("dns")
                val resolver = dns.getJSONArray("servers").getJSONObject(0)
                assertEquals("tcp", resolver.getString("type"))
                assertEquals(VpnTunnelPolicy.DNS_SERVER, resolver.getString("server"))
                assertEquals(53, resolver.getInt("server_port"))
                assertEquals("proxy", resolver.getString("detour"))
                assertEquals("noki-node-dns", dns.getString("final"))
                assertEquals("noki-node-dns", config.getJSONObject("route")
                    .getJSONObject("default_domain_resolver").getString("server"))
                val rules = config.getJSONObject("route").getJSONArray("rules")
                val sniff = (0 until rules.length()).map(rules::getJSONObject)
                    .firstOrNull { it.optString("action") == "sniff" }
                assertTrue(sniff != null)
                assertEquals("[\"tun\"]", sniff!!.getJSONArray("inbound").toString())
                assertFalse(sniff.has("rules"))
                assertEquals("gvisor", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
            }
        }
    }

    @Test
    fun nokinvDnsUsesTheExitThroughTheTunnelAndKeepsTheLocalTunDnsAddress() {
        for (host in listOf("93.184.216.34", "node.example")) for (transport in listOf("quic")) {
            val profile = VlessProfile(proxyType = "nokinv", transport = "nokinv", transportMode = transport,
                security = "tls",
                host = host, port = "10443", serverName = "node.example", uuid = "",
                nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
                nokinvCredentialGeneration = 1)
            val config = JSONObject(VpnConfigFactory.build(profile,
                AdvancedSettings(protocol = com.noki.vpn.data.VpnProtocol.NOKINV), geosite))
            val dns = config.getJSONObject("dns")
            val nodeDns = dns.getJSONArray("servers").getJSONObject(0)
            assertEquals(VpnTunnelPolicy.DNS_SERVER, nodeDns.getString("server"))
            assertEquals(53, nodeDns.getInt("server_port"))
            assertEquals("tcp", nodeDns.getString("type"))
            assertEquals("proxy", nodeDns.getString("detour"))
            assertEquals("bootstrap", nodeDns.getJSONObject("domain_resolver").getString("server"))
            assertEquals("ipv4_only", nodeDns.getJSONObject("domain_resolver").getString("strategy"))
            assertEquals("local", dns.getJSONArray("servers").getJSONObject(1).getString("type"))
            assertEquals("noki-node-dns", dns.getString("final"))
            assertEquals("noki-node-dns", config.getJSONObject("route")
                .getJSONObject("default_domain_resolver").getString("server"))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            assertEquals(transport, outbound.getString("transport"))
            assertFalse(outbound.has("ip_tunnel"))
            assertEquals("bootstrap", outbound.getJSONObject("domain_resolver").getString("server"))
            val probeOutbound = JSONObject(VpnConfigFactory.buildProbe(profile)).getJSONArray("outbounds").getJSONObject(0)
            assertFalse(probeOutbound.has("ip_tunnel"))
            assertEquals("bootstrap", probeOutbound.getJSONObject("domain_resolver").getString("server"))
            assertEquals("198.18.0.1", config.getJSONArray("inbounds").getJSONObject(0)
                .getJSONArray("dns_address").getString(0))
        }
    }

    @Test
    fun emittedConfigurationUsesNativeRouteAndInboundContracts() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(), geosite))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", inbound.getString("type"))
        assertEquals(1500, inbound.getInt("mtu"))
        assertEquals("gvisor", inbound.getString("stack"))
        assertEquals("10.10.0.2/32", inbound.getJSONArray("address").getString(0))
        assertFalse(inbound.getBoolean("auto_route"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(config.getJSONObject("route").getBoolean("auto_detect_interface"))
        assertFalse(config.has("routing"))
        assertFalse(config.has("stats"))
        assertFalse(config.has("policy"))
        assertFalse(inbound.has("include_package"))
        assertFalse(inbound.has("exclude_package"))
    }

    @Test
    fun fourProfilesKeepTheirNativeTransportAndTlsIdentity() {
        val profiles = primaryProfiles()
        for ((index, candidate) in profiles.withIndex()) {
            val config = JSONObject(VpnConfigFactory.build(candidate, AdvancedSettings(), geosite))
            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            assertEquals(candidate.host, outbound.getString("server"))
            assertEquals(candidate.port.toInt(), outbound.getInt("server_port"))
            assertEquals(if (index == 3) "hysteria2" else "vless", outbound.getString("type"))
            val tls = outbound.getJSONObject("tls")
            assertTrue(tls.getBoolean("enabled"))
            assertEquals(candidate.serverName, tls.getString("server_name"))
            if (index < 2) {
                assertEquals(candidate.publicKey, tls.getJSONObject("reality").getString("public_key"))
                assertEquals(candidate.shortId, tls.getJSONObject("reality").getString("short_id"))
            } else assertFalse(tls.has("reality"))
            if (index == 0) {
                val transport = outbound.getJSONObject("transport")
                assertEquals("xhttp", transport.getString("type"))
                assertEquals("stream-up", transport.getString("mode"))
                assertEquals("100-1000", transport.getString("x_padding_bytes"))
                assertEquals("/stream", transport.getString("path"))
                assertEquals("front.example", transport.getString("host"))
            } else assertFalse(outbound.has("transport"))
            if (index == 3) {
                assertEquals(candidate.uuid, outbound.getString("password"))
                assertEquals("h3", tls.getJSONArray("alpn").getString(0))
                assertFalse(tls.has("utls"))
            } else assertEquals(candidate.uuid, outbound.getString("uuid"))
        }
    }

    @Test
    fun backendHysteriaWireValueKeepsTheHy2Profile() {
        val candidate = profile().copy(proxyType = "hysteria", transport = "hysteria", security = "tls",
            port = "2443", flow = "", publicKey = "", shortId = "", alpn = "h3")
        val outbound = JSONObject(VpnConfigFactory.build(candidate, AdvancedSettings(), geosite))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", outbound.getString("type"))
        assertEquals(2443, outbound.getInt("server_port"))
        assertEquals(candidate.uuid, outbound.getString("password"))
        assertFalse(outbound.has("uuid"))
    }

    @Test
    fun russianDirectIsDisabledWhenThePresetIsOff() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            alwaysOnDomains = listOf("domain:russian.example", DomainRulePolicy.RUSSIAN_RESOURCES_RULE),
            bypassDomains = listOf("domain:local.example"),
        ), geosite))
        val rules = rules(config)
        val russian = rules.indexOfFirst { it.optJSONArray("domain_suffix")?.toString() == "[\"russian.example\"]" && it.optString("outbound") == "direct" }
        val vpn = rules.indexOfFirst { it.optString("outbound") == "proxy" }
        assertEquals(-1, russian)
        assertTrue(vpn >= 0)
        assertFalse(config.getJSONObject("dns").getJSONArray("rules").toString().contains("russian.example"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(rules.any { it.optJSONArray("domain_suffix")?.toString() == "[\"local.example\"]" && it.optString("outbound") == "direct" })
        assertFalse(config.toString().contains("geosite:"))
    }

    @Test
    fun dnsAndBlocksRemainBeforeDirectAndDnsDialerUsesTheTunnel() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            bypassDomains = listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE)), geosite))
        val rules = rules(config)
        assertEquals("sniff", rules[0].getString("action"))
        assertEquals("hijack-dns", rules[1].getString("action"))
        assertEquals("[53]", rules[1].getJSONArray("port").toString())
        assertEquals("reject", rules[2].getString("action"))
        assertEquals("[853]", rules[2].getJSONArray("port").toString())
        assertEquals("and", rules[3].getString("mode"))
        assertEquals("reject", rules[3].getString("action"))
        val constraints = rules[3].getJSONArray("rules")
        assertEquals("[\"cloudflare-dns.com\",\"one.one.one.one\"]",
            constraints.getJSONObject(0).getJSONArray("domain_suffix").toString())
        assertEquals("[443]", constraints.getJSONObject(1).getJSONArray("port").toString())
        assertEquals("[\"tcp\"]", constraints.getJSONObject(2).getJSONArray("network").toString())
        val dns = config.getJSONObject("dns")
        val server = dns.getJSONArray("servers").getJSONObject(0)
        assertEquals("tcp", server.getString("type"))
        assertEquals("198.18.0.1", server.getString("server"))
        assertEquals("proxy", server.getString("detour"))
        assertEquals("ipv4_only", dns.getString("strategy"))
        assertEquals("bootstrap", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
        assertEquals("[\"russian.example\"]", dns.getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").toString())
        assertEquals("bootstrap", config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("domain_resolver").getString("server"))
        assertTrue(rules.any { it.optInt("ip_version") == 6 && it.optString("action") == "reject" })
        assertTrue(rules.any { it.optJSONArray("ip_cidr")?.toString()?.contains("100.64.0.0/10") == true })
        assertFalse(config.toString().contains("ip_is_private"))
    }

    @Test
    fun excludedDomainDnsReusesTheDirectTrafficMatcherOnEveryPrimaryPath() {
        val settings = AdvancedSettings(
            alwaysOnDomains = listOf("full:protected.example"),
            bypassDomains = listOf("domain:local.example", "full:exact.example", "regexp:^corp[.]",
                "geosite:sample", "full:protected.example"),
        )
        val nokinv = VlessProfile(proxyType = "nokinv", transport = "nokinv", transportMode = "quic",
            security = "tls", host = "node.example", port = "10443", serverName = "node.example",
            nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
            nokinvCredentialGeneration = 1)
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820",
            java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1"), 1280, listOf("0.0.0.0/0"))
        val configs = (primaryProfiles() + nokinv).map {
            JSONObject(VpnConfigFactory.build(it, settings, geosite))
        } + JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 },
            settings.copy(ruRelayEnabled = true), geosite))
        for (config in configs) {
            val trafficMatches = rules(config).filter {
                it.optString("outbound") == "direct" && !it.has("ip_cidr")
            }.map { JSONObject(it.toString()).apply { remove("action"); remove("outbound") } }
            val dns = config.getJSONObject("dns")
            val dnsRules = dns.getJSONArray("rules")
            val underlayMatches = (0 until dnsRules.length()).map(dnsRules::getJSONObject)
                .filter { it.optString("server") == "bootstrap" }
                .map { JSONObject(it.toString()).apply { remove("server") } }
            assertEquals(2, trafficMatches.size)
            assertEquals(trafficMatches.size, underlayMatches.size)
            assertEquals(trafficMatches.map(JSONObject::toString).toSet(), underlayMatches.map(JSONObject::toString).toSet())
            val custom = underlayMatches.single { it.toString().contains("local.example") }
            assertTrue(custom.toString().contains("domain_regex"))
            assertTrue(custom.toString().contains("exact.example"))
            assertTrue(custom.toString().contains("sample.example"))
            assertFalse(custom.toString().contains("protected.example"))
            assertFalse(custom.toString().contains("ip_cidr"))
            assertFalse(custom.toString().contains("process"))
            val servers = dns.getJSONArray("servers")
            val underlay = (0 until servers.length()).map(servers::getJSONObject)
                .single { it.optString("tag") == "bootstrap" }
            assertEquals("local", underlay.getString("type"))
            assertFalse(underlay.has("detour"))
            assertEquals("noki-node-dns", dns.getString("final"))
            assertEquals("proxy", servers.getJSONObject(0).getString("detour"))
            assertEquals("hijack-dns", rules(config).first { it.has("port") }.getString("action"))
        }
    }

    @Test
    fun youtubeProtectedDnsPrecedesAnOverlappingDirectDnsException() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            youtubeNoAdsEnabled = true, bypassDomains = listOf("geosite:youtube"),
        ), geosite, youtubeLocal()))
        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        val protected = dnsRules.getJSONObject(0)
        assertEquals("noki-node-dns", protected.getString("server"))
        val direct = (1 until dnsRules.length()).map(dnsRules::getJSONObject)
            .single { it.toString().contains("googlevideo.com") }
        assertEquals("bootstrap", direct.getString("server"))
        val protectedMatch = JSONObject(protected.toString()).apply { remove("server") }
        val directMatch = JSONObject(direct.toString()).apply { remove("server") }
        assertEquals(protectedMatch.toString(), directMatch.toString())
        val ordinary = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            youtubeNoAdsEnabled = true, bypassDomains = listOf("domain:local.example"),
        ), geosite, youtubeLocal()))
        val ordinaryDns = ordinary.getJSONObject("dns").getJSONArray("rules")
        assertFalse(ordinaryDns.getJSONObject(0).toString().contains("local.example"))
        assertEquals("bootstrap", (0 until ordinaryDns.length()).map(ordinaryDns::getJSONObject)
            .single { it.toString().contains("local.example") }.getString("server"))
        val traffic = rules(ordinary)
        assertTrue(traffic.indexOfFirst { it.optString("outbound") == "youtube-local" } <
            traffic.indexOfFirst { it.toString().contains("local.example") })
        assertEquals("direct", traffic.single { it.toString().contains("local.example") }.getString("outbound"))
    }

    @Test
    fun wireGuardHasOnlyTheReadyRuPeerAndKeepsDirectForMultiHop() {
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1"), 1280, listOf("0.0.0.0/0"))
        for (multiHop in listOf(false, true)) {
            val config = JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 },
                AdvancedSettings(ruRelayEnabled = true, multiHop = MultiHopSettings(enabled = multiHop),
                    bypassDomains = listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE)), geosite))
            val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
            assertEquals("wireguard", endpoint.getString("type"))
            assertFalse(endpoint.getBoolean("system"))
            assertEquals("10.240.1.2/32", endpoint.getJSONArray("address").getString(0))
            assertEquals(1280, endpoint.getInt("mtu"))
            val peers = endpoint.getJSONArray("peers")
            assertEquals(1, peers.length())
            val peer = peers.getJSONObject(0)
            assertEquals("203.0.113.10", peer.getString("address"))
            assertEquals(51820, peer.getInt("port"))
            assertEquals(wg.serverPublicKey, peer.getString("public_key"))
            assertEquals(5, peer.getInt("persistent_keepalive_interval"))
            assertEquals(1280, config.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
            assertEquals("10.240.1.2/32", config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").getString(0))
            assertEquals("gvisor", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
            assertEquals("1.1.1.1", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("server"))
            assertEquals("direct", rules(config).first { it.has("domain_suffix") && it.getJSONArray("domain_suffix").toString().contains("russian.example") }.getString("outbound"))
            assertFalse(config.toString().contains("vless"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 }, AdvancedSettings(), geosite)
        }
    }

    @Test
    fun knownEmptyAttributeMatchCannotCreateAnUnconditionalVpnRule() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(
            alwaysOnDomains = listOf("geosite:sample@missing"),
        ), geosite))
        assertFalse(rules(config).any { it.optString("outbound") == "proxy" })
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.build(profile(), AdvancedSettings(alwaysOnDomains = listOf("geosite:unknown")), geosite)
        }
    }

    @Test
    fun wireGuardDnsListKeepsTheExplicitPrimaryWithoutInventingFailover() {
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1", "8.8.8.8"), 1280, listOf("0.0.0.0/0"))
        val config = JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 }, AdvancedSettings(ruRelayEnabled = true), geosite))
        val dns = config.getJSONObject("dns").getJSONArray("servers")
        assertEquals(2, dns.length())
        assertEquals("1.1.1.1", dns.getJSONObject(0).getString("server"))
        assertEquals("local", dns.getJSONObject(1).getString("type"))
        assertFalse(config.toString().contains("8.8.8.8"))
    }

    @Test
    fun wireGuardTunDnsDoesNotAdvertiseItsRemotePublicResolver() {
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1", "8.8.8.8"), 1280, listOf("0.0.0.0/0"))
        val config = JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 },
            AdvancedSettings(ruRelayEnabled = true), geosite))

        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("hijack", inbound.getString("dns_mode"))
        assertEquals("[\"198.18.0.1\"]", inbound.getJSONArray("dns_address").toString())
        val resolver = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("tcp", resolver.getString("type"))
        assertEquals("1.1.1.1", resolver.getString("server"))
        assertEquals(53, resolver.getInt("server_port"))
        assertEquals("proxy", resolver.getString("detour"))
    }

    @Test
    fun mixedSelectorsBecomeOrBranchesInsteadOfAndOrSilentDrops() {
        val config = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(alwaysOnDomains = listOf(
            "domain:secure.example", "full:exact.example", "regexp:^private[.]", "geosite:sample",
        )), geosite))
        val vpn = rules(config).single { it.optString("outbound") == "proxy" }
        assertEquals("or", vpn.getString("mode"))
        val branches = vpn.getJSONArray("rules")
        assertTrue((0 until branches.length()).any { branches.getJSONObject(it).has("domain_regex") })
        assertTrue(branches.toString().contains("exact.example"))
        assertTrue(branches.toString().contains("sample.example"))
        assertTrue(branches.toString().contains("secure.example"))
    }

    @Test
    fun retiredAndUnsupportedProfilesFailBeforeNativePublication() {
        val invalid = listOf(
            profile().copy(transport = "httpupgrade"), profile().copy(transport = "unknown"),
            profile().copy(transport = "xhttp", transportMode = "packet-up"),
            profile().copy(enableMux = true), profile().copy(randomUserAgent = true),
            profile().copy(allowInsecure = true), profile().copy(spiderX = "/custom"),
        )
        invalid.forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { VpnConfigFactory.buildProbe(candidate) } }
        assertThrows(IllegalArgumentException::class.java) {
            VpnConfigFactory.build(profile(), AdvancedSettings(multiHop = MultiHopSettings(enabled = true)), geosite)
        }
    }

    @Test
    fun probeHasOnlyTheNativeProxyAndNoTunOrGeositeDependency() {
        val probe = JSONObject(VpnConfigFactory.buildProbe(profile()))
        assertFalse(probe.has("inbounds"))
        assertFalse(probe.has("endpoints"))
        assertEquals(1, probe.getJSONArray("outbounds").length())
        assertEquals("vless", probe.getJSONArray("outbounds").getJSONObject(0).getString("type"))
        assertTrue(probe.getJSONObject("route").getBoolean("auto_detect_interface"))
        assertFalse(probe.toString().contains("geosite"))
    }

    @Test
    fun youtubeConnectsDirectlyForEveryPrimaryProtocolAndOverridesUserBypass() {
        val settings = AdvancedSettings(youtubeNoAdsEnabled = true, bypassDomains = listOf("geosite:youtube"))
        val naive = profile().copy(proxyType = "naive", transport = "naive", security = "tls", flow = "",
            publicKey = "", shortId = "", vpnUsername = "main-user")
        for (primary in primaryProfiles() + naive) {
            val config = JSONObject(VpnConfigFactory.build(primary, settings, geosite, youtubeLocal()))
            assertYoutubeRoute(config)
            assertYoutubeTransport(config)
            assertFalse(config.has("endpoints"))
            assertEquals(primary.host, config.getJSONArray("outbounds").getJSONObject(0).getString("server"))
            val routeRules = rules(config)
            assertTrue(routeRules.indexOfFirst { it.optString("outbound") == "youtube-local" } <
                routeRules.indexOfFirst { it.optString("outbound") == "direct" && it.toString().contains("googlevideo.com") })
        }
    }

    @Test
    fun youtubeConnectsDirectlyWithoutReplacingTheSelectedNatMultiHopEntry() {
        val session = BackendVpnSessionJsonParser.parse(contractFixture("multihop_session_v2.json"))
        val route = requireNotNull(session.multiHop)
        val settings = AdvancedSettings(youtubeNoAdsEnabled = true, multiHop = route.selection)
        for (candidate in session.endpointCandidates) {
            val profile = EndpointSelector.profileFromCandidate(session, candidate)
            val config = JSONObject(VpnConfigFactory.build(profile, settings, geosite, youtubeLocal()))
            assertYoutubeRoute(config)
            assertYoutubeTransport(config)
            assertEquals(route.entryIp, config.getJSONArray("outbounds").getJSONObject(0).getString("server"))
            assertEquals(candidate.entryPort, config.getJSONArray("outbounds").getJSONObject(0).getInt("server_port"))
        }
    }

    @Test
    fun youtubeConnectsDirectlyAlongsideTheWireGuardEndpointForBothRuRoutes() {
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1"), 1280, listOf("0.0.0.0/0"))
        for (multiHop in listOf(false, true)) {
            val settings = AdvancedSettings(ruRelayEnabled = true, youtubeNoAdsEnabled = true,
                multiHop = MultiHopSettings(enabled = multiHop))
            val config = JSONObject(VpnConfigFactory.buildWireGuard(wg, ByteArray(32) { 1 }, settings, geosite, youtubeLocal()))
            assertYoutubeRoute(config)
            assertYoutubeTransport(config)
            assertEquals(1, config.getJSONArray("endpoints").length())
            assertEquals("proxy", config.getJSONArray("endpoints").getJSONObject(0).getString("tag"))
            assertEquals(wg.endpoint.substringBefore(':'), config.getJSONArray("endpoints").getJSONObject(0)
                .getJSONArray("peers").getJSONObject(0).getString("address"))
        }
    }

    @Test
    fun youtubeDisabledDoesNotInstallTheSecondaryRouteAndMissingProfileCannotFallThrough() {
        val disabled = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(), geosite, youtubeLocal()))
        assertFalse(disabled.toString().contains("youtube-local"))
        assertFalse(disabled.toString().contains("youtube-exit.example"))
        val missing = JSONObject(VpnConfigFactory.build(profile(), AdvancedSettings(youtubeNoAdsEnabled = true), geosite))
        val youtubeRules = rules(missing).filter { it.toString().contains("googlevideo.com") }
        assertEquals(4, youtubeRules.size)
        assertEquals(3, youtubeRules.count { it.getString("action") == "reject" && !it.has("outbound") })
        assertEquals("ipv4_only", youtubeRules.single { it.getString("action") == "resolve" }.getString("strategy"))
        assertEquals("proxy", missing.getJSONObject("route").getString("final"))
    }

    private fun assertYoutubeRoute(config: JSONObject) {
        assertEquals(1, config.getJSONArray("inbounds").length())
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertFalse(config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").toString().contains("10.241"))
        val rejects = rules(config).filter { it.toString().contains("googlevideo.com") && it.optString("action") == "reject" }
        assertTrue(rejects.any { it.getJSONArray("rules").getJSONObject(1).optInt("ip_version") == 6 })
        val udp = rejects.single { it.getJSONArray("rules").getJSONObject(1).has("network") }
        assertEquals("[\"udp\"]", udp.getJSONArray("rules").getJSONObject(1).getJSONArray("network").toString())
        assertFalse(udp.getJSONArray("rules").getJSONObject(1).has("port"))
        val resolve = rules(config).single { it.optString("action") == "resolve" }
        assertEquals("ipv4_only", resolve.getString("strategy"))
        assertEquals("noki-node-dns", resolve.getString("server"))
        assertFalse(resolve.toString().contains("google.com"))
        assertEquals("[\"tcp\"]", rules(config).single { it.optString("outbound") == "youtube-local" }
            .getJSONArray("rules").getJSONObject(1).getJSONArray("network").toString())
        val dns = config.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertTrue(dns.toString().contains("googlevideo.com"))
        assertEquals("noki-node-dns", dns.getString("server"))
    }

    private fun assertYoutubeTransport(config: JSONObject) {
        val outbounds = config.getJSONArray("outbounds")
        val youtube = (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.getString("tag") == "youtube-local" }
        assertEquals("socks", youtube.getString("type"))
        assertEquals("127.0.0.1", youtube.getString("server"))
        assertEquals(30_001, youtube.getInt("server_port"))
        assertEquals("5", youtube.getString("version"))
        assertEquals("tcp", youtube.getString("network"))
        assertEquals("lo", youtube.getString("bind_interface"))
        assertFalse(youtube.has("uuid"))
        assertFalse(youtube.has("tls"))
        assertFalse(youtube.has("transport"))
        assertFalse(youtube.has("detour"))
    }

    private fun youtubeLocal() = YoutubeConnectionConfig(30_001)

    private fun primaryProfiles() = listOf(
        profile().copy(transport = "xhttp", transportMode = "stream-up", port = "443", flow = "", path = "/stream", requestHost = "front.example"),
        profile(),
        profile().copy(security = "tls", port = "9443", publicKey = "", shortId = ""),
        profile().copy(proxyType = "hysteria2", transport = "hysteria2", security = "tls", port = "2443", flow = "", publicKey = "", shortId = "", alpn = "h3"),
    )

    private fun rules(config: JSONObject): List<JSONObject> = config.getJSONObject("route").getJSONArray("rules")
        .let { array -> (0 until array.length()).map(array::getJSONObject) }

    private fun profile() = VlessProfile(host = "lv.example", port = "8443",
        uuid = "75e9f9e8-a9f8-4d42-9de1-f91f1a210991", flow = "xtls-rprx-vision", security = "reality",
        serverName = "www.example.com", publicKey = "pOCSkrZRwni5dyxWn1-puxPZBrRqtoyd-dwrRAn4ogk", shortId = "abcd")
}
