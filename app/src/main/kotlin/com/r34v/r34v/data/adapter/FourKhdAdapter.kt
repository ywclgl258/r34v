package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * 4KHD — a WordPress photo-set archive.
 *
 * One post is one model book ("[JP] Tano Yu – 田野憂 in マイルーム[206MB-76photos]"). The book's images
 * are spread over numbered content pages (`…/jp-tano-yu….html/2`, 20 images each), and every image
 * is served through the Jetpack CDN at `i0.wp.com/pic.4khd.com/...`.
 *
 * The reader is the same one the comics use — a titled, ordered list of images — so a book is
 * exposed as one flat page index while the content page and the offset inside it travel in the
 * page URL fragment. Nothing here has to remember page sizes across a process restart.
 */
class FourKhdAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.CATEGORY, Capability.DOWNLOAD,
    )
    override val sorts = listOf(Sort.NEWEST, Sort.POPULARITY)
    override fun sortsFor(request: FeedRequest) = FourKhdProtocol.sorts(request)
    override val creatorTitle = "模特"
    override val catalogueTitle = "写真分类"

    override val sections = listOf(
        Section("latest", "最新更新", "刚发布的写真", FeedRequest(FeedKind.LATEST)),
        Section("popular", "热门写真", "", FeedRequest(FeedKind.POPULAR)),
        Section("cosplay", "Cosplay", "", FeedRequest(FeedKind.CATEGORY, "cosplay")),
        Section("album", "写真集", "", FeedRequest(FeedKind.CATEGORY, "album")),
    )

    override fun homeUrl() = "${site.base}/"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, page = page)

    override fun resolve(request: FeedRequest): String = FourKhdProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = FourKhdProtocol.title(site, request, sections)

    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(FeedKind.SEARCH, query)

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url, site), url)
        val entries = FourKhdProtocol.feed(doc, site)
        check(entries.isNotEmpty() || FourKhdProtocol.looksEmpty(doc)) {
            "4KHD 未返回可识别的列表，站点可能已改版"
        }
        // A plain numbered pager; the site renders no total-pages element, so the highest numbered
        // link is the last page. Without one this page is also the last one.
        val total = FourKhdProtocol.totalPages(doc)?.coerceAtLeast(request.page) ?: request.page
        return FeedPage(entries, request.page, total, feedTitle(request))
    }

    override suspend fun gallery(url: String): GalleryDetail {
        val canonical = FourKhdProtocol.contentUrl(site, url) ?: error("无法识别 4KHD 内容地址")
        val first = document(canonical, 1)
        val count = FourKhdProtocol.pageCount(first)
        val firstImages = FourKhdProtocol.images(first)
        check(firstImages.isNotEmpty()) { "这条内容没有可读取的图片，可能需要登录或站点已改版" }
        pages["$canonical|1"] = firstImages
        val sizes = (1..count).map { page ->
            imagesOf(canonical, page).size.also { check(it > 0) { "这条内容的第 $page 个分页没有图片" } }
        }
        val pageLinks = PhotoPages.links(sizes) { page ->
            if (page == 1) canonical else "$canonical/$page"
        }
        val total = pageLinks.size
        val entry = Video(
            id = FourKhdProtocol.postId(canonical), title = FourKhdProtocol.title(first), url = canonical,
            thumb = FourKhdProtocol.cover(first).ifBlank { firstImages.first() },
            duration = "$total 张", views = FourKhdProtocol.postedOn(first),
            type = FourKhdProtocol.CATEGORY, site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = 850, thumbHeight = 1158,
        )
        return GalleryDetail(
            entry = entry, pageCount = total, pageLinks = pageLinks, previewPageSize = firstImages.size,
            description = "共 $total 张 · $count 个内容分页", groups = emptyMap(),
        )
    }

    override suspend fun galleryImage(url: String): GalleryImage {
        val identity = FourKhdProtocol.imageIdentity(site, url) ?: error("无法识别 4KHD 图片地址")
        // The encoded page and offset are normally exact; a page that turned out shorter than the
        // first one is followed forward instead of failing the whole read.
        var page = identity.page
        var offset = identity.offset
        repeat(4) {
            val images = imagesOf(identity.canonical, page)
            check(images.isNotEmpty()) { "这条内容的第 $page 个分页没有图片，请刷新后重试" }
            if (offset in images.indices) return GalleryImage(identity.index, url, images[offset], null, null)
            if (offset < 0) error("图片序号无效，请刷新后重试")
            offset -= images.size
            page++
        }
        error("这条内容的分页结构与记录不一致，请刷新后重试")
    }

    override fun linkRequest(url: String): FeedRequest? = FourKhdProtocol.linkRequest(site, url)

    override suspend fun detail(url: String): Detail = error("请使用阅读器打开写真集")

    override suspend fun index(section: String): List<Link> = emptyList()

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("4KHD 支持公开浏览，无需登录")

    /** Content pages are small and revisited while scrolling, so each one is decoded once. */
    private val pages = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, List<String>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 32
    })

    private suspend fun document(canonical: String, page: Int): Document {
        val url = FourKhdProtocol.pageUrl(canonical, 0, page, -1).substringBefore('#')
        return Jsoup.parse(network.html(url, site), url)
    }

    private suspend fun imagesOf(canonical: String, page: Int): List<String> {
        val key = "$canonical|$page"
        return pages[key] ?: FourKhdProtocol.images(document(canonical, page)).also { pages[key] = it }
    }
}

