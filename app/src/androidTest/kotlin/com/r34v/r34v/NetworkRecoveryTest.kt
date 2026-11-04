package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NetworkRecoveryTest {
    @Test fun ioFailureRemainsVisibleUntilTheUserRetries() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context,"network-retry-${System.nanoTime()}","no_legacy_retry")
        val network = Network(context,store)
        val calls = AtomicInteger()
        val client = network.client.newBuilder().addInterceptor { chain ->
            if(calls.incrementAndGet()==1) throw SocketTimeoutException("stalled response")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<div class='thumbs'></div>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        Network::class.java.getDeclaredField("current").apply { isAccessible=true; set(network,client) }
        try {
            try { network.html("https://rule34video.com/latest-updates/"); fail("Automatic route retry must be removed") } catch (_: SocketTimeoutException) { assertEquals(1,calls.get()) }
            assertTrue(network.html("https://rule34video.com/latest-updates/").contains("thumbs")); assertEquals(2,calls.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun legacyOverridesAreRetiredAndEveryClientUsesSystemRouting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val name = "retired-network-${System.nanoTime()}"
        context.getSharedPreferences(name, 0).edit().putString("settings_v1_player_proxy", "http://bad.invalid:1234")
            .putBoolean("settings_v1_direct_ip", true).putString("r34v:ip_pool", "[\"192.0.2.1\"]").putString("sentinel", "keep").commit()
        try {
            val store = LibraryStore(context, name, "$name-legacy"); val network = Network(context, store)
            assertFalse(store.prefs.contains("settings_v1_player_proxy")); assertFalse(store.prefs.contains("r34v:ip_pool")); assertEquals("keep", store.prefs.getString("sentinel", null))
            assertSame(Dns.SYSTEM, network.client.dns); assertSame(Dns.SYSTEM, network.mediaClient.dns)
            assertTrue(network.client.proxySelector is SystemProxySelector); assertSame(network.client.proxySelector, network.mediaClient.proxySelector)
            assertNull(network.client.proxy); assertNull(network.mediaClient.proxy)
        } finally { context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-legacy") }
    }
}
