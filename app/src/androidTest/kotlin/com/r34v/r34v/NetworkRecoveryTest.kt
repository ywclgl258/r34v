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
    private fun fixture(proxy: Boolean = false): Pair<Network, AtomicInteger> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context,"network_retry_fixture","no_legacy_retry")
        store.prefs.edit().clear().commit(); store.set("direct_ip",true)
        if(proxy) store.set("player_proxy","http://127.0.0.1:65530")
        val network = Network(context,store)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if(calls.incrementAndGet()==1) throw SocketTimeoutException("stalled response")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<div class='thumbs'></div>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        Network::class.java.getDeclaredField("current").apply { isAccessible=true; set(network,client) }
        return network to calls
    }
    @Test fun safeSiteReadCanRecoverAfterAnIoFailure() = runBlocking<Unit> {
        val (network,calls) = fixture()
        assertTrue(network.html("${Site.BASE}/latest-updates/").contains("thumbs")); assertEquals(2,calls.get())
    }
    @Test fun configuredProxyIsNotBypassedOnFailure() = runBlocking<Unit> {
        val (network,calls) = fixture(proxy=true)
        try { network.html("${Site.BASE}/latest-updates/"); fail("Proxy failure must remain visible") }
        catch (_: SocketTimeoutException) { assertEquals(1,calls.get()) }
    }
}
