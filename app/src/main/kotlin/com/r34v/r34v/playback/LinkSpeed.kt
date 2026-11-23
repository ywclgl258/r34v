@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * 当前设备的媒体链路速度估计，进程内共享。
 *
 * 两个来源合成一个数，都用“真实字节 / 真实时间”算出来的值：
 * 1. 播放中的样本：`DefaultBandwidthMeter` 每次取完一块媒体就回调一次，最可信；
 * 2. 起播前的探测：[MediaProbe] 读一小段真实媒体字节，让**第一次播放**就有依据选档，
 *    而不是先按 720p 死拉、卡半天才降。
 *
 * 这里刻意**不读** `meter.bitrateEstimate`：它在拿到任何样本之前会返回 1 Mbps 的出厂默认值，
 * 那不是实测值——把它当成实测会把每一次首播都误判成 1 Mbps 的烂网、直接按最低档起播。
 * 单位统一为 kbps，0 表示“还不知道”。任何来源都不写磁盘：换网络后旧的估值必须失效。
 */
class LinkSpeed(context: Context) {
    val meter: DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

    /** 播放中实测到的最近一次估计（kbps）。 */
    @Volatile private var sampled = 0

    /** 起播前探测得到的估计（kbps）。 */
    @Volatile private var seeded = 0

    /** 证据时间；0 表示由测试直接注入的值，不做过期判断。 */
    @Volatile private var sampledAt = 0L
    @Volatile private var seededAt = 0L

    init {
        meter.addEventListener(Handler(Looper.getMainLooper()), object : BandwidthMeter.EventListener {
            override fun onBandwidthSample(elapsedMs: Int, bytes: Long, bitrate: Long) {
                // 播放列表、清单和 1 KB 级的探测只有往返延迟，没有吞吐量。让它们写进估计，
                // 会让一个 HLS 站点把整个进程的档位钉在最低档（见 PlayerActivity.chosenStream）。
                if (bytes < MIN_SAMPLE_BYTES) return
                val estimate = (bitrate / 1000).toInt()
                if (estimate > 0) { sampled = estimate; sampledAt = SystemClock.elapsedRealtime() }
            }
        })
    }

    /**
     * 播放实测优先；没有播放实测时用探测值。两者都没有就返回 0，调用方按“未知”处理。
     *
     * 证据有寿命：弱网下录到的一个低样本不该让整个进程此后永远按最低档起播，
     * 换了网络之后更不该继续生效。过期后回到“未知”，由下一次探测重新测。
     */
    fun estimateKbps(): Int {
        val now = SystemClock.elapsedRealtime()
        val sample = sampled.takeIf { it > 0 && fresh(sampledAt, now) } ?: 0
        val probe = seeded.takeIf { it > 0 && fresh(seededAt, now) } ?: 0
        return StreamSelection.mergeEstimates(sample, probe)
    }

    private fun fresh(at: Long, now: Long) = at == 0L || now - at < ESTIMATE_TTL_MS

    fun seed(kbps: Int) {
        if (kbps > 0) { seeded = kbps; seededAt = SystemClock.elapsedRealtime() }
    }

    /**
     * 丢掉旧证据。
     *
     * 估值是整个进程共享的，但网络随时会换：开关系统代理、Wi-Fi 与移动数据互切之后，
     * 旧样本既不代表新链路的吞吐，也会让新会话继续按旧档位起播。代理变化时调用。
     */
    fun reset() {
        sampled = 0
        seeded = 0
        sampledAt = 0L
        seededAt = 0L
    }

    fun known(): Boolean = estimateKbps() > 0

    fun describe(): String {
        val value = estimateKbps()
        return if (value <= 0) "尚未测得链路速度" else "约 $value kbps（${"%.2f".format(value / 8.0 / 1024)} MB/s）"
    }

    private companion object {
        /** 低于这个字节数的传输按延迟处理，不当作吞吐样本。 */
        const val MIN_SAMPLE_BYTES = 16L * 1024

        /** 证据有效期：过了这段时间就重新测，避免一次弱网把进程钉死在低档。 */
        const val ESTIMATE_TTL_MS = 5 * 60_000L
    }
}
