package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * Shared engine for the photo-set archives.
 *
 * Every one of these sites is the same shape: one post is one model book, the book's images sit
 * in the article body, and long books are split over numbered content pages. Only the selectors and
 * the route layout differ, so they live in a [PhotoProfile] and the paging, caching and image
 * addressing stay here.
 *
 * A book is exposed to the reader as one flat page index. Each page address carries
 * `#index-page-offset`, which lets the reader resolve an image after a restart without knowing how
 * many images any content page holds.
 */
class PhotoArchiveAdapter(
    override val site: SiteContext,
    private val network: Network,
    private val profile: PhotoProfile,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.CATEGORY, Capability.DOWNLOAD,
    )
    override val sorts get() = profile.sorts
    override fun sortsFor(request: FeedRequest) = profile.sortsFor(request)
    override val creatorTitle = "模特"
    override val catalogueTitle = "写真分类"
    override val sections = profile.sections()

    override fun homeUrl() = profile.resolve(site, FeedRequest())
    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, page = page)
    override fun resolve(request: FeedRequest) = profile.resolve(site, request)
    override fun feedTitle(request: FeedRequest) = profile.title(site, request)
    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(FeedKind.SEARCH, query.trim())
    override fun linkRequest(url: String) = profile.linkRequest(site, url)
    override fun indexSection(request: FeedRequest) = profile.indexSection(request)

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = document(url)
        val entries = profile.cards(doc, site, url)
        check(entries.isNotEmpty() || profile.looksEmpty(doc)) { profile.emptyMessage }
        val total = profile.totalPages(doc)?.coerceAtLeast(request.page) ?: request.page
        return FeedPage(entries, request.page, total, feedTitle(request))
    }

    override suspend fun gallery(url: String): GalleryDetail {
        val canonical = profile.canonical(site, url) ?: error("无法识别 ${site.name} 内容地址")
        val first = document(profile.pageUrl(canonical, 1))
        val pageCount = profile.pageCount(first)
        val firstImages = profile.images(first)
        check(firstImages.isNotEmpty()) { "这条内容没有可读取的图片，可能需要登录或站点已改版" }
        pages["$canonical|1"] = firstImages
        // Every content page contributes its actual size; middle pages can be shorter too.
        val sizes = (1..pageCount).map { page ->
            imagesOf(canonical, page).size.also { check(it > 0) { "这条内容的第 $page 个分页没有图片" } }
        }
        val pageLinks = PhotoPages.links(sizes) { profile.pageUrl(canonical, it) }
        val total = pageLinks.size
        val entry = Video(
            id = profile.postId(canonical), title = profile.title(first).ifBlank { "未命名写真集" },
            url = canonical, thumb = profile.cover(first).ifBlank { firstImages.first() },
            duration = "$total 张", views = profile.meta(first), type = profile.category(first),
            site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = profile.thumbWidth(first), thumbHeight = profile.thumbHeight(first),
        )
        return GalleryDetail(
            entry = entry, pageCount = total, pageLinks = pageLinks, previewPageSize = firstImages.size,
            description = profile.description(first, total, pageCount), groups = profile.groups(first, site),
            videoEntries = articleVideos(first, canonical),
        )
    }

    override suspend fun galleryImage(url: String): GalleryImage {
        val identity = profile.identity(site, url) ?: error("无法识别 ${site.name} 图片地址")
        var page = identity.page
        var offset = identity.offset
        // A page shorter than the first one shifts everything after it; follow forward instead of
        // failing the whole read.
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

    override suspend fun detail(url: String): Detail {
        val canonical = profile.canonical(site, url) ?: error("无法识别 ${site.name} 内容地址")
        val article = document(profile.pageUrl(canonical, 1))
        val (video, embed) = CossoraProtocol.select(article.outerHtml(), canonical, url)
        val media = CossoraProtocol.media(document(embed.url).outerHtml(), embed)
        val title = profile.title(article)
        return Detail(
            video = video.copy(title = "$title · ${video.title}", thumb = media.poster),
            streams = listOf(media.stream), groups = profile.groups(article, site),
            description = "$title 的文章内视频。图片请返回写真集阅读。",
            related = emptyList(), comments = emptyList(), downloads = emptyList(),
        )
    }

    private fun articleVideos(article: Document, canonical: String): List<Video> =
        if (site.id == SiteRegistry.COSPLAYTELE.id) CossoraProtocol.videos(article.outerHtml(), canonical) else emptyList()

    override suspend fun index(section: String): List<Link> {
        val url = profile.indexUrl(site, section) ?: return emptyList()
        val doc = document(url)
        val links = profile.indexLinks(doc, site)
        check(links.isNotEmpty() || profile.looksEmpty(doc)) { profile.emptyMessage }
        return links
    }

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("${site.name} 支持公开浏览，无需登录")

    /** Content pages are small and revisited while scrolling, so each is decoded once. */
    private val pages = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, List<String>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 32
    })

    private suspend fun document(url: String): Document =
        Jsoup.parse(network.html(url, site), url)

    private suspend fun imagesOf(canonical: String, page: Int): List<String> {
        val key = "$canonical|$page"
        return pages[key] ?: profile.images(document(profile.pageUrl(canonical, page)))
            .also { pages[key] = it }
    }
}

