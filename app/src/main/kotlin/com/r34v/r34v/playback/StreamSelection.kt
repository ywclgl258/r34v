package com.r34v.r34v.playback

import com.r34v.r34v.data.Stream

/**
 * 一段缓冲窗口的四个参数，对应 Media3 `DefaultLoadControl.setBufferDurationsMs`。
 *
 * 单独抽出来的原因是：原来写死的 `8000 / 50000 / 400 / 800` 对大码率站点过于乐观——
 * 720p 的 8 秒起播缓冲就是 2.4 MB，在只有 250 KB/s 的链路上要下 9.8 秒，用户看到的是
 * “转很久才出画面”。缓冲目标必须跟着片源码率和实测带宽走，而不是全局一个常数。
 */
data class BufferPlan(val minMs: Int, val maxMs: Int, val forPlaybackMs: Int, val afterRebufferMs: Int) {
    /** 用于判断引擎是否需要因为缓冲参数变化而重建。 */
    val signature: String get() = "$minMs/$maxMs/$forPlaybackMs/$afterRebufferMs"
}

/**
 * 按实测带宽选清晰度、按载荷决定缓冲窗口的纯策略。
 *
 * 站点不会在 `flashvars` 里给每个档位的码率，所以用“高度→典型交付码率”的参考表估算：
 * 它只用来判断“这条链路喂不喂得动这一档”，不需要精确到 Kbps。
 */
object StreamSelection {
    /** 计划带宽上限只占实测带宽的 70%，留出重传、多请求共用和 CDN 抖动的余量。 */
    const val HEADROOM = 0.7

    /** 无法从标签推断高度时（例如“原画”“自动”）按 720p 量级处理，避免把它当成零成本。 */
    private const val UNKNOWN_KBPS = 2500

    /** 偏好标签里没有数字时（“原画”“原始文件”）套用旧的 720p 上限。 */
    private const val DEFAULT_CEILING = 720

    /** 常见交付码率（kbps），按高度升序；判断时取“不超过该高度的最后一档”。 */
    private val referenceKbps = listOf(
        144 to 200,
        240 to 450,
        360 to 800,
        480 to 1200,
        720 to 2500,
        1080 to 4500,
        1440 to 9000,
        2160 to 18000,
    )

    /** 实测带宽换算成允许计划使用的带宽；未知（<=0）返回 0 表示“没有可用的实测值”。 */
    fun bandwidthBudget(kbps: Int): Int = if (kbps <= 0) 0 else (kbps * HEADROOM).toInt()

    /**
     * 两个速度来源的合成规则。
     *
     * 播放实测比一次性探测更贴近真实负载（探测只读开头 1 MB），所以有实测时压过探测值；
     * 都为 0 表示**未知**，调用方必须按旧行为（尊重用户设置）处理，不能当成“网速为零”。
     */
    fun mergeEstimates(sampledKbps: Int, seededKbps: Int): Int = when {
        sampledKbps > 0 -> sampledKbps
        seededKbps > 0 -> seededKbps
        else -> 0
    }

    /** 该清晰度持续播放所需的码率估算（kbps）。 */
    fun requiredKbps(stream: Stream): Int {
        val height = stream.height
        if (height <= 0) return UNKNOWN_KBPS
        return referenceKbps.lastOrNull { it.first <= height }?.second ?: referenceKbps.first().second
    }

    /**
     * 用户偏好的那一档。
     *
     * 标签对不上时按高度兜底：认得数字就取“不超过该高度里最高的一档”；连数字都没有
     * （“原画”“自动”）就按旧的 720p 默认值处理，绝不一上来就给最低档。
     */
    fun preferred(streams: List<Stream>, label: String): Stream? {
        if (streams.isEmpty()) return null
        streams.firstOrNull { it.label == label }?.let { return it }
        val ceiling = Regex("\\d+").find(label)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_CEILING
        val ordered = streams.sortedByDescending { it.height }
        return ordered.firstOrNull { it.height in 1..ceiling } ?: ordered.last()
    }

    /**
     * 起播档位的最终选择。
     *
     * - 带宽未知：完全尊重用户设置，行为和以前一致。
     * - 偏好档喂得动：就用偏好档。
     * - 喂不动：在偏好档以下找最高、且仍在预算内的档位。
     * - 全都不在预算内（链路极差）：仍然给最低档，让播放器有东西可试，而不是报错。
     */
    fun pick(streams: List<Stream>, preferredLabel: String, bandwidthKbps: Int): Stream {
        require(streams.isNotEmpty()) { "没有可用的清晰度" }
        val wanted = preferred(streams, preferredLabel) ?: return streams.first()
        val budget = bandwidthBudget(bandwidthKbps)
        if (budget <= 0) return wanted
        if (requiredKbps(wanted) <= budget) return wanted
        val ceiling = if (wanted.height > 0) wanted.height else Int.MAX_VALUE
        return streams.sortedByDescending { it.height }
            .firstOrNull { it.height <= ceiling && requiredKbps(it) <= budget }
            ?: streams.minByOrNull { it.height }!!
    }

    /** 下一档更低的清晰度；已经是最低档时返回 null。 */
    fun lower(streams: List<Stream>, current: Stream): Stream? =
        streams.sortedByDescending { it.height }.dropWhile { it.label != current.label }.drop(1).firstOrNull()

    /** 诊断用：这条链路实际能支撑到哪一档。 */
    fun recommendedLabel(bandwidthKbps: Int): String {
        val budget = bandwidthBudget(bandwidthKbps)
        if (budget <= 0) return "无法判定"
        return referenceKbps.lastOrNull { it.second <= budget }?.let { "${it.first}p" } ?: "低于 144p"
    }

    /**
     * 缓冲窗口。
     *
     * 高码率还是用原来的宽窗口（起播 8 秒）；720p 收紧到 4 秒；低码率或链路明显吃紧时
     * 收紧到 2.5 秒起播、12–15 秒上限，把“等到攒够才播”的时间换成“先播再补”。
     */
    fun bufferPlan(streamKbps: Int, linkKbps: Int = 0): BufferPlan {
        val strained = linkKbps > 0 && bandwidthBudget(linkKbps) < streamKbps
        return when {
            streamKbps >= 4200 -> BufferPlan(8000, 40000, 500, 800)
            streamKbps >= 2000 -> if (strained) BufferPlan(2500, 12000, 500, 1000) else BufferPlan(4000, 15000, 500, 1000)
            else -> BufferPlan(2500, 12000, 500, 1000)
        }
    }
}

/**
 * 什么时候该自动降一档。
 *
 * 三种独立信号，任意一个成立就降：起播阶段等太久、播放中单次卡太久、短时间内反复卡。
 * 阈值取“用户已经开始觉得卡”的位置，不是网络抖一下就换档。
 */
object StallPolicy {
    /** 起播现象：位置还停在开头就缓冲超过 6 秒。 */
    const val STARTUP_MS = 6_000L

    /** 播放中单次缓冲超过 8 秒。 */
    const val PLAYBACK_MS = 8_000L

    /** 统计窗口，以及窗口内允许的最大卡顿次数。 */
    const val WINDOW_MS = 30_000L
    const val MAX_STALLS = 3

    /** 位置小于这个值视为“还没真正播起来”。 */
    const val STARTUP_POSITION_MS = 5_000L

    fun shouldDowngrade(bufferingMs: Long, positionMs: Long, stallsInWindow: Int): Boolean =
        (positionMs < STARTUP_POSITION_MS && bufferingMs >= STARTUP_MS) ||
            bufferingMs >= PLAYBACK_MS ||
            stallsInWindow >= MAX_STALLS
}
