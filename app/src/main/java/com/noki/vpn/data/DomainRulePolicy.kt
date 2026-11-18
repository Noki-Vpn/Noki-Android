package com.noki.vpn.data

import java.net.IDN
import java.net.URI
import java.util.Locale

object DomainRulePolicy {
    const val RUSSIAN_RESOURCES_RULE = "geosite:category-ru"

    private val ipv4Pattern = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    private val hostnameLabelPattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val geositePattern = Regex("^[a-z0-9_@-]+$")

    fun normalize(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.isBlank()) return null

        val prefix = value.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return when (prefix) {
            "regexp" -> value.substringAfter(':').trim().takeIf { it.isNotBlank() }?.let { "regexp:$it" }
            "geosite" -> value.substringAfter(':').trim().lowercase(Locale.ROOT)
                .takeIf { geositePattern.matches(it) }
                ?.let { "geosite:$it" }
            "domain", "full" -> normalizeHost(value.substringAfter(':'))?.let { "$prefix:$it" }
            else -> normalizeHost(extractHost(value))?.let { "domain:$it" }
        }
    }

    fun normalizeList(values: List<String>): List<String> {
        return values.mapNotNull(::normalize).distinct()
    }

    fun normalizeSettings(settings: AdvancedSettings): AdvancedSettings {
        val always = normalizeList(settings.alwaysOnDomains)
        val alwaysSet = always.toSet()
        val bypass = normalizeList(settings.bypassDomains).filterNot(alwaysSet::contains)
        return settings.copy(alwaysOnDomains = always, bypassDomains = bypass)
    }

    fun addAlways(settings: AdvancedSettings, rawValue: String): AdvancedSettings {
        val rule = normalize(rawValue) ?: return settings
        return normalizeSettings(
            settings.copy(
                alwaysOnDomains = settings.alwaysOnDomains + rule,
                bypassDomains = settings.bypassDomains.filterNot { normalize(it) == rule },
            ),
        )
    }

    fun addBypass(settings: AdvancedSettings, rawValue: String): AdvancedSettings {
        val rule = normalize(rawValue) ?: return settings
        return normalizeSettings(
            settings.copy(
                alwaysOnDomains = settings.alwaysOnDomains.filterNot { normalize(it) == rule },
                bypassDomains = settings.bypassDomains + rule,
            ),
        )
    }

    private fun extractHost(value: String): String {
        val candidate = if (value.contains("://")) value else "https://$value"
        return runCatching { URI(candidate).host }.getOrNull().orEmpty()
    }

    private fun normalizeHost(rawHost: String): String? {
        val trimmed = rawHost.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (trimmed.isBlank() || trimmed.contains(':') || ipv4Pattern.matches(trimmed)) return null
        val ascii = runCatching { IDN.toASCII(trimmed) }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
        if (ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { !hostnameLabelPattern.matches(it) }) return null
        return ascii
    }
}
