package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import android.os.Parcelable
import android.util.Log
import com.shilapi.xcertplay.compat.LegacyPlatformNative

/**
 * One interface alternate setting. [platform] is what UsbDeviceConnection.claimInterface takes;
 * before API 21 it only carries the interface number, which is all claiming uses.
 */
class UsbInterfaceInfo internal constructor(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpoint>,
    val platform: UsbInterface,
) {
    val endpointCount: Int get() = endpoints.size
}

class UsbConfigurationInfo internal constructor(
    val id: Int,
    val interfaces: List<UsbInterfaceInfo>,
    /** The API 21+ UsbConfiguration; untyped so Android 4.4 never resolves the class. */
    private val platform: Any?,
) {
    val interfaceCount: Int get() = interfaces.size

    /** UsbDeviceConnection.setConfiguration, through usbfs before API 21. */
    fun select(connection: UsbDeviceConnection): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return connection.setConfiguration(platform as UsbConfiguration)
        }
        val result = LegacyPlatformNative.usbSetConfiguration(connection.fileDescriptor, id)
        if (result != 0) Log.w(IphoneCarPlayConfiguration.TAG, "usbfs setConfiguration $id result=$result")
        return result == 0
    }
}

/**
 * USB configurations of a device. API 21+ reads them from UsbDevice; Android 4.4 exposes only a
 * flattened interface list, so its configurations come from the raw descriptors of a connection.
 */
object UsbTopology {
    fun configurations(device: UsbDevice, connection: UsbDeviceConnection): List<UsbConfigurationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            platformConfigurations(device)
        } else {
            legacyConfigurations(device, connection.rawDescriptors ?: ByteArray(0))
        }

    /** Like [configurations], opening a short-lived connection only where Android 4.4 needs one. */
    fun configurations(device: UsbDevice, open: () -> UsbDeviceConnection?): List<UsbConfigurationInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return platformConfigurations(device)
        val connection = open() ?: return emptyList()
        return try {
            legacyConfigurations(device, connection.rawDescriptors ?: ByteArray(0))
        } finally {
            connection.close()
        }
    }

    private fun platformConfigurations(device: UsbDevice): List<UsbConfigurationInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        return (0 until device.configurationCount).map { index ->
            val configuration = device.getConfiguration(index)
            val interfaces = (0 until configuration.interfaceCount).map { interfaceIndex ->
                val usbInterface = configuration.getInterface(interfaceIndex)
                UsbInterfaceInfo(
                    id = usbInterface.id,
                    alternateSetting = usbInterface.alternateSetting,
                    interfaceClass = usbInterface.interfaceClass,
                    interfaceSubclass = usbInterface.interfaceSubclass,
                    interfaceProtocol = usbInterface.interfaceProtocol,
                    endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint),
                    platform = usbInterface,
                )
            }
            UsbConfigurationInfo(configuration.id, interfaces, configuration)
        }
    }

    internal fun legacyConfigurations(device: UsbDevice, raw: ByteArray): List<UsbConfigurationInfo> {
        val known = (0 until device.interfaceCount).map(device::getInterface)
        val knownEndpoints = known.flatMap { usbInterface ->
            (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        }
        return UsbDescriptorParser.configurations(raw).map { configuration ->
            UsbConfigurationInfo(
                id = configuration.id,
                interfaces = configuration.interfaces.map { raw ->
                    val endpoints = raw.endpoints.map { endpoint ->
                        knownEndpoints.firstOrNull {
                            it.address == endpoint.address && it.attributes == endpoint.attributes &&
                                it.maxPacketSize == endpoint.maxPacketSize
                        } ?: hiddenEndpoint(endpoint)
                    }
                    UsbInterfaceInfo(
                        id = raw.id,
                        alternateSetting = raw.alternateSetting,
                        interfaceClass = raw.interfaceClass,
                        interfaceSubclass = raw.interfaceSubclass,
                        interfaceProtocol = raw.interfaceProtocol,
                        endpoints = endpoints,
                        platform = known.firstOrNull { it.id == raw.id } ?: hiddenInterface(raw, endpoints),
                    )
                },
                platform = null,
            )
        }
    }

    // Android 4.4's UsbDevice lists only the interfaces it parsed at attach; for the others the
    // framework objects are built with their hidden constructors, which only store these values.
    private fun hiddenEndpoint(raw: RawUsbEndpoint): UsbEndpoint =
        UsbEndpoint::class.java
            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(raw.address, raw.attributes, raw.maxPacketSize, raw.interval)

    private fun hiddenInterface(raw: RawUsbInterface, endpoints: List<UsbEndpoint>): UsbInterface =
        UsbInterface::class.java
            .getConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Array<Parcelable>::class.java,
            )
            .newInstance(raw.id, raw.interfaceClass, raw.interfaceSubclass, raw.interfaceProtocol, endpoints.toTypedArray<Parcelable>())
}

/** UsbDeviceConnection.setInterface, through usbfs before API 21. The interface must be claimed. */
internal fun UsbDeviceConnection.selectAlternateSetting(usbInterface: UsbInterfaceInfo): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return setInterface(usbInterface.platform)
    val result = LegacyPlatformNative.usbSetInterface(fileDescriptor, usbInterface.id, usbInterface.alternateSetting)
    if (result != 0) {
        Log.w(IphoneCarPlayConfiguration.TAG, "usbfs setInterface ${usbInterface.id}/${usbInterface.alternateSetting} result=$result")
    }
    return result == 0
}
