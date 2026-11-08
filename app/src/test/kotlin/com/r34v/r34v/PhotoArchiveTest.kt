package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the photo-archive engine and the four sites it serves.
 *
 * Fixtures are original neutral pages written for these tests, shaped like the live markup observed
 * on 2026-11-04. They pin three things that are easy to get wrong and expensive to notice later:
 * the card selector must not invent items from foreign hosts or download badges, the book's page
 * count must be read rather than guessed, and the image list must be the archive's, not the site's
 * chrome.
 */
class PhotoArchiveTest {
    private fun fixture(name: String) = javaClass.getResource("/photos/$name")!!.readText()

    private fun profile(site: SiteContext, doc: String, page: Int = 1) = Jsoup.parse(doc, site.base)

    // ---- everia.club ------------------------------------------------------------------------

    @Test fun everiaCardsCarryIdentityTitleAndCover() {
        val site = SiteRegistry.EVERIA
        val videos = PhotoProfiles.everia().cards(profile(site, fixture("everia-list.html")), site, site.base)
        assertEquals(2, videos.size)
        val first = videos.first()
        assertEquals("sample-model-photobook-one", first.id)
        assertEquals("Sample Model – PhotoBook One", first.title)
        assertEquals("https://everia.club/2026/11/04/sample-model-photobook-one/", first.url)
        assertTrue(first.thumb.startsWith("https://miya.fujibox.top/"))
        assertEquals("everia", first.site)
        assertEquals(MediaKind.GALLERY, first.mediaKind)
        assertEquals(400, first.thumbWidth); assertEquals(600, first.thumbHeight)
        assertTrue("foreign hosts must be rejected", videos.none { it.url.contains("attacker") })
    }

    @Test fun everiaContentPageReadsOnlyTheArchiveImages() {
        val site = SiteRegistry.EVERIA
        val doc = profile(site, fixture("everia-content-1.html"))
        val images = PhotoProfiles.everia().images(doc)
        assertEquals(12, images.size)
        // 图源域名按帖子轮换（fujibox.top / karubox.top …），所以只要求是正文 figure 里的图片，
        // 不锁定具体 CDN 主机；锁定会在站点换域名时让整帖读不到图。
        assertTrue(images.all { it.startsWith("https://") && it.contains("/wp-content/uploads/") })
        assertEquals("https://karubox.top/wp-content/uploads/2026/11/KOR10A325_1.webp", images.first())
        // Two real pages: the pager lists span + link, and the site renders them both.
        assertEquals(2, PhotoProfiles.everia().pageCount(doc))
        assertEquals("Sample Model - PhotoBook One", PhotoProfiles.everia().title(doc))
    }

    // ---- 4kup.net ---------------------------------------------------------------------------

    @Test fun fourKupCardsReadTitleDateAndThumbnail() {
        val site = SiteRegistry.FOURKUP
        val videos = PhotoProfiles.fourKup().cards(profile(site, fixture("4kup-list.html")), site, site.base)
        assertEquals(2, videos.size)
        assertEquals("sample-set-one", videos.first().id)
        assertEquals("Sample Set One", videos.first().title)
        assertEquals("November 2, 2026", videos.first().rating)
        assertTrue(videos.first().thumb.contains("googleusercontent.com"))
        assertTrue(videos.none { it.url.contains("attacker") })
    }

    @Test fun fourKupGalleryUsesTheFullSizeAnchorNotTheLazyPlaceholder() {
        val site = SiteRegistry.FOURKUP
        val doc = profile(site, fixture("4kup-content.html"))
        val images = PhotoProfiles.fourKup().images(doc)
        assertEquals(3, images.size)
        // The `src` is a data: placeholder; only the anchor holds the real file.
        assertTrue(images.all { it.contains("googleusercontent.com") && !it.startsWith("data:") })
        assertTrue(images.first().endsWith("fixture-001.webp"))
        assertFalse(images.any { it.contains("site-logo") })
        // 封面也必须是真图：取第一个 img 的 src 会拿到 data: 占位符，详情页就成空白封面。
        assertEquals(images.first(), PhotoProfiles.fourKup().cover(doc))
    }

