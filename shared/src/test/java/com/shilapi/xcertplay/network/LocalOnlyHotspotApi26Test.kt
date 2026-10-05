package com.shilapi.xcertplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalOnlyHotspotApi26Test {
    @Test
    fun legacyReservationBssidDoesNotRequireApi28MacAddress() {
        assertArrayEquals(
            byteArrayOf(0x4a, 0x4d, 0x90.toByte(), 0xcb.toByte(), 0xad.toByte(), 0x5c),
            parseMacAddressBytes("4a:4D:90:cb:ad:5c"),
        )
    }

    @Test
    fun malformedLegacyReservationBssidIsRejected() {
        assertNull(parseMacAddressBytes("4a:4d:90:cb:ad"))
        assertNull(parseMacAddressBytes("4a:4d:90:cb:ad:zz"))
        assertNull(parseMacAddressBytes("4a:4d:90:cb:ad:500"))
    }
}
