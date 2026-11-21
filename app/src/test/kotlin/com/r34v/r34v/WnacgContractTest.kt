package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.WnacgProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WnacgContractTest {
    private val site = SiteContext(
        id = "wnacg", name = "WNACG", base = "https://www.wnacg.com", host = "wnacg.com",
        mediaKind = MediaKind.GALLERY, category = SiteCategory.COMIC,
    )
    private val canonical = "${site.base}/photos-index-aid-42.html"
    private val reader = "${site.base}/photos-gallery-aid-42.html"
    private val cover = "https://t3.wnimg1.ru/data/t/0042/cover.jpg?v=2"
    private val firstImage = "https://img5.wnimg1.ru/data/0042/0009.jpg?x=1&sig=a%2Fb"
    private val secondImage = "https://img2.wnimg1.ru/data/0042/0002.gif"
    private val card = """
        <li class="li gallary_item">
          <img src="//t3.wnimg1.ru/data/t/0042/cover.jpg?v=2">
          <div class="title"><a href="/photos-index-aid-42.html?from=list#cover">City <em>Sketches</em></a></div>
          <div class="info_col">3張圖片， 創建於2026-01-01</div>
        </li>
    """
    private val imageBody = """
        <img src="https://img5.wnimg1.ru/data/0042/decoy.jpg">
        <script>
          var imglist = [
            {url: fast_img_host + "////img5.wnimg1.ru/data/0042/0009.jpg?x=1&sig=a%2Fb", caption: "[0009]"},
            {caption: '{tile}; , url: "https://img5.wnimg1.ru/ad.jpg"', url: 'https://img2.wnimg1.ru/data/0042/0002.gif'},
            {url: "$firstImage", caption: "[0009]"}
          ];
        </script>
    """

    @Test fun catalogCardsUseCanonicalGalleryIdentityAndFollowThePager() {
        val request = FeedRequest(page = 2)
        val url = WnacgProtocol.resolve(site, request)
        assertEquals("${site.base}/albums-index-page-2.html", url)
        val pager = "<span class='thispage'>2</span><a href='/albums-index-page-3.html'>3</a>"
        val page = WnacgProtocol.feed("<ul>$card$card</ul>$pager", url, site, request, "最新画廊")
        val entry = page.videos.single()
        assertEquals("42", entry.id)
        assertEquals("City Sketches", entry.title)
        assertEquals(canonical, entry.url)
        assertEquals(cover, entry.thumb)
        assertEquals("3 页", entry.duration)
        assertEquals("wnacg", entry.site)
        assertEquals(MediaKind.GALLERY, entry.mediaKind)
        assertEquals(2, page.current)
        assertTrue(page.hasNext)
        assertEquals("${site.base}/albums-index-page-3.html", WnacgProtocol.resolve(site, request.copy(page = 3)))
        val last = WnacgProtocol.feed("<ul>$card</ul><a href='/albums-index-page-1.html'>1</a><span class='thispage'>2</span>",
            url, site, request, "最新画廊")
        assertFalse(last.hasNext)
        assertFalse(WnacgProtocol.feed("<ul>$card</ul>${pager.replace("'/", "'https://www.wnacg.com.example/")}",
            url, site, request, "最新画廊").hasNext)
    }

    @Test fun desktopCatalogRetainsCoversFromTheCurrentCdn() {
        val request = FeedRequest()
        val url = WnacgProtocol.resolve(site, request)
        val desktopCard = card.replace("//t3.wnimg1.ru", "//t4.qy0.ru")
        val entry = WnacgProtocol.feed("<ul>$desktopCard</ul>", url, site, request, "最新画廊").videos.single()
        assertEquals("https://t4.qy0.ru/data/t/0042/cover.jpg?v=2", entry.thumb)
    }

    @Test fun searchKeepsTheEncodedQueryAcrossPages() {
        val query = "城市 & cafe+tea"
        val request = FeedRequest(FeedKind.SEARCH, query, page = 3)
        val url = WnacgProtocol.resolve(site, request).toHttpUrl()
        assertEquals("/search/index.php", url.encodedPath)
        assertEquals("create_time_DESC", url.queryParameter("s"))
        assertEquals(query, url.queryParameter("q"))
        assertEquals("3", url.queryParameter("p"))
        assertEquals(request, WnacgProtocol.linkRequest(site, url.toString()))
        val page = WnacgProtocol.feed("<ul>$card</ul><span class='thispage'>3</span><a href='?p=4'>4</a>",
            url.toString(), site, request, query)
        assertTrue(page.hasNext)
        assertEquals("42", page.videos.single().id)
        val next = WnacgProtocol.resolve(site, request.copy(page = page.current + 1)).toHttpUrl()
        assertEquals("4", next.queryParameter("p"))
        assertEquals(query, next.queryParameter("q"))
        val mismatched = "<ul>$card</ul><span class='thispage'>3</span><a href='?p=4&amp;q=another'>4</a>"
        assertFalse(WnacgProtocol.feed(mismatched, url.toString(), site, request, query).hasNext)
    }

    @Test fun explicitEmptySearchResultsAreDistinctFromUnexpectedHtml() {
        val request = FeedRequest(FeedKind.SEARCH, "example")
        val url = WnacgProtocol.resolve(site, request)
        val none = WnacgProtocol.feed("<div id='bodywrap'><div class='result'>共有 <b>0</b> 筆結果</div></div>", url, site, request, "example")
        assertTrue(none.videos.isEmpty())
        assertFalse(none.hasNext)
        assertThrows(IllegalStateException::class.java) {
            WnacgProtocol.feed("<h1>Forbidden</h1>", url, site, request, "example")
        }
    }

    @Test fun detailKeepsMetadataAndStableHashLinksForTheCompleteGallery() {
        val html = """
            <h2>City Sketches</h2><div class="uwthumb"><img src="$cover"></div>
            <div class="uwuinfo"><p>Sample Uploader</p></div>
            <div class="uwconn"><label>分類：速寫</label><label>頁數：3P</label><p>簡介：A notebook of city sketches.</p></div>
            <div class="addtags"><a class="tagshow" href="/albums-index-tag-%E5%9F%8E%E5%B8%82.html">城市</a></div>
        """
        val images = WnacgProtocol.imageUrls(imageBody, reader, site)
        val book = WnacgProtocol.gallery(html, canonical, site, images)
        assertEquals("42", book.entry.id)
        assertEquals(canonical, book.entry.url)
        assertEquals("City Sketches", book.entry.title)
        assertEquals("速寫", book.entry.type)
        assertEquals(cover, book.entry.thumb)
        assertEquals("Sample Uploader", book.uploader)
        assertEquals("A notebook of city sketches.", book.description)
        assertEquals(3, book.pageCount)
        assertEquals(3, book.previewPageSize)
        assertEquals(listOf(1, 2, 3), book.pageLinks.map { it.index })
        assertEquals((1..3).map { "$canonical#$it" }, book.pageLinks.map { it.url })
        val tag = book.groups.getValue("标签").single()
        assertEquals("城市", tag.label)
        assertEquals(FeedRequest(FeedKind.TAG, "城市"), WnacgProtocol.linkRequest(site, tag.url))
        assertThrows(IllegalStateException::class.java) {
            WnacgProtocol.gallery(html, canonical, site, images.dropLast(1))
        }
    }

    @Test fun imageUrlsPreserveOrderDuplicatesCdnQueriesAndTheGalleryReferer() {
        assertEquals(reader, WnacgProtocol.readerUrl(canonical))
        val urls = WnacgProtocol.imageUrls(imageBody, reader, site)
        assertEquals(listOf(firstImage, secondImage, firstImage), urls)
        val page = WnacgProtocol.image(WnacgProtocol.pageIdentity(site, "$canonical#2")!!, urls)
        assertEquals(2, page.index)
        assertEquals(secondImage, page.imageUrl)
        assertEquals("$canonical#2", page.pageUrl)
        assertEquals(canonical, page.pageUrl.substringBefore('#'))
        assertEquals("$canonical#1", page.previousUrl)
        assertEquals("$canonical#3", page.nextUrl)
        assertNull(WnacgProtocol.image(WnacgProtocol.pageIdentity(site, "$canonical#1")!!, urls).previousUrl)
        assertNull(WnacgProtocol.image(WnacgProtocol.pageIdentity(site, "$canonical#3")!!, urls).nextUrl)
        assertThrows(IllegalArgumentException::class.java) {
            WnacgProtocol.image(WnacgProtocol.pageIdentity(site, "$canonical#4")!!, urls)
        }
    }

    @Test fun readerUnwrapsTheWrittenArrayAndSkipsTheTrailingFavoriteReminder() {
        val first = "https://img5.qy0.ru/data/0042/009.jpg?verify=sample%2Ffirst&part=1"
        val second = "https://img5.qy0.ru/data/0042/002.jpg?verify=sample-second"
        val reminder = """{ url: fast_img_host+\"/themes/weitu/images/bg/shoucang.jpg\", caption: \"請加入收藏\"}"""
        val html = """
            document.writeln("<script type=\"text/javascript\">");
            document.writeln("		var imglist = [{ url: fast_img_host+\"${first.removePrefix("https:")}\", caption: \"[009]\"},{ url: fast_img_host+\"${second.removePrefix("https:")}\", caption: \"[002]\"},$reminder];");
            document.writeln("</script>");
        """.trimIndent()
        assertEquals(listOf(first, second), WnacgProtocol.imageUrls(html, reader, site))
        assertThrows(IllegalStateException::class.java) {
            WnacgProtocol.imageUrls(html.replace("img5.qy0.ru", "other.qy0.ru"), reader, site)
        }
        assertThrows(IllegalStateException::class.java) {
            WnacgProtocol.imageUrls("""document.writeln("var imglist = [$reminder];");""", reader, site)
        }
    }

    @Test fun pageAddressesRejectInvalidReferencesAndForeignCdnUrls() {
        assertEquals(canonical, WnacgProtocol.canonical(site, "https://wnacg.com/photos-index-aid-42-page-2.html?from=list#3"))
        assertEquals(canonical, WnacgProtocol.canonical(site, reader))
        assertNull(WnacgProtocol.canonical(site, "https://www.wnacg.com.example/photos-index-aid-42.html"))
        assertNull(WnacgProtocol.canonical(site, "https://user@www.wnacg.com/photos-index-aid-42.html"))
        assertNull(WnacgProtocol.pageIdentity(site, "$canonical#0"))
        assertNull(WnacgProtocol.pageIdentity(site, "$canonical#01"))
        assertNull(WnacgProtocol.pageIdentity(site, canonical))
        for (bad in listOf("https://img5.wnimg1.ru.example/2.gif", "https://unknown.wnimg1.ru/2.gif",
            "http://img2.wnimg1.ru/2.gif", "https://fixture@img2.wnimg1.ru/2.gif", "https://img2.wnimg1.ru:8080/2.gif")) {
            assertThrows(IllegalStateException::class.java) {
                WnacgProtocol.imageUrls(imageBody.replace(secondImage, bad), reader, site)
            }
        }
    }
}
