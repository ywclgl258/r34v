package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.FourKhdProtocol
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Parser tests for the 4KHD photo-set archive, written against original neutral fixtures that
 * mirror the live markup observed on 2026-11-04. They pin the shapes the adapter depends on, so a
 * site redesign fails here instead of silently producing an empty list or a wrong page count.
 */
class FourKhdParserTest {
    private val site = SiteRegistry.FOURKHD
    private fun fixture(name: String) = javaClass.getResource("/4khd/$name")!!.readText()

    @Test fun sortingChoicesOnlyIncludeOrdersTheCurrentRouteCanApply() {
        assertEquals(listOf(Sort.NEWEST), FourKhdProtocol.sorts(FeedRequest(FeedKind.SEARCH, "sample")))
        assertEquals(listOf(Sort.NEWEST), FourKhdProtocol.sorts(FeedRequest(FeedKind.CATEGORY, "cosplay")))
        assertEquals(listOf(Sort.POPULARITY), FourKhdProtocol.sorts(FeedRequest(FeedKind.POPULAR)))
        val latest = FeedRequest(FeedKind.LATEST)
        val choices = FourKhdProtocol.sorts(latest)
        assertEquals(listOf(Sort.NEWEST, Sort.POPULARITY), choices)
        assertEquals(choices.size, choices.map { FourKhdProtocol.resolve(site, latest.copy(sort = it)) }.distinct().size)
    }

    @Test fun listingCardsCarryEveryField() {
        val videos = FourKhdProtocol.feed(Jsoup.parse(fixture("list.html")), site)
        assertEquals(2, videos.size)
        val first = videos.first()
        assertEquals("sample-set-one", first.id)
        assertEquals("[JP] Sample Model – Sample One[21MB-31photos]", first.title)
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html", first.url)
        assertTrue(first.thumb.startsWith("https://i0.wp.com/pic.4khd.com/"))
        assertEquals("2786", first.views)
        assertEquals("11/4", first.rating)
        assertEquals("4khd", first.site)
        assertEquals(MediaKind.GALLERY, first.mediaKind)
        assertEquals(850, first.thumbWidth); assertEquals(1158, first.thumbHeight)
    }

    @Test fun cardsFromOtherHostsNeverParse() {
        val videos = FourKhdProtocol.feed(Jsoup.parse(fixture("list.html")), site)
        assertTrue(videos.none { it.url.contains("attacker") })
        assertTrue(videos.all { it.url.startsWith("https://www.4khd.com/content/") })
    }

    @Test fun pagerTotalComesFromTheHighestNumberedLink() {
        assertEquals(411, FourKhdProtocol.totalPages(Jsoup.parse(fixture("list.html"))))
        // A search result without a pager is its own last page.
        assertNull(FourKhdProtocol.totalPages(Jsoup.parse(fixture("search.html"))))
        assertEquals(1, FourKhdProtocol.feed(Jsoup.parse(fixture("search.html")), site).size)
    }

    @Test fun contentPageImagesIgnoreBannersAndStripDuplicates() {
        val images = FourKhdProtocol.images(Jsoup.parse(fixture("content-1.html")))
        assertEquals(3, images.size)
        assertTrue(images.all { it.contains("pic.4khd.com") })
        assertEquals("https://i0.wp.com/pic.4khd.com/fixture/set-one-001.webp?w=1300", images.first())
        assertEquals(2, FourKhdProtocol.pageCount(Jsoup.parse(fixture("content-2.html"))))
    }

    @Test fun titleAndCoverDropTheSiteSuffix() {
        val doc = Jsoup.parse(fixture("content-1.html"))
        assertEquals("[JP] Sample Model - Sample One[21MB-31photos]", FourKhdProtocol.title(doc))
        assertEquals("https://i0.wp.com/pic.4khd.com/fixture/set-one-001.webp?w=1300", FourKhdProtocol.cover(doc))
    }

    @Test fun flatBookIndexMapsOntoContentPages() {
        // 3 images on the first page, 2 on the second: page 4 lives on content page 2, offset 0.
        assertEquals(1 to 0, FourKhdProtocol.position(1, 3, 2))
        assertEquals(1 to 2, FourKhdProtocol.position(3, 3, 2))
        assertEquals(2 to 0, FourKhdProtocol.position(4, 3, 2))
        assertEquals(2 to 1, FourKhdProtocol.position(5, 3, 2))
        // A short trailing page cannot push the index past the last content page.
        assertEquals(2 to 2, FourKhdProtocol.position(6, 3, 2))
    }

    @Test fun pageAddressesSurviveARestart() {
        val canonical = "https://www.4khd.com/content/04/sample-set-one.html"
        val url = FourKhdProtocol.pageUrl(canonical, 7, 3, 1)
        assertEquals("https://www.4khd.com/content/04/sample-set-one.html/3#7-3-1", url)
        val identity = FourKhdProtocol.imageIdentity(site, url)!!
        assertEquals(canonical, identity.canonical)
        assertEquals(7, identity.index); assertEquals(3, identity.page); assertEquals(1, identity.offset)
        // The first content page carries no path suffix.
        assertEquals(canonical, FourKhdProtocol.pageUrl(canonical, 1, 1, 0).substringBefore('#'))
    }

