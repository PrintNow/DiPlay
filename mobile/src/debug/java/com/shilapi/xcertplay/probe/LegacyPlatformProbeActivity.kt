package com.shilapi.xcertplay.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.media.MediaCodecList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.network.LegacyAppHotspotManager
import java.security.Provider
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Debug-only checks of what Android 4.4 head units really allow (docs/ANDROID_4.4_PORT_PLAN.md,
 * phase 0b). Start with:
 * adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.probe.LegacyPlatformProbeActivity
 * Results are shown and logged under the DiPlayProbe tag.
 */
class LegacyPlatformProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    @Volatile private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(button("Run passive checks") { passiveChecks() })
            addView(button("Start DiPlay hotspot for 30 s") { hotspotCheck() })
            addView(button("Create a Wi-Fi Direct group for 30 s") { wifiDirectCheck() })
            addView(output)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        report("Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
        @Suppress("DEPRECATION")
        report("ABIs ${Build.CPU_ABI}/${Build.CPU_ABI2}")
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true
            thread(name = "probe") {
                try {
                    action()
                } catch (error: Throwable) {
                    report("FAILED ${error.javaClass.simpleName}: ${error.message}")
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun passiveChecks() {
        for (library in listOf("xcertplay_i2c", "local_hotspot_radio", "xcertplay_legacy_platform", "conscrypt_jni")) {
            val result = runCatching { System.loadLibrary(library) }
            report("loadLibrary $library: ${result.exceptionOrNull()?.let { "FAILED $it" } ?: "ok"}")
        }
        @Suppress("DEPRECATION")
        for (index in 0 until MediaCodecList.getCodecCount()) {
            val info = MediaCodecList.getCodecInfoAt(index)
            val types = info.supportedTypes.filter { it.startsWith("video/avc") || it.startsWith("video/hevc") || it.startsWith("audio/mp4a") || it.startsWith("audio/opus") }
            if (!info.isEncoder && types.isNotEmpty()) report("decoder ${info.name}: ${types.joinToString()}")
        }
        report("platform TLS: ${SSLContext.getDefault().createSSLEngine().supportedProtocols.joinToString()}")
        val conscrypt = runCatching {
            Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null) as Provider
        }
        conscrypt.onSuccess { provider ->
            val engine = SSLContext.getInstance("TLS", provider).apply { init(null, null, null) }.createSSLEngine()
            report("Conscrypt TLS: ${engine.supportedProtocols.joinToString()}")
        }.onFailure { report("Conscrypt FAILED $it") }
    }

    private fun hotspotCheck() {
        val manager = LegacyAppHotspotManager(this) { report("hotspot: $it") }
        try {
            val info = manager.start(20_000)
            report("DiPlay hotspot up: $info")
            report("Join '${info.ssid}' with password '${info.passphrase}' from the iPhone to test; closing in 30 s")
            Thread.sleep(30_000)
        } finally {
            manager.close()
            report("DiPlay hotspot closed; owner hotspot and Wi-Fi switch restored")
        }
    }

    // Android 4.4 grants these permissions at install; the probe is for those head units.
    @SuppressLint("MissingPermission")
    private fun wifiDirectCheck() {
        val manager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return report("Wi-Fi Direct: no WifiP2pManager")
        val channel = manager.initialize(this, mainLooper, null)
        val created = java.util.concurrent.CountDownLatch(1)
        main.post {
            manager.createGroup(channel, listener("createGroup") { created.countDown() })
        }
        created.await(10, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(3_000)
        val info = java.util.concurrent.CountDownLatch(1)
        main.post {
            manager.requestGroupInfo(channel) { group ->
                report(
                    if (group == null) "Wi-Fi Direct group: none"
                    else "Wi-Fi Direct group ssid='${group.networkName}' password='${group.passphrase}' " +
                        "iface=${group.`interface`} owner=${group.isGroupOwner}",
                )
                info.countDown()
            }
        }
        info.await(10, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(30_000)
        main.post { manager.removeGroup(channel, listener("removeGroup") {}) }
    }

    private fun listener(name: String, done: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { report("$name ok"); done() }
        override fun onFailure(reason: Int) { report("$name failed reason=$reason"); done() }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        main.post { output.append(line + "\n") }
    }

    private companion object {
        const val TAG = "DiPlayProbe"
    }
}
