package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDescriptorParserTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte)

    private fun device() = bytes(18, 0x01, 0x00, 0x02, 0, 0, 0, 64, 0xac, 0x05, 0xa8, 0x12, 0, 0x10, 1, 2, 3, 4)
    private fun configuration(value: Int, interfaces: Int) =
        bytes(9, 0x02, 0, 0, interfaces, value, 0, 0xc0, 250)
    private fun usbInterface(number: Int, alternate: Int, endpoints: Int, cls: Int, sub: Int, protocol: Int) =
        bytes(9, 0x04, number, alternate, endpoints, cls, sub, protocol, 0)
    private fun endpoint(address: Int, attributes: Int, maxPacket: Int, interval: Int = 0) =
        bytes(7, 0x05, address, attributes, maxPacket and 0xff, maxPacket shr 8, interval)
    private fun cdcFunctional() = bytes(5, 0x24, 0x00, 0x10, 0x01)

    @Test
    fun readsEveryConfigurationAndAlternateSettingInUsbfsOrder() {
        val raw = (
            device() +
                configuration(1, 1) + usbInterface(0, 0, 0, 0x06, 0x01, 0x01) +
                configuration(5, 3) +
                usbInterface(1, 0, 2, 0xff, 0xfe, 0x02) + endpoint(0x04, 0x02, 512) + endpoint(0x85, 0x02, 512) +
                usbInterface(2, 0, 1, 0x02, 0x0d, 0x00) + cdcFunctional() + endpoint(0x87, 0x03, 16, 9) +
                usbInterface(3, 0, 0, 0x0a, 0x00, 0x01) +
                usbInterface(3, 1, 2, 0x0a, 0x00, 0x01) + endpoint(0x88, 0x02, 512) + endpoint(0x09, 0x02, 512)
            ).toByteArray()

        val configurations = UsbDescriptorParser.configurations(raw)

        assertEquals(listOf(1, 5), configurations.map { it.id })
        assertEquals(1, configurations[0].interfaces.size)
        val carPlay = configurations[1].interfaces
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 0, 3 to 1), carPlay.map { it.id to it.alternateSetting })
        assertEquals(listOf(0x04, 0x85), carPlay[0].endpoints.map { it.address })
        assertEquals(RawUsbEndpoint(0x87, 0x03, 16, 9), carPlay[1].endpoints.single())
        assertEquals(emptyList<RawUsbEndpoint>(), carPlay[2].endpoints)
        assertEquals(listOf(0x88, 0x09), carPlay[3].endpoints.map { it.address })
        assertEquals(0x0a, carPlay[3].interfaceClass)
        assertEquals(512, carPlay[3].endpoints[0].maxPacketSize)
    }

    @Test
    fun stopsAtATruncatedDescriptor() {
        val raw = (device() + configuration(1, 1) + usbInterface(0, 0, 1, 0xff, 0, 0) + bytes(7, 0x05, 0x81))
            .toByteArray()
        val configurations = UsbDescriptorParser.configurations(raw)
        assertEquals(1, configurations.size)
        assertEquals(emptyList<RawUsbEndpoint>(), configurations[0].interfaces.single().endpoints)
    }
}
