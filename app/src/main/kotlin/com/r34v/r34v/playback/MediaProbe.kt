package com.r34v.r34v.playback

import android.os.SystemClock
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.Stream
import com.r34v.r34v.data.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

/**
 * 实测媒体链路的速度。
 *
 * 原来的“网络诊断”只请求首页 HTML：它当然会显示成功，因为 178 KB 的页面在 250 KB/s 下
 * 也就 0.7 秒。真正决定能不能看的是媒体文件那一路——它要多一次 302 跳到 CDN，而且 CDN 按
 * 连接限速。所以诊断必须读一段真实媒体字节，才能回答“我这条链路能撑哪一档”。
 */
class MediaProbe(private val network: Network) {
    data class Result(val kbps: Int, val bytes: Long, val ms: Long, val status: Int, val playlist: Boolean) {
        val trustedKbps: Int? get() = kbps.takeIf { !playlist && bytes >= MIN_TRUSTED_BYTES && it > 0 }
        val suggested: String? get() = trustedKbps?.let(StreamSelection::recommendedLabel)
        val summary: String
            get() {
                val speed = trustedKbps
                val read = "HTTP $status · $bytes B / $ms ms"
                return when {
                    playlist -> "$read · 已读取 HLS 播放列表，未测量媒体分片吞吐"
                    speed == null -> "$read · 样本不足，未测得媒体吞吐"
                    else -> "$read · 约 $speed kbps （${"%.2f".format(speed / 8.0 / 1024)} MB/s）"
                }
            }
    }

    /**
     * 读 [stream] 的前 [limitBytes] 字节并计时。
     *
     * 用 Range 而不是整文件：诊断不能被 13 MB 的成片拖住；CDN 支持 `Accept-Ranges: bytes`，
     * 一小段就足以体现出口限速。失败抛 [IOException]，由调用方决定怎么展示。
     */
    suspend fun measure(site: SiteContext, stream: Stream, limitBytes: Long = SAMPLE_BYTES): Result =
        withContext(Dispatchers.IO) {
            require(limitBytes > 0)
            val playlistUrl = HlsDownloads.mime(stream.url) != null
            val request = Request.Builder().url(stream.url).tag(SiteContext::class.java, site)
                .header("Referer", site.mediaReferer)
                .header("Cache-Control", "no-store")
                .apply { if (!playlistUrl) header("Range", "bytes=0-${limitBytes - 1}") }
                .build()
            network.mediaClient.readResponse(request) { response ->
                if (!response.isSuccessful) throw IOException("媒体 HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("媒体没有响应体")
                // 吞吐计时从响应头之后开始，去掉连接与重定向等待。
                val started = SystemClock.elapsedRealtime()
                val playlist = playlistUrl || HlsDownloads.mime(response.request.url.toString()) != null ||
                    isPlaylistContentType(response.header("Content-Type")) ||
                    (limitBytes >= HLS_HEADER.size && source.rangeEquals(0, HLS_HEADER))
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (total < limitBytes) {
                    val read = source.read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - total).toInt())
                    if (read < 0) break
                    total += read
                }
                if (total == 0L) throw IOException("媒体没有返回任何字节")
                val ms = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                val kbps = (total * 8 / ms).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Result(kbps, total, ms, response.code, playlist)
            }
        }

    companion object {
        /** 1 MB：720p 大约半秒的量，足够暴露限速，又不至于让诊断等太久。 */
        const val SAMPLE_BYTES = 1024L * 1024

        const val MIN_TRUSTED_BYTES = 128L * 1024
        private val HLS_HEADER = "#EXTM3U".encodeUtf8()

        internal fun isPlaylistContentType(value: String?): Boolean =
            when (value?.substringBefore(';')?.trim()?.lowercase()) {
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
                "audio/mpegurl", "audio/x-mpegurl" -> true
                else -> false
            }
    }
}
