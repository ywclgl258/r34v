package com.r34v.r34v.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.roundToInt

/** Owned by one slider session. Call [frameAt] serially, then [close] after the last decode. */
internal class LocalSeekPreview(private val file: File) {
    private var retriever: MediaMetadataRetriever? = null
    private var triedOpening = false
    // Access order retains hot positions and also remembers failed requests, within the same limit.
    private val frames = LinkedHashMap<Long, Bitmap?>(MAX_FRAMES, .75f, true)

    suspend fun frameAt(positionMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (frames.containsKey(positionMs)) return@withContext frames[positionMs]
        val frame = try {
            (retriever ?: open())?.let { decode(it, positionMs * 1_000) }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        frames[positionMs] = frame
        if (frames.size > MAX_FRAMES) {
            val entries = frames.entries.iterator()
            entries.next()
            entries.remove()
        }
        frame
    }

    private fun open(): MediaMetadataRetriever? {
        if (triedOpening) return null
        triedOpening = true
        val media = MediaMetadataRetriever()
        try {
            // The descriptor limits the retriever to the imported file, even when the container fails to open.
            FileInputStream(file).use { media.setDataSource(it.fd) }
            if (Build.VERSION.SDK_INT < 27) {
                val width = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                val height = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                // Older Android can only decode a full frame. Skip large and unknown-size frames.
                if (width <= 0 || height <= 0 || width > MAX_UNSCALED_PIXELS / height) return null
            }
            retriever = media
            return media
        } finally {
            if (retriever == null) runCatching { media.release() }
        }
    }

    private fun decode(media: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= 27) {
            return media.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, WIDTH, HEIGHT)
        }
        val frame = media.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1f, WIDTH.toFloat() / frame.width, HEIGHT.toFloat() / frame.height)
        if (scale == 1f) return frame
        return try {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1), true)
        } finally {
            frame.recycle()
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        frames.clear()
        retriever?.let { runCatching { it.release() } }
        retriever = null
    }

    companion object {
        private const val WIDTH = 160
        private const val HEIGHT = 90
        private const val MAX_FRAMES = 12
        private const val MAX_UNSCALED_PIXELS = 1_920L * 1_080L
        private const val STEP_MS = 500L

        fun framePosition(positionMs: Long, durationMs: Long): Long =
            positionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0)) / STEP_MS * STEP_MS
    }
}
