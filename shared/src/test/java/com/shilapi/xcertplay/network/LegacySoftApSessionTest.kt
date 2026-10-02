package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.LegacySoftApSession.Companion.WIFI_AP_STATE_DISABLED
import com.shilapi.xcertplay.network.LegacySoftApSession.Companion.WIFI_AP_STATE_ENABLED
import com.shilapi.xcertplay.network.LegacySoftApSession.Companion.WIFI_AP_STATE_FAILED
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacySoftApSessionTest {
    private class FakeAp(
        override var wifiEnabled: Boolean,
        var state: Int = WIFI_AP_STATE_DISABLED,
        var stored: String? = "owner",
        var refuse: Boolean = false,
        var failOnEnable: Boolean = false,
    ) : LegacySoftAp<String> {
        val calls = mutableListOf<String>()

        override fun setWifiEnabled(enabled: Boolean): Boolean {
            calls += "wifi=$enabled"
            wifiEnabled = enabled
            return true
        }

        override fun apState(): Int = state

        override fun setApEnabled(configuration: String?, enabled: Boolean): Boolean {
            calls += "ap=$enabled:$configuration"
            if (enabled && refuse) return false
            if (enabled && configuration != null) stored = configuration
            state = when {
                !enabled -> WIFI_AP_STATE_DISABLED
                failOnEnable -> WIFI_AP_STATE_FAILED
                else -> WIFI_AP_STATE_ENABLED
            }
            return true
        }

        override fun apConfiguration(): String? = stored

        override fun setApConfiguration(configuration: String): Boolean {
            calls += "config=$configuration"
            stored = configuration
            return true
        }
    }

    private var now = 0L
    private fun session(ap: FakeAp) = LegacySoftApSession(ap, sleep = { now += it }, nowMillis = { now })

    @Test
    fun turnsWifiOffThenRestoresOwnerConfigurationAndWifi() {
        val ap = FakeAp(wifiEnabled = true)
        val session = session(ap)
        session.enable("diplay", deadlineMillis = 10_000)
        assertFalse(ap.wifiEnabled)
        assertEquals(WIFI_AP_STATE_ENABLED, ap.state)
        assertEquals("diplay", ap.stored)

        session.restore()
        assertEquals("owner", ap.stored)
        assertEquals(WIFI_AP_STATE_DISABLED, ap.state)
        assertTrue(ap.wifiEnabled)
        assertEquals(listOf("wifi=false", "ap=true:diplay", "ap=false:null", "config=owner", "wifi=true"), ap.calls)

        session.restore()
        assertEquals(5, ap.calls.size)
    }

    @Test
    fun ownerHotspotThatWasOnIsTurnedBackOn() {
        val ap = FakeAp(wifiEnabled = false, state = WIFI_AP_STATE_ENABLED)
        val session = session(ap)
        session.enable("diplay", deadlineMillis = 10_000)
        session.restore()
        assertEquals(WIFI_AP_STATE_ENABLED, ap.state)
        assertEquals("owner", ap.stored)
        assertFalse(ap.wifiEnabled)
    }

    @Test
    fun refusalAndFailureAreReportedAndStillRestorable() {
        for (ap in listOf(FakeAp(wifiEnabled = true, refuse = true), FakeAp(wifiEnabled = true, failOnEnable = true))) {
            val session = session(ap)
            try {
                session.enable("diplay", deadlineMillis = 10_000)
                fail("expected IOException")
            } catch (_: IOException) {
            }
            assertTrue(session.active)
            session.restore()
            assertTrue(ap.wifiEnabled)
            assertEquals("owner", ap.stored)
        }
    }

    @Test
    fun generatedCredentialsAreValidWpa2() {
        var seed = 0
        val credentials = AppHotspotCredentials.generate { bound -> (seed++ * 7) % bound }
        assertTrue(credentials.ssid.matches(Regex("DiPlay-[0-9A-F]{4}")))
        assertEquals(16, credentials.passphrase.length)
        assertTrue(credentials.passphrase.all { it.isLetterOrDigit() })
    }
}