    // ---- cosplaytele.com --------------------------------------------------------------------

    @Test fun cosplayTeleCardsIgnoreTheRelatedStrip() {
        val site = SiteRegistry.COSPLAYTELE
        val videos = PhotoProfiles.cosplayTele().cards(profile(site, fixture("cosplaytele-list.html")), site, site.base)
        assertEquals(2, videos.size)
        assertEquals("sample-gallery", videos.first().id)
        assertTrue(videos.first().title.contains("Sample Cosplayer"))
        assertTrue(videos.first().thumb.contains("/wp-content/uploads/"))
    }

    @Test fun cosplayTeleGalleryStopsBeforeTheRecommendations() {
        val site = SiteRegistry.COSPLAYTELE
        val images = PhotoProfiles.cosplayTele().images(profile(site, fixture("cosplaytele-content.html")))
        assertEquals(3, images.size)
        assertTrue(images.all { it.contains("sample-") })
        assertFalse("related posts are not this set", images.any { it.contains("other-48") })
    }

    @Test fun cosplayTeleVideoIsIndependentOfTheImagePages() {
        val article = "https://cosplaytele.com/sample-gallery/"
        val html = fixture("cosplaytele-content.html").replace("</div>\n    <div class=\"crp_related\">", "</div><p><iframe src=\"https://cossora.stream/embed/33c3b905-5871-4657-9714-6fcfa42148e3\"></iframe></p></div>\n    <div class=\"crp_related\">")
        val entries = CossoraProtocol.videos(html, article)
        assertEquals(1, entries.size)
        assertTrue(com.google.gson.Gson().fromJson("{}", GalleryDetail::class.java).embeddedVideos.isEmpty())
        assertEquals("video-33c3b905-5871-4657-9714-6fcfa42148e3", entries.single().id)
        assertEquals("https://cosplaytele.com/sample-gallery/#video-33c3b905-5871-4657-9714-6fcfa42148e3", entries.single().url)
        assertEquals(entries.single() to CossoraProtocol.embeds(html, article).single(), CossoraProtocol.select(html, article, entries.single().url))
        assertEquals(3, PhotoProfiles.cosplayTele().images(profile(SiteRegistry.COSPLAYTELE, html)).size)
        assertTrue(CossoraProtocol.embeds("<iframe src='https://example.invalid/embed/33c3b905-5871-4657-9714-6fcfa42148e3'>", article).isEmpty())
        val decrypted = CossoraProtocol.decrypt(fixture("cossora-link.txt"), "03c34d8f64f8ff77b5b4d754bc8e25bf")
        assertEquals("https://cossora.stream/api-embed/33c3b905-5871-4657-9714-6fcfa42148e3/index.m3u8?token=fixture", decrypted)
        val player = """<script>const videoURL = '${fixture("cossora-link.txt")}'; const portadaURL = '${fixture("cossora-link.txt")}';
            const videoURLDescrypt = decryptLink(videoURL, '03c34d8f64f8ff77b5b4d754bc8e25bf');
            var config = { file: videoURLDescrypt, defaultQuality: '1920p', type: 'hls' };</script>"""
        val media = CossoraProtocol.media(player, CossoraProtocol.embeds(html, article).single())
        assertEquals("1920p", media.stream.label)
        assertEquals(decrypted, media.stream.url)
        assertEquals(SiteRegistry.COSPLAYTELE, SiteRegistry.forMediaHost("hls-cdn.cossora.stream"))
        val iv = ByteArray(16) { it.toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec("03c34d8f64f8ff77b5b4d754bc8e25bf".toByteArray(), "AES"), javax.crypto.spec.IvParameterSpec(iv))
        }
        val untrusted = java.util.Base64.getEncoder().encodeToString(iv + cipher.doFinal("https://example.invalid/video.m3u8".toByteArray()))
        assertThrows(IllegalStateException::class.java) {
            CossoraProtocol.media(player.replace(fixture("cossora-link.txt"), untrusted), CossoraProtocol.embeds(html, article).single())
        }
    }

    // ---- galleryepic.com --------------------------------------------------------------------

    @Test fun galleryEpicReadsTheWholeBookFromTheServerPayload() {
        val site = SiteRegistry.GALLERYEPIC
        val doc = profile(site, fixture("galleryepic-content.html"))
        val images = GalleryEpicProfile().images(doc)
        // Four previews are rendered; the payload lists six.
        assertEquals(6, images.size)
        assertTrue(images.all { it.startsWith("https://static.galleryepic.xyz/image/") })
        assertEquals("https://static.galleryepic.xyz/image/aaaaaaaa-1111-4111-8111-111111111111", images.first())
        assertEquals("Sample Cosplayer – Sample Cosplay (Series)", GalleryEpicProfile().title(doc))
        assertEquals("2026-11-03", GalleryEpicProfile().meta(doc))
    }

    @Test fun galleryEpicCardsComeFromTheCoversNotTheDownloadBadge() {
        val site = SiteRegistry.GALLERYEPIC
        val videos = GalleryEpicProfile().cards(profile(site, fixture("galleryepic-list.html")), site, site.base)
        assertEquals(2, videos.size)
        assertEquals("10824", videos.first().id)
        assertEquals("Sample Cosplay – Series", videos.first().title)
        assertEquals("56", videos.first().views)
        assertTrue(videos.none { it.url.contains("/download/") })
        assertTrue(videos.none { it.id == "291" })
    }

    @Test fun galleryEpicGroupsExposeCoserCharacterAndSeries() {
        val site = SiteRegistry.GALLERYEPIC
        val groups = GalleryEpicProfile().groups(profile(site, fixture("galleryepic-content.html")), site)
        assertEquals("Sample Cosplayer", groups.getValue("模特").single().label)
        assertEquals(listOf("Sample", "Series"), groups.getValue("标签").map { it.label })
    }

    @Test fun galleryEpicMetadataLinksResolveToTheirOwnFeedsOrDirectories() {
        val site = SiteRegistry.GALLERYEPIC
        val epic = GalleryEpicProfile()
        val links = epic.groups(profile(site, fixture("galleryepic-content.html")), site).values.flatten()
        val requests = links.map { epic.linkRequest(site, it.url)!! }
        assertEquals(listOf(FeedRequest(FeedKind.MODEL, "291"), FeedRequest(FeedKind.CHARACTER, "691"),
            FeedRequest(FeedKind.CATEGORY, "series:108")), requests)
        assertEquals(links.map { it.url }, requests.map { epic.resolve(site, it) })
        assertNull(epic.indexSection(requests[0])); assertNull(epic.indexSection(requests[1]))
        assertEquals("series:108", epic.indexSection(requests[2]))
        assertEquals("https://galleryepic.com/en/coser/291/2", epic.resolve(site, requests[0].copy(page = 2)))
    }

    @Test fun galleryEpicDirectoriesKeepSearchAndPagingInsteadOfPretendingToBeGalleries() {
        val site = SiteRegistry.GALLERYEPIC
        val epic = GalleryEpicProfile()
        val request = FeedRequest(FeedKind.SEARCH, "sample:model", page = 2)
        val section = epic.indexSection(request)!!
        assertEquals("cosers:2:sample:model", section)
        assertEquals("https://galleryepic.com/en/cosers/2?coserName=sample%3Amodel", epic.indexUrl(site, section))
        val doc = Jsoup.parse(fixture("galleryepic-cosers.html"), epic.resolve(site, request))
        val links = epic.indexLinks(doc, site)
        assertEquals(listOf("Sample Cosplayer", "Second Cosplayer"), links.take(2).map { it.label })
        assertTrue(epic.cards(doc, site, doc.location()).isEmpty())
        assertFalse(links.any { it.url.contains("example.invalid") })
        assertEquals(FeedRequest(FeedKind.MODEL, "291"), epic.linkRequest(site, links.first().url))
        val lastPage = links.first { it.slug == "page:22" }
        assertEquals(request.copy(page = 22), epic.linkRequest(site, lastPage.url))
        assertEquals("cosers:22:sample:model", epic.indexSection(epic.linkRequest(site, lastPage.url)!!))
        val series = Jsoup.parse(fixture("galleryepic-series.html"), "${site.base}/en/parody/108")
        val characters = epic.indexLinks(series, site)
        assertEquals(listOf("Sample Character", "Second Character"), characters.map { it.label })
        assertTrue(characters.all { epic.linkRequest(site, it.url)?.kind == FeedKind.CHARACTER })
    }

    @Test fun galleryEpicCoserPaginationStaysWithinTheCurrentModel() {
        val site = SiteRegistry.GALLERYEPIC
        val doc = Jsoup.parse(fixture("galleryepic-list.html"), "${site.base}/en/coser/291/1")
        doc.body().append("<nav><a href='/en/coser/291/3'>3</a><a href='/en/coser/292/9'>9</a></nav>")
        assertEquals(3, GalleryEpicProfile().totalPages(doc))
    }

    // ---- the shared engine ------------------------------------------------------------------

    @Test fun bookPageAddressesSurviveARestart() {
        // Twelve images per content page, two pages: image 13 opens page 2 at offset 0.
        val index = 13; val size = 12; val pages = 2
        val page = (((index - 1) / size) + 1).coerceAtMost(pages)
        val offset = index - 1 - (page - 1) * size
        val canonical = "https://everia.club/2026/11/04/sample-model-photobook-one/"
        val url = PhotoPages.encode("${canonical.trimEnd('/')}/$page/", index, page, offset)
        assertEquals("https://everia.club/2026/11/04/sample-model-photobook-one/2/#13-2-0", url)
        val identity = PhotoPages.decode(SiteRegistry.EVERIA, url) { raw ->
            PhotoProfiles.everia().canonical(SiteRegistry.EVERIA, raw)
        }!!
        assertEquals(canonical, identity.canonical)
        assertEquals(13, identity.index); assertEquals(2, identity.page); assertEquals(0, identity.offset)
    }

    @Test fun shortMiddlePagesProduceExactGlobalIndicesForBothPhotoReaders() {
        val canonical = "https://everia.club/2026/11/04/sample/"
        val links = PhotoPages.links(listOf(12, 6, 12)) { page -> if (page == 1) canonical else "${canonical}${page}/" }
        assertEquals(30, links.size)
        val nineteenth = PhotoProfiles.everia().identity(SiteRegistry.EVERIA, links[18].url)!!
        assertEquals(19, nineteenth.index); assertEquals(3, nineteenth.page); assertEquals(0, nineteenth.offset)
        assertEquals(11, PhotoProfiles.everia().identity(SiteRegistry.EVERIA, links.last().url)!!.offset)
        val fourKhd = "https://www.4khd.com/content/04/sample.html"
        val fourKhdLinks = PhotoPages.links(listOf(12, 6, 12)) { page -> if (page == 1) fourKhd else "$fourKhd/$page" }
        val decoded = FourKhdProtocol.imageIdentity(SiteRegistry.FOURKHD, fourKhdLinks[18].url)!!
        assertEquals(19, decoded.index); assertEquals(3, decoded.page); assertEquals(0, decoded.offset)
    }

    @Test fun photoProfilesOnlyOfferSortsTheirRoutesImplement() {
        val profiles = listOf(PhotoProfiles.everia(), PhotoProfiles.fourKup(), PhotoProfiles.cosplayTele(), GalleryEpicProfile())
        assertTrue(profiles.all { it.sorts == listOf(Sort.NEWEST) })
        assertEquals("最新写真", PhotoProfiles.everia().title(SiteRegistry.EVERIA, FeedRequest(sort = Sort.POPULARITY)))
    }

    @Test fun imageAddressesCannotCrossSites() {
        val site = SiteRegistry.EVERIA
        assertNull(PhotoProfiles.everia().canonical(site, "https://example.invalid/2026/11/04/x/"))
        assertNull(PhotoProfiles.everia().canonical(SiteRegistry.FOURKUP, "https://everia.club/2026/11/04/x/"))
        assertNull(PhotoPages.decode(site, "https://everia.club/page/2/") { PhotoProfiles.everia().canonical(site, it) })
        assertNull(PhotoPages.decode(site, "https://everia.club/2026/11/04/x/#nonsense") { PhotoProfiles.everia().canonical(site, it) })
    }

    @Test fun listingPagesResolveToTheSitesOwnRoutes() {
        val everia = SiteRegistry.EVERIA
        val p = PhotoProfiles.everia()
        assertEquals("https://everia.club/", p.resolve(everia, FeedRequest()))
        assertEquals("https://everia.club/page/3/", p.resolve(everia, FeedRequest(page = 3)))
        assertEquals("https://everia.club/page/2/?s=sample%20model", p.resolve(everia, FeedRequest(FeedKind.SEARCH, "sample model", page = 2)))

        val fourKup = SiteRegistry.FOURKUP
        assertEquals("https://4kup.net/", PhotoProfiles.fourKup().resolve(fourKup, FeedRequest()))
        assertEquals("https://4kup.net/page/2/", PhotoProfiles.fourKup().resolve(fourKup, FeedRequest(page = 2)))

        val epic = SiteRegistry.GALLERYEPIC
        assertEquals("https://galleryepic.com/en/cosplays/1", GalleryEpicProfile().resolve(epic, FeedRequest()))
        assertEquals("https://galleryepic.com/en/cosplays/4", GalleryEpicProfile().resolve(epic, FeedRequest(page = 4)))
    }

    @Test fun searchUrlsCollapseBackIntoTheSearchFeed() {
        assertEquals(FeedRequest(FeedKind.SEARCH, "sample"), PhotoProfiles.fourKup()
            .linkRequest(SiteRegistry.FOURKUP, "https://4kup.net/?s=sample"))
        assertEquals(FeedRequest(FeedKind.SEARCH, "sample"),
            GalleryEpicProfile().linkRequest(SiteRegistry.GALLERYEPIC, "https://galleryepic.com/en/cosers/1?coserName=sample"))
        assertNull(PhotoProfiles.fourKup().linkRequest(SiteRegistry.FOURKUP, "https://example.invalid/?s=sample"))
    }

    @Test fun everyPhotoSiteIsGroupedAsAPhotoArchive() {
        val photos = SiteRegistry.ofCategory(SiteCategory.PHOTO)
        assertEquals(listOf("4khd", "everia", "4kup", "galleryepic", "cosplaytele"), photos.map { it.id })
        assertTrue(photos.all { it.mediaKind == MediaKind.GALLERY })
        assertTrue(photos.all { it.category.itemNoun == "写真集" })
        assertEquals(18, SiteRegistry.all.size)
        assertEquals(SiteRegistry.all.size, SiteCategory.entries.sumOf { SiteRegistry.ofCategory(it).size })
    }

    @Test fun imageHostsRouteToTheirOwnerWithoutSessionCookies() {
        assertEquals(SiteRegistry.FOURKUP, SiteRegistry.forMediaHost("blogger.googleusercontent.com"))
        assertEquals(SiteRegistry.GALLERYEPIC, SiteRegistry.forMediaHost("static.galleryepic.xyz"))
        assertEquals(SiteRegistry.FOURKHD, SiteRegistry.forMediaHost("i0.wp.com"))
        // Everia 的图片域名按帖子轮换，不登记固定媒体域；图片请求由代码显式带站点身份。
        assertNull(SiteRegistry.forMediaHost("karubox.top"))
        assertNull(SiteRegistry.forHost("example.invalid"))
    }
}
