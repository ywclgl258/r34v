package com.r34v.r34v

import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.GalleryResponseException
import com.r34v.r34v.data.MediaKind
import com.r34v.r34v.data.SiteCategory
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.Sort
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.incomingLink
import com.r34v.r34v.data.adapter.JmcomicMirrorRouter
import com.r34v.r34v.data.adapter.JmcomicProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JmcomicContractTest {
    private val site = SiteContext("jmcomic", "禁漫天堂", "https://jmcomic-zzz.one", "jmcomic-zzz.one",
        mediaKind = MediaKind.GALLERY, category = SiteCategory.COMIC)
    private val mirror = "https://18comic.ink"
    private val cdn = "https://cdn-msp2.18comic.ink"

    @Test fun searchPreservesQueryAndCatalogUsesPublishedPagination() {
        val query = "速写 & city + café"
        val request = FeedRequest(FeedKind.SEARCH, query, Sort.RELEVANCE, page = 3)
        val url = JmcomicProtocol.resolve(site, request).toHttpUrl()
        assertEquals("/search/photos", url.encodedPath)
        assertEquals(query, url.queryParameter("search_query"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals(request, JmcomicProtocol.linkRequest(url.newBuilder().host("18comic.ink").build().toString()))
        assertEquals("${site.base}/albums?o=mr", JmcomicProtocol.resolve(site, FeedRequest()))
        assertEquals("${site.base}/albums?o=mr&page=2", JmcomicProtocol.resolve(site, FeedRequest(page = 2)))
    }

    @Test fun catalogueRequiresWorkCardsAndUsesStableAlbumIdsAcrossMirrors() {
        val page = Jsoup.parse("""
            <div class="list-col"><div class="thumb-overlay-albums"><a href="/album/501/city-sketch">
                <img data-original="$cdn/media/albums/501_3x4.jpg?u=9" src="/media/albums/blank.jpg"></a></div>
                <div class="video-title">City Sketch</div></div>
            <div class="list-col"><div class="thumb-overlay"><a href="https://18comic.vip/album/501/other-slug">Duplicate</a></div>
                <div class="video-title">Duplicate</div></div>
            <div class="list-col"><div class="thumb-overlay"><a href="https://example.invalid/album/503">Other host</a></div>
                <div class="video-title">Other host</div></div>
            <ul class="pagination"><li><a href="$mirror/albums?o=mr&amp;page=2">2</a></li></ul>
        """.trimIndent(), "$mirror/albums?o=mr")
        assertTrue(JmcomicProtocol.hasCatalogue(page))
        val feed = JmcomicProtocol.feed(site, page, FeedRequest(), "最新漫画")
        assertTrue(feed.hasNext)
        val work = feed.videos.single()
        assertEquals("501", work.id)
        assertEquals("${site.base}/album/501", work.url)
        assertEquals("$cdn/media/albums/501_3x4.jpg?u=9", work.thumb)
        assertEquals(MediaKind.GALLERY, work.mediaKind)
        assertEquals(site.id, work.site)
        assertFalse(JmcomicProtocol.hasCatalogue(Jsoup.parse("<title>Just a moment...</title>", "$mirror/albums?o=mr")))
    }

    @Test fun anEmptySearchRequiresThePublicNoResultsMarkup() {
        val url = "$mirror/search/photos?search_query=city"
        val request = FeedRequest(FeedKind.SEARCH, "city", Sort.RELEVANCE)
        val empty = Jsoup.parse("<span class='text-danger'>查無A漫.</span><h3>找不到和<span>city</span>相符的內容</h3>", url)
        val page = JmcomicProtocol.feed(site, empty, request, "city")
        assertTrue(page.videos.isEmpty())
        assertFalse(page.hasNext)
        assertThrows(IOException::class.java) {
            JmcomicProtocol.feed(site, Jsoup.parse("<title>Just a moment...</title>", url), request, "city")
        }
    }

    @Test fun episodeListingKeepsAlbumAndChapterHistoriesIndependent() {
        val document = Jsoup.parse("""
            <h1 id="book-name">City Sketch</h1>
            <img itemprop="image" src="$cdn/media/albums/501.jpg">
            <a class="reading" href="/photo/500001">开始阅读</a>
            <div id="episode-block"><div class="episode">
                <a href="/photo/500001">First</a><a href="/photo/500002">Second</a>
                <a href="https://another.example/photo/500003">Other host</a>
            </div></div>
        """.trimIndent(), "$mirror/album/501/city-sketch")
        val album = JmcomicProtocol.album(site, document, "501")
        val chapter = chapter()
        val book = JmcomicProtocol.gallery(site, album, chapter)
        assertEquals("501", book.entry.id)
        assertEquals("500001", album.readingId)
        assertEquals("${site.base}/photo/500001#1", book.pageLinks.first().url)
        val entryLink = book.groups.getValue("章节").single().url
        assertNull(JmcomicProtocol.linkRequest(album.entry.url))
        val listing = FeedRequest(FeedKind.CATEGORY, "501")
        assertEquals(listing, JmcomicProtocol.linkRequest(entryLink))
        assertEquals("${site.base}/album/501", JmcomicProtocol.resolve(site, listing))
        assertEquals(listOf("chapter-500001", "chapter-500002"), JmcomicProtocol.chapterFeed(site, album).videos.map { it.id })
        assertEquals("chapter-500001", JmcomicProtocol.gallery(site, null, chapter).entry.id)
    }

    @Test fun sharedAlbumPreservesOnlyTheExactChapterListingFragment() {
        val album = "$mirror/album/501/city-sketch"
        val link = incomingLink("Chapter list ($album#chapters)")
        assertEquals(SiteRegistry.JMCOMIC, link.site)
        assertEquals("$album#chapters", link.url)
        assertEquals(FeedRequest(FeedKind.CATEGORY, "501"), JmcomicProtocol.linkRequest(link.url))
        for (fragment in listOf("Chapters", "%63hapters", "chapters-extra")) {
            assertEquals(album, incomingLink("$album#$fragment").url)
        }
        assertEquals("$mirror/photo/500001", incomingLink("$mirror/photo/500001#chapters").url)
        assertEquals("$mirror/album/0", incomingLink("$mirror/album/0#chapters").url)
        assertEquals("https://hanime1.me/video/501", incomingLink("https://hanime1.me/video/501#chapters").url)
    }

    @Test fun readerUsesPageNumbersAndPreservesTheActualImageReferer() {
        val chapter = chapter()
        val first = JmcomicProtocol.image(site, chapter, 1)
        assertEquals("${site.base}/photo/500001#1", first.pageUrl)
        assertEquals("${site.base}/photo/500001#2", first.nextUrl)
        assertEquals("${site.base}/photo/500001?reload=1#1", first.reloadUrl)
        assertEquals("$mirror/photo/500001", first.imageReferer)
        assertEquals("$cdn/media/photos/500001/00001.webp?u=9", first.imageUrl)
        assertEquals(4, first.imageStripCount)
        assertNull(first.previousUrl)
        val second = JmcomicProtocol.image(site, chapter, 2)
        assertEquals(first.pageUrl, second.previousUrl)
        assertNull(second.nextUrl)
        assertEquals(0, second.imageStripCount)
    }

    @Test fun usesPublicCanvasThresholdsAndUnshuffledMode() {
        assertEquals(0, JmcomicProtocol.stripCount(220979, 220980, "00001"))
        assertEquals(10, JmcomicProtocol.stripCount(220980, 220980, "00001"))
        assertEquals(10, JmcomicProtocol.stripCount(268849, 220980, "00001"))
        assertEquals(6, JmcomicProtocol.stripCount(268850, 220980, "00001"))
        assertEquals(18, JmcomicProtocol.stripCount(421925, 220980, "00001"))
        assertEquals(14, JmcomicProtocol.stripCount(421926, 220980, "00001"))
        assertEquals(0, JmcomicProtocol.image(site, chapter(speed = "1"), 1).imageStripCount)
    }

    @Test fun readerAcceptsCdnRotationWithoutChangingTheChapterIdentity() {
        val alternate = "https://cdn-msp3.jmcomic-zzz.one"
        val original = JmcomicProtocol.image(site, chapter(), 1)
        val rotated = JmcomicProtocol.image(site, chapter(imageHost = alternate), 1)
        assertEquals(original.pageUrl, rotated.pageUrl)
        assertEquals("$alternate/media/photos/500001/00001.webp?u=9", rotated.imageUrl)
        assertEquals(original.imageStripCount, rotated.imageStripCount)
    }

    @Test fun unrelatedMediaAndAuthenticationRoutesAreRejected() {
        val initial = "$mirror/photo/500001".toHttpUrl()
        assertTrue(JmcomicProtocol.redirectAllowed(initial, "${site.base}/photo/500001/sample".toHttpUrl()))
        assertFalse(JmcomicProtocol.redirectAllowed(initial, "$mirror/login".toHttpUrl()))
        assertFalse(JmcomicProtocol.redirectAllowed(initial, "$mirror/photo/500002".toHttpUrl()))
        assertFalse(JmcomicProtocol.redirectAllowed(initial, "https://18comic.ink.example.invalid/photo/500001".toHttpUrl()))
        assertNull(JmcomicProtocol.identity("http://18comic.ink/album/501"))
        assertThrows(IllegalStateException::class.java) { chapter(imageHost = "https://cdn-msp2.18comic.ink.example.invalid") }
        assertThrows(IllegalStateException::class.java) { chapter(secondIndex = 2) }
        assertThrows(IllegalStateException::class.java) {
            val book = Jsoup.parse("<h1 id='book-name'>Unreleased chapter</h1><a href='/account/login'>Login</a>", "$mirror/album/501")
            JmcomicProtocol.album(site, book, "501")
        }
    }

    @Test fun healthyMirrorIsReusedUntilRouteChangeOrExpiry() = runBlocking {
        var systemRoute = "network 1"
        var now = 0L
        val primary = site.host
        val backup = "18comic.ink"
        val probes = mutableListOf<String>()
        val router = JmcomicMirrorRouter(listOf(primary, backup), { systemRoute }, { now }, 30L) { host ->
            probes += host
            host.takeIf { host == backup || systemRoute == "network 2" }
        }
        assertEquals(backup, router.load { it.host })
        assertEquals(backup, router.load { it.host })
        assertEquals(listOf(primary, backup), probes)
        systemRoute = "network 2"
        assertEquals(primary, router.load { it.host })
        now = 31
        assertEquals(primary, router.load { it.host })
        assertEquals(listOf(primary, backup, primary, primary), probes)
    }

    @Test fun transientFailureRecoversOnceAndAccessDenialDoesNotRetryRequest() = runBlocking {
        val primary = site.host
        val backup = "18comic.ink"
        val probes = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        val router = JmcomicMirrorRouter(listOf(primary, backup), { "same network" }) { host ->
            probes += host
            host
        }
        val result = router.load { route ->
            attempts += route.host
            if (route.host == primary) throw IOException("connection reset")
            route.host
        }
        assertEquals(backup, result)
        assertEquals(listOf(primary, backup), attempts)
        assertEquals(listOf(primary, backup), probes)
        val denied = runCatching { router.load { throw GalleryResponseException(403, message = "Sign-in required") } }
        assertEquals(403, (denied.exceptionOrNull() as GalleryResponseException).status)
        assertEquals(listOf(primary, backup), probes)
        assertEquals(backup, router.load { it.host })
    }

    private fun chapter(speed: String = "", imageHost: String = cdn, secondIndex: Int = 1): JmcomicProtocol.Chapter =
        JmcomicProtocol.chapter(Jsoup.parse("""
            <h1 class="pull-left">First chapter</h1>
            <div class="center scramble-page" id="00002.gif"><img id="album_photo_00002.gif" data-page="$secondIndex"
                data-original="$imageHost/media/photos/500001/00002.gif"></div>
            <div class="center scramble-page" id="00001.webp"><img id="album_photo_00001.webp" data-page="0"
                src="$cdn/media/albums/blank.jpg" data-original="$imageHost/media/photos/500001/00001.webp?u=9"></div>
            <script>var scramble_id = 220980; var aid = 500001; var speed = '$speed';</script>
        """.trimIndent(), "$mirror/photo/500001"), "500001")
}
