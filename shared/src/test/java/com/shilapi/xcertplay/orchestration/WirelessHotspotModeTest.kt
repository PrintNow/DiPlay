package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessHotspotModeTest {
    @Test
    fun androidKitKatUsesDiPlayHotspotInsteadOfUnavailableModes() {
        assertEquals(WirelessHotspotMode.APP_HOTSPOT, WirelessHotspotMode.default(19))
        assertEquals(WirelessHotspotMode.APP_HOTSPOT, WirelessHotspotMode.WIFI_P2P.supported(19))
        assertEquals(WirelessHotspotMode.APP_HOTSPOT, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT.supported(19))
        assertEquals(WirelessHotspotMode.APP_HOTSPOT, WirelessHotspotMode.APP_HOTSPOT.supported(22))
        assertEquals(WirelessHotspotMode.APP_HOTSPOT, WirelessHotspotMode.EXISTING_WIFI.supported(19))
        assertEquals(WirelessHotspotMode.APP_HOTSPOT, WirelessHotspotMode.EXISTING_WIFI.supported(22))
        assertEquals(WirelessHotspotMode.MANUAL, WirelessHotspotMode.MANUAL.supported(19))
    }

    @Test
    fun modernAndroidKeepsExistingModes() {
        assertEquals(WirelessHotspotMode.MANUAL, WirelessHotspotMode.default(23))
        assertEquals(WirelessHotspotMode.MANUAL, WirelessHotspotMode.APP_HOTSPOT.supported(23))
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.default(26))
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.WIFI_P2P.supported(28))
        assertEquals(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.WIFI_P2P.supported(29))
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT.supported(26))
        assertEquals(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT.supported(34))
    }
}
