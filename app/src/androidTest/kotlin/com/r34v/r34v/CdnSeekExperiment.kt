@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.r34v.r34v.playback.HlsSeekSession
import com.r34v.r34v.playback.PlaybackStore
import okhttp3.HttpUrl
import java.io.Closeable
import java.io.File
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal enum class CdnSeekPolicy(val label: String) {
    CURRENT("current"), LIMIT_PREFETCH("limit-prefetch"), RESERVE_PLAYED("reserve-played"), PRIORITIZE_NEXT("prioritize-next"),
}

internal data class ProbeSegment(val uri: HttpUrl, val startMs: Long, val offset: Long, val length: Long) {
    fun covers(position: Long, requested: Long): Boolean {
        if (position < offset) return false
        if (length == C.LENGTH_UNSET.toLong()) return true
        val distance = position - offset
        return distance < length && requested > 0 && requested <= length - distance
    }

    fun overlap(position: Long, bytes: Long): Long {
        if (length == C.LENGTH_UNSET.toLong()) return bytes
        return (minOf(position + bytes, offset + length) - maxOf(position, offset)).coerceAtLeast(0)
    }
}

internal class ProbePlaylist(val uri: HttpUrl, val segments: List<ProbeSegment>, private val initialization: Set<ProbeSegment>) {
    private val byUri = (segments + initialization).groupBy { it.uri.toString() }
    fun at(positionMs: Long) = segments.last { it.startMs <= positionMs }
    fun nextStart(positionMs: Long) = segments.firstOrNull { it.startMs > positionMs }?.startMs ?: positionMs

    private fun entryFor(spec: DataSpec) = byUri[spec.uri.toString()]
        ?.singleOrNull { it.covers(spec.position, spec.length) }

    fun segmentFor(spec: DataSpec) = entryFor(spec)?.takeUnless { it in initialization }
    fun isInitialization(spec: DataSpec) = entryFor(spec)?.let(initialization::contains) == true
}

/** Candidates run through the application's HLS media source and data sink. */
internal class CdnSeekExperiment(
    context: Context,
    private val policy: CdnSeekPolicy,
) : Closeable {
    private val playhead = AtomicLong()
    private val played = ConcurrentHashMap.newKeySet<String>()
    private val skipped = AtomicInteger()
    private val skippedSegments = ConcurrentHashMap<ProbeSegment, Int>()
    private val directory = File(context.cacheDir, "hls-cdn-benchmark-${System.nanoTime()}")
    private val database = StandaloneDatabaseProvider(context)
    private val limit = PlaybackStore.HLS_SEEK_CACHE_BYTES
    val cache = SimpleCache(directory, if (policy == CdnSeekPolicy.RESERVE_PLAYED) {
        PlayedQuotaEvictor(limit, 24L * 1024 * 1024) { it.substringAfter('|') in played }
    } else LeastRecentlyUsedCacheEvictor(limit), database)
    val skippedWrites get() = skipped.get()
    fun skippedWritesFor(segment: ProbeSegment) = skippedSegments[segment] ?: 0

    fun startSession(playlist: ProbePlaylist): HlsSeekSession {
        playhead.set(0)
        played.clear()
        skipped.set(0)
        skippedSegments.clear()
        return HlsSeekSession(lazyOf(cache), allowWrite = if (policy == CdnSeekPolicy.LIMIT_PREFETCH || policy == CdnSeekPolicy.PRIORITIZE_NEXT) {
            { spec ->
                val segment = playlist.segmentFor(spec)
                val position = playhead.get()
                val writeThrough = if (policy == CdnSeekPolicy.PRIORITIZE_NEXT) playlist.nextStart(position) else position + 5_000
                val allow = if (segment == null) playlist.isInitialization(spec) else segment.startMs <= writeThrough
                if (!allow) {
                    skipped.incrementAndGet()
                    if (segment != null) skippedSegments.merge(segment, 1, Int::plus)
                }
                allow
            }
        } else null) { prefix ->
            cache.keys.filter { it.startsWith(prefix) }.forEach(cache::removeResource)
        }
    }

    fun seekTo(position: Long) { playhead.set(position) }

    /** Sample the main looper; a seek jump never marks the skipped interval as played. */
    fun observe(player: Player, playlist: ProbePlaylist): Closeable {
        val handler = Handler(Looper.getMainLooper())
        var previous = player.currentPosition
        val poll = object : Runnable {
            override fun run() {
                val position = player.currentPosition
                playhead.set(position)
                if (policy == CdnSeekPolicy.RESERVE_PLAYED && player.isPlaying && position - previous in 1..250) {
                    played.add(playlist.at(position).uri.toString())
                }
                previous = position
                handler.postDelayed(this, 50)
            }
        }
        poll.run()
        return Closeable { handler.removeCallbacks(poll) }
    }

    override fun close() {
        cache.release()
        SimpleCache.delete(directory, database)
        database.close()
    }
}

internal class PlayedQuotaEvictor(
    private val capacity: Long,
    private val playedQuota: Long,
    private val played: (String) -> Boolean,
) : CacheEvictor {
    private val lru = TreeSet(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenComparator { a, b -> a.compareTo(b) })
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) trim(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        lru.add(span)
        size += span.length
        trim(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        lru.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        lru.remove(oldSpan)
        lru.add(newSpan)
    }

    private fun trim(cache: Cache, upcoming: Long) {
        while (size + upcoming > capacity && lru.isNotEmpty()) {
            val oldest = lru.first()
            val protectedBytes = lru.filter { played(it.key) }.sumOf { it.length }
            val victim = if (played(oldest.key) && protectedBytes - oldest.length < playedQuota) {
                lru.firstOrNull { !played(it.key) } ?: oldest
            } else oldest
            cache.removeSpan(victim)
        }
    }
}
