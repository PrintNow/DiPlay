package com.shilapi.xcertplay.transport

/** One endpoint descriptor, as raw descriptor fields. */
internal data class RawUsbEndpoint(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int,
)

/** One interface descriptor (one alternate setting) and the endpoint descriptors after it. */
internal data class RawUsbInterface(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<RawUsbEndpoint>,
)

internal data class RawUsbConfiguration(val id: Int, val interfaces: List<RawUsbInterface>)

/**
 * Parses UsbDeviceConnection.getRawDescriptors(): the device descriptor followed by every
 * configuration descriptor with its interfaces and endpoints (Linux usbfs order). Android 4.4 has
 * no UsbConfiguration and no alternate setting on UsbInterface, so this is the only source for them.
 */
internal object UsbDescriptorParser {
    private const val CONFIGURATION = 0x02
    private const val INTERFACE = 0x04
    private const val ENDPOINT = 0x05

    fun configurations(raw: ByteArray): List<RawUsbConfiguration> {
        val configurations = ArrayList<RawUsbConfiguration>()
        var interfaces: MutableList<RawUsbInterface>? = null
        var configurationId = 0
        var current: RawUsbInterface? = null
        var endpoints = ArrayList<RawUsbEndpoint>()

        fun closeInterface() {
            current?.let { interfaces?.add(it.copy(endpoints = endpoints.toList())) }
            current = null
            endpoints = ArrayList()
        }

        fun closeConfiguration() {
            closeInterface()
            interfaces?.let { configurations += RawUsbConfiguration(configurationId, it.toList()) }
            interfaces = null
        }

        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw.u8(offset)
            val type = raw.u8(offset + 1)
            if (length < 2 || offset + length > raw.size) break
            when {
                type == CONFIGURATION && length >= 9 -> {
                    closeConfiguration()
                    configurationId = raw.u8(offset + 5)
                    interfaces = ArrayList()
                }
                type == INTERFACE && length >= 9 && interfaces != null -> {
                    closeInterface()
                    current = RawUsbInterface(
                        id = raw.u8(offset + 2),
                        alternateSetting = raw.u8(offset + 3),
                        interfaceClass = raw.u8(offset + 5),
                        interfaceSubclass = raw.u8(offset + 6),
                        interfaceProtocol = raw.u8(offset + 7),
                        endpoints = emptyList(),
                    )
                }
                type == ENDPOINT && length >= 7 && current != null -> endpoints += RawUsbEndpoint(
                    address = raw.u8(offset + 2),
                    attributes = raw.u8(offset + 3),
                    maxPacketSize = raw.u8(offset + 4) or (raw.u8(offset + 5) shl 8),
                    interval = raw.u8(offset + 6),
                )
            }
            offset += length
        }
        closeConfiguration()
        return configurations
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff
}
