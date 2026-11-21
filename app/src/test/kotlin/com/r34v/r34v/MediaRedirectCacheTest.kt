package com.r34v.r34v

import com.r34v.r34v.data.MediaRedirectCache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class MediaRedirectCacheTest {
    /** 实现只在跨主机（站点主域 → CDN）时缓存；测试里用 DNS 覆盖伪造一个真正的 CDN 主机名。 */
    private fun clientOf(cache: MediaRedirectCache) = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(host: String) =
                if (host == "cdn.example") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(host)
        })
        .addInterceptor(cache.interceptor)
        .addNetworkInterceptor(cache.networkInterceptor)
        .build()

    private fun body(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

    @Test fun secondRequestGoesStraightToTheResolvedCdn() {
        val origin = MockWebServer(); val cdn = MockWebServer()
        val cdnUrl = "http://cdn.example:${cdn.port}"
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$cdnUrl/media"))
        cdn.enqueue(MockResponse().setResponseCode(200).setBody("a"))
        cdn.enqueue(MockResponse().setResponseCode(200).setBody("b"))
        val client = clientOf(MediaRedirectCache(eligible = { true }))
        val url = origin.url("/get-file/x")
        assertEquals("a", body(client, url.toString()))
        assertEquals("b", body(client, url.toString()))
        // 第二次必须直达 CDN，主域只被敲一次。
        assertEquals(1, origin.requestCount)
        assertEquals(2, cdn.requestCount)
        origin.shutdown(); cdn.shutdown()
    }

    @Test fun cdnRejectionInvalidatesAndReResolvesWithinTheSameCall() {
        val origin = MockWebServer(); val cdn = MockWebServer()
        val cdnUrl = "http://cdn.example:${cdn.port}"
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$cdnUrl/media"))
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$cdnUrl/media"))
        cdn.enqueue(MockResponse().setResponseCode(200).setBody("a"))
        cdn.enqueue(MockResponse().setResponseCode(403))
        cdn.enqueue(MockResponse().setResponseCode(200).setBody("fresh"))
        cdn.enqueue(MockResponse().setResponseCode(200).setBody("direct"))
        val client = clientOf(MediaRedirectCache(eligible = { true }))
        val url = origin.url("/get-file/x")
        assertEquals("a", body(client, url.toString()))
        // 旧令牌被 CDN 拒绝：这次调用内部作废缓存、回站点重解析，最终仍给出 200。
        assertEquals("fresh", body(client, url.toString()))
        assertEquals("direct", body(client, url.toString()))
        assertEquals(2, origin.requestCount)
        assertEquals(4, cdn.requestCount)
        origin.shutdown(); cdn.shutdown()
    }

    @Test fun nonRedirectingMediaNeverEntersTheCache() {
        val origin = MockWebServer()
        origin.enqueue(MockResponse().setResponseCode(200).setBody("1"))
        origin.enqueue(MockResponse().setResponseCode(200).setBody("2"))
        val client = clientOf(MediaRedirectCache(eligible = { true }))
        val url = origin.url("/media/x.mp4")
        assertEquals("1", body(client, url.toString()))
        assertEquals("2", body(client, url.toString()))
        assertEquals(2, origin.requestCount)
        origin.shutdown()
    }
}