/** Shared page-addressing rules for every photo archive. */
object PhotoPages {
    private val identity = Regex("^(\\d+)-(\\d+)-(\\d+)$")

    data class Identity(val canonical: String, val index: Int, val page: Int, val offset: Int)

    /** A page address that states its own meaning: book index, content page, offset. */
    fun encode(url: String, index: Int, page: Int, offset: Int) = "$url#$index-$page-$offset"

    /** Build the flat index from actual page lengths instead of assuming a fixed page size. */
    fun links(sizes: List<Int>, pageUrl: (Int) -> String): List<GalleryPageLink> = buildList {
        require(sizes.isNotEmpty() && sizes.all { it > 0 }) { "内容分页没有图片" }
        sizes.forEachIndexed { page, size ->
            repeat(size) { offset ->
                val index = this.size + 1
                add(GalleryPageLink(index, encode(pageUrl(page + 1), index, page + 1, offset)))
            }
        }
    }

    fun decode(site: SiteContext, raw: String, canonical: (String) -> String?): Identity? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        val canonicalUrl = canonical(raw) ?: return null
        val numbers = identity.matchEntire(url.fragment.orEmpty())?.groupValues ?: return null
        val index = numbers[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val page = numbers[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val offset = numbers[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Identity(canonicalUrl, index, page, offset)
    }
}

/** Everything that differs between two photo archives. */
interface PhotoProfile {
    val emptyMessage: String get() = "未返回可识别的写真列表，站点可能已改版"
    val sorts: List<Sort> get() = listOf(Sort.NEWEST)
    fun sortsFor(request: FeedRequest): List<Sort> = sorts
    fun indexSection(request: FeedRequest): String? = null
    fun indexUrl(site: SiteContext, section: String): String? = null
    fun indexLinks(doc: Document, site: SiteContext): List<Link> = emptyList()

    fun sections(): List<Section>
    fun resolve(site: SiteContext, request: FeedRequest): String
    fun title(site: SiteContext, request: FeedRequest): String
    fun cards(doc: Document, site: SiteContext, url: String): List<Video>
    fun totalPages(doc: Document): Int?
    fun linkRequest(site: SiteContext, raw: String): FeedRequest?
    fun looksEmpty(doc: Document): Boolean

    /** Canonical post address without a content-page suffix and without the fragment. */
    fun canonical(site: SiteContext, raw: String): String?
    fun postId(canonical: String): String
    /** Address of the nth content page of one post. */
    fun pageUrl(canonical: String, page: Int): String
    fun pageCount(doc: Document): Int
    fun images(doc: Document): List<String>
    fun identity(site: SiteContext, raw: String): PhotoPages.Identity?

    fun title(doc: Document): String
    fun cover(doc: Document): String
    fun meta(doc: Document): String
    fun category(doc: Document): String
    fun thumbWidth(doc: Document): Int
    fun thumbHeight(doc: Document): Int
    fun description(doc: Document, total: Int, pages: Int): String
    fun groups(doc: Document, site: SiteContext): Map<String, List<Link>> = emptyMap()
    /** Exact image count when the page itself states it; null means "derive from page sizes". */
    fun totalImages(doc: Document): Int? = null
}

/**
 * The WordPress-shaped archives: everia.club, 4kup.net and cosplaytele.com.
 *
 * Their card markup, article containers and content-page routes differ. Cosplaytele's supported
 * video embeds stay separate from the image-gallery profile and never become gallery pages.
 */
class WordPressPhotoProfile(
    private val latestPath: String = "/",
    private val popularPath: String? = null,
    private val categoryPaths: List<Pair<String, String>> = emptyList(),
    private val itemSelector: String,
    private val linkSelector: String,
    private val thumbSelector: String,
    private val dateSelector: String = "time",
    private val viewsSelector: String = "",
    private val articleSelectors: List<String>,
    private val pageLinkSelector: String = "",
    private val imageSelector: String = "img",
    private val imageHosts: Set<String> = emptySet(),
    private val postPath: Regex,
    private val reservedSegments: Set<String> = emptySet(),
) : PhotoProfile {

    override val sorts get() = if (popularPath == null) listOf(Sort.NEWEST) else listOf(Sort.NEWEST, Sort.POPULARITY)
    override fun sortsFor(request: FeedRequest): List<Sort> = when {
        request.kind in listOf(FeedKind.SEARCH, FeedKind.CATEGORY) -> listOf(Sort.NEWEST)
        request.kind == FeedKind.POPULAR && popularPath != null -> listOf(Sort.POPULARITY)
        else -> sorts
    }

    override fun sections() = buildList {
        add(Section("latest", "最新更新", "刚发布的写真", FeedRequest(FeedKind.LATEST)))
        if (popularPath != null) add(Section("popular", "热门", "", FeedRequest(FeedKind.POPULAR)))
        categoryPaths.forEach { (key, label) -> add(Section(key, label, "", FeedRequest(FeedKind.CATEGORY, key))) }
    }

    override fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        // WordPress puts both the pager and the search on the same `/page/N/` slot.
        fun paged(query: String) = buildString {
            append(site.base)
            if (page > 1) append("/page/$page")
            append("/")
            if (query.isNotBlank()) append("?$query")
        }
        return when (request.kind) {
            FeedKind.SEARCH -> paged("s=" + encoded(request.target))
            FeedKind.CATEGORY -> categoryPaths.firstOrNull { it.first == request.target }
                ?.let { paged("cat=${it.first}") } ?: paged("")
            FeedKind.POPULAR -> if (popularPath != null) paged(popularPath.trimStart('/')) else paged("")
            else -> if (request.sort == Sort.POPULARITY && popularPath != null) paged(popularPath.trimStart('/')) else paged("")
        }
    }

    private fun encoded(query: String) = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    override fun title(site: SiteContext, request: FeedRequest) = when {
        request.kind == FeedKind.SEARCH -> request.target.ifBlank { "搜索写真" }
        request.kind == FeedKind.CATEGORY -> categoryPaths.firstOrNull { it.first == request.target }?.second ?: "写真分类"
        popularPath != null && (request.kind == FeedKind.POPULAR || request.sort == Sort.POPULARITY) -> "热门写真"
        else -> "最新写真"
    }

    override fun cards(doc: Document, site: SiteContext, url: String): List<Video> =
        doc.select(itemSelector).mapNotNull { card(it, site, url) }.distinctBy { it.id }

    private fun card(card: Element, site: SiteContext, base: String): Video? {
        val link = card.selectFirst(linkSelector) ?: return null
        val raw = link.absUrl("href").ifBlank { link.attr("href") }
        val canonical = canonical(site, raw) ?: return null
        val title = text(link.text()).ifBlank { text(card.selectFirst("img")?.attr("alt").orEmpty()) }
        if (title.isBlank()) return null
        val image = card.selectFirst(thumbSelector) ?: card.selectFirst("img")
        return Video(
            id = postId(canonical), title = title, url = canonical,
            thumb = absolute(image, base),
            views = viewsSelector.takeIf { it.isNotBlank() }
                ?.let { card.selectFirst(it)?.text() }
                ?.let { raw -> Regex("(\\d[\\d,]*)").find(raw)?.groupValues?.get(1)?.replace(",", "").orEmpty() }
                .orEmpty(),
            rating = card.selectFirst(dateSelector)?.text()?.trim().orEmpty(),
            site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = image?.attr("width")?.toIntOrNull() ?: 0,
            thumbHeight = image?.attr("height")?.toIntOrNull() ?: 0,
        )
    }

    private fun absolute(image: Element?, base: String): String {
        val element = image ?: return ""
        val raw = element.absUrl("src").ifBlank { element.attr("src") }
        return if (raw.startsWith("http")) raw else runCatching { java.net.URI(base).resolve(raw).toString() }.getOrDefault("")
    }

    override fun totalPages(doc: Document): Int? = doc.select("a[href*=page/], a.page-numbers, a.page-number, a[data-paged]")
        .mapNotNull { Regex("(?:page/|paged=\"?)(\\d+)").find(it.attr("href") + " " + it.attr("data-paged"))?.groupValues?.get(1)?.toIntOrNull() ?: it.text().trim().toIntOrNull() }
        .maxOrNull()

    override fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        url.queryParameter("s")?.takeIf { it.isNotBlank() }?.let { return FeedRequest(FeedKind.SEARCH, it) }
        return null
    }

