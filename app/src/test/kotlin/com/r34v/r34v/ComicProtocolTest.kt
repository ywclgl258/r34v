package com.r34v.r34v

import com.google.gson.JsonParser
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ComicProtocolTest {
    @Test fun languageFiltersKeepSearchCategoryAndPopularSemantics() {
        val old = com.google.gson.Gson().fromJson("""{"kind":"LATEST","target":"","sort":"NEWEST","page":1,"cursor":""}""", FeedRequest::class.java)
        assertEquals(GalleryLanguage.ALL, old.language)
        val chinese = NhentaiProtocol.resolve(FeedRequest(language = GalleryLanguage.CHINESE)).toHttpUrl()
        assertEquals("/api/v2/search", chinese.encodedPath)
        assertEquals("language:chinese", chinese.queryParameter("query"))
        val search = NhentaiProtocol.resolve(FeedRequest(FeedKind.MODEL, "City Artist", page = 2, language = GalleryLanguage.JAPANESE)).toHttpUrl()
        assertEquals("artist:\"City Artist\" language:japanese", search.queryParameter("query")); assertEquals("2", search.queryParameter("page"))
        val popular = NhentaiProtocol.resolve(FeedRequest(FeedKind.POPULAR, language = GalleryLanguage.CHINESE)).toHttpUrl()
        assertEquals("popular-today", popular.queryParameter("sort"))
        assertEquals("category:manga language:chinese", NhentaiProtocol.resolve(FeedRequest(FeedKind.CATEGORY, "manga", language = GalleryLanguage.CHINESE)).toHttpUrl().queryParameter("query"))
    }
    @Test fun galleryCoverMetadataReservesTheCorrectMasonryHeight() {
        val old = com.google.gson.Gson().fromJson("""{"id":"old"}""", Video::class.java)
        assertFalse(old.hasThumbDimensions); assertEquals(2f/3f, old.thumbAspectRatio, .001f)
        assertEquals(.5f, Video(thumbWidth = 400, thumbHeight = 800).thumbAspectRatio, .001f)
        val data = hitomi.apply { getAsJsonArray("files")[0].asJsonObject.apply { addProperty("width", 800); addProperty("height", 1600) } }
        assertEquals(.5f, HitomiProtocol.gallery(data, HitomiProtocol.routing(gg)).entry.thumbAspectRatio, .001f)
    }
    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject
    private val cdn = NhentaiProtocol.cdn(json("""{"image_servers":["https://i1.nhentai.net"],"thumb_servers":["https://t2.nhentai.net"]}"""))
    private val nh = """{"id":8800,"media_id":"9900","title":{"english":"City sketchbook","japanese":"城市速写"},"thumbnail":{"path":"galleries/9900/thumb.webp.webp"},"num_pages":2,"num_favorites":7,"tags":[{"type":"artist","name":"City Artist"},{"type":"language","name":"english"}],"pages":[{"number":1,"path":"galleries/9900/1.webp"},{"number":2,"path":"galleries/9900/2.png"}]}"""
    private val hash = "0".repeat(61) + "abc"
    private val gg = """gg={m:function(g){var o=0; switch(g){case 3243: case 123: o=1;break;}return o;},s:function(h){},b:'123456/'};"""
    private val hitomi get() = json("""{"id":"8800","title":"City sketchbook","japanese_title":"城市速写","type":"manga","language":"english","files":[{"hash":"$hash"},{"hash":"${"f".repeat(64)}"}],"artists":[{"artist":"City Artist"}],"tags":[{"tag":"city sketch","female":"0"}]}""")

    @Test fun nhUsesV2EndpointsAndPreservesSearchSyntax() {
        val latest = NhentaiProtocol.resolve(FeedRequest(page = 2)).toHttpUrl()
        assertEquals("/api/v2/galleries", latest.encodedPath); assertEquals("2", latest.queryParameter("page"))
        val q = NhentaiProtocol.resolve(FeedRequest(FeedKind.SEARCH, "language:english -artist:test pages:>10", Sort.POPULARITY)).toHttpUrl()
        assertEquals("/api/v2/search", q.encodedPath); assertEquals("popular", q.queryParameter("sort")); assertEquals("language:english -artist:test pages:>10", q.queryParameter("query"))
        assertEquals("artist:\"City Artist\"", NhentaiProtocol.resolve(FeedRequest(FeedKind.MODEL, "City Artist")).toHttpUrl().queryParameter("query"))
        assertThrows(IllegalArgumentException::class.java) { NhentaiProtocol.resolve(FeedRequest(page = 0)) }
    }
    @Test fun nhCdnAcceptsOnlySiteImageServers() {
        assertEquals("https://i1.nhentai.net", cdn.images.single())
        for (bad in listOf("http://i1.nhentai.net", "https://i1.nhentai.net.attacker.invalid", "https://api.github.com")) {
            assertThrows(IllegalStateException::class.java) { NhentaiProtocol.cdn(json("""{"image_servers":["$bad"],"thumb_servers":["https://t1.nhentai.net"]}""")) }
        }
    }
    @Test fun nhDetailAndImageFollowV2PathsAndCorrectlyBoundPages() {
        val d = NhentaiProtocol.gallery(json(nh), cdn)
        assertEquals(MediaKind.GALLERY, d.entry.mediaKind); assertEquals("nhentai", d.entry.site)
        assertEquals("https://t2.nhentai.net/galleries/9900/thumb.webp", d.entry.thumb)
        assertEquals(2, d.pageCount); assertEquals("City Artist", d.groups.getValue("画师").single().slug)
        val image = NhentaiProtocol.image(json(nh), 2, cdn)
        assertEquals("https://i1.nhentai.net/galleries/9900/2.png", image.imageUrl); assertNull(image.nextUrl)
        assertTrue(image.previousUrl!!.endsWith("/1/")); assertTrue(image.reloadUrl!!.contains("reload=1"))
        assertThrows(IllegalArgumentException::class.java) { NhentaiProtocol.image(json(nh), 3, cdn) }
    }
    @Test fun nhListPaginationAndPopularHaveDifferentShapes() {
        val card = """{"id":8800,"media_id":"9900","english_title":"City sketchbook","thumbnail":"galleries/9900/thumb.webp","num_pages":2}"""
        assertTrue(NhentaiProtocol.feed(JsonParser.parseString("""{"result":[$card],"num_pages":3}"""), 2, cdn).hasNext)
        assertFalse(NhentaiProtocol.feed(JsonParser.parseString("[$card]"), 1, cdn).hasNext)
        assertTrue(NhentaiProtocol.feed(json("""{"result":[],"num_pages":0}"""), 1, cdn).videos.isEmpty())
    }
    @Test fun nhRejectsForeignGalleryAndMediaPaths() {
        assertNull(NhentaiProtocol.galleryId("https://nhentai.net.attacker.invalid/g/8800/"))
        assertNull(NhentaiProtocol.pageIdentity("https://hitomi.la/g/8800/1/"))
        assertEquals(2, NhentaiProtocol.pageIdentity("https://nhentai.net/g/8800/2/")!!.second)
        assertThrows(IllegalArgumentException::class.java) { NhentaiProtocol.image(json(nh.replace("galleries/9900/2.png", "https://attacker.invalid/image.png")), 2, cdn) }
    }
    @Test fun nhMetadataLinksKeepNamespaces() {
        val url = NhentaiProtocol.gallery(json(nh), cdn).groups.getValue("画师").single().url
        assertEquals("artist:\"City Artist\"", NhentaiProtocol.linkRequest(url)!!.target)
        assertEquals("language:\"english\"", NhentaiProtocol.linkRequest("https://nhentai.net/language/english/")!!.target)
    }
    @Test fun hitomiRoutingParsesOnlyDataAndUsesWebpOnAllAndroidVersions() {
        val route = HitomiProtocol.routing(gg)
        assertEquals(3243, route.bucket(hash))
        assertEquals("https://w2.gold-usergeneratedcontent.net/123456/3243/$hash.webp", route.image(hash))
        assertTrue(route.thumb(hash).contains("btn.gold-usergeneratedcontent.net/webpbigtn/c/ab/"))
        assertTrue(route.image("f".repeat(64)).contains("w1."))
        assertThrows(IllegalStateException::class.java) { HitomiProtocol.routing("eval('remote script')") }
        assertThrows(IllegalArgumentException::class.java) { route.image("../other") }
    }
    @Test fun hitomiIdsAreBigEndianAndRejectTruncatedData() {
        assertEquals(listOf(8800, 42), HitomiProtocol.ids(ByteBuffer.allocate(8).putInt(8800).putInt(42).array()))
        assertThrows(IllegalArgumentException::class.java) { HitomiProtocol.ids(byteArrayOf(1,2,3)) }
        assertThrows(IllegalArgumentException::class.java) { HitomiProtocol.ids(ByteBuffer.allocate(4).putInt(-1).array()) }
    }
    @Test fun hitomiSearchNodeReadsPointersAs64BitAndComparesUnsignedHashes() {
        val b = ByteBuffer.allocate(464).putInt(1).putInt(4).put(byteArrayOf(-1,0,0,0)).putInt(1).putLong(4_000_000_000L).putInt(12)
        repeat(17) { b.putLong(if (it == 1) 928 else 0) }
        val node = HitomiProtocol.node(b.array())
        assertEquals(4_000_000_000L, node.data.single().first); assertEquals(928L, node.children[1])
        assertTrue(HitomiProtocol.compare(byteArrayOf(-1), byteArrayOf(1)) > 0)
        assertThrows(IllegalStateException::class.java) { HitomiProtocol.node(byteArrayOf(0,0)) }
    }
    @Test fun hitomiQueriesUseActualIndexPathsAndRejectUnboundedNegation() {
        assertEquals("https://ltn.gold-usergeneratedcontent.net/index-all.nozomi", HitomiProtocol.nozomi(""))
        assertTrue(HitomiProtocol.nozomi("language:chinese").endsWith("index-chinese.nozomi"))
        assertTrue(HitomiProtocol.nozomi("language:chinese", true).endsWith("popular/year-chinese.nozomi"))
        assertTrue(HitomiProtocol.nozomi("type:manga", true).endsWith("type/popular/year/manga-all.nozomi"))
        assertTrue(HitomiProtocol.nozomi("artist:city sketch").contains("artist/city%20sketch-all.nozomi"))
        assertEquals(listOf(false to "city", false to "artist:city sketch", true to "type:gamecg"), HitomiProtocol.terms("City artist:city_sketch -type:gamecg"))
        assertThrows(IllegalArgumentException::class.java) { HitomiProtocol.terms("-type:gamecg") }
        assertThrows(IllegalArgumentException::class.java) { HitomiProtocol.nozomi("unsupported:anything") }
    }
    @Test fun hitomiGalleryUsesStableReaderPageIdentityAndOwner() {
        val route = HitomiProtocol.routing(gg)
        val detail = HitomiProtocol.gallery(hitomi, route)
        assertEquals("hitomi", detail.entry.site); assertEquals(2, detail.pageCount)
        assertEquals("City Artist", detail.groups.getValue("画师").single().slug)
        val second = HitomiProtocol.image(hitomi, 2, route)
        assertEquals("https://hitomi.la/reader/8800.html#2", second.pageUrl); assertNull(second.nextUrl)
        assertNotEquals(HitomiProtocol.image(hitomi, 1, route).pageUrl, second.pageUrl)
        assertEquals("8800" to 2, HitomiProtocol.pageIdentity(second.reloadUrl!!))
    }
    @Test fun hitomiDoesNotExecuteMetadataAndValidatesGalleryOwnership() {
        val data = "var galleryinfo=$hitomi;"
        assertEquals("8800", HitomiProtocol.info(data, "8800").get("id").asString)
        assertThrows(IllegalStateException::class.java) { HitomiProtocol.info(data, "9999") }
        assertThrows(Exception::class.java) { HitomiProtocol.info("var galleryinfo=(function(){throw 1})();", "8800") }
        assertNull(HitomiProtocol.galleryId("https://hitomi.la.attacker.invalid/galleries/8800.html"))
        assertEquals("8800", HitomiProtocol.galleryId("https://hitomi.la/manga/city-sketch-8800.html"))
    }
    @Test fun hitomiOptionalMetadataArraysMayBeExplicitlyNull() {
        val data = hitomi.apply { add("artists", com.google.gson.JsonNull.INSTANCE); add("groups", com.google.gson.JsonNull.INSTANCE); add("parodys", com.google.gson.JsonNull.INSTANCE) }
        assertEquals(2, HitomiProtocol.gallery(data, HitomiProtocol.routing(gg)).pageCount)
    }
    @Test fun hitomiPopularResultsKeepRankingWithoutBoxingTheEntireIndex() {
        val index = ByteBuffer.allocate(12).putInt(9).putInt(2).putInt(15).array()
        assertEquals(listOf(9,15), HitomiProtocol.orderedMatches(index, setOf(15,9)))
    }
}
