package com.r34v.r34v.playback

import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.Stream
import com.r34v.r34v.data.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * 实测媒体链路的速度。
 *
 * 原来的“网络诊断”只请求首页 HTML：它当然会显示成功，因为 178 KB 的页面在 250 KB/s 下
 * 也就 0.7 秒。真正决定能不能看的是媒体文件那一路——它要多一次 302 跳到 CDN，而且 CDN 按
 * 连接限速。所以诊断必须读一段真实媒体字节，才能回答“我这条链路能撑哪一档”。
 */
class MediaProbe(private val network: Network) {
    data class Result(val kbps: Int, val bytes: Long, val ms: Long, val status: Int) {
        val suggested: String get() = StreamSelection.recommendedLabel(kbps)
        val summary: String
            get() = "HTTP $status · ${bytes / 1024} KB / $ms ms · 约 $kbps kbps （${"%.2f".format(kbps / 8.0 / 1024)} MB/s）"
    }

    /**
     * 读 [stream] 的前 [limitBytes] 字节并计时。
     *
     * 用 Range 而不是整文件：诊断不能被 13 MB 的成片拖住；CDN 支持 `Accept-Ranges: bytes`，
     * 一小段就足以体现出口限速。失败抛 [IOException]，由调用方决定怎么展示。
     */
    suspend fun measure(site: SiteContext, stream: Stream, limitBytes: Long = SAMPLE_BYTES): Result =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(stream.url).tag(SiteContext::class.java, site)
                .header("Referer", site.mediaReferer)
                .header("Cache-Control", "no-store")
                .header("Range", "bytes=0-${limitBytes - 1}")
                .build()
            network.mediaClient.readResponse(request) { response ->
                if (!response.isSuccessful) throw IOException("媒体 HTTP ${response.code}")
                val input = response.body?.byteStream() ?: throw IOException("媒体没有响应体")
                // 在拿到响应头后再开始计时：避免把 DNS / TLS / 302 重定向和首包等待算成下载速率，
                // 否则高延迟节点上的 1 MB 样本会被严重低估，误把 720p/1080p 压到 480p。
                val started = System.currentTimeMillis()
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                input.use { source ->
                    while (total < limitBytes) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                }
                if (total <= 0) throw IOException("媒体没有返回任何字节")
                val ms = (System.currentTimeMillis() - started).coerceAtLeast(1)
                Result(kbps = (total * 8 / ms).toInt(), bytes = total, ms = ms, status = response.code)
            }
        }

    companion object {
        /** 1 MB：720p 大约半秒的量，足够暴露限速，又不至于让诊断等太久。 */
        const val SAMPLE_BYTES = 1024L * 1024

        /**
         * 只有读到这么多字节，样本才能代表吞吐。
         *
         * HLS 的“文件”是播放列表：1 MB Range 实际只回几百字节，算出来的是请求延迟。把这种
         * 样本交给选档逻辑，会把整条链路误判成极慢，见 [StreamSelection.pick]。
         */
        const val MIN_TRUSTED_BYTES = 128L * 1024
    }
}
