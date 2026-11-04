package com.r34v.r34v

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Explicitly selected read-only checks through the user's system proxy. No content screenshots. */
@RunWith(AndroidJUnit4::class)
class LiveSystemProxyProbe {
    @org.junit.Before fun requireExplicitSelection() = requireLiveProbe(javaClass.name)
    private fun probe(site: SiteContext) = runBlocking<Unit> {
        val ins = InstrumentationRegistry.getInstrumentation(); val name = "system-proxy-live-${site.id}"
        val store = LibraryStore(ins.targetContext, name, "$name-legacy"); val network = Network(ins.targetContext, store)
        val report = mutableListOf<String>(); var stage = "system proxy"
        try {
            val route = network.routeDescription(site.base); report.add(route); assertTrue(route, route.contains("127.0.0.1:7897"))
            val adapter: SiteAdapter = when (site.id) {
                "r34v" -> KvsAdapter(site, network); "hanime1" -> HanimeAdapter(site, network); "iwara" -> IwaraAdapter(site, network)
                "ehentai", "exhentai" -> EhAdapter(site, network); "nhentai" -> NhentaiAdapter(network); else -> HitomiAdapter(network)
            }
            if (site.id == "exhentai") {
                try { adapter.feed(FeedRequest()); fail("ExHentai must require the user's session") } catch (_: GalleryAccessException) { report.add("Account permission required; authenticated access not tested") }
                return@runBlocking
            }
            stage = "list"; val start = System.currentTimeMillis()
            val feed = if (site.id == "iwara") IwaraProtocol.feed(network.json("${site.apiBase}/videos?rating=general&limit=3"), site, FeedRequest())
                else adapter.feed(if (site.id == "ehentai") FeedRequest(FeedKind.CATEGORY, "256") else adapter.homeRequest())
            assertTrue("Empty live list", feed.videos.isNotEmpty()); report.add("list: ${feed.videos.size} cards, ${System.currentTimeMillis()-start} ms")
            stage = "detail"
            val entry = feed.videos.first { it.type != "站外视频" }
            suspend fun bytes(url: String, referer: String, full: Boolean = false): ByteArray = network.mediaClient.readResponse(
                Request.Builder().url(url).tag(SiteContext::class.java, site).header("Referer", referer.substringBefore('#')).header("Cache-Control", "no-store")
                    .apply { if (!full) header("Range", "bytes=0-65535") }.build()
            ) { response ->
                check(response.isSuccessful) { "media HTTP ${response.code}" }
                val maximum = if (full) 24 * 1024 * 1024 else 65536
                response.body!!.byteStream().use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (out.size() < maximum) { val n = input.read(buffer, 0, minOf(buffer.size, maximum - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                    out.toByteArray()
                }.also { report.add("media: HTTP ${response.code}, ${it.size} bytes, ${response.header("Content-Type").orEmpty()}") }
            }
            if (site.mediaKind == MediaKind.GALLERY) {
                val detail = adapter.gallery(entry.url); report.add("detail: ${detail.pageCount} pages")
                stage = "image"; val image = adapter.galleryImage(detail.pageLinks.first().url)
                val body = bytes(image.imageUrl, image.pageUrl, true)
                val bitmap = BitmapFactory.decodeByteArray(body, 0, body.size, BitmapFactory.Options().apply { inSampleSize = 4 })
                assertNotNull("Image failed to decode", bitmap); report.add("image decoded: ${bitmap.width}x${bitmap.height}, sample=4"); bitmap.recycle()
            } else {
                val detail = adapter.detail(entry.url); assertTrue(detail.streams.isNotEmpty()); report.add("detail: ${detail.streams.map { it.label }}")
                stage = "cover"; val cover = bytes(detail.video.thumb, detail.video.url)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(cover, 0, cover.size, bounds)
                assertTrue("Invalid cover image", bounds.outWidth > 0)
                stage = "stream"; val stream = detail.streams.filter { it.height > 0 }.minByOrNull { it.height } ?: detail.streams.first()
                val data = bytes(stream.url, site.mediaReferer)
                assertTrue("Empty video response", data.size > 32)
                val head = data.take(256).toByteArray().toString(Charsets.ISO_8859_1)
                assertTrue("Media response is not MP4/HLS", head.contains("ftyp") || head.startsWith("#EXTM3U"))
            }
            assertTrue("A request bypassed the system proxy", network.lastPeer.contains("系统代理")); report.add(network.lastPeer)
        } catch (error: Exception) { report.add("FAILED at $stage: ${error.javaClass.simpleName}: ${error.message}"); throw error }
        finally {
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } } ?: ins.targetContext.filesDir
            File(dir, "${site.id}-proxy.txt").writeText(report.joinToString("\n"))
            ins.targetContext.deleteSharedPreferences(name); ins.targetContext.deleteSharedPreferences("$name-legacy")
        }
    }
    @Test fun rule34Video() = probe(SiteRegistry.R34V)
    @Test fun hanime1() = probe(SiteRegistry.HANIME1)
    @Test fun iwara() = probe(SiteRegistry.IWARA)
    @Test fun ehentai() = probe(SiteRegistry.EHENTAI)
    @Test fun exhentaiPermissionGate() = probe(SiteRegistry.EXHENTAI)
    @Test fun nhentai() = probe(SiteRegistry.NHENTAI)
    @Test fun hitomi() = probe(SiteRegistry.HITOMI)
}
