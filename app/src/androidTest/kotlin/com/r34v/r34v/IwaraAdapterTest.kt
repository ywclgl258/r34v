package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.IwaraAdapter
import com.r34v.r34v.data.adapter.IwaraSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IwaraAdapterTest {
    private fun fixture(action: suspend (IwaraAdapter, IwaraFixtures) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "iwara-api-test-${System.nanoTime()}"
        val store = LibraryStore(context, name, "$name-legacy")
        val network = Network(context, store)
        val server = IwaraFixtures()
        val client = network.client.newBuilder().addInterceptor { server.respond(it.request()) }.build()
        Network::class.java.getDeclaredField("current").apply { isAccessible = true }.set(network, client)
        try { action(IwaraAdapter(SiteRegistry.IWARA, network), server) }
        finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-legacy")
        }
    }

    @Test fun publicApiPaginationProfileAndSourceHeaders() = fixture { adapter, server ->
        assertEquals(2, adapter.feed(FeedRequest(page = 2)).current)
        adapter.feed(adapter.searchRequest("City walk & MMD", FeedKind.SEARCH))
        adapter.feed(FeedRequest(FeedKind.MODEL, "sample_author"))
        val detail = adapter.detail("https://www.iwara.tv/video/IwaraSample01/city-walk")
        assertEquals("720p 原画", detail.streams.single().label)
        assertTrue(detail.downloads.single().url.endsWith("?download=1"))
        assertTrue(server.requests.any { it.url.encodedPath == "/search" && it.url.queryParameter("type") == "videos" && it.url.queryParameter("query") == "City walk & MMD" })
        assertTrue(server.requests.any { it.url.queryParameter("user") == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" })
        for (request in server.requests) {
            assertEquals("https://www.iwara.tv/", request.header("Referer"))
            assertEquals("https://www.iwara.tv", request.header("Origin"))
            assertEquals("www.iwara.tv", request.header("x-site"))
            assertEquals("R34V/Android", request.header("User-Agent"))
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        }
        val file = server.requests.first { it.url.encodedPath.startsWith("/file/") }
        assertEquals(IwaraSignature.headers(file.url.toString(), IwaraSignature.DEFAULT_SALT)["X-Version"], file.header("X-Version"))
    }

    @Test fun refreshesFrontendSaltOnlyAfterOriginalIsMissing() = fixture { adapter, server ->
        server.refreshSalt = "newSaltAbcdef0123456789"
        val url = "https://www.iwara.tv/video/IwaraSample01/city-walk"
        assertEquals("720p 原画", adapter.detail(url).streams.single().label)
        assertEquals(2, server.requests.count { it.url.encodedPath.startsWith("/file/") })
        assertEquals(1, server.requests.count { it.url.encodedPath.endsWith(".js") })
        assertEquals("720p 原画", adapter.detail(url).streams.single().label)
        assertEquals(1, server.requests.count { it.url.encodedPath.endsWith(".js") })
    }

    @Test fun failedResignKeepsTheFirstPlayableSource() = fixture { adapter, server ->
        server.refreshSalt = "newSaltAbcdef0123456789"; server.failRefresh = true
        val detail = adapter.detail("https://www.iwara.tv/video/IwaraSample01/city-walk")
        assertEquals("360p", detail.streams.single().label)
    }
}
