@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.util.WeakHashMap

/** 进程内的媒体吞吐估计，单位为 kbps。网络路径变化或证据过期后回到未知。 */
class LinkSpeed(private val context: Context, private val currentRouteEpoch: () -> Long) {
    private var epoch = currentRouteEpoch()
    private var sampled = 0
    private var seeded = 0
    private var sampledAt = 0L
    private var seededAt = 0L

    private class Transfer(val epoch: Long, var startedAt: Long = 0, var bytes: Long = 0)

    // 连接在响应头前失败时不会收到 onTransferEnd，弱引用允许回收已关闭的数据源。
    private val transfers = WeakHashMap<DataSource, Transfer>()
    private var playbackMeter: BandwidthMeter? = null

    private inner class PlaybackMeter(private val adaptiveMeter: DefaultBandwidthMeter) :
        BandwidthMeter by adaptiveMeter, TransferListener {
        override fun getTransferListener(): TransferListener = this

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this@LinkSpeed) {
                transfers.remove(source)
                if (isNetwork && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED) &&
                    dataSpec.uri.lastPathSegment?.endsWith(".m3u8", ignoreCase = true) != true) {
                    transfers[source] = Transfer(routeEpoch())
                }
            }
            adaptiveMeter.onTransferInitializing(source, dataSpec, isNetwork)
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this@LinkSpeed) {
                transfers[source]?.let { transfer ->
                    val contentType = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.firstOrNull()
                    if (MediaProbe.isPlaylistContentType(contentType)) transfers.remove(source)
                    else transfer.startedAt = SystemClock.elapsedRealtime()
                }
            }
            adaptiveMeter.onTransferStart(source, dataSpec, isNetwork)
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            synchronized(this@LinkSpeed) { transfers[source]?.let { it.bytes += bytesTransferred } }
            adaptiveMeter.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(this@LinkSpeed) {
                val transfer = transfers.remove(source)
                if (transfer != null && transfer.startedAt > 0 && transfer.bytes >= MIN_SAMPLE_BYTES && transfer.epoch == routeEpoch()) {
                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - transfer.startedAt
                    if (elapsedMs > 0) {
                        sampled = (transfer.bytes * 8 / elapsedMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        sampledAt = now
                    }
                }
            }
            adaptiveMeter.onTransferEnd(source, dataSpec, isNetwork)
        }
    }

    // 新播放器从当前路径的 Media3 带宽表开始；应用选档始终只用实际媒体传输的证据。
    val meter: BandwidthMeter
        @Synchronized get() {
            routeEpoch()
            return playbackMeter ?: PlaybackMeter(DefaultBandwidthMeter.Builder(context).build())
                .also { playbackMeter = it }
        }

    @Synchronized internal fun routeEpoch(): Long {
        val current = currentRouteEpoch()
        if (current != epoch) {
            epoch = current
            transfers.clear()
            playbackMeter = null
            sampled = 0
            seeded = 0
            sampledAt = 0
            seededAt = 0
        }
        return epoch
    }

    @Synchronized fun estimateKbps(): Int {
        routeEpoch()
        val now = SystemClock.elapsedRealtime()
        val sample = sampled.takeIf { it > 0 && fresh(sampledAt, now) } ?: 0
        val probe = seeded.takeIf { it > 0 && fresh(seededAt, now) } ?: 0
        return StreamSelection.mergeEstimates(sample, probe)
    }

    private fun fresh(at: Long, now: Long) = at == 0L || now - at < ESTIMATE_TTL_MS

    @Synchronized internal fun seed(kbps: Int, measuredOn: Long): Boolean {
        if (kbps <= 0 || measuredOn != routeEpoch()) return false
        seeded = kbps
        seededAt = SystemClock.elapsedRealtime()
        return true
    }

    fun known(): Boolean = estimateKbps() > 0

    fun describe(): String {
        val value = estimateKbps()
        return if (value <= 0) "尚未测得链路速度" else "约 $value kbps（${"%.2f".format(value / 8.0 / 1024)} MB/s）"
    }

    private companion object {
        const val MIN_SAMPLE_BYTES = 16L * 1024
        const val ESTIMATE_TTL_MS = 5 * 60_000L
    }
}
