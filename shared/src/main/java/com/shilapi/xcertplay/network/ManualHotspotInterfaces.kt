package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

internal class ManualHotspotInterfaces(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val connectivity = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
    private val publicTethering: TetheringObservation? =
        if (Build.VERSION.SDK_INT >= 36) PublicTethering(context) else null
    private var lastLegacyDiagnostic: String? = null

    fun sample(): HotspotNetworkSnapshot {
        val ap = publicTethering?.interfaces ?: legacyApInterfaces()
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ModernNetworks.sample(connectivity)
        } else {
            null
        }
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { iface ->
                runCatching {
                    if (iface.isLoopback) null else HotspotInterfaceSnapshot(
                        iface.name, iface.index, iface.isUp, Collections.list(iface.inetAddresses),
                        wirelessInterfaceName(iface.name) || File("/sys/class/net/${iface.name}/wireless").isDirectory,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return HotspotNetworkSnapshot(
            interfaces, ap, modern?.upstreams, modern?.defaultName,
            consistent = modern?.consistent ?: true,
            apEnabled = CarHotspotStatus.isEnabled(context),
        )
    }

    // 旧平台只使用允许读取的结果；接口归属读不到时保持 unknown，不放宽普通网卡资格。
    @SuppressLint("PrivateApi")
    private fun legacyApInterfaces(): Set<String>? = runCatching {
        val manager = checkNotNull(connectivity)
        val tethered = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            .invoke(manager) as Array<*>
        val regexes = ConnectivityManager::class.java.getMethod("getTetherableWifiRegexs")
            .invoke(manager) as Array<*>
        val names = tethered.filterIsInstance<String>()
        val patterns = regexes.filterIsInstance<String>()
        val ap = legacyHotspotInterfaces(names, patterns)
        val diagnostic = "legacy hotspot ownership=${if (ap == null) "unobservable" else "observed"} " +
            "tethered=$names wifiRegexes=$patterns matched=$ap"
        if (diagnostic != lastLegacyDiagnostic) {
            lastLegacyDiagnostic = diagnostic
            onDiagnostic(diagnostic)
        }
        ap
    }.getOrNull()

    override fun close() {
        publicTethering?.close()
    }

    private interface TetheringObservation : Closeable {
        val interfaces: Set<String>?
    }

    private data class ModernSnapshot(
        val upstreams: Set<String>?,
        val defaultName: String?,
        val consistent: Boolean,
    )

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private object ModernNetworks {
        fun sample(connectivity: ConnectivityManager?): ModernSnapshot {
            if (connectivity == null) return ModernSnapshot(null, null, false)
            val before = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { connectivity.activeNetwork }
            } else {
                Result.success(null)
            }
            val upstreams = runCatching {
                connectivity.allNetworks.mapNotNull { network ->
                    val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                    val links = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                    links.interfaceName?.takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                }.toSet()
            }.getOrNull()
            val defaultName = before.getOrNull()?.let { connectivity.getLinkProperties(it)?.interfaceName }
            val consistent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val after = runCatching { connectivity.activeNetwork }
                before.isSuccess && after.isSuccess && before.getOrNull() == after.getOrNull()
            } else {
                true
            }
            return ModernSnapshot(upstreams, defaultName, consistent)
        }
    }

    @RequiresApi(36)
    private class PublicTethering(context: Context) : TetheringObservation {
        @Volatile override var interfaces: Set<String>? = null
            private set
        private val manager = context.getSystemService(TetheringManager::class.java)
        private val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                this@PublicTethering.interfaces = interfaces.filter { it.type == TetheringManager.TETHERING_WIFI }
                    .map { it.getInterface() }.toSet()
            }
        }
        private val registered = runCatching {
            checkNotNull(manager).registerTetheringEventCallback(context.mainExecutor, callback)
        }.isSuccess

        override fun close() {
            if (registered) runCatching { manager?.unregisterTetheringEventCallback(callback) }
        }
    }
}

internal fun legacyHotspotInterfaces(tethered: List<String>, wifiRegexes: List<String>): Set<String>? {
    if (wifiRegexes.isEmpty()) return null
    val patterns = wifiRegexes.map(::Regex)
    val matched = tethered.filter { name -> patterns.any { it.matches(name) } }.toSet()
    // 厂商可能动态使用 wlan0，而静态配置仍写 softap0；匹配失败不能证明没有热点。
    return matched.takeUnless { tethered.isNotEmpty() && it.isEmpty() }
}

internal fun wirelessInterfaceName(name: String): Boolean =
    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") ||
        name.contains("softap", ignoreCase = true) || name.startsWith("wifi") || name.startsWith("p2p")
