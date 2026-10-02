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
    private class Saved<C>(val wifiWasOn: Boolean, val apWasOn: Boolean, val configuration: C?)

    private var saved: Saved<C>? = null

    val active: Boolean get() = saved != null

    /** Throws [IOException] when Android refuses or the hotspot does not come up before [deadlineMillis]. */
    fun enable(configuration: C, deadlineMillis: Long) {
        check(saved == null) { "hotspot already enabled" }
        val previous = Saved(
            wifiWasOn = runCatching { ap.wifiEnabled }.getOrDefault(false),
            apWasOn = ap.apState() == WIFI_AP_STATE_ENABLED,
            configuration = runCatching { ap.apConfiguration() }.getOrNull(),
        )
        saved = previous
        if (previous.apWasOn) {
            ap.setApEnabled(null, false)
            waitFor(deadlineMillis) { ap.apState() != WIFI_AP_STATE_ENABLED }
        }
        if (previous.wifiWasOn) ap.setWifiEnabled(false)
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
    }

    /** Best effort; safe to call repeatedly. */
    fun restore(deadlineMillis: Long = nowMillis() + RESTORE_TIMEOUT_MILLIS) {
        val previous = saved ?: return
        saved = null
        runCatching { ap.setApEnabled(null, false) }
        waitFor(deadlineMillis) {
            ap.apState().let { it == null || it == WIFI_AP_STATE_DISABLED || it == WIFI_AP_STATE_FAILED }
        }
        previous.configuration?.let { runCatching { ap.setApConfiguration(it) } }
        when {
            previous.apWasOn -> runCatching { ap.setApEnabled(previous.configuration, true) }
            previous.wifiWasOn -> runCatching { ap.setWifiEnabled(true) }
        }
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
        const val WIFI_AP_STATE_ENABLED = 13
        const val WIFI_AP_STATE_FAILED = 14
        const val POLL_MILLIS = 250L
        const val RESTORE_TIMEOUT_MILLIS = 5_000L
    }
}
