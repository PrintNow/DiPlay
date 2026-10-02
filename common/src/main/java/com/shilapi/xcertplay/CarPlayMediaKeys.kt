package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: CarPlayController? = null
    private var session: MediaKeySession? = null
    /** API 26+ focus request; earlier releases own focus through [focusListener]. */
    private var focusRequest: AudioFocusRequest? = null
    private var focusRequested = false
    private var focusHeld = false
    private var appContext: Context? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change=$change")
        // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
        if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) releaseLocked()
        appContext = context.applicationContext
        controller = next
        next.playbackListener = ::onIphonePlaying
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    fun onIphonePlaying(playing: Boolean) {
        if (playing) mainHandler.post { synchronized(this) { regainFocusLocked() } }
    }

    /** Android 4.4: a hardware key delivered to [CarPlayMediaButtonReceiver]. */
    fun onLegacyMediaButton(event: KeyEvent): Boolean {
        if (synchronized(this) { session == null }) return false
        return CarPlayMediaKeyEvents.handle(event, ::send)
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        if (!focusRequested || focusHeld) return
        val audio = appContext?.let { ContextCompat.getSystemService(it, AudioManager::class.java) } ?: return
        focusHeld = requestFocus(audio)
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        session?.setPlaying(active)
    }

    private fun start(context: Context) {
        val audio = ContextCompat.getSystemService(context, AudioManager::class.java)
        val granted = audio != null && requestFocus(audio)
        focusRequested = true
        focusHeld = granted
        session = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PlatformMediaKeySession(context, CarPlayMediaCallback(::send), mainHandler)
        } else {
            LegacyMediaKeySession(context)
        }
        Log.i(TAG, "media keys active focusGranted=$granted")
    }

    private fun requestFocus(audio: AudioManager): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
                .also { focusRequest = it }
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseLocked() {
        session?.release()
        session = null
        val audio = appContext?.let { ContextCompat.getSystemService(it, AudioManager::class.java) }
        if (focusRequested && audio != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(audio::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null
        focusRequested = false
        focusHeld = false
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }
}

/** Where the car delivers media keys while CarPlay plays. */
private interface MediaKeySession {
    fun setPlaying(playing: Boolean)
    fun release()
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
private class PlatformMediaKeySession(
    context: Context,
    callback: MediaSession.Callback,
    handler: Handler,
) : MediaKeySession {
    private val session = MediaSession(context, "DiPlay CarPlay").apply {
        setCallback(callback, handler)
        isActive = true
    }

    override fun setPlaying(playing: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    override fun release() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}

/** Android 4.4 has no MediaSession: the focus owner registers a MEDIA_BUTTON receiver instead. */
private class LegacyMediaKeySession(context: Context) : MediaKeySession {
    private val audio = ContextCompat.getSystemService(context, AudioManager::class.java)
    private val receiver = ComponentName(context, CarPlayMediaButtonReceiver::class.java)

    init {
        @Suppress("DEPRECATION")
        audio?.registerMediaButtonEventReceiver(receiver)
    }

    override fun setPlaying(playing: Boolean) = Unit

    override fun release() {
        @Suppress("DEPRECATION")
        audio?.unregisterMediaButtonEventReceiver(receiver)
    }
}

/** Receives the steering-wheel keys on Android 4.4; see [LegacyMediaKeySession]. */
class CarPlayMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (CarPlayMediaKeys.onLegacyMediaButton(event) && isOrderedBroadcast) abortBroadcast()
    }
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class CarPlayMediaCallback(private val send: (index: Int, source: String) -> Unit) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        return CarPlayMediaKeyEvents.handle(event, send) || super.onMediaButtonEvent(mediaButtonIntent)
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}

/** Hardware media keys, shared by [CarPlayMediaCallback] and the Android 4.4 receiver. */
internal object CarPlayMediaKeyEvents {
    /** Sends the CarPlay press for [event]; false when it is not a CarPlay media key. */
    fun handle(event: KeyEvent, send: (index: Int, source: String) -> Unit): Boolean {
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }
}
