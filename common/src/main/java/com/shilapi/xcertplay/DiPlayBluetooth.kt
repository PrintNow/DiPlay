package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        // Android 6+ only returns the real adapter address to privileged LOCAL_MAC_ADDRESS holders.
        // Avoid both the protected call and its runtime permission surface there; the secure setting
        // remains useful on vendor head units that expose it.
        val adapter = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) legacyAdapterAddress(context) else null
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }

    @Suppress("DEPRECATION", "MissingPermission") // Pre-M BLUETOOTH is install-time and declared by the app.
    private fun legacyAdapterAddress(context: Context): String? = runCatching {
        ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter?.address
    }.getOrNull()
}
