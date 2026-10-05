package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayCodecCapabilityTest {
    @Test
    fun opusOutputRequiresApi21() {
        assertFalse(AirPlayCodecCapabilities.opusOutput(19))
        assertFalse(AirPlayCodecCapabilities.opusOutput(20))
        assertTrue(AirPlayCodecCapabilities.opusOutput(21))
    }

    @Test
    fun opusInputRequiresApi29() {
        assertFalse(AirPlayCodecCapabilities.opusInput(19))
        assertFalse(AirPlayCodecCapabilities.opusInput(28))
        assertTrue(AirPlayCodecCapabilities.opusInput(29))
    }
}