    override fun looksEmpty(doc: Document) = doc.text().let {
        it.contains("Nothing Found", true) || it.contains("no results", true) || it.contains("没有找到", true)
    }

    override fun canonical(site: SiteContext, raw: String): String? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        val slug = postPath.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        val first = slug.trim('/').split('/').firstOrNull().orEmpty()
        if (first.lowercase() in reservedSegments) return null
        return "${site.base}/$slug/"
    }

    override fun postId(canonical: String) = canonical.trimEnd('/').substringAfterLast('/')

    override fun pageUrl(canonical: String, page: Int) =
        if (page <= 1) canonical else "${canonical.trimEnd('/')}/$page/"

    override fun pageCount(doc: Document): Int =
        if (pageLinkSelector.isBlank()) 1 else doc.select(pageLinkSelector).size.coerceAtLeast(1)

    override fun images(doc: Document): List<String> {
        val container = articleSelectors.firstNotNullOfOrNull { doc.selectFirst(it) } ?: return emptyList()
        return container.select(imageSelector).mapNotNull { image ->
            val source = image.absUrl("src").ifBlank { image.attr("src") }
            val href = image.parent()?.attr("href").orEmpty()
            // The anchor of a thumbnail normally points at the same file, so accepting either is
            // safe; the host filter keeps logos, avatars and ad images out.
            listOf(href, source).firstOrNull { it.startsWith("https://") && accepted(it) }
        }.distinct()
    }

    override fun identity(site: SiteContext, raw: String) = PhotoPages.decode(site, raw, { canonical(site, it) })

    private fun accepted(value: String) = imageHosts.isEmpty() || imageHosts.any { value.contains(it) }

    override fun title(doc: Document): String {
        val meta = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val raw = meta.ifBlank { doc.title() }
        return text(raw).replace(Regex("\\s*[-|–]\\s*[^-|–]*$"), "").trim()
            .ifBlank { text(raw).trim() }
    }

    /**
     * 封面直接用图集的第一张。
     *
     * 不能取“正文里第一个 img”：惰性加载的站点（4KUp）把 `src` 写成 data: 占位符，真实地址在
     * `<a href>` 上，直接取 src 会得到一张永远加载不出来的封面。
     */
    override fun cover(doc: Document): String = images(doc).firstOrNull().orEmpty()

    override fun meta(doc: Document): String =
        doc.selectFirst("time[datetime]")?.attr("datetime")?.take(10).orEmpty()

    override fun category(doc: Document) = "写真"

    override fun thumbWidth(doc: Document): Int =
        (articleSelectors.firstNotNullOfOrNull { doc.selectFirst(it) }?.selectFirst("img"))
            ?.attr("width")?.toIntOrNull() ?: 0

    override fun thumbHeight(doc: Document): Int =
        (articleSelectors.firstNotNullOfOrNull { doc.selectFirst(it) }?.selectFirst("img"))
            ?.attr("height")?.toIntOrNull() ?: 0

    override fun description(doc: Document, total: Int, pages: Int) =
        "共 $total 张" + if (pages > 1) " · $pages 个内容分页" else ""

    private fun text(raw: String) = Parser.unescapeEntities(raw, false).trim()
}

