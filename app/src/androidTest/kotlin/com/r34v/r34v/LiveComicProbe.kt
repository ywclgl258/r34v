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
import java.io.File

/** Read-only public protocol smoke tests. Images are decoded, never displayed or exported. */
@RunWith(AndroidJUnit4::class)
class LiveComicProbe {
    @org.junit.Before fun requireExplicitSelection() = requireLiveProbe(javaClass.name)
    private fun probe(site: SiteContext, adapter: (Network) -> SiteAdapter) = runBlocking<Unit> {
        val ins = InstrumentationRegistry.getInstrumentation(); val app = ins.targetContext.applicationContext as R34Application
        val network = app.network; val source = adapter(network); val report = mutableListOf<String>()
        try {
            val start = System.currentTimeMillis(); val feed = source.feed(FeedRequest())
            assertTrue(feed.videos.isNotEmpty()); report.add("${site.name}: public list ${feed.videos.size} cards, ${System.currentTimeMillis()-start} ms, next=${feed.hasNext}")
            val detail = source.gallery(feed.videos.first().url); assertTrue(detail.pageCount > 0)
            report.add("Gallery detail: ${detail.pageCount} pages, ${detail.groups.values.sumOf { it.size }} metadata links")
            if (site.id == "hitomi") {
                val found = source.feed(FeedRequest(FeedKind.SEARCH, "city")); assertTrue(found.videos.isNotEmpty())
                report.add("Hitomi B-tree keyword search: ${found.videos.size} cards")
            }
            val image = source.galleryImage(detail.pageLinks.first().url)
            val bytes = network.client.readResponse(Request.Builder().url(image.imageUrl).tag(SiteContext::class.java, site).header("Referer", image.pageUrl.substringBefore('#')).build()) { response ->
                check(response.isSuccessful) { "Live image HTTP ${response.code}" }; response.body!!.bytes()
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            assertNotNull(bitmap); report.add("Single HTTPS display image decoded: ${bitmap.width}x${bitmap.height}, ${bytes.size} bytes, sample=4"); bitmap.recycle()
        } finally {
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } } ?: ins.targetContext.filesDir
            File(dir, "${site.id}-live.txt").writeText(report.joinToString("\n"))
        }
    }
    @Test fun nhentaiPublicV2() = probe(SiteRegistry.NHENTAI, ::NhentaiAdapter)
    @Test fun hitomiPublicIndexAndWebp() = probe(SiteRegistry.HITOMI, ::HitomiAdapter)
}
