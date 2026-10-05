package com.shilapi.xcertplay.network

import java.io.IOException

/** The pre-Android 6.0 hidden WifiManager SoftAP controls, abstracted for tests. */
internal interface LegacySoftAp<C : Any> {
    val wifiEnabled: Boolean
    fun setWifiEnabled(enabled: Boolean): Boolean

    /** A WIFI_AP_STATE_* value, or null when the firmware hides it. */
    fun apState(): Int?

    /** WifiManager.setWifiApEnabled; a null configuration keeps the stored one. */
    fun setApEnabled(configuration: C?, enabled: Boolean): Boolean
    fun apConfiguration(): C?
    fun setApConfiguration(configuration: C): Boolean
}

/**
 * Starts the device hotspot with DiPlay's configuration and later puts back what the owner had:
 * the stored hotspot configuration, the hotspot state and the Wi-Fi switch.
 *
 * Android 4.4 cannot run station Wi-Fi and a hotspot together, so the switch is turned off first.
 */
internal class LegacySoftApSession<C : Any>(
    private val ap: LegacySoftAp<C>,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Saved<C>(val wifiWasOn: Boolean, val apWasOn: Boolean, val configuration: C?) {
        var hotspotMutationRequested: Boolean = false
        var wifiMutationRequested: Boolean = false
    }

    private var saved: Saved<C>? = null

    @get:Synchronized
    val active: Boolean get() = saved != null

    /** Throws [IOException] when Android refuses or the hotspot does not come up before [deadlineMillis]. */
    @Synchronized
    fun enable(configuration: C, deadlineMillis: Long) {
        check(saved == null) { "hotspot already enabled" }
        val previous = Saved(
            wifiWasOn = runCatching { ap.wifiEnabled }.getOrDefault(false),
            apWasOn = ap.apState() == WIFI_AP_STATE_ENABLED,
            configuration = runCatching { ap.apConfiguration() }.getOrNull(),
        )
        saved = previous
        try {
            if (previous.apWasOn) {
                if (previous.configuration == null) {
                    throw IOException("Cannot replace the running hotspot because its configuration cannot be backed up")
                }
                if (!ap.setApEnabled(null, false)) {
                    throw IOException("Android refused to stop the existing hotspot")
                }
                previous.hotspotMutationRequested = true
                if (!waitFor(deadlineMillis) {
                        ap.apState().let { it == null || it == WIFI_AP_STATE_DISABLED || it == WIFI_AP_STATE_FAILED }
                    }) {
                    throw IOException("Timed out waiting for the existing hotspot to stop")
                }
            }
            if (previous.wifiWasOn) {
                if (!ap.setWifiEnabled(false)) {
                    throw IOException("Android refused to turn Wi-Fi off before starting the hotspot")
                }
                previous.wifiMutationRequested = true
                if (!waitFor(deadlineMillis) { !ap.wifiEnabled }) {
                    throw IOException("Timed out waiting for Wi-Fi to turn off before starting the hotspot")
                }
            }
            previous.hotspotMutationRequested = true
            if (!ap.setApEnabled(configuration, true)) {
                throw IOException("Android refused to start the DiPlay hotspot")
            }
            var failed = false
            val enabled = waitFor(deadlineMillis) {
                when (ap.apState()) {
                    WIFI_AP_STATE_ENABLED, null -> true
                    WIFI_AP_STATE_FAILED -> { failed = true; true }
                    else -> false
                }
            }
            if (failed) throw IOException("The DiPlay hotspot failed to start")
            if (!enabled) throw IOException("Timed out waiting for the DiPlay hotspot to start")
        } catch (failure: Exception) {
            restore()
            throw failure
        }
    }

    /** Restores the owner's state. A failed attempt keeps the snapshot so close can retry. */
    @Synchronized
    fun restore(deadlineMillis: Long = nowMillis() + RESTORE_TIMEOUT_MILLIS): Boolean {
        val previous = saved ?: return true
        if (previous.hotspotMutationRequested) {
            if (!runCatching { ap.setApEnabled(null, false) }.getOrDefault(false)) return false
            if (!waitFor(deadlineMillis) {
                ap.apState().let { it == null || it == WIFI_AP_STATE_DISABLED || it == WIFI_AP_STATE_FAILED }
            }) return false
            if (previous.configuration != null &&
                !runCatching { ap.setApConfiguration(previous.configuration) }.getOrDefault(false)
            ) return false
        }
        when {
            previous.apWasOn && previous.hotspotMutationRequested -> {
                if (!runCatching { ap.setApEnabled(previous.configuration, true) }.getOrDefault(false)) return false
                if (!waitFor(deadlineMillis) {
                        ap.apState().let { it == null || it == WIFI_AP_STATE_ENABLED }
                    }) return false
            }
            previous.wifiWasOn && previous.wifiMutationRequested -> {
                if (!runCatching { ap.setWifiEnabled(true) }.getOrDefault(false)) return false
                if (!waitFor(deadlineMillis) { ap.wifiEnabled }) return false
            }
        }
        saved = null
        return true
    }

    private fun waitFor(deadlineMillis: Long, done: () -> Boolean): Boolean {
        while (true) {
            if (runCatching(done).getOrDefault(false)) return true
            val remaining = deadlineMillis - nowMillis()
            if (remaining <= 0) return false
            sleep(minOf(remaining, POLL_MILLIS))
        }
    }

    companion object {
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_DISABLING = 10
        const val WIFI_AP_STATE_ENABLED = 13
        const val WIFI_AP_STATE_FAILED = 14
        const val POLL_MILLIS = 250L
        const val RESTORE_TIMEOUT_MILLIS = 5_000L
    }
}