/**
 * galleryepic.com — a Next.js cosplay archive.
 *
 * The page renders only 12 previews and loads the rest with a button, but the full image-id list
 * travels in the server payload of the same response, so the whole book is available without
 * driving the button.
 */
class GalleryEpicProfile : PhotoProfile {
    private val item = Regex("^/en/cosplay/(\\d+)/?$")
    private val imageId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val cdn = "https://static.galleryepic.xyz/image/"

    override fun sections() = listOf(
        Section("latest", "最新更新", "刚发布的写真", FeedRequest(FeedKind.LATEST)),
        Section("cosers", "Coser 索引", "", FeedRequest(FeedKind.CATEGORY, "cosers")),
    )

    override fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        return when (request.kind) {
            FeedKind.SEARCH -> "${site.base}/en/cosers/$page?coserName=" +
                URLEncoder.encode(request.target.trim(), "UTF-8").replace("+", "%20")
            FeedKind.MODEL -> "${site.base}/en/coser/${numericId(request.target)}/$page"
            FeedKind.CHARACTER -> "${site.base}/en/character/${numericId(request.target)}"
            FeedKind.CATEGORY -> if (request.target.startsWith("series:"))
                "${site.base}/en/parody/${numericId(request.target.substringAfter(':'))}"
                else "${site.base}/en/cosers/$page"
            else -> "${site.base}/en/cosplays/$page"
        }
    }

    private fun numericId(value: String) = value.takeIf { it.matches(Regex("[1-9]\\d*")) }
        ?: error("无法识别 GalleryEpic 索引地址")

    override fun indexSection(request: FeedRequest): String? = when {
        request.kind == FeedKind.SEARCH -> "cosers:${request.page}:${request.target}"
        request.kind == FeedKind.CATEGORY && request.target == "cosers" -> "cosers:${request.page}:"
        request.kind == FeedKind.CATEGORY && request.target.startsWith("series:") -> request.target
        else -> null
    }

    override fun indexUrl(site: SiteContext, section: String): String? {
        if (section.startsWith("series:")) return resolve(site, FeedRequest(FeedKind.CATEGORY, section))
        val parts = section.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "cosers") return null
        val page = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return resolve(site, FeedRequest(if (parts[2].isBlank()) FeedKind.CATEGORY else FeedKind.SEARCH,
            if (parts[2].isBlank()) "cosers" else parts[2], page = page))
    }

    override fun indexLinks(doc: Document, site: SiteContext): List<Link> {
        val location = doc.location().toHttpUrlOrNull() ?: return emptyList()
        val series = location.encodedPath.startsWith("/en/parody/")
        val selector = if (series) "a[href^=/en/character/]" else "a[href^=/en/coser/]"
        val entries = doc.select(selector).mapNotNull { anchor ->
            val url = anchor.absUrl("href")
            val request = linkRequest(site, url) ?: return@mapNotNull null
            val label = anchor.selectFirst(if (series) "p" else "h3")?.text()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            Link(request.target, label, url, anchor.selectFirst("img")?.absUrl("src").orEmpty())
        }.distinctBy { it.url }
        if (series) return entries
        val pager = doc.select("a[href]").mapNotNull { anchor ->
            val url = anchor.absUrl("href")
            val request = linkRequest(site, url) ?: return@mapNotNull null
            if (request.kind !in listOf(FeedKind.SEARCH, FeedKind.CATEGORY) || request.target.startsWith("series:") ||
                request.page.toString() != anchor.text().trim() || url == location.toString()) return@mapNotNull null
            // Carry the current search even when a site's pager omits the query string.
            val query = location.queryParameter("coserName").orEmpty()
            val destination = resolve(site, if (query.isBlank()) request else FeedRequest(FeedKind.SEARCH, query, page = request.page))
            Link("page:${request.page}", "第 ${request.page} 页", destination)
        }.distinctBy { it.url }
        return entries + pager
    }

    override fun title(site: SiteContext, request: FeedRequest) = when {
        request.kind == FeedKind.SEARCH -> request.target.ifBlank { "搜索写真" }
        request.kind == FeedKind.CATEGORY && request.target.startsWith("series:") -> "作品角色"
        request.kind == FeedKind.CATEGORY -> "Coser 索引"
        request.kind == FeedKind.MODEL -> "模特写真"
        request.kind == FeedKind.CHARACTER -> "角色写真"
        else -> "最新写真"
    }

    override fun cards(doc: Document, site: SiteContext, url: String): List<Video> =
        doc.select("a[href^=/en/cosplay/]").mapNotNull { anchor ->
            val canonical = canonical(site, anchor.absUrl("href").ifBlank { anchor.attr("href") }) ?: return@mapNotNull null
            val image = anchor.selectFirst("img[src*=static.galleryepic.xyz]") ?: return@mapNotNull null
            // The caption block is the one holding the h3; the photo-count badge is a sibling
            // paragraph inside the cover overlay and must not become the second half of the title.
            val caption = anchor.selectFirst("h3")?.parent()
            val heading = caption?.selectFirst("h3")?.text()?.trim().orEmpty()
            val subtitle = caption?.selectFirst("p")?.text()?.trim().orEmpty()
            val title = listOf(heading, subtitle).filter { it.isNotBlank() }.joinToString(" – ")
            if (title.isBlank()) return@mapNotNull null
            Video(
                id = postId(canonical), title = title, url = canonical,
                thumb = image.absUrl("src").ifBlank { image.attr("src") },
                views = Regex("(\\d+)\\s*P").find(anchor.text())?.groupValues?.get(1).orEmpty(),
                rating = Regex("\\d{1,2}/\\d{1,2}/\\d{4}").find(anchor.text())?.value.orEmpty(),
                site = site.id, mediaKind = MediaKind.GALLERY, thumbWidth = 600, thumbHeight = 800,
            )
        }.distinctBy { it.id }

    override fun totalPages(doc: Document): Int? {
        val path = doc.location().toHttpUrlOrNull()?.encodedPath.orEmpty()
        if (path.startsWith("/en/character/")) return 1
        val prefix = Regex("^/en/coser/\\d+/").find(path)?.value ?: "/en/cosplays/"
        return doc.select("a[href]").mapNotNull {
            val candidate = it.absUrl("href").toHttpUrlOrNull()?.encodedPath.orEmpty()
            candidate.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.trim('/')?.toIntOrNull()
        }.maxOrNull()
    }

    override fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        Regex("^/en/cosers/(\\d+)/?$").matchEntire(url.encodedPath)?.let {
            val page = it.groupValues[1].toIntOrNull()?.takeIf { p -> p > 0 } ?: return null
            val query = url.queryParameter("coserName").orEmpty()
            return FeedRequest(if (query.isBlank()) FeedKind.CATEGORY else FeedKind.SEARCH,
                if (query.isBlank()) "cosers" else query, page = page)
        }
        Regex("^/en/coser/(\\d+)/(\\d+)/?$").matchEntire(url.encodedPath)?.let {
            val page = it.groupValues[2].toIntOrNull()?.takeIf { p -> p > 0 } ?: return null
            return FeedRequest(FeedKind.MODEL, it.groupValues[1], page = page)
        }
        Regex("^/en/(character|parody)/(\\d+)/?$").matchEntire(url.encodedPath)?.let {
            return if (it.groupValues[1] == "character") FeedRequest(FeedKind.CHARACTER, it.groupValues[2])
                else FeedRequest(FeedKind.CATEGORY, "series:${it.groupValues[2]}")
        }
        return null
    }

    override fun looksEmpty(doc: Document) = doc.text().let { it.contains("No results", true) || it.contains("No data", true) }

    override fun canonical(site: SiteContext, raw: String): String? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        val id = item.find(url.encodedPath)?.groupValues?.get(1) ?: return null
        return "${site.base}/en/cosplay/$id"
    }

    override fun postId(canonical: String) = canonical.trimEnd('/').substringAfterLast('/')

    override fun pageUrl(canonical: String, page: Int) = canonical

    override fun pageCount(doc: Document) = 1

    /** The id list in the server payload is the whole book, not the rendered previews. */
    override fun images(doc: Document): List<String> {
        val payload = doc.outerHtml()
        val lists = Regex("\\\\\"images\\\\\":\\[(.*?)\\]").findAll(payload).toList()
            .ifEmpty { Regex("\"images\":\\[(.*?)\\]").findAll(payload).toList() }
        val ids = lists.flatMap { match -> imageId.findAll(match.groupValues[1]).map { it.value }.toList() }
            .filter { it.isNotBlank() }
            .ifEmpty { imageId.findAll(payload).map { it.value }.toList() }
        return ids.distinct().map { "$cdn$it" }
    }

    override fun identity(site: SiteContext, raw: String) = PhotoPages.decode(site, raw, { canonical(site, it) })

    override fun title(doc: Document) =
        text(doc.selectFirst("h1")?.text().orEmpty()).ifBlank { text(doc.title()) }

    override fun cover(doc: Document) =
        doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()

    override fun meta(doc: Document) =
        doc.selectFirst("time[datetime]")?.attr("datetime")?.take(10).orEmpty()

    override fun category(doc: Document) = "Cosplay"

    override fun thumbWidth(doc: Document) = 600
    override fun thumbHeight(doc: Document) = 800

    override fun description(doc: Document, total: Int, pages: Int) =
        doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: "共 $total 张"

    override fun groups(doc: Document, site: SiteContext): Map<String, List<Link>> {
        val groups = linkedMapOf<String, MutableList<Link>>()
        doc.select("dl dt").forEach { term ->
            val label = term.text().trim()
            val value = term.nextElementSibling() ?: return@forEach
            if (label !in setOf("Cosplayer", "Character", "Series")) return@forEach
            value.select("a[href]").forEach { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                if (!href.startsWith("http")) return@forEach
                groups.getOrPut(if (label == "Cosplayer") "模特" else "标签") { mutableListOf() }
                    .add(Link(anchor.text().trim(), anchor.text().trim(), href))
            }
        }
        return groups
    }

    private fun text(raw: String) = Parser.unescapeEntities(raw, false).trim()
}
