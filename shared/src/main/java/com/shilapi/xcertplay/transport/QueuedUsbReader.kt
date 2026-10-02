package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.os.Build
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bulk IN reads with a timeout before API 26, which has neither UsbRequest.queue(ByteBuffer) nor
 * UsbDeviceConnection.requestWait(timeout).
 *
 * A thread keeps one request queued and blocks in requestWait(); [read] waits for its results. As
 * with the API 26 path a timed-out read leaves the request queued, so no data is lost. The thread
 * must be the only requestWait() user of [connection].
 */
internal class QueuedUsbReader(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
    private val chunkBytes: Int,
    name: String,
) : Closeable {
    private val results = LinkedBlockingQueue<Any>(MAX_PENDING_CHUNKS)
    private val request = UsbRequest()
    @Volatile private var closed = false
    @Volatile private var failure: Exception? = null
    private val thread: Thread

    init {
        require(chunkBytes in 1..MAX_LEGACY_TRANSFER_BYTES) { "chunkBytes must be 1..$MAX_LEGACY_TRANSFER_BYTES" }
        if (!request.initialize(connection, endpoint)) {
            request.close()
            throw IphoneUsbException.DeviceUnavailable("Android could not initialize the $name read request")
        }
        thread = Thread({ run(name) }, "$name-in").apply {
            isDaemon = true
            start()
        }
    }

    /** The next completed chunk, or null when [timeoutMillis] passes first. */
    fun read(timeoutMillis: Long): ByteArray? {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USB reader is closed")
        return when (val result = results.poll(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            null -> failure?.let { throw it }
            is Exception -> throw result
            else -> result as ByteArray
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Completes the blocked requestWait(); the thread then sees [closed].
        runCatching { request.cancel() }
        thread.interrupt()
        runCatching { thread.join(JOIN_TIMEOUT_MILLIS) }
        if (!thread.isAlive) request.close()
    }

    private fun run(name: String) {
        val buffer = ByteBuffer.allocateDirect(chunkBytes)
        try {
            while (!closed) {
                buffer.clear()
                @Suppress("DEPRECATION")
                if (!request.queue(buffer, chunkBytes)) throw IphoneUsbException.DeviceUnavailable("Android could not queue the $name read request")
                val completed = connection.requestWait()
                if (closed) return
                if (completed == null) throw IphoneUsbException.DeviceUnavailable("Android returned no $name read request")
                if (completed !== request) throw IphoneUsbException.DeviceUnavailable("Android completed an unexpected $name request")
                val transferred = buffer.position()
                if (transferred > 0) {
                    val chunk = ByteArray(transferred)
                    buffer.flip()
                    buffer.get(chunk)
                    results.put(chunk)
                }
            }
        } catch (_: InterruptedException) {
            // Closed.
        } catch (error: Exception) {
            if (!closed) {
                val reported = error as? IphoneUsbException
                    ?: IphoneUsbException.DeviceUnavailable("$name read failed", error)
                failure = reported
                results.offer(reported)
            }
        }
    }

    companion object {
        /** Before API 28 a single USB transfer is limited to 16 KiB. */
        const val MAX_LEGACY_TRANSFER_BYTES = 16 * 1024
        private const val MAX_PENDING_CHUNKS = 64
        private const val JOIN_TIMEOUT_MILLIS = 1_000L

        /** True where this reader replaces the queue(ByteBuffer)/requestWait(timeout) path. */
        val needed: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

        /** The largest single transfer this Android version supports. */
        fun transferLimit(preferred: Int): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) preferred else minOf(preferred, MAX_LEGACY_TRANSFER_BYTES)
    }
}
