package com.r34v.r34v.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Retry only registered same-site mirrors; cancellation and ordinary page errors stay terminal. */
internal object SiteHtml {
    /**
     * 是否为“被拦截”页面，而不是正常内容页。
     *
     * `challenge-platform` 有两种形态：拦截页（整页只有挑战脚本）和正常内容页里的
     * Cloudflare 被动 JS 检测注入（`/cdn-cgi/challenge-platform/scripts/jsd/main.js`）。
     * 4KHD 每页都带后者，旧判定只看关键词和长度，把整站误判成挑战页。
     * 现在必须有“这是挑战页”的明确证据才拦截：标题级提示、cf-mitigated 标记，
     * 或既无内容语义、也没有任何正文结构的极短响应。
     */
    fun challenge(body: String) = body.contains("Checking your browser", true) ||
        body.contains("Just a moment", true) || body.contains("cf-mitigated", true) ||
        (body.contains("DDoS-Guard", true) && !body.contains("class=\"thumbs")) ||
        (body.contains("challenge-platform", true) && !hasPageContent(body))

    /** 正常内容页的证据：媒体元素、站点列表结构，或常见 CMS 的内容容器。 */
    private fun hasPageContent(body: String): Boolean =
        body.contains("</video") || body.contains("class=\"thumbs") ||
            CONTENT_MARKERS.any { body.contains(it, true) }

    private val CONTENT_MARKERS = listOf(
        "entry-title", "generate-columns", "post-thumbnail", "wp-content",
        "<main", "<article", "id=\"content", "class=\"content", "video-card",
    )

    /**
     * 429 的同址退避：站点对短时间内的连续请求会直接返回限流，等一拍再敲同一个地址通常就过，
     * 比立刻甩给镜像更稳（镜像往往是同一后端）。优先尊重 Retry-After（秒或 HTTP-date），
     * 上限 120 秒；没有头时等 [rateLimitBackoffMs]。测试把这个值压成 0。
     */
    internal var rateLimitBackoffMs = 1_000L
    internal fun retryWaitMs(retryAfter: String?): Long {
        val raw = retryAfter?.trim().orEmpty()
        if (raw.isEmpty()) return rateLimitBackoffMs
        val seconds = raw.toLongOrNull() ?: runCatching {
            // RFC 1123（HTTP-date）；SimpleDateFormat 在所有受支持的 API 级别都可用。
            val parsed = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .apply { isLenient = false }.parse(raw) ?: return@runCatching null
            ((parsed.time - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }.getOrNull() ?: return rateLimitBackoffMs
        // 站点偶尔给 3600 之类的长值；页面请求不能挂一小时，但也不该 15 秒就回去继续撞。
        return seconds.coerceIn(0, 120) * 1000
    }

    suspend fun load(url: String, site: SiteContext?, fetch: suspend (String) -> String): String {
        val parsed = url.toHttpUrlOrNull()
        val mirrors = if (site != null && parsed != null && site.isSite(parsed.host))
            (listOf(site.host) + site.mirrors).distinct().filter { it != parsed.host }.map {
                parsed.newBuilder().host(it).scheme("https").build().toString()
            } else emptyList()
        var firstError: Exception? = null
        for (target in listOf(url) + mirrors) {
            val host = target.toHttpUrlOrNull()?.host ?: target
            // 只有 429 允许在同一地址重试一次；验证页与连接错误维持“换下一个目标”的走法。
            for (attempt in 1..2) {
                try {
                    val body = fetch(target)
                    if (!challenge(body)) return body
                    if (firstError == null) firstError = IOException("站点要求浏览器验证，请稍后重试或调整网络")
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is GalleryResponseException && error.status == 429 && attempt == 1) {
                        val wait = retryWaitMs(error.retryAfter)
                        RateLimitLog.record(host, "HTTP 429，退避 ${wait}ms 后重试")
                        delay(wait)
                        continue
                    }
                    if (error is GalleryResponseException && error.status == 429)
                        RateLimitLog.record(host, "HTTP 429，重试后仍被拒")
                    val retryable = when (error) {
                        is GalleryResponseException -> error.status in setOf(403, 429) || error.status in 500..599
                        is IOException -> true
                        else -> false
                    }
                    if (!retryable && target == url) throw error
                    if (firstError == null) firstError = error
                    break
                }
            }
        }
        throw requireNotNull(firstError)
    }
}
