package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveSystemNetworkProbe {
    @Test fun freshRequestUsesTheSystemRoute() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context, "live_direct_probe", "no_legacy_live_probe")
        store.prefs.edit().clear().commit()
        val report = Network(context, store).diagnose()
        File(context.filesDir, "system-network-probe.txt").writeText(report)
        assertTrue(report, report.contains("实时请求成功") && report.contains("系统"))
    }
    @Test fun profileColdWarmPageDetailAndThumbnail() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context, "live_direct_profile", "no_legacy_profile")
        store.prefs.edit().clear().commit()
        val network = Network(context, store)
        val rows = mutableListOf<String>()
        val base = network.client.newBuilder().cache(null).eventListenerFactory {
            val start = System.nanoTime()
            fun ms() = (System.nanoTime() - start) / 1_000_000
            object : okhttp3.EventListener() {
                override fun dnsStart(call: okhttp3.Call, domainName: String) { synchronized(rows) { rows.add("${ms()}ms DNS start $domainName") } }
                override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { synchronized(rows) { rows.add("${ms()}ms DNS end ${inetAddressList.joinToString { it.hostAddress.orEmpty() }}") } }
                override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { synchronized(rows) { rows.add("${ms()}ms TCP ${inetSocketAddress.address?.hostAddress} ${proxy.type()}") } }
                override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { synchronized(rows) { rows.add("${ms()}ms TLS ready") } }
                override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { synchronized(rows) { rows.add("${ms()}ms HTTP ${response.code}") } }
            }
        }.build()
        fun fetch(label: String, url: String): ByteArray {
            rows.add("-- $label --")
            val started = System.nanoTime()
            return base.newCall(okhttp3.Request.Builder().url(url).cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()).execute().use { response ->
                check(response.isSuccessful) { "$label HTTP ${response.code}" }
                val bytes = response.body!!.bytes()
                rows.add("${(System.nanoTime()-started)/1_000_000}ms complete ${bytes.size} bytes")
                bytes
            }
        }
        try {
            val listing = fetch("cold page", "${com.r34v.r34v.data.Site.BASE}/latest-updates/")
            fetch("warm page", "${com.r34v.r34v.data.Site.BASE}/most-popular/")
            val video = com.r34v.r34v.data.HtmlParser.feed(listing.toString(Charsets.UTF_8), com.r34v.r34v.data.FeedRequest(), com.r34v.r34v.data.SiteRegistry.current).videos.first()
            fetch("detail", video.url)
            fetch("thumbnail", video.thumb)
        } finally { File(context.filesDir, "system-network-profile.txt").writeText(rows.joinToString("\n")) }
    }

}
