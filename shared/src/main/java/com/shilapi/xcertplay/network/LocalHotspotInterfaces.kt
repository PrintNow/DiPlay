package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/** A running AP interface on this device and the address the iPhone reaches DiPlay at. */
internal class LocalHotspotInterface(
    val name: String,
    val hostAddress: InetAddress,
    val hardwareAddress: String?,
    val score: Int,
)

/** Finds the device's own hotspot interface among the up interfaces, excluding the default route. */
internal object LocalHotspotInterfaces {
    private val EXCLUDED_INTERFACE_PREFIXES = listOf(
        "lo",
        "dummy",
        "rmnet",
        "r_rmnet",
        "tun",
        "ppp",
        "sit",
        "ip6",
        "bond",
    )

    fun find(connectivityManager: ConnectivityManager?): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val primaryInterface = primaryInterfaceName(connectivityManager)
        return Collections.list(interfaces)
            .asSequence()
            .filter { isUsableInterface(it, primaryInterface) }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = runCatching { networkInterface.hardwareAddress?.toMacAddressString() }
                            .getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
                            ?: HotspotInterfaceBssid.read(networkInterface.name),
                        score = interfaceScore(networkInterface.name, address),
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    private fun isUsableInterface(
        networkInterface: NetworkInterface,
        primaryInterface: String?,
    ): Boolean = try {
        networkInterface.name != primaryInterface &&
            !networkInterface.isLoopback &&
            networkInterface.isUp &&
            EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
    } catch (_: SocketException) {
        false
    }

    internal fun interfaceScore(name: String, address: InetAddress): Int {
        var score = when {
            name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
            name.startsWith("p2p") -> 80
            name.startsWith("wlan") -> 70
            else -> 0
        }
        if (address is Inet4Address) {
            val bytes = address.address
            when {
                bytes[0] == 192.toByte() && bytes[1] == 168.toByte() -> score += 30
                address.isSiteLocalAddress -> score += 20
            }
        }
        if (address is Inet6Address && address.isLinkLocalAddress) score += 15
        return score
    }

    /** The default-route interface, which is never the hotspot itself. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun primaryInterfaceName(manager: ConnectivityManager?): String? {
        manager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return manager.activeNetwork?.let { manager.getLinkProperties(it)?.interfaceName }
        }
        // Android 4.4/5.x: the same data through the then-hidden getActiveLinkProperties().
        return runCatching {
            val properties = ConnectivityManager::class.java.getMethod("getActiveLinkProperties").invoke(manager)
                ?: return null
            properties.javaClass.getMethod("getInterfaceName").invoke(properties) as? String
        }.getOrNull()
    }

    private fun NetworkInterface.hotspotAddress(): InetAddress? =
        wirelessHostAddress(Collections.list(inetAddresses), index)

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
