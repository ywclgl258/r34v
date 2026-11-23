@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Stream
import com.r34v.r34v.data.adapter.KvsAdapter
import com.r34v.r34v.data.adapter.SiteAdapter
import com.r34v.r34v.playback.LinkSpeed
import com.r34v.r34v.playback.MediaProbe
import com.r34v.r34v.playback.PlaybackDiagnostics
import com.r34v.r34v.playback.StreamSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okio.Buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

/**
 * 媒体测速与“网络诊断”的端到端判定，全部走本机假站点，不碰真实网络。
 *
 * 这条路径以前根本不存在：旧诊断只请求首页 HTML，所以永远显示成功，无法解释“缓冲很慢”。
 * 这里锁住的是：诊断真的会读媒体字节、真的会算出速度、并据此给出建议档位。
 */
@RunWith(AndroidJUnit4::class)
class MediaProbeTest {
    private lateinit var server: MockWebServer
    private lateinit var store: LibraryStore
    private lateinit var network: Network
    private lateinit var site: SiteContext
    private lateinit var previousSite: SiteContext
    private var previousAdapter: SiteAdapter? = null
    @Volatile private var mediaPath = "/media/720.mp4"
    private val playerClip by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("test-pattern.mp4").use { it.readBytes() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun adapterCache() = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        .get(AdapterRegistry) as MutableMap<String, SiteAdapter>

    private fun listing() = """
        <html><body><div class="thumbs">
          <div data-video-card-id="700001"><a class="th" href="/video/700001/neutral/"><span class="thumb_title">Neutral</span></a></div>
        </div><button class="page-of">Page 1 of 1</button></body></html>
    """.trimIndent()

    private fun detail() = """
        <html><head><link rel="canonical" href="$base/video/700001/neutral/"></head>
        <body><h1>Neutral</h1><script>flashvars = {
          video_id: '700001', video_title: 'Neutral',
          video_url: '$base/media/360.mp4', video_url_text: '360p',
          video_alt_url: '$base$mediaPath', video_alt_url_text: '720p'
        };</script></body></html>
    """.trimIndent()

    private var base = ""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val startupRange = "bytes=0-${MediaProbe.STARTUP_SAMPLE_BYTES - 1}"
                val probe = request.getHeader("Cache-Control") == "no-store"
                return when {
                    path.startsWith("/latest-updates") -> MockResponse().setBody(listing()).setHeader("Content-Type", "text/html")
                    path.startsWith("/video/700001") -> MockResponse().setBody(detail()).setHeader("Content-Type", "text/html")
                    path == "/media/short.mp4" -> MockResponse().setBody("small response").setHeader("Content-Type", "video/mp4")
                    path == "/media/movie.m3u8" -> if (request.getHeader("Range") == null) {
                        MockResponse().setBody("#EXTM3U\n#EXTINF:6,\nsegment.ts\n").setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    } else MockResponse().setResponseCode(416)
                    path == "/media/opaque" -> MockResponse().setBody("#EXTM3U\n" + "segment.ts\n".repeat(15_000))
                    path == "/media/startup-redirect.mp4" -> MockResponse().setResponseCode(302)
                        .setHeader("Location", "/media/startup-slow.mp4").setHeadersDelay(650, MILLISECONDS)
                    path == "/media/startup-slow.mp4" -> sampleResponse(MediaProbe.STARTUP_SAMPLE_BYTES.toInt())
                        .setHeadersDelay(650, MILLISECONDS).setBodyDelay(1_200, MILLISECONDS)
                    path == "/media/startup-held.mp4" -> sampleResponse(MediaProbe.STARTUP_SAMPLE_BYTES.toInt())
                        .throttleBody(32 * 1024, 2, SECONDS)
                    path.startsWith("/player/") -> when {
                        path.endsWith(".m3u8") -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                            .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n")
                        probe && path.endsWith("/failed.mp4") -> MockResponse().setResponseCode(503)
                        probe && (path.endsWith("/timed-out.mp4") || path.endsWith("/cancelled.mp4")) ->
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        probe && path.endsWith("/short.mp4") -> sampleResponse(12)
                        probe && request.getHeader("Range") == startupRange -> sampleResponse(MediaProbe.STARTUP_SAMPLE_BYTES.toInt())
                        else -> MockResponse().setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(playerClip))
                    }
                    // 故意回整整一兆，验证客户端在请求的字节上限停止。
                    path.startsWith("/media/") -> MockResponse().setBody(Buffer().write(ByteArray(1 shl 20)))
                        .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes").setResponseCode(206)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = LibraryStore(context, "media-probe-${System.nanoTime()}", "media-probe-legacy")
        network = Network(context, store)
        site = SiteRegistry.R34V.copy(base = base, host = server.hostName)
        previousSite = SiteRegistry.current
        previousAdapter = synchronized(AdapterRegistry) { adapterCache().put(site.id, KvsAdapter(site, network)) }
        SiteRegistry.current = site
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        SiteRegistry.current = previousSite
        synchronized(AdapterRegistry) {
            val cache = adapterCache()
            val original = previousAdapter
            if (original == null) cache.remove(site.id) else cache[site.id] = original
        }
    }

