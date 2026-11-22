@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.r34v.r34v.playback.StallPolicy
import java.util.concurrent.atomic.AtomicLong

internal class DowngradeThroughputExperiment(private val activity: PlayerActivity, private val mediaBytes: AtomicLong,
                                             private val requiredKbps: Int) {
    data class Decision(val bufferingMs: Long, val ratesKbps: List<Long>, val refillMs: Long)
    private data class Sample(val at: Long, val bytes: Long, val bufferedMs: Long)

    private val bufferingSince = PlayerActivity::class.java.getDeclaredField("bufferingSince").apply { isAccessible = true }
    private val downgrade = PlayerActivity::class.java.getDeclaredMethod("maybeDowngrade").apply { isAccessible = true }
    private val drops = PlayerActivity::class.java.getDeclaredField("autoDowngrades").apply { isAccessible = true }
    private val samples = ArrayDeque<Sample>(4)
    private var stall = 0L
    private var checkedAt = 0L
    var decision: Decision? = null
        private set

    fun observe(engine: ExoPlayer) {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt < 500) return
        checkedAt = now
        val since = bufferingSince.getLong(activity)
        if (since != stall) {
            samples.clear()
            stall = since
        }
        if (since == 0L || !engine.playWhenReady || engine.playbackState != Player.STATE_BUFFERING || decision != null) return
        val previous = samples.lastOrNull()
        if (previous != null && now - previous.at < 950) return
        if (samples.size == 4) samples.removeFirst()
        samples.addLast(Sample(now, mediaBytes.get(), engine.bufferedPosition))
        if (samples.size < 4 || now - since < 3_500) return
        val pairs = samples.zipWithNext()
        if (pairs.any { (earlier, later) -> later.bytes - earlier.bytes < 8 * 1024 || later.at - earlier.at > 1_600 }) return
        val rates = pairs.map { (earlier, later) -> (later.bytes - earlier.bytes) * 8 / (later.at - earlier.at) }
        val refill = (samples.last().bufferedMs - samples.first().bufferedMs).coerceAtLeast(0)
        val unchangedThreshold = if (engine.currentPosition < StallPolicy.STARTUP_POSITION_MS) StallPolicy.STARTUP_MS else StallPolicy.PLAYBACK_MS
        if (rates.any { it * 10 > requiredKbps * 6 } || refill > 750 || now - since >= unchangedThreshold) return

        val before = drops.getInt(activity)
        bufferingSince.setLong(activity, now - unchangedThreshold)
        try {
            downgrade.invoke(activity)
        } finally {
            if (drops.getInt(activity) == before) bufferingSince.setLong(activity, since)
        }
        if (drops.getInt(activity) > before) decision = Decision(now - since, rates, refill)
    }
}
