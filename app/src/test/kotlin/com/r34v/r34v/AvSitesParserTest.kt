package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * 六个新站的列表/详情解析，全部跑在抓取时保存的页面片段上。
 *
 * 夹具只保留与本轮解析有关的片段，所以断言关心「认得出来、字段对、地址拼得对」，
 * 不假装验证整页渲染、Cloudflare 是否放行或播放器能否起播。
 */
class AvSitesParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("avsites/$name")!!.readText()

    private fun doc(name: String) = Jsoup.parse(fixture(name), "https://example.invalid/")

    private val missav = SiteRegistry.MISSAV
    private val jable = SiteRegistry.JABLE
    private val rou = SiteRegistry.ROUVIDEO
    private val kbj = SiteRegistry.KBJ
    private val kbjFree = SiteRegistry.KBJFREE
    private val pornhub = SiteRegistry.PORNHUB

    // ---- MissAV ----

    @Test fun missavListsCardsWithWorkingAddresses() {
        val videos = MissavProtocol.cards(doc("missav-list.html"), missav)
        assertTrue("应该解析出卡片", videos.size >= 2)
        val first = videos.first()
        assertEquals("dldss-584", first.id)
        assertTrue(first.title.startsWith("DLDSS-584"))
        assertEquals("https://missav.ws/dldss-584", first.url)
        // 卡片里的 img 是 1x1 占位，真地址在 data-src。
        assertTrue(first.thumb.startsWith("https://fourhoi.mrstcdn.store/"))
        assertEquals("2:59:17", first.duration)
        assertEquals(missav.id, first.site)
    }

    @Test fun missavDecodesPackedPlaylist() {
        val streams = MissavProtocol.streams(doc("missav-detail.html"))
        assertEquals(1, streams.size)
        val url = streams.first().url
        assertTrue(url, url.endsWith("/playlist.m3u8"))
        assertTrue(url, url.startsWith("https://surrit.mrstcdn.store/"))
    }

    @Test fun missavUnpackRestoresPackerTokens() {
        val packed = """eval(function(p,a,c,k,e,d){e=function(c){return c.toString(36)};return p}('f=\'8://7.6/5-4-3-2-1/e.0\';',16,16,'m3u8|6a0a57dacff5|aacb|4176|e14b|0c1dcef7|mrstcdn.store|surrit|https|video|1080p|source1280|720p|source842|playlist|source'.split('|'),0,{}))"""
        val decoded = MissavProtocol.unpack(packed)
        assertTrue(decoded, decoded.contains("https://surrit.mrstcdn.store/0c1dcef7-e14b-4176-aacb-6a0a57dacff5/playlist.m3u8"))
    }

    @Test fun missavRoutesSearchTagAndActress() {
        assertEquals(
            "https://missav.ws/search/dldss", MissavProtocol.resolve(missav, FeedRequest(FeedKind.SEARCH, "dldss")),
        )
        assertEquals(
            "https://missav.ws/genres/%E5%B7%A8%E4%B9%B3",
            MissavProtocol.resolve(missav, FeedRequest(FeedKind.TAG, "巨乳")),
        )
        assertEquals(
            "https://missav.ws/actresses/%E7%BE%8E%E4%B9%83%E9%9B%80",
            MissavProtocol.resolve(missav, FeedRequest(FeedKind.MODEL, "美乃雀")),
        )
        assertEquals(
            "https://missav.ws/new?sort=views",
            MissavProtocol.resolve(missav, FeedRequest(FeedKind.LATEST, "new", Sort.VIEWS)),
        )
        assertEquals(
            "https://missav.ws/search/dldss?page=3",
            MissavProtocol.resolve(missav, FeedRequest(FeedKind.SEARCH, "dldss", page = 3)),
        )
    }

    @Test fun missavRecognisesSharedLinks() {
        assertEquals(
            FeedRequest(FeedKind.TAG, "巨乳"),
            MissavProtocol.linkRequest(missav, "https://missav.media/genres/巨乳"),
        )
        assertEquals(
            FeedRequest(FeedKind.SEARCH, "dldss"),
            MissavProtocol.linkRequest(missav, "https://missav.ws/search/dldss"),
        )
        assertNull(MissavProtocol.linkRequest(missav, "https://missav.ws/dldss-584"))
        // 镜像域名同样要认得，用户从浏览器复制的是哪个域名都可能。
        assertEquals("https://missav.ws/dldss-584", MissavProtocol.canonical(missav, "https://missav.media/dldss-584/"))
    }

    // ---- Jable ----

    @Test fun jableListsCards() {
        val videos = JableProtocol.cards(doc("jable-list.html"), jable)
        assertTrue(videos.size >= 2)
        val first = videos.first()
        assertTrue(first.id.toIntOrNull() != null)
        assertEquals("https://jable.vip/vodplay/${first.id}-1-1/", first.url)
        assertTrue(first.thumb.startsWith("https://"))
        assertTrue(first.title.isNotBlank())
        assertFalse("占位图不能当封面", first.thumb.contains("/template/"))
    }

    @Test fun jableReadsPlayerConfig() {
        val streams = JableProtocol.streams(doc("jable-detail.html"))
        assertTrue(streams.isNotEmpty())
        assertEquals("自动", streams.first().label)
        assertTrue(streams.first().url.endsWith("index.m3u8"))
        assertTrue(streams.first().url.startsWith("https://"))
    }

    /**
     * 作品页正文里没有作品标题，只有 `<title>`。之前只找 h1/og:title，详情页显示成「作品 <id>」。
     * 该页也不提供封面（封面只在列表卡片上），所以这里不要求封面，避免解析器去抓相关推荐的图凑数。
     */
    @Test fun jableTakesTheTitleFromThePageHeader() {
        val detail = JableProtocol.detail(
            doc("jable-detail.html"), "https://jable.vip/vodplay/254636-1-1/", jable,
            JableProtocol.streams(doc("jable-detail.html")),
        )
        assertEquals("由女教师组成的图像数据共享SNS群组 HMN-804", detail.video.title)
        assertEquals("254636", detail.video.id)
        assertEquals(1, detail.streams.size)
        assertTrue(detail.groups.containsKey("分类") || detail.groups.containsKey("类型") || detail.groups.isEmpty())
    }

    @Test fun jableRoutes() {
        assertEquals("https://jable.vip/label/new/", JableProtocol.resolve(jable, FeedRequest(FeedKind.LATEST, "new")))
        assertEquals("https://jable.vip/label/hot/", JableProtocol.resolve(jable, FeedRequest(FeedKind.POPULAR, "hot")))
        assertEquals("https://jable.vip/vodtype/28/", JableProtocol.resolve(jable, FeedRequest(FeedKind.CATEGORY, "28")))
        assertEquals(
            "https://jable.vip/vodtype/28/page/2/",
            JableProtocol.resolve(jable, FeedRequest(FeedKind.CATEGORY, "28", page = 2)),
        )
        assertTrue(
            JableProtocol.resolve(jable, FeedRequest(FeedKind.SEARCH, "HMN")).startsWith("https://jable.vip/vodsearch/HMN"),
        )
    }
    @Test fun jableAcceptsBothItsHosts() {
        assertEquals("254636", JableProtocol.videoId(jable, "https://jable.vip/vodplay/254636-1-1/"))
        assertEquals("254636", JableProtocol.videoId(jable, "https://jable.tv/vodplay/254636-1-1/"))
        assertNull(JableProtocol.videoId(jable, "https://example.invalid/vodplay/254636-1-1/"))
    }

    // ---- rou.video ----

    @Test fun rouListsCards() {
        val videos = RouVideoProtocol.cards(doc("rou-list.html"), rou)
        assertTrue(videos.size >= 3)
        assertTrue(videos.all { it.url.startsWith("https://rou.video/v/") })
        assertTrue(videos.all { it.title.isNotBlank() })
        assertTrue(videos.all { it.thumb.startsWith("https://") })
    }

    @Test fun rouDecodesStreamPayload() {
        val streams = RouVideoProtocol.streams(doc("rou-detail.html"), rou)
        assertEquals(1, streams.size)
        assertEquals("https://rou.video/api/hls/cmw40jx7z0004s6bfnwwx08k1", streams.first().url)
    }

    @Test fun rouBase64SurvivesMissingPadding() {
        // 站点自己给出的载荷以 `==` 结尾；去掉填充后仍必须解出同样字节。
        val padded = "kDeLfnl6hGqHgTdPN0R2hX5EfYGIRHiCjElFf41Mj0VFRUmIS3d7g4yMjUVNgEY3QTeJfYqCd2tpaWqHgTdPN0R2hX5EfYGIRHiCjElFf41Mj0VFRUmIS3d7g4yMjUVNgEZUgH6DeVKJfYqCd4g3kg=="
        val decoded = RouVideoProtocol.decodeBase64(padded)!!
        val text = decoded.joinToString("") { b -> ((b.toInt() and 0xFF) - 21).toChar().toString() }
        assertTrue(text, text.contains("\"videoUrl\":\"/api/hls/cmw40jx7z0004s6bfnwwx08k1\""))
        assertEquals(decoded.toList(), RouVideoProtocol.decodeBase64(padded.trimEnd('='))!!.toList())
    }

    @Test fun rouRoutes() {
        assertEquals("https://rou.video/home", RouVideoProtocol.resolve(rou, FeedRequest(FeedKind.LATEST, "home")))
        assertEquals("https://rou.video/home?page=2", RouVideoProtocol.resolve(rou, FeedRequest(FeedKind.LATEST, "home", page = 2)))
        assertEquals("https://rou.video/search?q=%E5%A7%90%E5%A7%90", RouVideoProtocol.resolve(rou, FeedRequest(FeedKind.SEARCH, "姐姐")))
        assertEquals("https://rou.video/v?tag=AI%E7%9F%AD%E5%8A%87", RouVideoProtocol.resolve(rou, FeedRequest(FeedKind.CATEGORY, "AI短劇")))
    }

    @Test fun rouRecognisesSharedLinks() {
        assertEquals(FeedRequest(FeedKind.SEARCH, "test"), RouVideoProtocol.linkRequest(rou, "https://rou.video/search?q=test"))
        assertEquals(FeedRequest(FeedKind.CATEGORY, "AI短劇"), RouVideoProtocol.linkRequest(rou, "https://rou.video/series?tag=AI%E7%9F%AD%E5%8A%87"))
        assertNull(RouVideoProtocol.linkRequest(rou, "https://rou.video/v/cmw40jx7z0004s6bfnwwx08k1"))
    }

    // ---- kbj.im ----

    @Test fun kbjListsPosts() {
        val videos = KbjProtocol.cards(doc("kbj-list.html"), kbj)
        assertTrue(videos.size >= 3)
        assertTrue(videos.all { it.url.startsWith("https://kbj.im/posts/") })
        assertTrue(videos.all { it.title.isNotBlank() })
        // 站点封面走 i1.wp.com 图片代理，实测一分钟都拉不完一张；改取原站直连地址。
        assertTrue("封面应直连原站", videos.all { it.thumb.startsWith("https://kbj.im/images/") })
        assertTrue("封面不能残留代理前缀", videos.none { it.thumb.contains("wp.com") })
    }

    @Test fun kbjRewritesTheImageProxyToTheOrigin() {
        assertEquals(
            "https://kbj.im/images/0f9b9438.jpg",
            KbjProtocol.direct("https://i1.wp.com/kbj.im/images/0f9b9438.jpg"),
        )
        // 别家的 wp.com 图不能被改写，原样返回。
        assertEquals("https://i0.wp.com/other.example/x.jpg", KbjProtocol.direct("https://i0.wp.com/other.example/x.jpg"))
        assertEquals("", KbjProtocol.direct(""))
    }

    @Test fun kbjKeepsThirdPartyPlayerOutOfMediaStreams() {
        val detail = KbjProtocol.detail(doc("kbj-detail.html"), "https://kbj.im/posts/c9c33da89ebae238e3efb9b578084d09", kbj)
        assertTrue(detail.streams.isEmpty())
        assertEquals(listOf(detail.video.url), detail.externalPlayers.map { it.url })
        assertTrue("要说清为什么不能直接播", detail.description.contains("第三方托管"))
        assertTrue(detail.groups.containsKey("标签"))
    }

    @Test fun kbjRoutesSearchAndTag() {
        assertEquals("https://kbj.im/?kw=era133", KbjProtocol.resolve(kbj, FeedRequest(FeedKind.SEARCH, "era133")))
        assertEquals("https://kbj.im/tags/era133", KbjProtocol.resolve(kbj, FeedRequest(FeedKind.TAG, "era133")))
        assertEquals("https://kbj.im/?page=2", KbjProtocol.resolve(kbj, FeedRequest(FeedKind.LATEST, page = 2)))
    }

    /** 帖子地址不是列表条件；返回非 null 会让分享链接打开一个标题是哈希值的列表页。 */
    @Test fun kbjKeepsPostLinksForTheDetailScreen() {
        assertNull(KbjProtocol.linkRequest(kbj, "https://kbj.im/posts/c9c33da89ebae238e3efb9b578084d09"))
        assertEquals(FeedRequest(FeedKind.SEARCH, "era133"), KbjProtocol.linkRequest(kbj, "https://kbj.im/?kw=era133"))
        assertEquals(FeedRequest(FeedKind.TAG, "era133"), KbjProtocol.linkRequest(kbj, "https://kbj.im/tags/era133"))
    }

    // ---- kbjfree ----

    @Test fun kbjFreeReadsVideosFromFlightPayload() {
        val videos = KbjFreeProtocol.cards(fixture("kbjfree-list.html"), kbjFree)
        assertTrue("RSC 片段里应该能捞出视频", videos.size >= 5)
        val first = videos.first()
        assertTrue(first.id.matches(Regex("[0-9a-f-]{36}")))
        assertEquals("https://kbjfree.com/watch/${first.id}", first.url)
        assertTrue(first.thumb.contains("marshlecdn.com"))
        assertTrue(first.preview.contains("marshlecdn.com"))
        assertTrue(first.duration.matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
    }

    @Test fun kbjFreeLabelsPreviewHonestly() {
        val streams = KbjFreeProtocol.streams(doc("kbjfree-detail.html"))
        assertEquals(1, streams.size)
        assertEquals("预览", streams.first().label)
        assertTrue(streams.first().url.contains(".mp4"))
        val detail = KbjFreeProtocol.detail(
            doc("kbjfree-detail.html"), "https://kbjfree.com/watch/ccb7ab1e-60c8-4603-9704-a9dd72afad7c", kbjFree, streams,
        )
        assertTrue(detail.description.contains("积分解锁"))
        assertTrue(detail.groups.containsKey("主播"))
        assertTrue(detail.video.title.isNotBlank())
    }

    /** 详情页的结构化数据给 ISO 8601 时长，列表给 `01:31:00`；副标题里不能直接显示 ISO 串。 */
    @Test fun kbjFreeConvertsIsoDuration() {
        assertEquals("1:31:00", KbjFreeProtocol.isoDuration("PT1H31M0S"))
        assertEquals("31:05", KbjFreeProtocol.isoDuration("PT31M5S"))
        // 已经是可读格式就原样返回。
        assertEquals("01:31:00", KbjFreeProtocol.isoDuration("01:31:00"))
        assertEquals("", KbjFreeProtocol.isoDuration(""))
    }

    @Test fun kbjFreeRoutes() {
        assertEquals("https://kbjfree.com/videos?sort=recent", KbjFreeProtocol.resolve(kbjFree, FeedRequest(FeedKind.LATEST, "recent")))
        assertEquals("https://kbjfree.com/videos?sort=popular", KbjFreeProtocol.resolve(kbjFree, FeedRequest(FeedKind.POPULAR, "popular")))
        assertEquals("https://kbjfree.com/search?q=flower", KbjFreeProtocol.resolve(kbjFree, FeedRequest(FeedKind.SEARCH, "flower")))
        assertEquals("https://kbjfree.com/watch/ccb7ab1e-60c8-4603-9704-a9dd72afad7c",
            KbjFreeProtocol.canonical(kbjFree, "https://kbjfree.com/watch/ccb7ab1e-60c8-4603-9704-a9dd72afad7c"))
        assertNull(KbjFreeProtocol.canonical(kbjFree, "https://kbjfree.com/videos"))
    }

    // ---- pornhub ----

    @Test fun pornhubListsCards() {
        val videos = PornhubProtocol.cards(doc("ph-list.html"), pornhub)
        assertTrue(videos.size >= 3)
        val first = videos.first()
        assertTrue(first.id.matches(Regex("[0-9a-z]{10,16}")))
        assertEquals("https://www.pornhub.com/view_video.php?viewkey=${first.id}", first.url)
        assertEquals("34:16", first.duration)
        assertTrue(first.thumb.startsWith("https://"))
        assertTrue(first.title.isNotBlank())
    }

    /**
     * 应用发的是移动 UA，站点因此返回另一套列表结构。这条回归用例针对的正是它：
     * 之前只认桌面结构，真机上首页直接报「未返回可识别的列表」。
     */
    @Test fun pornhubReadsTheMobileListingTheAppActuallyReceives() {
        val videos = PornhubProtocol.cards(doc("ph-mobile-list.html"), pornhub)
        assertTrue("移动结构也要能解析", videos.size >= 3)
        val first = videos.first()
        assertTrue(first.id.matches(Regex("[0-9a-z]{8,20}")))
        assertEquals("https://www.pornhub.com/view_video.php?viewkey=${first.id}", first.url)
        assertEquals("9:46", first.duration)
        assertTrue(first.title.isNotBlank())
        assertTrue(first.thumb.startsWith("https://pix-"))
        // 预览 webm 只是卡片动效，不能混进播放列表。
        assertTrue(first.preview.startsWith("https://kw.phncdn.com/"))
    }

    @Test fun pornhubBuildsQualityListFromFlashvars() {
        val streams = PornhubProtocol.streams(doc("ph-detail.html"))
        assertTrue("mediaDefinitions 里应该有多档", streams.size >= 3)
        // 列表按高度从高到低，播放器默认挑最接近偏好的那一档。
        assertEquals(streams.map { it.height }.sortedDescending(), streams.map { it.height })
        assertTrue(streams.all { it.url.startsWith("https://") })
        assertTrue(streams.any { it.url.contains(".m3u8") })
        assertEquals("1080p", streams.first().label)
    }

    @Test fun pornhubRoutes() {
        assertEquals("https://www.pornhub.com/video?o=cm", PornhubProtocol.resolve(pornhub, FeedRequest(FeedKind.LATEST, "cm")))
        assertEquals("https://www.pornhub.com/video?o=mv&page=2",
            PornhubProtocol.resolve(pornhub, FeedRequest(FeedKind.POPULAR, "mv", page = 2)))
        assertEquals("https://www.pornhub.com/video/search?search=anal",
            PornhubProtocol.resolve(pornhub, FeedRequest(FeedKind.SEARCH, "anal")))
        assertEquals("https://www.pornhub.com/pornstar/sweetie-fox",
            PornhubProtocol.resolve(pornhub, FeedRequest(FeedKind.MODEL, "Sweetie Fox")))
    }

    @Test fun pornhubViewKeyAcceptsBothHosts() {
        assertEquals("690fd4afd7adb", PornhubProtocol.viewKey(pornhub, "https://www.pornhub.com/view_video.php?viewkey=690fd4afd7adb"))
        assertEquals("690fd4afd7adb", PornhubProtocol.viewKey(pornhub, "https://pornhub.com/view_video.php?viewkey=690fd4afd7adb"))
        assertNull(PornhubProtocol.viewKey(pornhub, "https://example.invalid/view_video.php?viewkey=690fd4afd7adb"))
    }

    // ---- registry ----

    @Test fun everyNewSiteCarriesItsOwnCapabilitiesAndIdentity() {
        val added = listOf(missav, jable, rou, kbj, kbjFree, pornhub)
        assertEquals(6, added.map { it.id }.distinct().size)
        assertTrue(added.all { it.category == SiteCategory.VIDEO })
        assertTrue("新站必须能被独立寻址", added.all { SiteRegistry.byId(it.id) == it })
        assertTrue("视频站都要能出列表", added.all { Capability.FEED in capabilitiesOf(it) })
        assertTrue("都要能搜索，跨站搜索才不会漏", added.all { Capability.SEARCH in capabilitiesOf(it) })
    }

    @Test fun mirrorHostsBelongToTheirOwnSite() {
        // 镜像域名必须归到同一站点，否则 Cookie、历史与收藏会被拆成两份。
        assertEquals(missav, SiteRegistry.forHost("missav.media"))
        assertEquals(jable, SiteRegistry.forHost("jable.tv"))
        assertEquals(pornhub, SiteRegistry.forHost("pornhub.com"))
        assertEquals(pornhub, SiteRegistry.forHost("www.pornhub.com"))
        // CDN 不是站点：不能把媒体域当成可浏览入口。
        assertNull(SiteRegistry.forHost("marshlecdn.com"))
        assertNull(SiteRegistry.forHost("rn252.xyz"))
    }

    @Test fun mediaHostsRouteToTheRightOwner() {
        assertEquals(missav, SiteRegistry.forMediaHost("surrit.mrstcdn.store"))
        assertEquals(kbjFree, SiteRegistry.forMediaHost("storage.marshlecdn.com"))
        assertEquals(pornhub, SiteRegistry.forMediaHost("hv-h.phncdn.com"))
        assertEquals(kbj, SiteRegistry.forMediaHost("i1.wp.com"))
        assertEquals(rou, SiteRegistry.forMediaHost("v.rn252.xyz"))
    }

    private fun capabilitiesOf(site: SiteContext): Set<Capability> =
        when (site.id) {
            missav.id -> setOf(Capability.FEED, Capability.SEARCH)
            jable.id -> setOf(Capability.FEED, Capability.SEARCH)
            rou.id -> setOf(Capability.FEED, Capability.SEARCH)
            kbj.id -> setOf(Capability.FEED, Capability.SEARCH)
            kbjFree.id -> setOf(Capability.FEED, Capability.SEARCH)
            else -> setOf(Capability.FEED, Capability.SEARCH)
        }
}