    @Test fun theProbeMeasuresRealBytesAndTimeInsteadOfTrustingThePageLoad() = runBlocking {
        val probe = MediaProbe(network)
        val limit = MediaProbe.MIN_TRUSTED_BYTES + 123
        val result = probe.measure(site, Stream("720p", "$base/media/720.mp4"), limit)
        assertEquals(206, result.status)
        assertEquals("bytes=0-${limit - 1}", server.takeRequest().getHeader("Range"))
        assertEquals(limit, result.bytes)
        assertTrue("必须算出正的速度", (result.trustedKbps ?: 0) > 0)
    }

    @Test fun theDiagnosisReportsAMediaSpeedAndASuggestedQuality() = runBlocking {
        val report = PlaybackDiagnostics(network, MediaProbe(network)).report(site, "720p")
        assertTrue("诊断必须包含媒体测速结果：$report", report.contains("kbps"))
        assertTrue("诊断必须给出建议档位：$report", report.contains("这条链路适合"))
    }

    @Test fun aShortResponseHasNoThroughputOrQualityRecommendation() = runBlocking {
        mediaPath = "/media/short.mp4"
        val report = PlaybackDiagnostics(network, MediaProbe(network)).report(site, "720p")
        assertTrue(report, report.contains("HTTP 200 · 14 B"))
        assertTrue(report, report.contains("样本不足"))
        assertFalse(report, report.contains("kbps"))
        assertFalse(report, report.contains("这条链路适合"))
    }

    @Test fun hlsDiagnosisChecksThePlaylistWithoutRequestingARangeOrRecommendingQuality() = runBlocking {
        mediaPath = "/media/movie.m3u8"
        val report = PlaybackDiagnostics(network, MediaProbe(network)).report(site, "720p")
        assertTrue(report, report.contains("已读取 HLS 播放列表"))
        assertFalse(report, report.contains("kbps"))
        assertFalse(report, report.contains("这条链路适合"))
    }

    @Test fun aLargeExtensionlessPlaylistDoesNotCountAsThroughput() = runBlocking {
        val result = MediaProbe(network).measure(site, Stream("720p", "$base/media/opaque"))
        assertTrue(result.bytes >= MediaProbe.MIN_TRUSTED_BYTES)
        assertTrue(result.playlist)
        assertNull(result.trustedKbps)
    }

    @Test fun aMeasuredSampleFlowsIntoTheStartingQualityDecision() = runBlocking {
        // 本机回环很快，所以偏好档应当被保留；反过来把预算压到 720p 之下时必须降档。
        val probe = MediaProbe(network)
        val measured = checkNotNull(probe.measure(site, Stream("720p", "$base/media/720.mp4")).trustedKbps)
        val streams = listOf(Stream("360p", "u"), Stream("720p", "u"))
        assertEquals("720p", StreamSelection.pick(streams, "720p", measured).label)
        assertEquals("360p", StreamSelection.pick(streams, "720p", 300).label)
    }

