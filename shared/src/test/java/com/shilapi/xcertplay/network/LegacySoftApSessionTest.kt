package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.LegacySoftApSession.Companion.WIFI_AP_STATE_DISABLED
import com.shilapi.xcertplay.network.LegacySoftApSession.Companion.WIFI_AP_STATE_DISABLING
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
        var refuseStop: Boolean = false,
        var refuseWifiChange: Boolean = false,
        var configurationReadable: Boolean = true,
        val reportedStates: ArrayDeque<Int> = ArrayDeque(),
    ) : LegacySoftAp<String> {
        val calls = mutableListOf<String>()

        override fun setWifiEnabled(enabled: Boolean): Boolean {
            calls += "wifi=$enabled"
            if (refuseWifiChange) return false
            wifiEnabled = enabled
            return true
        }

        override fun apState(): Int = if (reportedStates.isEmpty()) state else reportedStates.removeFirst()

        override fun setApEnabled(configuration: String?, enabled: Boolean): Boolean {
            calls += "ap=$enabled:$configuration"
            if (!enabled && refuseStop) return false
            if (enabled && refuse) return false
            if (enabled && configuration != null) stored = configuration
            state = when {
                !enabled -> WIFI_AP_STATE_DISABLED
                failOnEnable -> WIFI_AP_STATE_FAILED
                else -> WIFI_AP_STATE_ENABLED
            }
            return true
        }

        override fun apConfiguration(): String? = if (configurationReadable) stored else null

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
            assertFalse(session.active)
            assertTrue(ap.wifiEnabled)
            assertEquals("owner", ap.stored)
        }
    }

    @Test
    fun waitsUntilTheOwnerHotspotIsFullyDisabledBeforeStartingTheReplacement() {
        val states = ArrayDeque(listOf(WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_DISABLED))
        val ap = FakeAp(wifiEnabled = false, state = WIFI_AP_STATE_ENABLED, reportedStates = states)

        session(ap).enable("diplay", deadlineMillis = 10_000)

        assertTrue(states.isEmpty())
        assertEquals(WIFI_AP_STATE_ENABLED, ap.state)
        assertEquals("diplay", ap.stored)
    }

    @Test
    fun failedRestoreKeepsTheSnapshotForARetry() {
        val ap = FakeAp(wifiEnabled = true)
        val session = session(ap)
        session.enable("diplay", deadlineMillis = 10_000)
        ap.refuseStop = true

        assertFalse(session.restore())
        assertTrue(session.active)
        assertEquals("diplay", ap.stored)

        ap.refuseStop = false
        assertTrue(session.restore())
        assertFalse(session.active)
        assertTrue(ap.wifiEnabled)
        assertEquals("owner", ap.stored)
    }

    @Test
    fun refusesToReplaceRunningOwnerHotspotWhenItCannotBeStopped() {
        val ap = FakeAp(wifiEnabled = false, state = WIFI_AP_STATE_ENABLED, refuseStop = true)
        val session = session(ap)

        try {
            session.enable("diplay", deadlineMillis = 1_000)
            fail("expected IOException")
        } catch (failure: IOException) {
            assertTrue(failure.message!!.contains("stop"))
        }

        assertEquals(WIFI_AP_STATE_ENABLED, ap.state)
        assertEquals("owner", ap.stored)
        assertFalse(ap.calls.contains("ap=true:diplay"))
    }

    @Test
    fun refusesToReplaceRunningOwnerHotspotWithoutRestorableConfiguration() {
        val ap = FakeAp(
            wifiEnabled = false,
            state = WIFI_AP_STATE_ENABLED,
            configurationReadable = false,
        )
        val session = session(ap)

        try {
            session.enable("diplay", deadlineMillis = 1_000)
            fail("expected IOException")
        } catch (failure: IOException) {
            assertTrue(failure.message!!.contains("configuration"))
        }

        assertEquals(WIFI_AP_STATE_ENABLED, ap.state)
        assertFalse(ap.calls.contains("ap=true:diplay"))
    }

    @Test
    fun refusesToStartHotspotWhenWifiCannotBeDisabled() {
        val ap = FakeAp(wifiEnabled = true, refuseWifiChange = true)
        val session = session(ap)

        try {
            session.enable("diplay", deadlineMillis = 1_000)
            fail("expected IOException")
        } catch (failure: IOException) {
            assertTrue(failure.message!!.contains("Wi-Fi"))
        }

        assertTrue(ap.wifiEnabled)
        assertFalse(ap.calls.contains("ap=true:diplay"))
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
