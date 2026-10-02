package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.security.SecureRandom

/**
 * Wireless CarPlay on Android 4.4–5.1: DiPlay turns on the head unit's own hotspot with its own
 * WPA2 credentials, so the owner has nothing to set up.
 *
 * Before Android 6.0 the hidden WifiManager.setWifiApEnabled needs only CHANGE_WIFI_STATE. The
 * owner's hotspot configuration and Wi-Fi switch are restored on [close].
 */
class LegacyAppHotspotManager(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager = ContextCompat.getSystemService(appContext, ConnectivityManager::class.java)
    private val wifiManager = ContextCompat.getSystemService(appContext, WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val session = LegacySoftApSession(ReflectiveSoftAp(wifiManager))

    @Volatile
    private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyAppHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.currentTimeMillis() + timeoutMillis
        val credentials = AppHotspotCredentials.load(appContext)
        try {
            session.enable(credentials.toWifiConfiguration(), deadline)
        } catch (failure: SecurityException) {
            throw IOException("This Android version does not let DiPlay start the hotspot; use the car hotspot instead", failure)
        }

        var localInterface: LocalHotspotInterface? = null
        while (localInterface == null) {
            check(!closed) { "LegacyAppHotspotManager is closed" }
            localInterface = LocalHotspotInterfaces.find(connectivityManager)
            if (localInterface == null) {
                if (System.currentTimeMillis() >= deadline) {
                    throw IOException("The DiPlay hotspot started but its interface has no address")
                }
                Thread.sleep(LegacySoftApSession.POLL_MILLIS)
            }
        }
        val frequencyMHz = settledFrequency(localInterface.name, deadline)
        val channel = frequencyMHz?.let(::wifiFrequencyMhzToChannel) ?: 0
        onDiagnostic(
            "DiPlay hotspot iface=${localInterface.name} channelKnown=${channel > 0} " +
                "hardwareAddressKnown=${localInterface.hardwareAddress != null}",
        )
        return WirelessHotspotInfo(
            ssid = credentials.ssid,
            passphrase = credentials.passphrase,
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = localInterface.hardwareAddress,
            interfaceName = localInterface.name,
            hostAddress = localInterface.hostAddress,
            bandLabel = when (frequencyMHz) {
                null -> "Auto"
                in 2400..2500 -> "2.4 GHz"
                else -> "5 GHz"
            },
            backend = WirelessHotspotBackend.APP_HOTSPOT,
        )
    }

    override fun close() {
        closed = true
        session.restore()
    }

    /** The driver channel, once it has stopped changing; unknown is reported as iAP2 "auto". */
    private fun settledFrequency(interfaceName: String, deadline: Long): Int? {
        val settled = LegacyHotspotRadio.Settled()
        val stop = minOf(deadline, System.currentTimeMillis() + FREQUENCY_BUDGET_MILLIS)
        while (true) {
            val reading = LegacyHotspotRadio.read(interfaceName, band = null)
            settled.observe(reading.frequencyMHz, System.currentTimeMillis())?.let { return it }
            if (reading.frequencyMHz == null && reading.error != null && reading.error != "unrecognized driver frequency") {
                onDiagnostic("DiPlay hotspot frequency unavailable: ${reading.error}")
                return null
            }
            if (System.currentTimeMillis() >= stop) return null
            Thread.sleep(LegacySoftApSession.POLL_MILLIS)
        }
    }

    private companion object {
        const val FREQUENCY_BUDGET_MILLIS = 3_000L
    }
}

/** DiPlay's hotspot name and password, generated once so the iPhone can remember the network. */
internal class AppHotspotCredentials(val ssid: String, val passphrase: String) {
    @SuppressLint("PrivateApi")
    fun toWifiConfiguration(): WifiConfiguration = WifiConfiguration().apply {
        // SoftAP configurations take the raw SSID, unlike station configurations.
        SSID = ssid
        preSharedKey = passphrase
        allowedKeyManagement.set(WPA2_PSK)
        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
    }

    companion object {
        private const val PREFS = "diplay_app_hotspot"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val PASSPHRASE_ALPHABET = "abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRTUVWXY23456789"

        /** KeyMgmt.WPA2_PSK was hidden until API 23; its value has always been 4. */
        private val WPA2_PSK: Int = runCatching {
            WifiConfiguration.KeyMgmt::class.java.getField("WPA2_PSK").getInt(null)
        }.getOrDefault(4)

        fun load(context: Context): AppHotspotCredentials {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ssid = prefs.getString(KEY_SSID, null)
            val passphrase = prefs.getString(KEY_PASSPHRASE, null)
            if (ssid != null && passphrase != null) return AppHotspotCredentials(ssid, passphrase)
            val random = SecureRandom()
            val created = generate(random::nextInt)
            prefs.edit().putString(KEY_SSID, created.ssid).putString(KEY_PASSPHRASE, created.passphrase).apply()
            return created
        }

        internal fun generate(nextInt: (Int) -> Int): AppHotspotCredentials {
            val suffix = (0 until 4).joinToString("") { "%X".format(nextInt(16)) }
            val passphrase = (0 until 16).map { PASSPHRASE_ALPHABET[nextInt(PASSPHRASE_ALPHABET.length)] }
                .joinToString("")
            return AppHotspotCredentials("DiPlay-$suffix", passphrase)
        }
    }
}

/** The hidden WifiManager SoftAP methods, present and app-callable on Android 4.0–5.1. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private class ReflectiveSoftAp(private val wifi: WifiManager) : LegacySoftAp<WifiConfiguration> {
    override val wifiEnabled: Boolean get() = wifi.isWifiEnabled

    override fun setWifiEnabled(enabled: Boolean): Boolean = wifi.setWifiEnabled(enabled)

    override fun apState(): Int? = runCatching {
        WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int
    }.getOrNull()

    override fun setApEnabled(configuration: WifiConfiguration?, enabled: Boolean): Boolean = try {
        WifiManager::class.java
            .getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            .invoke(wifi, configuration, enabled) as Boolean
    } catch (failure: java.lang.reflect.InvocationTargetException) {
        throw failure.targetException as? SecurityException ?: IOException("setWifiApEnabled failed", failure.targetException)
    } catch (failure: ReflectiveOperationException) {
        throw IOException("This firmware has no setWifiApEnabled", failure)
    }

    override fun apConfiguration(): WifiConfiguration? =
        WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration

    override fun setApConfiguration(configuration: WifiConfiguration): Boolean =
        WifiManager::class.java.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
            .invoke(wifi, configuration) as Boolean
}
