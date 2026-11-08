package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * Jable — 番号式 JAV 站。
 *
 * 站点主体长期挂在 Cloudflare 托管挑战后面，浏览器以外的请求一律拿到挑战页，因此这里接入的是
 * 同一内容库的 MacCMS 前台（`jable.vip`）：列表是 `.video-img-box`，作品页 `/vodplay/<id>-1-1/`
 * 把播放地址写成 `player_aaaa.url` 里的 m3u8 直链。`jable.tv` 保留在 `mirrors` 里，用于识别用户
 * 从浏览器分享进来的原站链接。
 */
class JableAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.CATEGORY, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.NEWEST, Sort.POPULARITY)
    override val catalogueTitle = "分类"

    override val sections = listOf(
        Section("new", "最近更新", "刚上线的作品", FeedRequest(FeedKind.LATEST, "new")),
        Section("hot", "最高人气", "", FeedRequest(FeedKind.POPULAR, "hot")),
        Section("c28", "中文字幕", "", FeedRequest(FeedKind.CATEGORY, "28")),
        Section("c21", "日本有码", "", FeedRequest(FeedKind.CATEGORY, "21")),
        Section("c22", "日本无码", "", FeedRequest(FeedKind.CATEGORY, "22")),
        Section("c20", "国产", "", FeedRequest(FeedKind.CATEGORY, "20")),
        Section("c23", "欧美", "", FeedRequest(FeedKind.CATEGORY, "23")),
        Section("c24", "动漫", "", FeedRequest(FeedKind.CATEGORY, "24")),
    )

    override fun homeUrl() = "${site.base}/label/new/"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, "new", page = page)

    override fun sortsFor(request: FeedRequest) = JableProtocol.sorts(request)

    override fun resolve(request: FeedRequest) = JableProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = JableProtocol.title(request, sections)

    override fun searchRequest(query: String, kind: FeedKind) =
        FeedRequest(if (kind == FeedKind.SEARCH) FeedKind.SEARCH else FeedKind.SEARCH, query.trim(), Sort.NEWEST)

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url, site), url)
        val videos = JableProtocol.cards(doc, site)
        check(videos.isNotEmpty() || JableProtocol.looksEmpty(doc)) { "Jable 未返回可识别的列表，站点可能已改版" }
        val total = JableProtocol.lastPage(doc)?.coerceAtLeast(request.page) ?: request.page
        return FeedPage(videos, request.page, total, feedTitle(request))
    }

    override suspend fun detail(url: String): Detail {
        val id = JableProtocol.videoId(site, url) ?: error("无法识别 Jable 作品地址")
        val target = "${site.base}/vodplay/$id-1-1/"
        val doc = Jsoup.parse(network.html(target, site), target)
        val streams = JableProtocol.streams(doc)
        check(streams.isNotEmpty()) { "详情页没有播放地址，可能需要 VIP 权限或站点已改版" }
        return JableProtocol.detail(doc, target, site, streams)
    }

    override suspend fun index(section: String): List<Link> = emptyList()

    override fun linkRequest(url: String): FeedRequest? = JableProtocol.linkRequest(site, url)

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("Jable 支持公开浏览，无需登录")
}

/** Jable 的路由与解析。 */
object JableProtocol {
    private val playPath = Regex("^/vodplay/(\\d+)-\\d+-\\d+/?$")
    private val searchPath = Regex("^/vodsearch/([^/]+?)(?:-{2,}\\d+-{1,}-{1,})?/?$")