    @Test fun imageAddressesCannotCrossSites() {
        assertNull(FourKhdProtocol.contentUrl(site, "https://example.invalid/content/04/sample-set-one.html"))
        assertNull(FourKhdProtocol.contentUrl(SiteRegistry.NHENTAI, "https://www.4khd.com/content/04/x.html"))
        assertNull(FourKhdProtocol.imageIdentity(site, "https://www.4khd.com/content/04/sample-set-one.html"))
        assertNull(FourKhdProtocol.imageIdentity(site, "https://www.4khd.com/content/04/sample-set-one.html#not-a-position"))
        assertThrows(IllegalStateException::class.java) { FourKhdProtocol.postId("https://www.4khd.com/pages/popular") }
    }

    @Test fun adapterResolvesTheSitesOwnRoutes() {
        val routes = SiteRegistry.FOURKHD
        val sections = listOf(
            com.r34v.r34v.data.adapter.Section("popular", "热门写真", "", FeedRequest(FeedKind.POPULAR)),
            com.r34v.r34v.data.adapter.Section("cosplay", "Cosplay", "", FeedRequest(FeedKind.CATEGORY, "cosplay")),
        )
        assertEquals("https://www.4khd.com/pages/popular", FourKhdProtocol.resolve(routes, FeedRequest(FeedKind.POPULAR)))
        assertEquals("https://www.4khd.com/", FourKhdProtocol.resolve(routes, FeedRequest()))
        assertEquals("https://www.4khd.com/?query-3-page=3", FourKhdProtocol.resolve(routes, FeedRequest(page = 3)))
        assertEquals("https://www.4khd.com/pages/cosplay?query-3-page=2", FourKhdProtocol.resolve(routes, FeedRequest(FeedKind.CATEGORY, "cosplay", page = 2)))
        assertEquals("https://www.4khd.com/pages/popular?query-3-page=2", FourKhdProtocol.resolve(routes, FeedRequest(sort = Sort.POPULARITY, page = 2)))
        assertEquals("https://www.4khd.com/search/sample%20model", FourKhdProtocol.resolve(routes, FeedRequest(FeedKind.SEARCH, "sample model")))
        assertEquals("https://www.4khd.com/search/sample%20model/page/4", FourKhdProtocol.resolve(routes, FeedRequest(FeedKind.SEARCH, "sample model", page = 4)))
        assertEquals("热门写真", FourKhdProtocol.title(routes, FeedRequest(FeedKind.POPULAR), sections))
        assertEquals("Cosplay", FourKhdProtocol.title(routes, FeedRequest(FeedKind.CATEGORY, "cosplay"), sections))
        assertEquals("sample model", FourKhdProtocol.title(routes, FeedRequest(FeedKind.SEARCH, "sample model"), sections))
        assertEquals("最新写真", FourKhdProtocol.title(routes, FeedRequest(), sections))
        assertEquals("latest", routes.let { "latest" })
        assertEquals(FeedRequest(FeedKind.SEARCH, "sample"), FourKhdProtocol.linkRequest(routes, "https://www.4khd.com/search/sample/page/2"))
        assertNull(FourKhdProtocol.linkRequest(routes, "https://example.invalid/search/sample"))
        assertNull(FourKhdProtocol.linkRequest(routes, "https://www.4khd.com/content/04/sample-set-one.html"))
        // The photo archive is read as a gallery but is never presented as a comic.
        assertEquals("写真集", routes.category.itemNoun)
        assertEquals(MediaKind.GALLERY, routes.mediaKind)
        assertEquals(SiteCategory.PHOTO, routes.category)
    }

    @Test fun siteCategoriesGroupEveryRegisteredSite() {
        assertEquals(
            listOf("r34v", "hanime1", "iwara", "missav", "jable", "rouvideo", "kbj", "kbjfree", "pornhub"),
            SiteRegistry.ofCategory(SiteCategory.VIDEO).map { it.id },
        )
        assertEquals(listOf("ehentai", "exhentai", "nhentai", "hitomi"), SiteRegistry.ofCategory(SiteCategory.COMIC).map { it.id })
        assertTrue(SiteRegistry.ofCategory(SiteCategory.PHOTO).first().id == "4khd")
        assertEquals(SiteRegistry.all.size, SiteCategory.entries.sumOf { SiteRegistry.ofCategory(it).size })
        assertEquals("写真集", SiteRegistry.FOURKHD.category.itemNoun)
        // The photo archive reads page by page like a gallery, but it is not a comic.
        assertEquals(MediaKind.GALLERY, SiteRegistry.FOURKHD.mediaKind)
        assertEquals(SiteCategory.PHOTO, SiteRegistry.FOURKHD.category)
    }

    @Test fun imageHostsRouteToTheArchiveWithoutSessionCookies() {
        assertEquals(SiteRegistry.FOURKHD, SiteRegistry.forMediaHost("i0.wp.com"))
        assertEquals(SiteRegistry.FOURKHD, SiteRegistry.forMediaHost("pic.4khd.com"))
        assertEquals(SiteRegistry.FOURKHD, SiteRegistry.forHost("www.4khd.com"))
        assertNull(SiteRegistry.forHost("18comic.vip"))
    }
}
