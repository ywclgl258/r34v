package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * pornhub.com — 综合视频站。
 *
 * 列表卡片是 `li.pcVideoListItem`：`data-video-vkey` 是稳定 id，`/view_video.php?viewkey=` 是地址，
 * 时长在 `var.duration`，播放量在 `span.views var`。详情页的 `flashvars_<id>` 里带
 * `mediaDefinitions`，其中 `format:"hls"` 的条目就是各档 m3u8，`format:"mp4"` 那条是站内跳转。
 */
class PornhubAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL,
        Capability.CATEGORY, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.NEWEST, Sort.VIEWS, Sort.RATING)
    override fun sortsFor(request: FeedRequest) = when (request.kind) {
        FeedKind.LATEST -> sorts
        FeedKind.POPULAR -> listOf(Sort.VIEWS)
        FeedKind.RATED -> listOf(Sort.RATING)
        else -> listOf(Sort.NEWEST)
    }
    override val creatorTitle = "演员"
    override val catalogueTitle = "分类"

    override val sections = listOf(
        Section("newest", "最新", "刚发布的视频", FeedRequest(FeedKind.LATEST, "cm")),
        Section("mostviewed", "最多播放", "", FeedRequest(FeedKind.POPULAR, "mv")),
        Section("toprated", "评分最高", "", FeedRequest(FeedKind.RATED, "tr")),
    )

    override fun homeUrl() = "${site.base}/video?o=cm"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, "cm", page = page)

    override fun resolve(request: FeedRequest) = PornhubProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = PornhubProtocol.title(request, sections)

    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(
        when (kind) { FeedKind.TAG -> FeedKind.TAG; FeedKind.MODEL -> FeedKind.MODEL; else -> FeedKind.SEARCH },
        query.trim(),
    )

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url, site), url)
        val videos = PornhubProtocol.cards(doc, site)
        check(videos.isNotEmpty() || PornhubProtocol.looksEmpty(doc)) { "Pornhub 未返回可识别的列表，站点可能已改版或要求登录" }
        return FeedPage(videos, request.page, PornhubProtocol.total(doc, request.page), feedTitle(request))
    }

    override suspend fun detail(url: String): Detail {
        val key = PornhubProtocol.viewKey(site, url) ?: error("无法识别 Pornhub 视频地址")
        val target = "${site.base}/view_video.php?viewkey=$key"
        val doc = Jsoup.parse(network.html(target, site), target)
        val streams = PornhubProtocol.streams(doc)
        check(streams.isNotEmpty()) { "详情页没有播放地址，可能需要登录或站点已改版" }
        return PornhubProtocol.detail(doc, target, site, streams)
    }

    override suspend fun index(section: String): List<Link> {
        val (path, segment) = when (section) {
            "categories" -> "/categories" to "/categories/"
            "pornstars" -> "/pornstars" to "/pornstar/"
            else -> return emptyList()
        }
        val doc = Jsoup.parse(network.html(site.base + path, site), site.base + path)
        return doc.select("a[href*=$segment]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter(segment).trimEnd('/').substringBefore('?')
            if (slug.isBlank() || slug.contains('/')) return@mapNotNull null
            Link(slug, MissavProtocol.clean(a.text()).ifBlank { slug }, site.absolute(a.attr("href")))
        }.distinctBy { it.slug }.take(400)
    }

    override fun sectionUrl(key: String): String? = when (key) {
        "categories" -> "${site.base}/categories"
        "pornstars" -> "${site.base}/pornstars"
        else -> null
    }

    override fun linkRequest(url: String): FeedRequest? = PornhubProtocol.linkRequest(site, url)

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("Pornhub 支持公开浏览，无需登录")
}

