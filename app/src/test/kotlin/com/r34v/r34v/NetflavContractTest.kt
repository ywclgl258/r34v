package com.r34v.r34v

import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.Sort
import com.r34v.r34v.data.adapter.NetflavProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NetflavContractTest {
    private val site = SiteContext("netflav", "Netflav", "https://netflav.com", "netflav.com")

    @Test fun listingRoutesSelectTheMatchingHomeStateAndPage() {
        val home = page("""{
            "censored": ${listing("city-c")},
            "uncensored": ${listing("city-u")},
            "chinese": ${listing("city-zh")},
            "trending": {"page": 1, "pages": 4, "docs": [{"video": ${card("city-t")}}]}
        }""")
        val sources = listOf(
            Triple(FeedRequest(), "/", "city-c"),
            Triple(FeedRequest(target = "censored"), "/censored", "city-c"),
            Triple(FeedRequest(target = "uncensored"), "/uncensored", "city-u"),
            Triple(FeedRequest(target = "chinese"), "/chinese-sub", "city-zh"),
            Triple(FeedRequest(FeedKind.POPULAR, sort = Sort.TRENDING), "/trending", "city-t"),
        )
        for ((request, path, id) in sources) {
            assertEquals(path, NetflavProtocol.resolve(site, request).toHttpUrl().encodedPath)
            val video = NetflavProtocol.feed(home, site, request).videos.single()
            assertEquals(id, video.id)
            assertEquals("netflav", video.site)
            val next = NetflavProtocol.resolve(site, request.copy(page = 2)).toHttpUrl()
            assertEquals(if (path == "/") "/censored" else path, next.encodedPath)
            assertEquals("2", next.queryParameter("page"))
        }
    }

    @Test fun searchAndFiltersRoundTripAndUseTheirOwnPagedDocuments() {
        val search = FeedRequest(FeedKind.SEARCH, "城市 & river + park", Sort.RELEVANCE, page = 3)
        val searchUrl = NetflavProtocol.resolve(site, search).toHttpUrl()
        assertEquals("/search", searchUrl.encodedPath)
        assertEquals(search.target, searchUrl.queryParameter("keyword"))
        assertEquals("3", searchUrl.queryParameter("page"))
        assertEquals(search, NetflavProtocol.linkRequest(site, searchUrl.toString()))
        assertEquals("1", NetflavProtocol.resolve(site, search.copy(page = 1)).toHttpUrl().queryParameter("page"))
        val results = NetflavProtocol.feed(page("""{"search": ${listing("river-3", 3, 6)}}"""), site, search)
        assertEquals(3, results.current)
        assertEquals(6, results.total)
        assertTrue(results.hasNext)
        assertEquals("river-3", results.videos.single().id)

        val filtered = listOf(FeedKind.CATEGORY to "genre", FeedKind.TAG to "genre", FeedKind.MODEL to "actress")
        for ((kind, parameter) in filtered) {
            val request = FeedRequest(kind, "Sample & 水岸", page = 2)
            val url = NetflavProtocol.resolve(site, request).toHttpUrl()
            assertEquals("/all", url.encodedPath)
            assertEquals(request.target, url.queryParameter(parameter))
            assertEquals("2", url.queryParameter("page"))
            val result = NetflavProtocol.feed(page("""{"all": ${listing("park-2", 2, 2)}}"""), site, request)
            assertEquals(2, result.current)
            assertFalse(result.hasNext)
        }
        assertEquals("/all", NetflavProtocol.resolve(site, FeedRequest(target = "all")).toHttpUrl().encodedPath)
    }

    @Test fun zeroResultsAndMalformedResponsesAreDistinct() {
        val search = FeedRequest(FeedKind.SEARCH, "empty", Sort.RELEVANCE)
        val empty = NetflavProtocol.feed(page("""{"search":{"page":1,"pages":0,"docs":[]}}"""), site, search)
        assertTrue(empty.videos.isEmpty())
        assertFalse(empty.hasNext)

        val invalid = listOf(
            "<html><p>Service unavailable</p></html>",
            "<script id='__NEXT_DATA__'>{</script>",
            page("""{"all": ${listing("wrong-bucket")}}"""),
            page("""{"search":{"page":1,"pages":1,"docs":null}}"""),
            page("""{"search":{"page":1,"pages":1,"docs":[{"_id":"not-a-video-id"}]}}"""),
            page("""{"search": ${listing("unexpected-page", 2, 3)}}"""),
        )
        for (body in invalid) {
            assertThrows(IllegalStateException::class.java) { NetflavProtocol.feed(body, site, search) }
        }
    }

    @Test fun directoriesUsePublishedLinksAndKeepTheirDecodedNames() {
        val body = """
            <a href="/all?genre=Navigation">Navigation link</a>
            <a class="genre_item" href="/all?genre=Urban%20%26%20Nature">Urban &amp; Nature</a>
            <a class="genre_item" href="/all?genre=Urban%20%26%20Nature">Duplicate</a>
            <a class="genre_item" href="https://example.invalid/all?genre=Unrelated">Unrelated</a>
            <a href="/all?actress=Sample%20Person"><img src="/images/person.jpg" alt="Sample Person"></a>
            <a href="/all?actress=">Blank</a>
        """.trimIndent()
        val category = NetflavProtocol.index(body, site, "categories").single()
        assertEquals("Urban & Nature", category.label)
        assertEquals(FeedRequest(FeedKind.CATEGORY, "Urban & Nature"), NetflavProtocol.linkRequest(site, category.url))
        val actor = NetflavProtocol.index(body, site, "models").single()
        assertEquals("Sample Person", actor.label)
        assertEquals("https://netflav.com/images/person.jpg", actor.thumb)
        assertEquals(FeedRequest(FeedKind.MODEL, "Sample Person"), NetflavProtocol.linkRequest(site, actor.url))
        for ((section, path) in listOf("categories" to "/genre", "models" to "/actress")) {
            val url = NetflavProtocol.sectionUrl(site, section)!!
            assertEquals(path, url.toHttpUrl().encodedPath)
            assertEquals(section, NetflavProtocol.indexSection(NetflavProtocol.linkRequest(site, url)!!))
        }
    }

    @Test fun detailsUseVideoIdAndOfferOnlyTheCanonicalOfficialPage() {
        val html = page("""{
            "video": {"isLoading": false, "isError": false, "data": {
                "_id": "database-row", "videoId": "city-detail", "title": "City shadows",
                "preview": "https://images.example.invalid/city.jpg", "duration": "124分鐘",
                "description": "A synthetic fixture", "actors": ["Sample Person", "Sample Person"],
                "tags": ["Urban & Nature"], "src": "", "srcs": [], "otherSrcs": [], "uSrc": [],
                "related1": [${card("city-detail")}, ${card("walk-1")}],
                "related2": [${card("walk-1")}, ${card("walk-2")}]
            }}
        }""") + """<iframe src="https://ads.example.invalid/frame"></iframe>"""
        val official = "https://netflav.com/video?id=city-detail"
        val detail = NetflavProtocol.detail(html, site, "https://www.netflav.com/video?id=city-detail&from=sample#player")
        assertEquals("city-detail", detail.video.id)
        assertEquals("City shadows", detail.video.title)
        assertEquals(official, detail.video.url)
        assertEquals("https://images.example.invalid/city.jpg", detail.video.thumb)
        assertEquals("2:04:00", detail.video.duration)
        assertEquals("A synthetic fixture", detail.description)
        assertEquals("Sample Person", detail.groups.getValue("演员").single().url.toHttpUrl().queryParameter("actress"))
        assertEquals("Urban & Nature", detail.groups.getValue("标签").single().url.toHttpUrl().queryParameter("genre"))
        assertEquals(listOf("walk-1", "walk-2"), detail.related.map { it.id })
        assertEquals(official, detail.externalPlayers.single().url)
        assertTrue(detail.streams.isEmpty())
        assertTrue(detail.downloads.isEmpty())
        assertTrue(detail.video.preview.isEmpty())
        assertNull(NetflavProtocol.linkRequest(site, official))
    }

    @Test fun mismatchedUnavailableAndOffSiteDetailsAreRejected() {
        val url = "/video?id=city-detail"
        for (invalid in listOf(
            "https://netflav.com.example.invalid/video?id=city-detail", "//example.invalid/video?id=city-detail",
            "/all?id=city-detail", "/video?id=%2F", "/video?id=city-detail&id=other", "/video",
        )) {
            assertNull(NetflavProtocol.canonicalVideo(site, invalid))
        }
        assertEquals("${site.base}$url", NetflavProtocol.canonicalVideo(site, url))
        val unavailable = listOf(
            page("""{"video":{"isError":true,"data":${card("city-detail")}}}"""),
            page("""{"video":{"data":null}}"""),
            page("""{"video":{"data":{"videoId":"city-detail","title":"City shadows","isDeleted":true}}}"""),
            page("""{"video":{"data":${card("different-id")}}}"""),
        )
        for (html in unavailable) {
            assertThrows(IllegalStateException::class.java) { NetflavProtocol.detail(html, site, url) }
        }
    }

    private fun page(state: String) =
        """<script id="__NEXT_DATA__" type="application/json">{"props":{"initialState":$state}}</script>"""

    private fun listing(id: String, page: Int = 1, pages: Int = 4) =
        """{"page":$page,"pages":$pages,"docs":[${card(id)}]}"""

    private fun card(id: String) =
        """{"_id":"row-$id","videoId":"$id","title":"City study","preview":"/images/cover.jpg"}"""
}
