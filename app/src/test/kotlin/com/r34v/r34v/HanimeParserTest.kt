package com.r34v.r34v

import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.adapter.HanimeAdapter
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Parser tests against HTML captured from the live site on 2026-11-03.
 *
 * The reference project (Han1meViewer) targets `div[class^=card-mobile-panel]`, a class the site
 * no longer emits. These fixtures pin the current markup so a future redesign fails here rather
 * than silently producing an empty list.
 */
class HanimeParserTest {
    private val site = SiteRegistry.HANIME1
    private fun fixture(name: String) = javaClass.getResource("/hanime/$name")!!.readText()

    @Test fun listingCardsParseWithEveryField() {
        val videos = HanimeAdapter.parseCards(Jsoup.parse(fixture("search-newest.html")), site)
        assertTrue("expected a full page of cards", videos.size >= 20)
        assertEquals(videos.size, videos.map { it.id }.distinct().size)
        assertTrue(videos.all { it.id.isNotBlank() && it.title.isNotBlank() })
        assertTrue(videos.all { it.url.contains("watch?v=${it.id}") })
        assertTrue(videos.all { it.site == "hanime1" })
        // Duration, rating and views are rendered as siblings inside the card.
        assertTrue(videos.count { it.duration.isNotBlank() } >= videos.size - 2)
        assertTrue(videos.count { it.rating.contains("%") } >= videos.size - 2)
    }

    @Test fun advertCardsAreExcluded() {
        val videos = HanimeAdapter.parseCards(Jsoup.parse(fixture("search-newest.html")), site)
        // The fixture contains two sponsored cards pointing at l.erodalabs.com.
        assertTrue(videos.none { it.url.contains("erodalabs") })
        assertTrue(videos.none { it.id.isBlank() })
    }

    @Test fun detailExposesBothStreamsHighestFirst() {
        val detail = HanimeAdapter.parseDetail(Jsoup.parse(fixture("watch-408990.html")), "https://hanime1.me/watch?v=408990", site)
        assertEquals("408990", detail.video.id)
        assertTrue(detail.streams.size >= 2)
        assertEquals(listOf(720, 480), detail.streams.map { it.height })
        assertTrue(detail.streams.all { it.url.startsWith("https://") })
        assertEquals("720p", detail.streams.first().label)
    }

    @Test fun detailMetadataIsExtracted() {
        val detail = HanimeAdapter.parseDetail(Jsoup.parse(fixture("watch-408990.html")), "https://hanime1.me/watch?v=408990", site)
        assertTrue(detail.video.title.contains("ネルとおうちデート"))
        assertTrue(detail.video.thumb.startsWith("https://"))
        assertTrue("views missing", detail.video.views.contains("萬次"))
        assertTrue("tags missing", detail.groups["标签"]?.isNotEmpty() == true)
        assertTrue("artist missing", detail.groups["作者"]?.isNotEmpty() == true)
    }

    @Test fun aPageWithoutStreamsFailsLoudly() {
        assertThrows(IllegalArgumentException::class.java) {
            HanimeAdapter.parseDetail(Jsoup.parse("<html><body><h1>gone</h1></body></html>"), "https://hanime1.me/watch?v=1", site)
        }
    }

    @Test fun videoIdIsReadFromEitherQueryForm() {
        assertEquals("408990", HanimeAdapter.videoId("https://hanime1.me/watch?v=408990"))
        assertEquals("408990", HanimeAdapter.videoId("/watch?foo=1&v=408990"))
        assertNull(HanimeAdapter.videoId("https://hanime1.me/search?query=x"))
    }

    @Test fun homepageRowsParseWithTheSamSelectors() {
        val videos = HanimeAdapter.parseCards(Jsoup.parse(fixture("home.html")), site)
        assertTrue("home page rows should yield cards", videos.size >= 20)
        assertEquals(videos.size, videos.map { it.id }.distinct().size)
    }
}