object FourKhdProtocol {
    private val contentPath = Regex("^/content/(\\d+)/([^/]+)\\.html(?:/(\\d+))?/?$")
    private val identity = Regex("^(\\d+)-(\\d+)-(\\d+)$")
    const val CATEGORY = "写真"

    fun sorts(request: FeedRequest): List<Sort> = when (request.kind) {
        FeedKind.SEARCH, FeedKind.CATEGORY -> listOf(Sort.NEWEST)
        FeedKind.POPULAR -> listOf(Sort.POPULARITY)
        else -> listOf(Sort.NEWEST, Sort.POPULARITY)
    }

    /** Everything one gallery page address carries: which book, which image, where it lives. */
    data class Identity(val canonical: String, val index: Int, val page: Int, val offset: Int)

    fun postId(raw: String): String = entryId(raw)

    /**
     * The stable identity of a book is its slug: `/content/<group>/<slug>.html`. The numeric
     * segment is a posting year bucket shared by thousands of posts, so it can never be the key.
     */
    fun entryId(raw: String): String =
        contentPath.find(raw.toHttpUrlOrNull()?.encodedPath.orEmpty())?.groupValues?.get(2)
            ?: error("无法识别 4KHD 内容地址")

    /** Canonical content URL: no page suffix, no fragment. */
    fun contentUrl(site: SiteContext, raw: String): String? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        val match = contentPath.find(url.encodedPath) ?: return null
        return "${site.base}/content/${match.groupValues[1]}/${match.groupValues[2]}.html"
    }

    /**
     * Feed routes. The list and the two fixed sections page through `?query-3-page=N`, while a
     * keyword search has its own path (`/search/<query>/page/N`), because a search URL with a
     * query string returns page 1 again.
     */
    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        fun paged(url: String) = if (page <= 1) url else "$url?query-3-page=$page"
        return when (request.kind) {
            FeedKind.SEARCH -> {
                val query = URLEncoder.encode(request.target.trim(), "UTF-8").replace("+", "%20")
                if (page <= 1) "${site.base}/search/$query" else "${site.base}/search/$query/page/$page"
            }
            FeedKind.CATEGORY -> paged("${site.base}/pages/${request.target}")
            FeedKind.POPULAR -> paged("${site.base}/pages/popular")
            else -> if (request.sort == Sort.POPULARITY) paged("${site.base}/pages/popular") else paged("${site.base}/")
        }
    }

    fun title(site: SiteContext, request: FeedRequest, sections: List<Section>) = when {
        request.kind == FeedKind.SEARCH -> request.target.ifBlank { "搜索写真" }
        request.kind == FeedKind.CATEGORY -> sections.firstOrNull { it.key == request.target }?.title ?: "写真分类"
        request.kind == FeedKind.POPULAR || request.sort == Sort.POPULARITY -> "热门写真"
        else -> "最新写真"
    }

    /**
     * A page address that states its own meaning: the content page is the path suffix the site
     * understands, and `index-page-offset` in the fragment lets the reader resolve the image
     * without knowing how many images any page holds.
     */
    fun pageUrl(canonical: String, index: Int, page: Int, offset: Int): String =
        (if (page <= 1) canonical else "$canonical/$page") + "#$index-$page-$offset"

    /** Flat book index → (content page, offset inside that page). */
    fun position(index: Int, pageSize: Int, pageCount: Int): Pair<Int, Int> {
        require(index > 0 && pageSize > 0) { "书籍位置无效" }
        val page = (((index - 1) / pageSize) + 1).coerceAtMost(pageCount)
        return page to (index - 1 - (page - 1) * pageSize)
    }

    fun imageIdentity(site: SiteContext, raw: String): Identity? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        val canonical = contentUrl(site, raw) ?: return null
        val numbers = identity.matchEntire(url.fragment.orEmpty())?.groupValues ?: return null
        val index = numbers[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val page = numbers[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val offset = numbers[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Identity(canonical, index, page, offset)
    }

    fun feed(doc: Document, site: SiteContext): List<Video> =
        (doc.select("li.wp-block-post") + doc.select("li.post")).distinct()
            .mapNotNull { card(it, site) }.distinctBy { it.id }

    private fun card(card: Element, site: SiteContext): Video? {
        val link = card.selectFirst("h2.wp-block-post-title a[href]")
            ?: card.selectFirst("a[href*=/content/]") ?: return null
        val raw = link.absUrl("href").ifBlank { link.attr("href") }
        val url = contentUrl(site, raw) ?: return null
        val title = text(link.text()).ifBlank { text(card.selectFirst("img")?.attr("alt").orEmpty()) }
        if (title.isBlank()) return null
        val image = card.selectFirst("figure img")
        // The view counter is a bare number next to an icon, and its wrapper also holds the date.
        val views = card.selectFirst(".eyeicon")?.parent()?.text().orEmpty()
            .let { raw -> Regex("(\\d[\\d,]*)").find(raw)?.groupValues?.get(1)?.replace(",", "").orEmpty() }
        return Video(
            id = postId(url), title = title, url = url,
            thumb = image?.absUrl("src").orEmpty().ifBlank { image?.attr("src").orEmpty() },
            views = views,
            rating = card.selectFirst("time")?.text()?.trim().orEmpty(),
            site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = image?.attr("width")?.toIntOrNull() ?: 0,
            thumbHeight = image?.attr("height")?.toIntOrNull() ?: 0,
        )
    }

    fun looksEmpty(doc: Document) = doc.text().let {
        it.contains("Nothing Found", true) || it.contains("没有找到", true) || it.contains("沒有找到", true)
    }

    /** A site search URL collapses to the same search feed the search box produces. */
    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        val parts = url.pathSegments.filter { it.isNotBlank() }
        if (parts.size < 2 || parts[0] != "search") return null
        return FeedRequest(FeedKind.SEARCH, parts[1])
    }

    fun totalPages(doc: Document): Int? = doc.select("a.page-numbers[href]").mapNotNull {
        it.text().trim().replace(",", "").toIntOrNull()
    }.maxOrNull()

    private fun body(doc: Document): Element? = doc.selectFirst("div.entry-content")

    /**
     * Images of one content page, in document order.
     *
     * Each image is wrapped in a link to the full-size file and its `src` is the same file behind
     * the Jetpack wrapper, so the anchor wins when both point at the archive.
     */
    fun images(doc: Document): List<String> {
        val container = body(doc) ?: return emptyList()
        return container.select("img").mapNotNull { image ->
            val source = image.absUrl("src").ifBlank { image.attr("src") }
            val href = image.parent()?.attr("href").orEmpty()
            listOf(href, source).firstOrNull(::isArchiveImage)
        }.distinct()
    }

    private fun isArchiveImage(value: String) =
        value.startsWith("https://") && (value.contains("pic.4khd.com") || value.contains("/4KHD-beautifulGirls"))

    fun pageCount(doc: Document): Int = doc.select("ul.page-links li.numpages").size.coerceAtLeast(1)

    /**
     * The first image of the article, which is also the cover. It is fetched at the same size the
     * cards use so the detail view and the list agree on the crop.
     */
    fun cover(doc: Document): String = images(doc).firstOrNull().orEmpty()

    fun postedOn(doc: Document): String = doc.selectFirst("time[datetime]")?.attr("datetime")?.take(10).orEmpty()

    fun title(doc: Document): String {
        val meta = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val raw = meta.ifBlank { doc.title() }
        return text(raw).replace(Regex("\\s*-\\s*4KHD\\s*$"), "").trim().ifBlank { "未命名写真集" }
    }

    private fun text(raw: String) = Parser.unescapeEntities(raw, false).trim()
}
