package com.r34v.r34v.playback

import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.Stream
import kotlinx.coroutines.CancellationException

/**
 * “网络诊断”的媒体部分。
 *
 * 页面请求成功只能说明 HTML 拿得到；能不能看取决于媒体那一路：它要多一次 302 跳到第三方 CDN，
 * 而 CDN 是按连接限速的。这里把两段都测出来，并直接给出“这条链路现实能撑哪一档”。
 */
class PlaybackDiagnostics(private val network: Network, private val probe: MediaProbe) {

    suspend fun report(site: SiteContext, preferred: String = "720p"): String = buildString {
        appendLine(network.diagnose(site))
        appendLine()
        val adapter = AdapterRegistry.adapter(site, network)
        val entry = try {
            adapter.feed(adapter.homeRequest()).videos.firstOrNull { it.type != "站外视频" }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (entry == null) { append("没有可以测速的影片，媒体链路未验证。"); return@buildString }
        val detail = try { adapter.detail(entry.url) } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            append("详情页取不到播放地址：${error.message}\n媒体链路未验证。")
            return@buildString
        }
        val target = pickTarget(detail.streams, preferred) ?: run {
            append("详情页没有可测速的播放地址。")
            return@buildString
        }
        appendLine("测速样本：${target.label}")
        val epoch = network.routeEpoch()
        val result = try { probe.measure(site, target) } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            append("媒体请求失败：${error.message}\n建议换网络或稍后重试。")
            return@buildString
        }
        if (epoch != network.routeEpoch()) {
            append("测速期间网络路径已切换，请重新诊断。")
            return@buildString
        }
        appendLine(result.summary)
        result.suggested?.let {
            appendLine("该档约需 ${StreamSelection.requiredKbps(target)} kbps")
            append("这条链路适合：$it（播放中卡顿会自动降档）")
        }
    }

    /**
     * 优先测用户设置里那一档：它的码率最接近平时的实际负担。用户设置高于站点提供的最高档时
     * 退到最高档，标签对不上时退到“不高于该高度里最高的一档”。
     */
    internal fun pickTarget(streams: List<Stream>, preferred: String): Stream? =
        StreamSelection.preferred(streams, preferred)
}
