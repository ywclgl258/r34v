package com.r34v.r34v

import com.google.gson.Gson
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.EhProtocol
import com.r34v.r34v.data.adapter.GalleryAccessException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class EhContractTest {
    private val site = SiteRegistry.EHENTAI
    private fun fixture(name: String) = javaClass.getResource("/eh/$name")!!.readText()

    @Test fun languageChoiceSurvivesCategoriesSearchAndCursorPagination() {
        for (site in listOf(SiteRegistry.EHENTAI, SiteRegistry.EXHENTAI)) {
            val request = FeedRequest(FeedKind.SEARCH, "city", language = GalleryLanguage.CHINESE_JAPANESE)
            val url = EhProtocol.resolve(site, request).toHttpUrl()
            assertEquals("city ~language:chinese$ ~language:japanese$", url.queryParameter("f_search"))
            assertEquals("on", url.queryParameter("f_sfl"))
            val next = EhProtocol.resolve(site, request.copy(page = 2, cursor = "${site.base}/?next=42")).toHttpUrl()
            assertEquals("42", next.queryParameter("next")); assertEquals(url.queryParameter("f_search"), next.queryParameter("f_search"))
            val category = EhProtocol.resolve(site, FeedRequest(FeedKind.CATEGORY, "4", language = GalleryLanguage.CHINESE)).toHttpUrl()
            assertEquals("1019", category.queryParameter("f_cats")); assertEquals("language:chinese$", category.queryParameter("f_search"))
        }
    }

    @Test fun popularPageUsesLanguageTagsBecauseItIgnoresSearchParameters() {
        val body = "<html><table class='itg'>" + listOf("chinese", "japanese", "english").mapIndexed { i, language ->
            "<tr><td class='glname'><a href='/g/${9000 + i}/abcdef/'><div class='glink'>City $i</div></a><div title='language:$language'></div></td></tr>"
        }.joinToString("") + "</table></html>"
        assertEquals(listOf("9000"), EhProtocol.feed(body, site.base, site, 1, GalleryLanguage.CHINESE).videos.map { it.id })
        assertEquals(listOf("9000", "9001"), EhProtocol.feed(body, site.base, site, 1, GalleryLanguage.CHINESE_JAPANESE).videos.map { it.id })
        assertEquals(3, EhProtocol.feed(body, site.base, site, 1).videos.size)
        val none = body.replace("language:chinese", "language:english").replace("language:japanese", "language:english")
        assertTrue(EhProtocol.feed(none, site.base, site, 1, GalleryLanguage.JAPANESE).videos.isEmpty())
    }

    @Test fun searchPreservesTheGallerySearchSyntax() {
        val url = EhProtocol.resolve(site, FeedRequest(FeedKind.SEARCH, "artist:city_sketch & English")).toHttpUrl()
        assertEquals("artist:city_sketch & English", url.queryParameter("f_search"))
        assertEquals("dm_l", url.queryParameter("inline_set"))
        assertEquals("uploader:\"Sample Author\"", EhProtocol.resolve(site, FeedRequest(FeedKind.MODEL, "Sample Author")).toHttpUrl().queryParameter("f_search"))
        assertEquals("767", EhProtocol.resolve(site, FeedRequest(FeedKind.CATEGORY, "256")).toHttpUrl().queryParameter("f_cats"))
        assertThrows(IllegalStateException::class.java) { EhProtocol.resolve(site, FeedRequest(FeedKind.CATEGORY, "99999")) }
    }
    @Test fun cursorPaginationFollowsTheActualNextLinkAndRejectsOtherOrigins() {
        val page = EhProtocol.feed(fixture("list.html"), site.base, site, 1)
        assertEquals(2, page.total); assertTrue(page.hasNext)
        val next = EhProtocol.resolve(site, FeedRequest(page = 2, cursor = page.nextCursor!!)).toHttpUrl()
        assertEquals("8887", next.queryParameter("next")); assertEquals("city", next.queryParameter("f_search"))
        assertThrows(IllegalArgumentException::class.java) { EhProtocol.resolve(site, FeedRequest(cursor = "https://example.invalid/?next=1")) }
        assertThrows(IllegalArgumentException::class.java) { EhProtocol.resolve(site, FeedRequest(cursor = "https://e-hentai.org/s/a1/8888-1")) }
    }
    @Test fun listCardsAreGalleriesAndDeduplicateByGalleryId() {
        val entry = EhProtocol.feed(fixture("list.html"), site.base, site, 1).videos.single()
        assertEquals("8888", entry.id); assertEquals("City sketchbook", entry.title)
        assertEquals(MediaKind.GALLERY, entry.mediaKind); assertEquals("ehentai", entry.site)
        assertEquals("5 页", entry.duration); assertEquals("https://ehgt.org/city-cover.jpg", entry.thumb)
        assertEquals("Non-H", entry.type)
    }
    @Test fun noResultsAndNoPermissionAreDifferentStates() {
        assertTrue(EhProtocol.feed("<html><p>No hits found</p></html>", site.base, site, 1).videos.isEmpty())
        assertThrows(GalleryAccessException::class.java) { EhProtocol.feed("GIF89a", SiteRegistry.EXHENTAI.base, SiteRegistry.EXHENTAI, 1) }
        assertThrows(IllegalStateException::class.java) { EhProtocol.feed("<html><table class='itg'></table></html>", site.base, site, 1) }
    }
    @Test fun galleryDetailKeepsTotalPagesAndPartialPreviewIndexSeparate() {
        val detail = EhProtocol.gallery(fixture("detail.html"), "https://e-hentai.org/g/8888/abcdef1234/", site)
        assertEquals(5, detail.pageCount); assertEquals(2, detail.previewPageSize)
        assertEquals(listOf(1,2), detail.pageLinks.map { it.index })
        assertEquals("城市速写", detail.alternativeTitle); assertEquals("Sample Author", detail.uploader)
        assertEquals("English", detail.language); assertEquals("https://ehgt.org/city-cover.jpg", detail.entry.thumb)
        assertEquals(FeedRequest(FeedKind.TAG, "artist:city sketch"), EhProtocol.linkRequest(site, detail.groups.getValue("标签").single().url))
    }
    @Test fun pageParserUsesTheDisplayedImageAndNavigationWithoutFetchingOriginals() {
        val page = EhProtocol.image(fixture("page.html"), "https://e-hentai.org/s/a2/8888-2", site)
        assertEquals(2, page.index); assertTrue(page.imageUrl.startsWith("https://sample.hath.network:1443/"))
        assertFalse(page.imageUrl.contains("fullimg")); assertTrue(page.nextUrl!!.endsWith("8888-3"))
        assertEquals("sample-reload", page.reloadUrl!!.toHttpUrl().queryParameter("nl"))
    }
    @Test fun quotaAndUnavailableImagesFailExplicitly() {
        assertThrows(GalleryAccessException::class.java) { EhProtocol.image("<html><img id='img' src='https://ehgt.org/g/509.gif'></html>", "https://e-hentai.org/s/a2/8888-2", site) }
        assertThrows(GalleryAccessException::class.java) { EhProtocol.checkAccess("Image View Limit Exceeded", site) }
        assertThrows(IllegalStateException::class.java) { EhProtocol.image("<html><img id='img' src='http://example.invalid/image.jpg'></html>", "https://e-hentai.org/s/a2/8888-2", site) }
    }
    @Test fun galleryAndPageLinksCannotCrossSites() {
        assertNull(EhProtocol.galleryUrl(site, "https://exhentai.org/g/8888/abcdef1234/"))
        assertNull(EhProtocol.pageIndex(site, "https://e-hentai.org.attacker.invalid/s/a1/8888-1"))
        assertNull(EhProtocol.linkRequest(site, "https://example.invalid/tag/artist:city"))
        assertEquals("https://e-hentai.org/g/8888/abcdef1234/", EhProtocol.galleryUrl(site, "https://e-hentai.org/g/8888/abcdef1234/?p=3"))
    }
    @Test fun importedSessionsAreBoundToOneSiteAndDiscardUnrelatedCookies() {
        val cookies = EhProtocol.sessionCookies(SiteRegistry.EXHENTAI, "Cookie: ipb_member_id=12345; ipb_pass_hash=0123456789abcdef0123456789abcdef; igneous=fixture; unrelated=ignored")
        assertEquals(setOf("ipb_member_id", "ipb_pass_hash", "igneous"), cookies.map { it.name }.toSet())
        assertTrue(cookies.all { it.matches("https://exhentai.org/".toHttpUrl()) && it.secure && it.httpOnly })
        assertTrue(cookies.none { it.matches("https://e-hentai.org/".toHttpUrl()) || it.matches("https://sample.hath.network/image.jpg".toHttpUrl()) })
        assertThrows(IllegalArgumentException::class.java) { EhProtocol.sessionCookies(site, "ipb_member_id=0; ipb_pass_hash=bad") }
        assertThrows(IllegalArgumentException::class.java) { EhProtocol.sessionCookies(site, "ipb_member_id=1; ipb_pass_hash=0123456789abcdef; igneous=bad\r\nheader") }
    }
    @Test fun newMediaFieldsKeepExistingVideoAndFeedDefaults() {
        assertEquals(MediaKind.VIDEO, Gson().fromJson("""{"id":"42","title":"Old video"}""", Video::class.java).mediaKind)
        assertEquals("", Gson().fromJson("""{"page":1}""", FeedRequest::class.java).cursor)
        // Every registered site must belong to exactly one switcher group; the count itself is
        // asserted in FourKhdParserTest, where the grouping is under test.
        assertEquals(SiteRegistry.all.size, SiteCategory.entries.sumOf { SiteRegistry.ofCategory(it).size })
    }
}
