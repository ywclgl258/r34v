@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    init {
        meter.addEventListener(Handler(Looper.getMainLooper()), object : BandwidthMeter.EventListener {
            override fun onBandwidthSample(elapsedMs: Int, bytes: Long, bitrate: Long) {
                val estimate = (bitrate / 1000).toInt()
                if (estimate > 0) sampled = estimate
            }
        })
    }

    /** 播放实测优先；没有播放实测时用探测值。两者都没有就返回 0，调用方按“未知”处理。 */
    fun estimateKbps(): Int = StreamSelection.mergeEstimates(sampled, seeded)

    /** 记录一次起播前探测，让本次播放立刻有依据选档。 */
    fun seed(kbps: Int) {
        if (kbps > 0) seeded = kbps
    }

    fun known(): Boolean = estimateKbps() > 0

    fun describe(): String {
        val value = estimateKbps()
        return if (value <= 0) "尚未测得链路速度" else "约 $value kbps（${"%.2f".format(value / 8.0 / 1024)} MB/s）"
    }
}
