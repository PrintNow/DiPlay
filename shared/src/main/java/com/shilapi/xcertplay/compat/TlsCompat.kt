package com.shilapi.xcertplay.compat

import android.os.Build
import android.util.Log
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import org.conscrypt.Conscrypt

/**
 * TLS contexts that offer TLS 1.2. Android 4.4's SSLEngine has no TLS 1.2 (API 20) and its
 * sockets leave it disabled, so before API 20 a bundled Conscrypt provides the protocol.
 */
internal object TlsCompat {
    private val legacyProvider: Provider? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) return@lazy null
        try {
            Conscrypt.newProvider()
        } catch (error: Throwable) {
            Log.w("xcertplay-usb", "bundled Conscrypt unavailable; TLS 1.2 will be missing", error)
            null
        }
    }

    fun sslContext(): SSLContext =
        legacyProvider?.let { SSLContext.getInstance("TLS", it) } ?: SSLContext.getInstance("TLS")

    /** For HttpsURLConnection before API 20, with the system trust store; null when not needed. */
    fun legacySocketFactory(): SSLSocketFactory? {
        if (legacyProvider == null) return null
        return sslContext().apply { init(null, null, null) }.socketFactory
    }
}
