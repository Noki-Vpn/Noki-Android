package com.noki.vpn.vpn

import com.noki.vpn.data.AppFilterMode.ALL_EXCEPT_SELECTED
import com.noki.vpn.data.AppFilterMode.ONLY_SELECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVpnRoutingPolicyTest {
    @Test
    fun staleOnlySelectedPackageProducesExplicitEmptyError() {
        val rules = AppVpnRoutingPolicy.rules(
            appPackageName = "com.noki.vpn",
            filterMode = ONLY_SELECTED,
            selectedPackages = setOf("gone.app"),
            isInstalled = { false },
        )

        assertTrue(rules is AppVpnRoutingRules.Rejected)
        assertEquals(AppVpnRoutingPolicy.EMPTY_ONLY_SELECTED_REASON, (rules as AppVpnRoutingRules.Rejected).failureReason)
        assertEquals(setOf("gone.app"), rules.removedPackages)
    }

    @Test
    fun staleExcludedPackageDoesNotAbortAllAppsMode() {
        val rules = AppVpnRoutingPolicy.rules(
            appPackageName = "com.noki.vpn",
            filterMode = ALL_EXCEPT_SELECTED,
            selectedPackages = setOf("gone.app"),
            isInstalled = { false },
        )

        assertTrue(rules is AppVpnRoutingRules.Ready)
        assertEquals(setOf("com.noki.vpn"), (rules as AppVpnRoutingRules.Ready).disallowedPackages)
        assertFalse(rules.requiresOwnerCheck)
    }

    @Test
    fun installedAppRulesRequireOwnerChecks() {
        for (mode in listOf(ONLY_SELECTED, ALL_EXCEPT_SELECTED)) {
            val rules = AppVpnRoutingPolicy.rules("com.noki.vpn", mode, setOf("installed.app"))
            assertTrue((rules as AppVpnRoutingRules.Ready).requiresOwnerCheck)
        }
    }
}
