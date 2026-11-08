package com.r34v.r34v.data

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Retry only registered same-site mirrors; cancellation and ordinary page errors stay terminal. */
internal object SiteHtml {
    fun challenge(body: String) = body.contains("Checking your browser", true) ||
        body.contains("Just a moment", true) || body.contains("cf-mitigated", true) ||
        (body.contains("DDoS-Guard", true) && !body.contains("class=\"thumbs")) ||
        (body.contains("challenge-platform", true) && body.length < 120_000 && !body.contains("</video"))

    suspend fun load(url: String, site: SiteContext?, fetch: suspend (String) -> String): String {
        val parsed = url.toHttpUrlOrNull()
        val mirrors = if (site != null && parsed != null && site.isSite(parsed.host))
            (listOf(site.host) + site.mirrors).distinct().filter { it != parsed.host }.map {
                parsed.newBuilder().host(it).scheme("https").build().toString()
            } else emptyList()
        var firstError: Exception? = null
        for (target in listOf(url) + mirrors) {
            try {
                val body = fetch(target)
                if (!challenge(body)) return body
                if (firstError == null) firstError = IOException("站点要求浏览器验证，请稍后重试或调整网络")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val retryable = when (error) {
                    is GalleryResponseException -> error.status in setOf(403, 429) || error.status in 500..599
                    is IOException -> true
                    else -> false
                }
                if (!retryable && target == url) throw error
                if (firstError == null) firstError = error
            }
        }
        throw requireNotNull(firstError)
    }
}
