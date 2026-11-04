package com.r34v.r34v

import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private val site = com.r34v.r34v.data.SiteRegistry.R34V
    @Test fun latestAndPagination() {
        val first = HtmlParser.feed(fixture("latest-p1.html"), FeedRequest(), site)
        val second = HtmlParser.feed(fixture("latest-p2.html"), FeedRequest(page = 2), site)
        assertTrue(first.videos.size >= 20); assertEquals(1, first.current); assertEquals(2, second.current)
        assertTrue(first.total > 100); assertTrue(first.hasNext)
        assertTrue(first.videos.none { it.url in second.videos.map(Video::url) })
        assertEquals(first.videos.size, first.videos.map { it.id }.distinct().size)
        assertTrue(first.videos.all { it.thumb.startsWith("https://") && !it.title.isBlank() })
    }
    @Test fun otherListingKinds() {
        for (name in listOf("tag-sex-p2.html", "search-zelda-p2.html", "model-derpixon.html")) assertTrue(name, HtmlParser.feed(fixture(name), FeedRequest(), site).videos.isNotEmpty())
    }
    @Test fun malformedListingIsNotSilentlyEmpty() {
        assertThrows(IllegalStateException::class.java) { HtmlParser.feed("<h1>Login</h1>", FeedRequest(), site) }
        assertThrows(IllegalStateException::class.java) { HtmlParser.feed("<div class='thumbs'><div class='item'>changed</div></div>", FeedRequest(), site) }
    }
    @Test fun emptySearchIsValid() {
        val page = HtmlParser.feed("<div class='thumbs'></div>", FeedRequest(FeedKind.SEARCH), site)
        assertTrue(page.videos.isEmpty()); assertFalse(page.hasNext)
    }
    @Test fun pageFallbackNeverUsesVideoIds() {
        val html = "<div class='thumbs'><div class='item thumb'><a href='/video/9000000/example/'><div class='thumb_title'>Example</div></a></div></div>"
        assertEquals(1, HtmlParser.feed(html, FeedRequest(), site).total)
    }
    @Test fun detailAndStreamQualities() {
        val detail = HtmlParser.detail(fixture("video-4703101.html"), site = site)
        assertEquals("4703101", detail.video.id)
        assertTrue(detail.video.url.contains("/video/4703101/"))
        assertTrue(detail.streams.size >= 3)
        assertTrue(detail.streams.all { it.url.startsWith("https://") })
        assertEquals(detail.streams.sortedByDescending { it.height }, detail.streams)
        assertTrue(detail.groups.isNotEmpty())
        assertTrue(detail.related.isNotEmpty())
    }
    @Test fun fourGroupsStaySeparate() {
        val detail = HtmlParser.detail(fixture("video-4cat.html"), site = site)
        assertEquals(setOf("角色", "分类", "标签"), detail.groups.keys)
        assertTrue(detail.groups.values.all { it.isNotEmpty() })
        val withArtist = HtmlParser.detail(fixture("video-4703101.html"), site = site)
        assertTrue(withArtist.groups["作者"]!!.all { it.url.contains("/models/") })
        assertTrue(detail.groups["角色"]!!.none { it.url.contains("/characters/") })
    }
    @Test fun flashvarsHandlesEscapesAndBraces() {
        val fields = HtmlParser.flashvars(listOf("""var flashvars = {video_id:'42', video_title:'a } b \'c\'', video_url:'https:\/\/cdn.example/a.mp4?a=1\u0026b=2', video_url_text:'720p'};"""))
        assertEquals("a } b 'c'", fields["video_title"])
        assertEquals("https://cdn.example/a.mp4?a=1&b=2", fields["video_url"])
    }
    @Test fun missingStreamsFails() { assertThrows(IllegalArgumentException::class.java) { HtmlParser.detail("<script>flashvars={video_id:'42'};</script>", site = site) } }
    @Test fun categoryIndexUsesLazyImages() {
        val items = HtmlParser.index(fixture("categories.html"), "categories", site)
        assertEquals(36, items.size); assertTrue(items.count { it.thumb.isNotBlank() } >= 10)
        assertTrue(items.none { it.thumb.startsWith("data:") })
    }
    @Test fun characterWorkIndex() { val items = HtmlParser.works(fixture("top-characters.html"), site); assertTrue(items.size > 5); assertEquals(items.size, items.map { it.slug }.distinct().size) }
    @Test fun bothRankingFormats() {
        assertTrue(HtmlParser.ranking(fixture("top-characters-work.html"), site).size > 10)
        val artists = HtmlParser.ranking(fixture("top-model.html"), site)
        assertTrue(artists.size >= 5); assertEquals(artists.size, artists.map { it.slug }.distinct().size)
    }
}