    fun sorts(request: FeedRequest): List<Sort> = when (request.kind) {
        FeedKind.POPULAR -> listOf(Sort.POPULARITY)
        FeedKind.SEARCH -> listOf(Sort.NEWEST, Sort.POPULARITY)
        else -> listOf(Sort.NEWEST)
    }

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        val sorted = if (request.sort == Sort.POPULARITY) "-hits" else "-time"
        return when (request.kind) {
            FeedKind.SEARCH -> {
                val query = URLEncoder.encode(request.target.trim(), "UTF-8")
                val path = if (page <= 1) "/vodsearch/$query$sorted----------/"
                else "/vodsearch/$query$sorted-----${page}---/"
                site.base + path
            }
            FeedKind.CATEGORY -> paged("${site.base}/vodtype/${request.target}/", page)
            FeedKind.POPULAR -> paged("${site.base}/label/hot/", page)
            else -> {
                val key = request.target.ifBlank { "new" }.removePrefix("c").let { key ->
                    if (request.target.startsWith("c")) "vodtype/$key" else "label/$key"
                }
                paged(if (key.startsWith("vodtype/")) "${site.base}/$key/" else "${site.base}/$key/", page)
            }
        }
    }

    private fun paged(url: String, page: Int) = if (page <= 1) url else "${url.trimEnd('/')}/page/$page/"

    fun title(request: FeedRequest, sections: List<Section>): String = when (request.kind) {
        FeedKind.SEARCH -> request.target.ifBlank { "搜索作品" }
        FeedKind.CATEGORY -> sections.firstOrNull { it.key == "c${request.target}" }?.title ?: "分类"
        FeedKind.POPULAR -> "最高人气"
        else -> sections.firstOrNull { it.key == request.target }?.title ?: "最近更新"
    }

    /** 列表卡片：`.video-img-box`，标题 `h6.title a`，封面在 `img.lazyload[data-src]`。 */
    fun cards(doc: Document, site: SiteContext): List<Video> =
        doc.select("div.video-img-box").mapNotNull { card(it, site) }.distinctBy { it.id }

    private fun card(card: Element, site: SiteContext): Video? {
        val link = card.selectFirst("h6.title a[href]") ?: card.selectFirst("a[href*=vodplay]") ?: return null
        val id = videoId(site, link.attr("href").ifBlank { link.absUrl("href") }) ?: return null
        val title = clean(link.text()).ifBlank { clean(card.selectFirst("img")?.attr("alt").orEmpty()) }
        if (title.isBlank()) return null
        val image = card.selectFirst("img.lazyload, img")
        val thumb = listOf(image?.attr("data-src"), image?.attr("src"))
            .firstOrNull { !it.isNullOrBlank() && !it.startsWith("/template/") }
            ?.let { site.absolute(it!!) }.orEmpty()
        val stats = card.select("p.sub-title span").map { clean(it.text()) }.filter { it.isNotBlank() }
        return Video(
            id = id, title = title, url = "${site.base}/vodplay/$id-1-1/", thumb = thumb,
            views = stats.firstOrNull().orEmpty(), rating = stats.getOrNull(1).orEmpty(), site = site.id,
        )
    }

    fun videoId(site: SiteContext, raw: String): String? {
        if (raw.isBlank()) return null
        val candidate = if (raw.startsWith("/")) site.base.trimEnd('/') + raw else raw
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        return playPath.find(url.encodedPath)?.groupValues?.get(1)
    }

    /**
     * 播放配置写在 `<script>var player_aaaa={…}</script>` 里。页面并不总带结尾分号，
     * 所以按花括号配对取值，再从中读 `"url"`，不依赖 JSON 结构（里面还嵌着 vod_data）。
     */
    fun streams(doc: Document): List<Stream> {
        val html = doc.outerHtml()
        val marker = html.indexOf("player_aaaa")
        if (marker < 0) return emptyList()
        val open = html.indexOf('{', marker)
        if (open < 0) return emptyList()
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in open until html.length) {
            val ch = html[i]
            if (escaped) { escaped = false; continue }
            if (inString) {
                if (ch == '\\') escaped = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end < 0) return emptyList()
        val payload = html.substring(open, end + 1)
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return emptyList()
        if (!url.startsWith("http")) return emptyList()
        return listOf(Stream("自动", url))
    }

    fun detail(doc: Document, url: String, site: SiteContext, streams: List<Stream>): Detail {
        val id = playPath.find(url.toHttpUrlOrNull()?.encodedPath.orEmpty())?.groupValues?.get(1) ?: url.substringAfterLast('/')
        val image = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty().ifBlank {
            doc.selectFirst("img.lazyload")?.attr("data-src").orEmpty()
        }
        val groups = linkedMapOf<String, List<Link>>()
        val tags = doc.select("a[href*=vodsearch]").mapNotNull { a ->
            val slug = clean(a.text())
            if (slug.isBlank()) null else Link(MissavProtocol.encode(slug), slug, "${site.base}/vodsearch/${MissavProtocol.encode(slug)}-time----------/")
        }.distinctBy { it.slug }.take(24)
        if (tags.isNotEmpty()) groups["标签"] = tags
        val categories = doc.select("a[href*=vodtype]").mapNotNull { a ->
            val id2 = Regex("/vodtype/(\\d+)/").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val label = clean(a.text())
            if (label.isBlank() || label == "更多") null else Link(id2, label, "${site.base}/vodtype/$id2/")
        }.distinctBy { it.slug }
        if (categories.isNotEmpty()) groups["分类"] = categories
        val related = doc.select("div.video-img-box").mapNotNull { card(it, site) }.distinctBy { it.id }
        return Detail(
            video = Video(
                id = id, title = title(doc, id), url = url,
                thumb = site.absolute(image), site = site.id,
                views = clean(doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()).take(120),
            ),
            streams = streams, groups = groups, description = "",
            related = related, comments = emptyList(), downloads = emptyList(),
        )
    }

    /**
     * 作品页没有 `h1`，标题只在 `<title>` 里：`分类《片名》在线播放… | Jable`，
     * 播放器上方则是一条纯文本。优先取页面标题里《》包住的部分，退回播放器标题。
     */
    fun title(doc: Document, id: String): String {
        val raw = doc.title().trim()
        val bracketed = Regex("《(.+?)》").find(raw)?.groupValues?.get(1)?.let(::clean)
        if (!bracketed.isNullOrBlank()) return bracketed
        val heading = clean(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()).ifBlank {
            clean(doc.title()).substringBefore("在线播放").substringBefore(" | Jable")
        }
        return heading.ifBlank { "作品 $id" }
    }

    fun looksEmpty(doc: Document) = doc.select("div.video-img-box").isEmpty() &&
        (doc.text().contains("沒有找到", true) || doc.text().contains("没有找到", true) ||
            doc.text().contains("暂无", true))

    fun lastPage(doc: Document): Int? = doc.select("ul.pagination a[href]").mapNotNull {
        it.text().trim().toIntOrNull()
    }.maxOrNull()

    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val parts = url.pathSegments.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "vodsearch" -> parts.getOrNull(1)?.let {
                val match = Regex("^(.*?)-(time|hits)(-*)?(\\d+)?-*$").matchEntire(it)
                FeedRequest(FeedKind.SEARCH, clean(match?.groupValues?.get(1) ?: it),
                    if (match?.groupValues?.get(2) == "hits") Sort.POPULARITY else Sort.NEWEST,
                    match?.groupValues?.get(4)?.toIntOrNull() ?: 1)
            }
            "vodtype" -> parts.getOrNull(1)?.let { FeedRequest(FeedKind.CATEGORY, it) }
            "label" -> parts.getOrNull(1)?.let { FeedRequest(if (it == "hot") FeedKind.POPULAR else FeedKind.LATEST, it) }
            "vodplay" -> searchPath.find(url.encodedPath)?.let { FeedRequest(FeedKind.SEARCH, it.groupValues[1]) }
            else -> null
        }
    }

    internal fun clean(raw: String) = raw.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
}
