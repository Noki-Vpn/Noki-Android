package com.noki.vpn.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnProfileValidator
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object VpnConfigFactory {
    private const val DNS_TAG = "noki-node-dns"
    private const val BOOTSTRAP_TAG = "bootstrap"
    private const val YOUTUBE_TAG = "youtube-local"
    private const val YOUTUBE_RULE = "geosite:youtube"

    // The same V2Fly private set used by the server compiler, including CGNAT/reserved ranges.
    private val privateCidrs = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "255.255.255.255/32", "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8",
    )

    fun build(profile: VlessProfile, advancedSettings: AdvancedSettings, geositeBytes: ByteArray,
              youtubeConnection: YoutubeConnectionConfig? = null): String {
        require(!advancedSettings.ruRelayEnabled) { "ru_wireguard_session_required" }
        require(!advancedSettings.gamingModeEnabled || VpnProfileValidator.isUsable(profile, advancedSettings)) { "gaming_profile_invalid" }
        require(profile.multiHop == null && !advancedSettings.multiHop.enabled ||
            VpnProfileValidator.isUsable(profile, advancedSettings)) { "multihop_profile_invalid" }
        return routedConfig(advancedSettings, geositeBytes, JSONArray().put(proxyOutbound(profile, advancedSettings.gamingModeEnabled)),
            mtu = VpnTunnelPolicy.MTU, address = "10.10.0.2/32", dnsServers = listOf(VpnTunnelPolicy.DNS_SERVER),
            youtubeConnection = youtubeConnection).toString(2)
    }

    fun buildWireGuard(config: BackendRuWireGuardConfig, privateKey: ByteArray,
                       advancedSettings: AdvancedSettings, geositeBytes: ByteArray,
                       youtubeConnection: YoutubeConnectionConfig? = null): String {
        require(advancedSettings.ruRelayEnabled) { "wireguard_requires_ru_relay" }
        val endpoints = JSONArray().put(wireGuardEndpoint(config, privateKey, "proxy", keepalive = 5))
        return routedConfig(advancedSettings, geositeBytes, JSONArray(), config.mtu, config.address, config.dns,
            youtubeConnection, endpoints).toString(2)
    }

    private fun wireGuardEndpoint(config: BackendRuWireGuardConfig, privateKey: ByteArray,
                                  tag: String, keepalive: Int): JSONObject {
        require(privateKey.size == 32 && privateKey.any { it != 0.toByte() } && config.dns.isNotEmpty()) { "wireguard_config_invalid" }
        val peerHost = config.endpoint.substringBeforeLast(':')
        val peerPort = config.endpoint.substringAfterLast(':').toIntOrNull()
        require(peerHost.isNotBlank() && peerPort != null && peerPort in 1..65535) { "wireguard_config_invalid" }
        return JSONObject().put("type", "wireguard").put("tag", tag).put("system", false)
            .put("address", JSONArray().put(config.address)).put("private_key", Base64.getEncoder().encodeToString(privateKey))
            .put("mtu", config.mtu).put("peers", JSONArray().put(JSONObject()
                .put("address", peerHost).put("port", peerPort).put("public_key", config.serverPublicKey)
                .put("allowed_ips", JSONArray(config.allowedIps)).put("persistent_keepalive_interval", keepalive)))
    }

    fun buildProbe(profile: VlessProfile): String = JSONObject()
        .put("log", JSONObject().put("level", "warn"))
        .put("dns", JSONObject().put("servers", JSONArray().put(bootstrapServer())).put("final", BOOTSTRAP_TAG))
        .put("outbounds", JSONArray().put(proxyOutbound(profile)))
        .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true)
            .put("default_domain_resolver", resolver(BOOTSTRAP_TAG)))
        .toString(2)

    private fun routedConfig(settings: AdvancedSettings, geositeBytes: ByteArray, outbounds: JSONArray,
                              mtu: Int, address: String, dnsServers: List<String>,
                              youtubeConnection: YoutubeConnectionConfig?, endpoints: JSONArray = JSONArray()): JSONObject {
        val normalized = DomainRulePolicy.normalizeSettings(settings)
        val selectors = (normalized.alwaysOnDomains + normalized.bypassDomains + DomainRulePolicy.RUSSIAN_RESOURCES_RULE +
            if (normalized.youtubeNoAdsEnabled) listOf(YOUTUBE_RULE) else emptyList())
            .filter { it.startsWith("geosite:") }.toSet()
        val resolved = GeositeAsset.resolveDomains(geositeBytes, selectors)
        val russian = domainMatch(listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE), resolved)
            ?: throw IllegalArgumentException("geosite_category_ru_empty")
        val rules = JSONArray()
        rules.put(JSONObject().put("inbound", JSONArray().put("tun"))
            .put("action", "sniff").put("sniffer", JSONArray().put("http").put("tls")
                .apply { if (normalized.youtubeNoAdsEnabled) put("quic") }))
        rules.put(JSONObject().put("port", JSONArray().put(53)).put("action", "hijack-dns"))
            .put(JSONObject().put("port", JSONArray().put(853)).put("network", JSONArray().put("tcp").put("udp"))
                .put("action", "reject"))
            .put(JSONObject().put("type", "logical").put("mode", "and").put("rules", JSONArray()
                .put(JSONObject().put("domain_suffix", JSONArray().put("cloudflare-dns.com").put("one.one.one.one")))
                .put(JSONObject().put("port", JSONArray().put(443)))
                .put(JSONObject().put("network", JSONArray().put("tcp")))).put("action", "reject"))
            .put(route(JSONObject().put("ip_cidr", JSONArray(privateCidrs)), "direct"))
        val dnsRules = JSONArray()
        if (normalized.youtubeNoAdsEnabled) {
            val youtube = domainMatch(listOf(YOUTUBE_RULE), resolved)
                ?: throw IllegalArgumentException("geosite_youtube_empty")
            rules.put(JSONObject().put("type", "logical").put("mode", "and").put("rules", JSONArray()
                .put(JSONObject(youtube.toString())).put(JSONObject().put("ip_version", 6))).put("action", "reject"))
            rules.put(networkDomainMatch(youtube, "udp").put("action", "reject"))
            rules.put(networkDomainMatch(youtube, "tcp").put("action", "resolve")
                .put("server", DNS_TAG).put("strategy", "ipv4_only"))
            val tcp = networkDomainMatch(youtube, "tcp")
            rules.put(if (youtubeConnection == null) tcp.put("action", "reject") else route(tcp, YOUTUBE_TAG))
            dnsRules.put(JSONObject(youtube.toString()).put("server", DNS_TAG))
            youtubeConnection?.let { local -> outbounds.put(JSONObject().put("type", "socks").put("tag", YOUTUBE_TAG)
                .put("server", "127.0.0.1").put("server_port", local.port).put("version", "5")
                .put("network", "tcp").put("bind_interface", "lo")) }
        }
        // DNS follows the same exclusions as data; a failed underlay lookup never falls back to node DNS.
        // YouTube's explicit protected DNS rule stays ahead of overlapping user exclusions.
        for (match in listOfNotNull(russian.takeIf { DomainRulePolicy.RUSSIAN_RESOURCES_RULE in normalized.bypassDomains },
            JSONObject().put("domain_suffix", JSONArray().put("googleapis.cn")),
            domainMatch(normalized.bypassDomains.filterNot { it == DomainRulePolicy.RUSSIAN_RESOURCES_RULE }, resolved))) {
            rules.put(route(JSONObject(match.toString()), "direct"))
            dnsRules.put(JSONObject(match.toString()).put("server", BOOTSTRAP_TAG))
        }
        rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))
        domainMatch(normalized.alwaysOnDomains, resolved)?.let { rules.put(route(it, "proxy")) }
        // The wire list supplies an explicit primary. Native DNS has no synthesized failover.
        val dns = JSONArray().put(JSONObject().put("type", "tcp").put("tag", DNS_TAG)
            .put("server", dnsServers.first()).put("server_port", 53).put("detour", "proxy")
            .put("domain_resolver", resolver(BOOTSTRAP_TAG)))
        dns.put(bootstrapServer())
        return JSONObject()
            .put("log", JSONObject().put("level", when {
                normalized.connectionLogsEnabled -> "info"
                normalized.errorLogsEnabled -> "error"
                else -> "warn"
            }))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun")
                .put("interface_name", "noki0").put("mtu", mtu).put("address", JSONArray().put(address))
                .put("dns_mode", "hijack").put("dns_address", JSONArray().put(VpnTunnelPolicy.DNS_SERVER))
                // The VPN app is excluded from TUN; mixed's kernel TCP replies would follow its underlay route.
                .put("auto_route", false).put("stack", "gvisor")))
            .put("outbounds", outbounds.put(JSONObject().put("type", "direct").put("tag", "direct")
                .put("domain_resolver", resolver(BOOTSTRAP_TAG))))
            .put("dns", JSONObject().put("servers", dns).put("final", DNS_TAG).put("strategy", "ipv4_only")
                .put("reverse_mapping", true).put("rules", dnsRules))
            .put("route", JSONObject().put("rules", rules).put("final", "proxy").put("auto_detect_interface", true)
                .put("default_domain_resolver", resolver(DNS_TAG)))
            .apply { if (endpoints.length() > 0) put("endpoints", endpoints) }
    }

    private fun route(match: JSONObject, outbound: String): JSONObject = match.put("action", "route").put("outbound", outbound)
    private fun bootstrapServer(): JSONObject = JSONObject().put("type", "local").put("tag", BOOTSTRAP_TAG)
    private fun resolver(server: String): JSONObject = JSONObject().put("server", server).put("strategy", "ipv4_only")

    private fun networkDomainMatch(domains: JSONObject, network: String): JSONObject =
        JSONObject().put("type", "logical").put("mode", "and").put("rules", JSONArray()
            .put(JSONObject(domains.toString())).put(JSONObject().put("network", JSONArray().put(network))))

    private fun domainMatch(selectors: List<String>, geosite: Map<String, JSONObject>): JSONObject? {
        val fields = linkedMapOf<String, LinkedHashSet<String>>()
        for (selector in selectors) {
            val values = if (selector.startsWith("geosite:")) geosite.getValue(selector) else {
                val field = when (selector.substringBefore(':')) {
                    "domain" -> "domain_suffix"
                    "full" -> "domain"
                    "regexp" -> "domain_regex"
                    else -> throw IllegalArgumentException("domain_selector_invalid")
                }
                JSONObject().put(field, JSONArray().put(selector.substringAfter(':')))
            }
            for (field in values.keys()) {
                val array = values.getJSONArray(field)
                val target = fields.getOrPut(field) { linkedSetOf() }
                for (index in 0 until array.length()) target += array.getString(index)
            }
        }
        val branches = fields.filterValues { it.isNotEmpty() }.map { (field, values) -> JSONObject().put(field, JSONArray(values)) }
        return when (branches.size) {
            0 -> null
            1 -> branches.single()
            else -> JSONObject().put("type", "logical").put("mode", "or").put("rules", JSONArray(branches))
        }
    }

    private fun proxyOutbound(profile: VlessProfile, gamingModeEnabled: Boolean = false): JSONObject {
        require(profile.host.isNotBlank()) { "vpn_profile_invalid" }
        val port = profile.port.toIntOrNull()
        require(port != null && port in 1..65535) { "vpn_profile_invalid" }
        require(!profile.enableMux && !profile.randomUserAgent && !profile.allowInsecure &&
            profile.spiderX in listOf("", "/")) { "vpn_profile_option_unsupported" }
        val transport = profile.transport.lowercase(Locale.ROOT)
        val kind = profile.proxyType.lowercase(Locale.ROOT)
        val security = profile.security.lowercase(Locale.ROOT)
        require(security in setOf("tls", "reality") && profile.serverName.isNotBlank()) { "vpn_profile_tls_required" }
        if (kind == "nokinv") {
            require(security == "tls" && transport == "nokinv" && profile.transportMode in setOf("", "quic") &&
                profile.publicKey.isBlank() && profile.shortId.isBlank() && profile.flow.isBlank() &&
                profile.nokinvToken.matches(Regex("[A-Za-z0-9_-]{43}")) && profile.nokinvCredentialGeneration > 0) {
                "vpn_profile_nokinv_invalid"
            }
            return JSONObject().put("tag", "proxy").put("type", "nokinv")
                .put("server", profile.host).put("server_port", port)
                .put("token", profile.nokinvToken).put("credential_generation", profile.nokinvCredentialGeneration)
                .put("transport", "quic")
                .put("mode", if (gamingModeEnabled) "gaming" else "balanced").put("path", profile.path.ifBlank { "/tunnel" })
                .put("shaping", "off").put("web_profile", "coherent")
                .put("tls", JSONObject().put("enabled", true).put("server_name", profile.serverName)
                    .put("min_version", "1.3"))
                .put("domain_resolver", resolver(BOOTSTRAP_TAG))
        }
        require(profile.uuid.isNotBlank()) { "vpn_profile_invalid" }
        if (kind == "naive") {
            require(security == "tls" && transport == "naive" && profile.vpnUsername.isNotBlank() &&
                profile.flow.isBlank() && profile.transportMode.isBlank() && profile.requestHost.isBlank() &&
                profile.path in listOf("", "/") && profile.publicKey.isBlank() && profile.shortId.isBlank() &&
                profile.alpn.isBlank()) { "vpn_profile_transport_unsupported" }
            return JSONObject().put("tag", "proxy").put("type", "naive")
                .put("server", profile.host).put("server_port", port)
                .put("username", profile.vpnUsername).put("password", profile.uuid)
                .put("quic", false).put("udp_over_tcp", JSONObject().put("enabled", true).put("version", 2))
                .put("tls", JSONObject().put("enabled", true).put("server_name", profile.serverName))
                .put("domain_resolver", resolver(BOOTSTRAP_TAG))
        }
        val hy2 = kind in setOf("hysteria", "hysteria2", "hy2")
        if (hy2) {
            require(transport in setOf("hysteria", "hysteria2", "hy2") && security == "tls" &&
                profile.flow.isBlank() && profile.transportMode.isBlank() && profile.requestHost.isBlank() &&
                profile.path in listOf("", "/") && profile.publicKey.isBlank() && profile.shortId.isBlank()) { "vpn_profile_transport_unsupported" }
        } else {
            require(kind == "vless" && transport in setOf("tcp", "raw", "xhttp") &&
                profile.flow in listOf("", "xtls-rprx-vision")) { "vpn_profile_transport_unsupported" }
            if (transport == "xhttp") require(profile.transportMode in listOf("", "stream-up") &&
                security == "reality" && profile.flow.isBlank()) { "vpn_profile_transport_unsupported" }
            else require(profile.transportMode.isBlank() && profile.requestHost.isBlank() && profile.path in listOf("", "/")) {
                "vpn_profile_option_unsupported"
            }
        }
        val tls = JSONObject().put("enabled", true).put("server_name", profile.serverName)
        val alpn = profile.alpn.split(',').map(String::trim).filter(String::isNotEmpty)
        if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn)) else if (hy2) tls.put("alpn", JSONArray().put("h3"))
        if (!hy2) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", profile.fingerprint.ifBlank { "chrome" }))
        if (security == "reality") {
            require(profile.publicKey.isNotBlank()) { "vpn_profile_reality_invalid" }
            tls.put("reality", JSONObject().put("enabled", true).put("public_key", profile.publicKey).put("short_id", profile.shortId))
        } else require(profile.publicKey.isBlank() && profile.shortId.isBlank()) { "vpn_profile_option_unsupported" }
        return JSONObject().put("tag", "proxy").put("type", if (hy2) "hysteria2" else "vless")
            .put("server", profile.host).put("server_port", port).put("tls", tls)
            .put("domain_resolver", resolver(BOOTSTRAP_TAG)).apply {
                if (hy2) put("password", profile.uuid) else {
                    put("uuid", profile.uuid)
                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                    if (transport == "xhttp") put("transport", JSONObject().put("type", "xhttp").put("mode", "stream-up")
                        .put("path", profile.path.ifBlank { "/" }).put("host", profile.requestHost.ifBlank { profile.serverName })
                        .put("x_padding_bytes", "100-1000"))
                }
            }
    }
}
