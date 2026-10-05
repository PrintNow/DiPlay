package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVpnCompatibilityTest {
    @Test
    fun kitKatUsesDocumentedLinkLocalGlobalFallback() {
        assertFalse(WiredVpnCompatibility.supportsPerAppScope(19))
        assertTrue("global UID scope" in WiredVpnCompatibility.KITKAT_DIAGNOSTIC)
        assertTrue("link-local" in WiredVpnCompatibility.KITKAT_DIAGNOSTIC)
    }

    @Test
    fun lollipopAndNewerKeepPerAppVpnScope() {
        assertTrue(WiredVpnCompatibility.supportsPerAppScope(21))
        assertTrue(WiredVpnCompatibility.supportsPerAppScope(35))
    }
}
