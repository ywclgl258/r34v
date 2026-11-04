package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.EhAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only live smoke test: non-H category, one displayed image, no archive/original downloads. */
@RunWith(AndroidJUnit4::class)
class LiveEhProbe {
    @org.junit.Before fun requireExplicitSelection() = requireLiveProbe(javaClass.name)
    @Test fun publicGalleryAndSingleHttpsPage() = runBlocking<Unit> {
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as R34Application
        val adapter = EhAdapter(SiteRegistry.EHENTAI, app.network)
        val report = mutableListOf<String>()
        try {
            val started = System.currentTimeMillis()
            val feed = adapter.feed(FeedRequest(FeedKind.CATEGORY, "256"))
            assertTrue(feed.videos.isNotEmpty())
            report.add("E-Hentai non-H list: ${feed.videos.size} cards, ${System.currentTimeMillis() - started} ms, next=${feed.nextCursor != null}")
            val detail = adapter.gallery(feed.videos.first().url)
            report.add("Gallery detail: ${detail.pageCount} pages, ${detail.pageLinks.size} page links, ${detail.groups.values.sumOf { it.size }} metadata links")
            val page = app.galleries.page(SiteRegistry.EHENTAI, detail.pageLinks.first().url)
            assertTrue(page.bitmap.width > 0 && page.bitmap.height > 0)
            assertTrue(page.image.imageUrl.startsWith("https://"))
            report.add("Displayed image: HTTPS, ${page.bitmap.width}x${page.bitmap.height}, cached=${page.cached}")
            page.bitmap.recycle()
            report.add("ExHentai authenticated live access: not tested (no real account session supplied)")
        } finally {
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            val dir = if (output == null) ins.targetContext.filesDir else File(output).apply { mkdirs() }
            File(dir, "eh-live.txt").writeText(report.joinToString("\n"))
        }
    }
}