    @Test fun theStartupBudgetAndBaselineCanUseTheSameProbeWithoutAffectingDiagnostics() = runBlocking {
        val probe = MediaProbe(network)
        val stream = Stream("720p", "$base/media/720.mp4")
        probe.startupPolicy = MediaProbe.StartupPolicy.BUDGETED
        val budgeted = checkNotNull(probe.measureStartup(site, stream))
        assertEquals("bytes=0-131071", nextRequest().getHeader("Range"))
        assertEquals(128L * 1024, budgeted.bytes)
        assertTrue((budgeted.trustedKbps ?: 0) > 0)

        val full = probe.measure(site, stream)
        assertEquals("bytes=0-1048575", nextRequest().getHeader("Range"))
        assertEquals(1024L * 1024, full.bytes)

        probe.startupPolicy = MediaProbe.StartupPolicy.BASELINE
        val baseline = checkNotNull(probe.measureStartup(site, stream))
        assertEquals("bytes=0-1048575", nextRequest().getHeader("Range"))
        assertEquals(full.bytes, baseline.bytes)
    }

    @Test fun theStartupDeadlineSpansRedirectsHeadersAndBodyAndCancelsTheCall() = runBlocking {
        val cancelled = watchProbeCancellation(network)
        val started = SystemClock.elapsedRealtime()
        val result = MediaProbe(network).measureStartup(site, Stream("720p", "$base/media/startup-redirect.mp4"),
            MediaProbe.StartupPolicy.BUDGETED)
        val elapsed = SystemClock.elapsedRealtime() - started
        assertNull(result)
        assertTrue("Startup deadline took $elapsed ms", elapsed in 1_800L..2_700L)
        assertTrue("The timed-out HTTP call was still active", cancelled.await(1, SECONDS))
        assertEquals("/media/startup-redirect.mp4", nextRequest().path)
        assertEquals("/media/startup-slow.mp4", nextRequest().path)
        assertNull(server.takeRequest(150, MILLISECONDS))
    }

    @Test fun cancellingTheCallerWhileReadingTheBodyCancelsTheCall() = runBlocking {
        val readingBody = CountDownLatch(1)
        val cancelled = watchProbeCancellation(network, readingBody)
        val probe = async {
            MediaProbe(network).measureStartup(site, Stream("720p", "$base/media/startup-held.mp4"),
                MediaProbe.StartupPolicy.BUDGETED)
        }
        assertTrue("The fixture never entered the sample body", withContext(Dispatchers.IO) { readingBody.await(2, SECONDS) })
        probe.cancelAndJoin()
        assertTrue(probe.isCancelled)
        assertTrue("Caller cancellation did not reach the HTTP call", cancelled.await(1, SECONDS))
        assertEquals("/media/startup-held.mp4", nextRequest().path)
        assertNull(server.takeRequest(150, MILLISECONDS))
    }

    @Test fun anEstimateFromBeforeTheRouteChangedCannotSeedTheNewRoute() = runBlocking {
        val link = LinkSpeed(InstrumentationRegistry.getInstrumentation().targetContext, network::routeEpoch)
        val route = link.routeEpoch()
        val stream = Stream("720p", "$base/media/720.mp4")
        val sampled = checkNotNull(MediaProbe(network).measureStartup(site, stream, MediaProbe.StartupPolicy.BUDGETED)?.trustedKbps)
        network.invalidate()
        assertFalse(link.seed(sampled, route))
        assertEquals(0, link.estimateKbps())
    }

    @Test fun singleMp4AndPreferredHlsStartWithoutAProbe() {
        val low = Stream("360p", "$base/player/low.mp4")
        val single = Stream("720p", "$base/player/single.mp4")
        val hls = Stream("720p", "$base/player/movie.m3u8")
        for (streams in listOf(listOf(single), listOf(low, hls))) {
            withPlayer(streams) { _, _, _ ->
                val first = nextRequest()
                assertEquals(streams.last().url, first.requestUrl?.toString())
                assertFalse("Unexpected startup probe: $first", first.getHeader("Cache-Control") == "no-store")
            }
        }
    }

    @Test fun timeoutFailureAndShortSampleOpenTheUsersPreferredQuality() {
        val low = Stream("360p", "$base/player/low.mp4")
        for (name in listOf("timed-out", "failed", "short")) {
            val preferred = Stream("720p", "$base/player/$name.mp4")
            withPlayer(listOf(low, preferred)) { fixture, _, _ ->
                val probe = nextRequest()
                assertEquals(preferred.url, probe.requestUrl?.toString())
                assertEquals("bytes=0-131071", probe.getHeader("Range"))
                val playback = nextRequest()
                assertEquals(preferred.url, playback.requestUrl?.toString())
                assertFalse(playback.getHeader("Cache-Control") == "no-store")
                assertEquals("720p", fixture.app.library.string("quality", ""))
            }
        }
    }

