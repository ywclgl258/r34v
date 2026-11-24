package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.IwaraAdapter
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IwaraSessionNetworkTest {
    @Test fun subscriptionRequestIsCanceledOnLogoutAndNextAccountUsesItsOwnToken() = runBlocking {
        val started = CompletableDeferred<Pair<Request, Call>>()
        val nextRequest = CompletableDeferred<Request>()
        val releaseFirst = CountDownLatch(1)
        val requestCount = AtomicInteger()
        withNetwork(Interceptor { chain ->
            val request = chain.request()
            val index = requestCount.incrementAndGet()
            if (index == 1) {
                started.complete(request to chain.call())
                check(releaseFirst.await(10, TimeUnit.SECONDS)) { "First fixture response was not released" }
            } else nextRequest.complete(request)
            response(request, 200, """{"page":0,"count":1,"limit":20,"results":[{"id":"account-$index","title":"Fixture $index"}]}""")
        }) { network, adapter, store ->
            network.saveIwaraSession("first fixture", "fixture-a", network.iwaraSessionGeneration.value)
            val firstFeed = async { runCatching { adapter.feed(FeedRequest(FeedKind.SUBSCRIPTIONS)) } }
            try {
                val (request, call) = withTimeout(5_000) { started.await() }
                assertEquals("Bearer fixture-a", request.header("Authorization"))
                assertTrue(request.cacheControl.noStore)
                assertTrue(request.cacheControl.noCache)
                network.clearAuthToken(SiteRegistry.IWARA.id)
                assertTrue(withTimeout(5_000) { firstFeed.await() }.exceptionOrNull() is CancellationException)
                assertTrue(call.isCanceled())
                assertNull(network.authToken(SiteRegistry.IWARA.id))
                assertEquals("", store.username(SiteRegistry.IWARA.id))

                network.saveIwaraSession("second fixture", "fixture-b", network.iwaraSessionGeneration.value)
                val next = withTimeout(5_000) { adapter.feed(FeedRequest(FeedKind.SUBSCRIPTIONS)) }
                assertEquals(listOf("account-2"), next.videos.map { it.id })
                assertEquals("Bearer fixture-b", withTimeout(5_000) { nextRequest.await() }.header("Authorization"))
            } finally { releaseFirst.countDown() }
        }
    }

    @Test fun plainSubscriptionApiIsAlsoBoundToTheSessionGeneration() = runBlocking {
        val requests = AtomicInteger()
        withNetwork(Interceptor { chain ->
            requests.incrementAndGet()
            response(chain.request(), 200, "{}")
        }) { network, _, store ->
            network.saveIwaraSession("fixture", "fixture", network.iwaraSessionGeneration.value)
            val staleGeneration = network.iwaraSessionGeneration.value
            val url = "${SiteRegistry.IWARA.apiBase}/subscriptions/videos?page=0"
            network.json(url)
            assertEquals(1, requests.get())
            network.session.clear(SiteRegistry.IWARA)
            assertTrue(network.iwaraSessionGeneration.value > staleGeneration)
            assertNull(network.authToken(SiteRegistry.IWARA.id))
            assertNull(store.authToken(SiteRegistry.IWARA.id))
            assertTrue(runCatching { network.iwaraSubscriptionJson(url, staleGeneration) }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, requests.get())
            network.saveIwaraSession("next fixture", "fixture-next", network.iwaraSessionGeneration.value)
            assertTrue(runCatching { network.iwaraSubscriptionJson(url, staleGeneration) }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, requests.get())
        }
    }

    @Test fun rateLimitedLoginSendsExactlyOnePost() = runBlocking {
        val requests = AtomicInteger()
        withNetwork(Interceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/auth/login", request.url.encodedPath)
            if (requests.incrementAndGet() == 1) response(request, 429, "{}", retryAfter = "0")
            else response(request, 200, """{"token":"fixture-retry"}""")
        }) { network, adapter, _ ->
            val before = network.iwaraSessionGeneration.value
            val result = runCatching { adapter.login(network, "fixture", "sample") }
            assertEquals("登录失败：HTTP 429", result.exceptionOrNull()?.message)
            assertEquals(1, requests.get())
            assertEquals(before, network.iwaraSessionGeneration.value)
            assertNull(network.authToken(SiteRegistry.IWARA.id))
        }
    }

    @Test fun completedLoginFromPreviousGenerationCannotOverwriteNewSession() = runBlocking {
        val withPrevious = CompletableDeferred<Unit>()
        val releasePrevious = CountDownLatch(1)
        withNetwork(Interceptor { chain ->
            withPrevious.complete(Unit)
            check(releasePrevious.await(10, TimeUnit.SECONDS)) { "Login fixture response was not released" }
            response(chain.request(), 200, """{"token":"fixture-old"}""")
        }) { network, adapter, store ->
            val pending = async { runCatching { adapter.login(network, "old fixture", "sample") } }
            try {
                withTimeout(5_000) { withPrevious.await() }
                network.clearAuthToken(SiteRegistry.IWARA.id)
                network.saveIwaraSession("new fixture", "fixture-new", network.iwaraSessionGeneration.value)
            } finally { releasePrevious.countDown() }
            assertTrue(withTimeout(5_000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals("fixture-new", network.authToken(SiteRegistry.IWARA.id))
            assertEquals("new fixture", store.username(SiteRegistry.IWARA.id))
        }
    }

    private suspend fun withNetwork(
        interceptor: Interceptor,
        test: suspend CoroutineScope.(Network, IwaraAdapter, LibraryStore) -> Unit,
    ) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "iwara-session-fixture-${System.nanoTime()}"
        val legacyName = "$name-legacy"
        val store = LibraryStore(context, name, legacyName)
        val network = Network(context, store)
        val client = network.client.newBuilder().addInterceptor(interceptor).build()
        Network::class.java.getDeclaredField("current").apply { isAccessible = true }.set(network, client)
        try { test(network, IwaraAdapter(SiteRegistry.IWARA, network), store) }
        finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            context.deleteSharedPreferences(name); context.deleteSharedPreferences(legacyName)
        }
    }

    private fun response(request: Request, code: Int, json: String, retryAfter: String? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Too many requests")
        .apply { retryAfter?.let { header("Retry-After", it) } }
        .body(json.toResponseBody("application/json".toMediaType())).build()
}
