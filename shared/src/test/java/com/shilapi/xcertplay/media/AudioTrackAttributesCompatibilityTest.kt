package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioTrackAttributesCompatibilityTest {
    @Test fun legacyFocusUsesMappedOrExplicitStreamWhileModernFocusUsesAttributes() {
        assertEquals(AudioManager.STREAM_VOICE_CALL, focusLegacyStreamType(19, 0, AudioManager.STREAM_VOICE_CALL))
        assertEquals(8, focusLegacyStreamType(25, 8, AudioManager.STREAM_MUSIC))
        assertEquals(-1, focusLegacyStreamType(26, 0, AudioManager.STREAM_VOICE_CALL))
        assertEquals(8, focusLegacyStreamType(26, 8, AudioManager.STREAM_MUSIC))
    }

    @Test
    @Config(sdk = [28])
    fun android9UsesTheConfiguredAttributesWithoutCallingTheNewGetter() {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = buildTrack(attributes)
        try {
            assertSame(attributes, Api21AudioTrackCompat.attributesForFocus(track, attributes))
        } finally {
            track.release()
        }
    }

    @Test
    @Config(sdk = [29])
    fun android10KeepsTheActualTrackAttributes() {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = buildTrack(attributes)
        try {
            val different = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
            assertEquals(attributes, Api21AudioTrackCompat.attributesForFocus(track, different))
        } finally {
            track.release()
        }
    }

    private fun buildTrack(attributes: AudioAttributes): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(4096)
        .build()
}