object PornhubProtocol {
    private val searchPath = Regex("^/video/search/?$")

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        val pagePart = if (page > 1) "&page=$page" else ""
        return when (request.kind) {
            FeedKind.SEARCH -> "${site.base}/video/search?search=${MissavProtocol.encode(request.target.trim())}$pagePart"
            FeedKind.TAG -> "${site.base}/video/search?search=${MissavProtocol.encode(request.target.trim())}&t=tag$pagePart"
            FeedKind.MODEL -> "${site.base}/pornstar/${slug(request.target)}" + if (page > 1) "?page=$page" else ""
            FeedKind.CATEGORY -> "${site.base}/video?c=${request.target.trim()}$pagePart"
            FeedKind.POPULAR -> "${site.base}/video?o=mv$pagePart"
            FeedKind.RATED -> "${site.base}/video?o=tr$pagePart"
            else -> "${site.base}/video?o=${when (request.sort) { Sort.VIEWS -> "mv"; Sort.RATING -> "tr"; else -> "cm" }}$pagePart"
        }
    }

    private fun slug(raw: String) = raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun title(request: FeedRequest, sections: List<Section>): String = when (request.kind) {
        FeedKind.SEARCH -> request.target.ifBlank { "搜索" }
        FeedKind.TAG -> "标签 · ${request.target}"
        FeedKind.MODEL -> "演员 · ${request.target}"
        FeedKind.CATEGORY -> "分类"
        FeedKind.POPULAR -> "最多播放"
        FeedKind.RATED -> "评分最高"
        else -> sections.firstOrNull { it.key == request.target }?.title ?: "最新"
    }

    /**
     * 站点按 UA 返回两套列表：桌面 UA 是 `li.pcVideoListItem`，移动 UA（应用默认发送的）是
     * `li[data-video-vkey]` + `.title a` + `.duration .time`。两套都收，字段名不同但模型一致。
     */
    fun cards(doc: Document, site: SiteContext): List<Video> {
        val desktop = cards(doc.select("li.pcVideoListItem"), site)
        if (desktop.isNotEmpty()) return desktop
        return doc.select("li[data-video-vkey]").mapNotNull { mobile(it, site) }.distinctBy { it.id }
    }

    private fun mobile(item: Element, site: SiteContext): Video? {
        val key = item.attr("data-video-vkey").trim()
        if (!key.matches(Regex("[0-9a-z]{8,24}"))) return null
        val anchor = item.selectFirst("div.title a[href*=viewkey]") ?: item.selectFirst("a[href*=viewkey]")
        val title = MissavProtocol.clean(anchor?.text().orEmpty()).ifBlank {
            anchor?.attr("title").orEmpty().let(MissavProtocol::clean)
        }
        if (title.isBlank()) return null
        val image = item.selectFirst("img[data-image], img[src]")
        val thumb = listOf(image?.attr("data-image"), image?.attr("data-poster"), image?.attr("src"))
            .firstOrNull { !it.isNullOrBlank() }?.let { site.absolute(it!!) }.orEmpty()
        val preview = item.selectFirst("[data-webm]")?.attr("data-webm").orEmpty()
        return Video(
            id = key, title = title, url = "${site.base}/view_video.php?viewkey=$key", thumb = thumb,
            duration = MissavProtocol.clean(item.selectFirst("div.duration span.time, span.time")?.text().orEmpty()),
            views = MissavProtocol.clean(item.selectFirst("div.views")?.text().orEmpty()).substringBefore(' '),
            preview = preview, site = site.id,
        )
    }

    private fun cards(items: List<Element>, site: SiteContext): List<Video> =
        items.mapNotNull { card(it, site) }.distinctBy { it.id }

    private fun card(item: Element, site: SiteContext): Video? {
        val key = item.attr("data-video-vkey").ifBlank {
            Regex("viewkey=([0-9a-z]+)").find(item.selectFirst("a[href*=viewkey]")?.attr("href").orEmpty())
                ?.groupValues?.get(1).orEmpty()
        }
        if (key.isBlank()) return null
        val anchor = item.selectFirst("a[href*=viewkey]")
        val title = MissavProtocol.clean(anchor?.attr("title").orEmpty()).ifBlank { anchor?.text().orEmpty() }
        if (title.isBlank()) return null
        val image = item.selectFirst("img[src]")
        val thumb = listOf(image?.attr("data-image"), image?.attr("src"))
            .firstOrNull { !it.isNullOrBlank() }?.let { site.absolute(it!!) }.orEmpty()
        return Video(
            id = key, title = title, url = "${site.base}/view_video.php?viewkey=$key", thumb = thumb,
            duration = MissavProtocol.clean(item.selectFirst("var.duration")?.text().orEmpty()),
            views = MissavProtocol.clean(item.selectFirst("span.views var")?.text().orEmpty()),
            preview = item.selectFirst("img[data-mediabook]")?.attr("data-mediabook").orEmpty(),
            site = site.id,
        )
    }

    fun viewKey(site: SiteContext, raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val key = url.queryParameter("viewkey")
            ?: url.pathSegments.firstOrNull { it.matches(Regex("[0-9a-z]{10,20}")) && url.queryParameter("viewkey") == null }
        return key?.takeIf { it.matches(Regex("[0-9a-z]{8,24}")) }
    }

    /**
     * `flashvars_<id> = {...}` 里的 `mediaDefinitions`：hls 条目是各档 m3u8（height 即档位），
     * mp4 条目是站内重定向接口。每个条目是扁平对象，值里不带花括号，所以按「height → format →
     * videoUrl」在同一对象内匹配，避免跨条目串位。
     */
    fun streams(doc: Document): List<Stream> {
        val scripts = doc.select("script").map { it.data() }.filter { it.contains("mediaDefinitions") }
        val html = scripts.joinToString("\n").ifBlank { doc.outerHtml() }
        val pattern = Regex(
            "\"height\":(\\d+)[^{}]*?\"format\":\"(hls|mp4)\"[^{}]*?\"videoUrl\":\"(https?:[^\"]+)\"",
        )
        val found = linkedMapOf<String, Stream>()
        for (match in pattern.findAll(html)) {
            val height = match.groupValues[1].toIntOrNull() ?: 0
            val format = match.groupValues[2]
            val url = match.groupValues[3].replace("\\/", "/")
            if (!url.startsWith("http")) continue
            val label = if (format == "mp4") "原画" else "${height}p"
            found.putIfAbsent(label, Stream(label, url))
        }
        if (found.isNotEmpty()) return found.values.sortedByDescending { it.height }
        return Regex("\"videoUrl\":\"(https?:[^\"]+\\.m3u8[^\"]*)\"").findAll(html)
            .map { Stream("自动", it.groupValues[1].replace("\\/", "/")) }
            .distinctBy { it.url }.toList()
    }

    fun detail(doc: Document, url: String, site: SiteContext, streams: List<Stream>): Detail {
        val key = viewKey(site, url) ?: url.substringAfter("viewkey=")
        val heading = MissavProtocol.clean(doc.selectFirst("h1.title span, h1.title")?.text().orEmpty())
            .ifBlank { MissavProtocol.clean(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()) }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val groups = linkedMapOf<String, List<Link>>()
        val models = doc.select("a[href^='/pornstar/'], a[href^='/model/']").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/pornstar/", a.attr("href").substringAfter("/model/")).trimEnd('/')
            val label = MissavProtocol.clean(a.text())
            if (slug.isBlank() || label.isBlank()) null else Link(slug, label, site.absolute(a.attr("href")))
        }.distinctBy { it.slug }.take(12)
        if (models.isNotEmpty()) groups["演员"] = models
        val categories = doc.select("a[href*=categories/], a[href*=video?c=]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("categories/", a.attr("href").substringAfter("c=")).trimEnd('/')
            val label = MissavProtocol.clean(a.text())
            if (slug.isBlank() || label.isBlank()) null else Link(slug, label, site.absolute(a.attr("href")))
        }.distinctBy { it.slug }.take(16)
        if (categories.isNotEmpty()) groups["分类"] = categories
        val tags = doc.select("a[href*=tags/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("tags/").trimEnd('/')
            val label = MissavProtocol.clean(a.text())
            if (slug.isBlank() || label.isBlank()) null else Link(slug, label, site.absolute(a.attr("href")))
        }.distinctBy { it.slug }.take(16)
        if (tags.isNotEmpty()) groups["标签"] = tags
        val related = cards(doc, site).filter { it.id != key }
        return Detail(
            video = Video(
                id = key, title = heading.ifBlank { "视频 $key" }, url = url, thumb = site.absolute(poster),
                site = site.id,
            ),
            streams = streams, groups = groups,
            description = MissavProtocol.clean(doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()),
            related = related, comments = emptyList(), downloads = emptyList(),
        )
    }

    fun total(doc: Document, page: Int): Int {
        val numbers = doc.select("a[href*=page=], li.page_number a[href]").mapNotNull {
            it.text().trim().toIntOrNull()
                ?: Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        return (numbers.maxOrNull() ?: page).coerceAtLeast(page)
    }

    fun looksEmpty(doc: Document) = doc.select("li.pcVideoListItem").isEmpty() &&
        doc.select("li[data-video-vkey]").isEmpty() &&
        (doc.text().contains("No results", true) || doc.text().contains("没有找到", true))

    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        if (searchPath.matches(url.encodedPath)) {
            return url.queryParameter("search")?.takeIf { it.isNotBlank() }?.let { FeedRequest(FeedKind.SEARCH, it) }
        }
        val parts = url.pathSegments.filter { it.isNotBlank() }
        return when (parts.firstOrNull()) {
            "pornstar", "model" -> parts.getOrNull(1)?.let { FeedRequest(FeedKind.MODEL, it.replace('-', ' ')) }
            "video" -> url.queryParameter("c")?.let { FeedRequest(FeedKind.CATEGORY, it) }
            else -> null
        }
    }
}
