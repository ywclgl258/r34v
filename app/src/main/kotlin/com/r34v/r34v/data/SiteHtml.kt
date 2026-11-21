package com.r34v.r34v.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Retry only registered same-site mirrors; cancellation and ordinary page errors stay terminal. */
internal object SiteHtml {
    fun challenge(body: String) = body.contains("Checking your browser", true) ||
        body.contains("Just a moment", true) || body.contains("cf-mitigated", true) ||
        (body.contains("DDoS-Guard", true) && !body.contains("class=\"thumbs")) ||
        (body.contains("challenge-platform", true) && body.length < 120_000 && !body.contains("</video"))

    /**
     * 429 的同址退避：站点对短时间内的连续请求会直接返回限流，等一拍再敲同一个地址通常就过，
     * 比立刻甩给镜像更稳（镜像往往是同一后端）。优先尊重 Retry-After（秒），封顶 15 秒；
     * 没有头时等 [rateLimitBackoffMs]。测试把这个值压成 0。
     */
    internal var rateLimitBackoffMs = 1_000L
    internal fun retryWaitMs(retryAfter: String?): Long =
        retryAfter?.trim()?.toLongOrNull()?.coerceIn(0, 15)?.let { it * 1000 } ?: rateLimitBackoffMs

    suspend fun load(url: String, site: SiteContext?, fetch: suspend (String) -> String): String {
        val parsed = url.toHttpUrlOrNull()
        val mirrors = if (site != null && parsed != null && site.isSite(parsed.host))
            (listOf(site.host) + site.mirrors).distinct().filter { it != parsed.host }.map {
                parsed.newBuilder().host(it).scheme("https").build().toString()
            } else emptyList()
        var firstError: Exception? = null
        for (target in listOf(url) + mirrors) {
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
                        delay(retryWaitMs(error.retryAfter))
                        continue
                    }
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
