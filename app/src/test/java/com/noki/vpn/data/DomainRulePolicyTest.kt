package com.noki.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulePolicyTest {
    @Test fun russianPresetUsesTheSameSavedListOwnerAsOtherRules() {
        val preset = DomainRulePolicy.RUSSIAN_RESOURCES_RULE
        val enabled = DomainRulePolicy.addBypass(AdvancedSettings(alwaysOnDomains = listOf(preset)), preset)
        assertEquals(listOf(preset), enabled.bypassDomains)
        assertTrue(enabled.alwaysOnDomains.isEmpty())
        val explicitVpn = DomainRulePolicy.addAlways(enabled, preset)
        assertEquals(listOf(preset), explicitVpn.alwaysOnDomains)
        assertTrue(explicitVpn.bypassDomains.isEmpty())
    }

    @Test
    fun geositeGrammarKeepsPositiveAttributeConjunctionAndRejectsNewSyntax() {
        assertEquals("geosite:category-ru@cn@ads", DomainRulePolicy.normalize(" GEOSITE:Category-RU@CN@ads "))
        assertNull(DomainRulePolicy.normalize("geosite:youtube@!cn"))
        assertNull(DomainRulePolicy.normalize("geosite:youtube@cn=true"))
        assertEquals("regexp:^Private[.]", DomainRulePolicy.normalize("regexp:^Private[.]"))
    }

    @Test
    fun urlBecomesAnchoredDomainRule() {
        assertEquals(
            "domain:example.com",
            DomainRulePolicy.normalize(" https://Example.COM/path "),
        )
    }

    @Test
    fun ipAndInvalidHostAreRejected() {
        assertNull(DomainRulePolicy.normalize("192.168.1.1"))
        assertNull(DomainRulePolicy.normalize("bad host"))
    }

    @Test
    fun addingAlwaysRuleRemovesEquivalentBypass() {
        val next = DomainRulePolicy.addAlways(
            AdvancedSettings(bypassDomains = listOf("domain:example.com")),
            "example.com",
        )

        assertEquals(listOf("domain:example.com"), next.alwaysOnDomains)
        assertTrue(next.bypassDomains.isEmpty())
    }

    @Test
    fun addingBypassRuleRemovesEquivalentAlwaysRule() {
        val next = DomainRulePolicy.addBypass(
            AdvancedSettings(alwaysOnDomains = listOf("domain:example.com")),
            "EXAMPLE.COM",
        )

        assertEquals(listOf("domain:example.com"), next.bypassDomains)
        assertTrue(next.alwaysOnDomains.isEmpty())
    }
}
