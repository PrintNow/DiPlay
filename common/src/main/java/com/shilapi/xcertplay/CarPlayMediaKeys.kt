package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var session: MediaKeySession? = null
    private var audioFocus: MediaAudioFocus? = null
    private var focusHeld = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change=$change")
        // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
        if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
    }

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post { synchronized(this) { if (controller === expected) regainFocusLocked() } }
    }

    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val changed = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                session?.setNowPlaying(update, shownArtworkLocked(), elapsedUpdatedAt, mediaAudioActive, changed)
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setNowPlaying(nowPlaying, shownArtworkLocked(), elapsedUpdatedAt, mediaAudioActive, true)
        }
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
        if (focusHeld) return
        focusHeld = audioFocus?.request() == true
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        session?.setNowPlaying(nowPlaying, shownArtworkLocked(), elapsedUpdatedAt, active, false)
    }

    private fun start(context: Context) {
        audioFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ModernMediaAudioFocus(context, mainHandler, focusListener)
        } else {
            LegacyMediaAudioFocus(context, focusListener)
        }
        val granted = audioFocus?.request() == true
        focusHeld = granted
        session = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PlatformMediaKeySession(context, CarPlayMediaCallback(::send), mainHandler)
        } else {
            LegacyMediaKeySession(context)
        }
        session?.setNowPlaying(nowPlaying, shownArtworkLocked(), elapsedUpdatedAt, mediaAudioActive, true)
        Log.i(TAG, "media keys active focusGranted=$granted")
    }

    private fun releaseLocked() {
        artworkOwner = null
        artworkQueue.clear()
        session?.release()
        session = null
        audioFocus?.abandon()
        audioFocus = null
        focusHeld = false
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
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

    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = ContextCompat.getDrawable(context, R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION || bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            .also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/** Where the car delivers media keys while CarPlay plays. */
private interface MediaKeySession {
    fun setNowPlaying(info: CarPlayNowPlaying, artwork: Bitmap?, elapsedUpdatedAt: Long, mediaAudioActive: Boolean, metadataChanged: Boolean)
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

    override fun setNowPlaying(info: CarPlayNowPlaying, artwork: Bitmap?, elapsedUpdatedAt: Long, mediaAudioActive: Boolean, metadataChanged: Boolean) {
        if (metadataChanged) session.setMetadata(PlatformMediaMetadata.create(info, artwork))
        val playing = if (info.elapsedMillis != null || info.title != null) info.playing else mediaAudioActive
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    info.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    elapsedUpdatedAt,
                )
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

    override fun setNowPlaying(info: CarPlayNowPlaying, artwork: Bitmap?, elapsedUpdatedAt: Long, mediaAudioActive: Boolean, metadataChanged: Boolean) = Unit

    override fun release() {
        @Suppress("DEPRECATION")
        audio?.unregisterMediaButtonEventReceiver(receiver)
    }
}

private interface MediaAudioFocus {
    fun request(): Boolean
    fun abandon()
}

private class LegacyMediaAudioFocus(
    context: Context,
    private val listener: AudioManager.OnAudioFocusChangeListener,
) : MediaAudioFocus {
    private val audio = ContextCompat.getSystemService(context, AudioManager::class.java)

    @Suppress("DEPRECATION")
    override fun request(): Boolean = audio?.requestAudioFocus(
        listener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN,
    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    @Suppress("DEPRECATION")
    override fun abandon() {
        audio?.abandonAudioFocus(listener)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private class ModernMediaAudioFocus(
    context: Context,
    handler: Handler,
    listener: AudioManager.OnAudioFocusChangeListener,
) : MediaAudioFocus {
    private val audio = ContextCompat.getSystemService(context, AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener(listener, handler)
        .build()

    override fun request(): Boolean = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audio?.abandonAudioFocusRequest(request)
    }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal object PlatformMediaMetadata {
    fun create(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()
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