    @Test fun leavingThePlayerCancelsAStalledProbeWithoutOpeningMedia() {
        val preferred = Stream("720p", "$base/player/cancelled.mp4")
        val low = Stream("360p", "$base/player/low.mp4")
        withPlayer(listOf(low, preferred), MediaProbe.StartupPolicy.BASELINE) { fixture, activity, cancelled ->
            val probe = nextRequest()
            assertEquals(preferred.url, probe.requestUrl?.toString())
            assertEquals("bytes=0-${MediaProbe.SAMPLE_BYTES - 1}", probe.getHeader("Range"))
            fixture.ins.runOnMainSync { activity.finish() }
            fixture.ins.waitForIdleSync()
            assertTrue("The stopped player left the probe active", cancelled.await(3, SECONDS))
            assertNull(server.takeRequest(250, MILLISECONDS))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withPlayer(streams: List<Stream>, policy: MediaProbe.StartupPolicy = MediaProbe.StartupPolicy.BUDGETED,
        checkPlayer: (ComposeFixture, PlayerActivity, CountDownLatch) -> Unit) {
        ComposeFixture().use { fixture ->
            // A previous player's terminal transfer callback must not repopulate the estimate mid-setup.
            fixture.ins.waitForIdleSync()
            val app = fixture.app
            val link = app.linkSpeed
            val speedFields = listOf("sampled", "seeded", "sampledAt", "seededAt").map { name ->
                LinkSpeed::class.java.getDeclaredField(name).apply { isAccessible = true }
            }
            val transfers = LinkSpeed::class.java.getDeclaredField("transfers").apply { isAccessible = true }
                .get(link) as MutableMap<Any, Any>
            fun clearEstimate() = synchronized(link) {
                transfers.clear()
                speedFields.take(2).forEach { it.setInt(link, 0) }
            }
            val originalPolicy = app.mediaProbe.startupPolicy
            val (route, speed) = synchronized(link) {
                link.routeEpoch() to speedFields.map { it.get(link) }
            }
            fixture.detailStreams = streams
            val cancelled = watchProbeCancellation(app.network)
            app.mediaProbe.startupPolicy = policy
            app.library.set("quality", "720p")
            app.library.set("stream_cache", false)
            app.library.set("seek_cache", false)
            app.library.set("auto_download", false)
            clearEstimate()
            fixture.ins.waitForIdleSync()
            clearEstimate()
            var activity: PlayerActivity? = null
            try {
                val player = fixture.launchPlayer()
                activity = player
                checkPlayer(fixture, player, cancelled)
            } finally {
                fixture.ins.runOnMainSync { activity?.finish() }
                fixture.ins.waitForIdleSync()
                while (server.takeRequest(0, MILLISECONDS) != null) Unit
                app.mediaProbe.startupPolicy = originalPolicy
                synchronized(link) {
                    if (link.routeEpoch() == route) speedFields.zip(speed).forEach { (field, value) -> field.set(link, value) }
                }
            }
        }
    }

    private fun watchProbeCancellation(target: Network, readingBody: CountDownLatch? = null): CountDownLatch {
        val cancelled = CountDownLatch(1)
        fun isProbe(call: Call) = call.request().header("Cache-Control") == "no-store"
        val client = target.client.newBuilder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                if (isProbe(call)) readingBody?.countDown()
            }

            override fun canceled(call: Call) {
                if (isProbe(call)) cancelled.countDown()
            }
        }).build()
        Network::class.java.getDeclaredField("current").apply { isAccessible = true }.set(target, client)
        return cancelled
    }

    private fun nextRequest(): RecordedRequest = checkNotNull(server.takeRequest(8, SECONDS)) { "Missing local HTTP request" }

    private fun sampleResponse(bytes: Int) = MockResponse().setResponseCode(206)
        .setHeader("Content-Type", "video/mp4").setBody(Buffer().write(ByteArray(bytes)))
}
