package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.os.Build
import java.nio.ByteBuffer

/**
 * MediaCodec.getInputBuffer/getOutputBuffer need API 21. Android 4.4 only has the buffer arrays,
 * which change after INFO_OUTPUT_BUFFERS_CHANGED; reading them per call never keeps a stale one.
 */
internal fun MediaCodec.inputBufferCompat(index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        getInputBuffer(index)
    } else {
        @Suppress("DEPRECATION")
        inputBuffers[index]
    }

internal fun MediaCodec.outputBufferCompat(index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        getOutputBuffer(index)
    } else {
        @Suppress("DEPRECATION")
        outputBuffers[index]
    }

/** Only Android 4.4 reports this; the next dequeue already uses the new buffers. */
@Suppress("DEPRECATION")
internal const val INFO_OUTPUT_BUFFERS_CHANGED_COMPAT = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
