@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.playback.MediaProbe
import com.r34v.r34v.playback.PlaybackDiagnostics
import com.r34v.r34v.playback.StreamSelection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okio.Buffer

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
          video_alt_url: '$base/media/720.mp4', video_alt_url_text: '720p'
        };</script></body></html>
    """.trimIndent()

    private var base = ""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/latest-updates") -> MockResponse().setBody(listing()).setHeader("Content-Type", "text/html")
                    path.startsWith("/video/700001") -> MockResponse().setBody(detail()).setHeader("Content-Type", "text/html")
                    // 一兆媒体，按 Range 回；客户端只读前一小段就能算出速度。
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
        SiteRegistry.current = site
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        SiteRegistry.current = SiteRegistry.R34V
    }

    @Test fun theProbeMeasuresRealBytesAndTimeInsteadOfTrustingThePageLoad() = runBlocking {
        val probe = MediaProbe(network)
        val result = probe.measure(site, com.r34v.r34v.data.Stream("720p", "$base/media/720.mp4"))
        assertEquals(206, result.status)
        assertTrue("必须真的读到字节", result.bytes > 0)
        assertTrue("必须算出正的速度", result.kbps > 0)
    }

    @Test fun theDiagnosisReportsAMediaSpeedAndASuggestedQuality() = runBlocking {
        val report = PlaybackDiagnostics(network, MediaProbe(network)).report(site, "720p")
        assertTrue("诊断必须包含媒体测速结果：$report", report.contains("kbps"))
        assertTrue("诊断必须给出建议档位：$report", report.contains("这条链路适合"))
    }

    @Test fun aMeasuredSampleFlowsIntoTheStartingQualityDecision() = runBlocking {
        // 本机回环很快，所以偏好档应当被保留；反过来把预算压到 720p 之下时必须降档。
        val probe = MediaProbe(network)
        val measured = probe.measure(site, com.r34v.r34v.data.Stream("720p", "$base/media/720.mp4")).kbps
        val streams = listOf(com.r34v.r34v.data.Stream("360p", "u"), com.r34v.r34v.data.Stream("720p", "u"))
        assertEquals("720p", StreamSelection.pick(streams, "720p", measured).label)
        assertEquals("360p", StreamSelection.pick(streams, "720p", 300).label)
    }
}
