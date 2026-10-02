package com.shilapi.xcertplay.compat

import android.util.Log

/**
 * Linux calls behind APIs that Android 4.4 lacks (see jni/legacy_platform.c). Each returns 0 or a
 * negative errno; [UNAVAILABLE] when the library could not be loaded.
 */
internal object LegacyPlatformNative {
    const val UNAVAILABLE = Int.MIN_VALUE

    private val loaded: Boolean = try {
        System.loadLibrary("xcertplay_legacy_platform")
        true
    } catch (error: LinkageError) {
        Log.w("xcertplay-usb", "legacy platform library unavailable", error)
        false
    }

    fun usbSetConfiguration(fd: Int, value: Int): Int = if (loaded) setConfiguration(fd, value) else UNAVAILABLE

    fun usbSetInterface(fd: Int, interfaceNumber: Int, alternateSetting: Int): Int =
        if (loaded) setInterface(fd, interfaceNumber, alternateSetting) else UNAVAILABLE

    fun clearNonBlocking(fd: Int): Int = if (loaded) setBlocking(fd) else UNAVAILABLE

    private external fun setConfiguration(fd: Int, value: Int): Int
    private external fun setInterface(fd: Int, interfaceNumber: Int, alternateSetting: Int): Int
    private external fun setBlocking(fd: Int): Int
}
