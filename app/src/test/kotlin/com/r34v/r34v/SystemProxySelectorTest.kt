package com.r34v.r34v

import com.r34v.r34v.data.SystemProxySelector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit

class SystemProxySelectorTest {
    private val url = URI("https://site.example/path")
    private fun selector(routes: List<Proxy>) = object : ProxySelector() {
        override fun select(uri: URI) = routes
        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {}
    }
    @Test fun noSystemProxyMeansDirect() { assertEquals(listOf(Proxy.NO_PROXY), SystemProxySelector { null }.select(url)) }
    @Test fun keepsTheSystemPacExclusionDecision() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7897))
        assertEquals(listOf(Proxy.NO_PROXY), SystemProxySelector { selector(listOf(Proxy.NO_PROXY, proxy)) }.select(url))
    }
    @Test fun proxyRoutesNeverIncludeADirectFallback() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7897))
        assertEquals(listOf(proxy), SystemProxySelector { selector(listOf(proxy, Proxy.NO_PROXY)) }.select(url))
    }
    @Test fun selectorFailureDoesNotBecomeDirect() {
        val broken = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = error("system error")
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {}
        }
        assertThrows(IOException::class.java) { SystemProxySelector { broken }.select(url) }
    }
    @Test fun proxyChangeUsesTheNewRouteAfterIdleConnectionsAreInvalidated() {
        val first = MockWebServer(); val second = MockWebServer(); first.start(); second.start()
        var current: ProxySelector = selector(listOf(first.toProxyAddress()))
        val client = OkHttpClient.Builder().proxySelector(SystemProxySelector { current }).build()
        try {
            first.enqueue(MockResponse().setBody("first")); second.enqueue(MockResponse().setBody("second"))
            val request = Request.Builder().url("http://catalogue.invalid/feed").build()
            client.newCall(request).execute().use { assertEquals("first", it.body!!.string()) }
            current = selector(listOf(second.toProxyAddress()))
            // Matches Application's system PROXY_CHANGE receiver.
            client.connectionPool.evictAll()
            client.newCall(request).execute().use { assertEquals("second", it.body!!.string()) }
            assertEquals(1, first.requestCount); assertEquals(1, second.requestCount)
        } finally { first.shutdown(); second.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun failedSystemProxyCannotReachEvenAReachableDirectOrigin() {
        val target = MockWebServer(); val proxy = MockWebServer(); target.start(); proxy.start()
        val closed = proxy.toProxyAddress(); proxy.shutdown()
        val client = OkHttpClient.Builder().proxySelector(SystemProxySelector { selector(listOf(closed, Proxy.NO_PROXY)) })
            .connectTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()
        try {
            target.enqueue(MockResponse().setBody("must not be read"))
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(target.url("/feed")).build()).execute().close() }
            assertEquals(0, target.requestCount)
        } finally { target.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun redirectsReevaluateSystemDecisionForTheNewUri() {
        val gateway = MockWebServer(); val target = MockWebServer(); gateway.start(); target.start()
        val seen = mutableListOf<String>()
        val policy = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { seen.add(uri.host); return listOf(if (uri.host == "first.invalid") gateway.toProxyAddress() else Proxy.NO_PROXY) }
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {}
        }
        val client = OkHttpClient.Builder().proxySelector(SystemProxySelector { policy }).build()
        try {
            gateway.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/next")))
            target.enqueue(MockResponse().setBody("redirected"))
            client.newCall(Request.Builder().url("http://first.invalid/").build()).execute().use { assertEquals("redirected", it.body!!.string()) }
            assertTrue(seen.contains("first.invalid")); assertTrue(seen.contains(target.hostName))
        } finally { gateway.shutdown(); target.shutdown(); client.connectionPool.evictAll() }
    }
}
