package com.r34v.r34v

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.IwaraProtocol
import com.r34v.r34v.data.adapter.IwaraSignature
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class IwaraContractTest {
    private val site = SiteRegistry.IWARA
    private fun fixture(name: String) = JsonParser.parseString(javaClass.getResource("/iwara/$name")!!.readText())
    private fun metadata() = fixture("list.json").asJsonObject.getAsJsonArray("results")[0].asJsonObject

    @Test fun routesEncodeSearchAndTranslatePagesToZeroBased() {
        val url = IwaraProtocol.resolve(site, FeedRequest(FeedKind.SEARCH, "夜景 & MMD", Sort.RELEVANCE, 3)).toHttpUrl()
        assertEquals("apiq.iwara.tv", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("videos", url.queryParameter("type"))
        assertEquals("夜景 & MMD", url.queryParameter("query"))
        assertEquals("2", url.queryParameter("page"))
        assertNull(url.queryParameter("sort"))
        assertThrows(IllegalArgumentException::class.java) { IwaraProtocol.resolve(site, FeedRequest(page = 0)) }
    }

    @Test fun rankingsTagsAndAuthorsUseIwaraParameters() {
        val tag = IwaraProtocol.resolve(site, FeedRequest(FeedKind.TAG, "blue_archive", Sort.VIEWS)).toHttpUrl()
        assertEquals("blue_archive", tag.queryParameter("tags"))
        assertEquals("views", tag.queryParameter("sort"))
        val author = IwaraProtocol.resolve(site, FeedRequest(FeedKind.MODEL, "user-id")).toHttpUrl()
        assertEquals("user-id", author.queryParameter("user"))
        for ((sort, expected) in listOf(Sort.NEWEST to "date", Sort.TRENDING to "trending", Sort.POPULARITY to "popularity", Sort.RATING to "likes")) {
            assertEquals(expected, IwaraProtocol.resolve(site, FeedRequest(sort = sort)).toHttpUrl().queryParameter("sort"))
        }
    }

    @Test fun feedKeepsMovingCountAndActualSiteIdentity() {
        val first = IwaraProtocol.feed(fixture("list.json"), site, FeedRequest())
        assertEquals(1, first.current); assertEquals(2, first.total); assertTrue(first.hasNext)
        val video = first.videos.single()
        assertEquals("iwara", video.site)
        assertEquals("https://www.iwara.tv/video/Sample9AbCdEf/city-walk", video.url)
        assertEquals("02:05", video.duration)
        assertEquals("https://i.iwara.tv/image/thumbnail/01234567-89ab-cdef-0123-456789abcdef/thumbnail-03.jpg", video.thumb)
        assertTrue(video.preview.endsWith("/preview.webp"))
        val next = fixture("list.json").asJsonObject.apply { addProperty("page", 1); addProperty("count", 41) }
        assertEquals(3, IwaraProtocol.feed(next, site, FeedRequest(page = 2)).total)
    }

    @Test fun customThumbnailUsesItsOwnFileAndEncodedName() {
        val data = metadata().apply { add("customThumbnail", JsonParser.parseString("""{"id":"cover-id","name":"a b.jpg"}""")) }
        assertEquals("https://i.iwara.tv/image/thumbnail/cover-id/a%20b.jpg", IwaraProtocol.video(data, site)!!.thumb)
        assertTrue(IwaraProtocol.video(data, site)!!.preview.endsWith("/preview.webp"))
    }

    @Test fun sourceQualityAndDownloadsStaySeparateAndExcludePreviewAndHttp() {
        val detail = IwaraProtocol.detail(metadata(), fixture("sources.json").asJsonArray, site)
        assertEquals(listOf("1080p 原画", "720p", "360p"), detail.streams.map { it.label })
        assertEquals(listOf(1080, 720, 360), detail.streams.map { it.height })
        assertTrue(detail.streams.all { it.url.startsWith("https://") })
        assertTrue(detail.downloads.first().url.endsWith("?download=1"))
        assertNotEquals(detail.downloads.first().url, detail.streams.first().url)
        assertThrows(IllegalStateException::class.java) { IwaraProtocol.detail(metadata(), JsonArray(), site) }
    }

    @Test fun groupLinksRoundTripWithoutLosingUnderscores() {
        val detail = IwaraProtocol.detail(metadata(), fixture("sources.json").asJsonArray, site)
        val tag = detail.groups.getValue("标签").single()
        assertEquals(FeedRequest(FeedKind.TAG, "city_walk"), IwaraProtocol.linkRequest(site, tag.url))
        val author = detail.groups.getValue("作者").single()
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", author.slug)
        assertEquals(FeedRequest(FeedKind.MODEL, "sample_author"), IwaraProtocol.linkRequest(site, author.url))
        assertNull(IwaraProtocol.linkRequest(site, "https://iwara.tv.attacker.invalid/videos?tags=city"))
        assertEquals("Sample9AbCdEf", IwaraProtocol.videoId(site, detail.video.url))
        assertNull(IwaraProtocol.videoId(site, "https://example.invalid/video/Sample9AbCdEf"))
    }

    @Test fun signatureMatchesARealProtocolVectorAndRejectsForeignFileServers() {
        val url = "https://filesq.iwara.tv/file/01234567-89ab-cdef-0123-456789abcdef?expires=1793712454041&hash=sample"
        assertEquals("eb47d7f3d8d8c188ba4fed04a61da8d9b4822509", IwaraSignature.headers(url, IwaraSignature.DEFAULT_SALT)["X-Version"])
        assertThrows(IllegalArgumentException::class.java) { IwaraSignature.headers(url.replace("filesq.iwara.tv", "example.invalid"), IwaraSignature.DEFAULT_SALT) }
        assertThrows(IllegalArgumentException::class.java) { IwaraSignature.headers(url.substringBefore('?'), IwaraSignature.DEFAULT_SALT) }
    }

    @Test fun frontendRefreshOnlyExtractsExpectedLiteralPatterns() {
        assertEquals("/main.838884031a28ff0e346d.js", IwaraSignature.extractScript("""<script src="/main.838884031a28ff0e346d.js"></script>"""))
        assertEquals("newSaltAbcdef0123456789", IwaraSignature.extractSalt("""var digest = id+"_"+e.expires+"_newSaltAbcdef0123456789";"""))
        assertNull(IwaraSignature.extractScript("""<script src="https://example.invalid/main.abcdef.js"></script>"""))
        assertNull(IwaraSignature.extractSalt("salt='unrelated-value'"))
    }

    @Test fun apiAndMediaHeadersDoNotRegisterCookieOrDnsAliases() {
        assertEquals(site, SiteRegistry.forRequestHost("apiq.iwara.tv"))
        assertEquals(site, SiteRegistry.forMediaHost("himeko.iwara.tv"))
        assertNull(SiteRegistry.forHost("apiq.iwara.tv"))
        assertFalse(SiteRegistry.isRegistered("himeko.iwara.tv"))
        assertNull(SiteRegistry.forMediaHost("iwara.tv.attacker.invalid"))
    }

    @Test fun unexpectedListResponsesFailInsteadOfMasqueradingAsEmptyResults() {
        assertThrows(IllegalArgumentException::class.java) { IwaraProtocol.feed(JsonParser.parseString("""{"message":"errors.invalid"}"""), site, FeedRequest()) }
    }
}
