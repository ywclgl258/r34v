package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * kbj.im — 韩国 BJ（AfreecaTV / WinkTV 等）录播聚合站，一个帖子就是一段录制。
 *
 * 站点是 Laravel + Tailwind 渲染：列表 `#main .grid a[href*=/posts/]`，播放器由第三方托管并以
 * iframe 嵌进帖子页，播放地址要求「在允许的域内嵌入」，应用内取不到直链。因此这里把播放器
 * 因此把浏览器入口单独交给上层，不把 iframe 网页送进媒体播放或下载队列。
 */
class KbjAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.CATEGORY,
    )

    override val sorts = listOf(Sort.NEWEST)
    override val catalogueTitle = "标签"
    override val creatorTitle = "主播"

    override val sections = listOf(
        Section("latest", "最新录制", "刚上传的录播", FeedRequest(FeedKind.LATEST)),
        Section("tags", "标签索引", "按标签浏览"),
    )

    override fun homeUrl() = "${site.base}/"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, page = page)

    override fun resolve(request: FeedRequest) = KbjProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = KbjProtocol.title(request, sections)

    override fun searchRequest(query: String, kind: FeedKind) =
        FeedRequest(if (kind == FeedKind.TAG) FeedKind.TAG else FeedKind.SEARCH, query.trim())

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url, site), url)
        val videos = KbjProtocol.cards(doc, site)
        check(videos.isNotEmpty() || KbjProtocol.looksEmpty(doc)) { "KBJ 未返回可识别的列表，站点可能已改版" }
        return FeedPage(videos, request.page, KbjProtocol.lastPage(doc)?.coerceAtLeast(request.page) ?: request.page, feedTitle(request))
    }

    override suspend fun detail(url: String): Detail {
        val absolute = KbjProtocol.canonical(site, url) ?: error("无法识别 KBJ 帖子地址")
        val doc = Jsoup.parse(network.html(absolute, site), absolute)
        return KbjProtocol.detail(doc, absolute, site)
    }

    override suspend fun index(section: String): List<Link> {
        if (section != "tags") return emptyList()
        val doc = Jsoup.parse(network.html("${site.base}/tags", site), "${site.base}/tags")
        return doc.select("a[href*=/tags/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/tags/").trimEnd('/').substringBefore('?')
            if (slug.isBlank()) return@mapNotNull null
            Link(slug, MissavProtocol.clean(a.text()).ifBlank { slug }, "${site.base}/tags/$slug")
        }.distinctBy { it.slug }
    }

    override fun sectionUrl(key: String): String? = when (key) {
        "tags" -> "${site.base}/tags"
        else -> null
    }

    override fun linkRequest(url: String): FeedRequest? = KbjProtocol.linkRequest(site, url)

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("KBJ 支持公开浏览，无需登录")
}

object KbjProtocol {
    private val postPath = Regex("^/posts/([a-f0-9]{16,64})/?$")
    private val tagPath = Regex("^/tags/([^/?#]+)/?$")

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        val query = if (page > 1) "?page=$page" else ""
        return when (request.kind) {
            FeedKind.TAG -> "${site.base}/tags/${MissavProtocol.encode(request.target.trim())}" +
                (if (page > 1) "?page=$page" else "")
            FeedKind.SEARCH -> "${site.base}/?kw=${MissavProtocol.encode(request.target.trim())}" +
                (if (page > 1) "&page=$page" else "")
            else -> "${site.base}/$query"
        }
    }

    fun title(request: FeedRequest, sections: List<Section>): String = when (request.kind) {
        FeedKind.SEARCH -> request.target.ifBlank { "搜索录制" }
        FeedKind.TAG -> "标签 · ${request.target}"
        else -> "最新录制"
    }

    /** 列表卡片：`#main .grid > div > div.relative`，标题取自 `h2` 或封面 `alt`。 */
    fun cards(doc: Document, site: SiteContext): List<Video> =
        doc.select("#main a[href*=/posts/]").mapNotNull { anchor -> card(anchor, site) }.distinctBy { it.id }

    private fun card(anchor: Element, site: SiteContext): Video? {
        val url = canonical(site, anchor.attr("href")) ?: return null
        val id = url.substringAfterLast('/')
        val image = anchor.selectFirst("img")
        // 标题在同一个卡片块里，锚点本身只包住封面，所以上溯到最近的容器再找。
        val container = anchor.parent()?.parent() ?: anchor.parent()
        var title = MissavProtocol.clean(
            container?.selectFirst("h1, h2, h3")?.text().orEmpty().ifBlank { image?.attr("alt").orEmpty() },
        )
        if (title.isBlank()) title = MissavProtocol.clean(image?.attr("alt").orEmpty())
        if (title.isBlank()) return null
        val thumb = listOf(image?.attr("data-src"), image?.attr("src"))
            .firstOrNull { !it.isNullOrBlank() }?.let { site.absolute(direct(it!!)) }.orEmpty()
        val tags = container?.select("a[href*=/tags/]")?.map { MissavProtocol.clean(it.text()) }
            ?.filter { it.isNotBlank() }.orEmpty()
        return Video(
            id = id, title = title, url = url, thumb = thumb,
            views = container?.selectFirst("time")?.text().orEmpty().let(::clean),
            type = tags.firstOrNull().orEmpty(), site = site.id,
            thumbWidth = 640, thumbHeight = 360,
        )
    }

    /**
     * 站点的 `<img>` 走 `i1.wp.com/kbj.im/images/...` 的 WordPress 图片代理。实测该代理在移动网络下
     * 一分钟都传不完一张图，而同一张图在原站直连只要零点几秒，所以取回原站地址：把代理前缀剥掉，
     * 只留 `https://<原站主机>/images/...`。
     */
    internal fun direct(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains("wp.com/")) return trimmed
        val after = trimmed.substringAfter(".wp.com/", "")
        if (after.isBlank()) return trimmed
        val host = after.substringBefore('/')
        if (host != "kbj.im" && !host.endsWith(".kbj.im")) return trimmed
        return "https://$after"
    }

    fun canonical(site: SiteContext, raw: String): String? {
        if (raw.isBlank()) return null
        val url = (if (raw.startsWith("/")) site.base.trimEnd('/') + raw else raw).toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val match = postPath.find(url.encodedPath) ?: return null
        return "${site.base}/posts/${match.groupValues[1]}"
    }

    fun detail(doc: Document, url: String, site: SiteContext): Detail {
        val id = url.substringAfterLast('/')
        val heading = MissavProtocol.clean(doc.selectFirst("h1")?.text().orEmpty()).ifBlank { "录制 $id" }
        val date = doc.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
        // 帖子把每个播放器写成 `<button data-url data-type>`，类型 hls 才是直链，iframe 是外站播放器。
        val buttons = doc.select("#players button[data-url]")
        // 按钮文字是服务商缩写（BI/AB），`data-server` 才是线路名，优先用它。
        fun label(button: Element) = MissavProtocol.clean(button.attr("data-server")).ifBlank {
            MissavProtocol.clean(button.text())
        }.ifBlank { "自动" }
        val direct = buttons.filter { it.attr("data-type").equals("hls", true) }
            .map { Stream(label(it), it.absUrl("data-url").ifBlank { it.attr("data-url") }) }
            .filter { it.url.startsWith("http") }
        val embeds = buttons.filter { !it.attr("data-type").equals("hls", true) }
            .map { it.absUrl("data-url").ifBlank { it.attr("data-url") } }
            .filter { it.startsWith("http") }
            .distinct()
        val groups = linkedMapOf<String, List<Link>>()
        val tags = doc.select("#main a[href*=/tags/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/tags/").trimEnd('/')
            if (slug.isBlank()) null else Link(slug, MissavProtocol.clean(a.text()).ifBlank { slug }, "${site.base}/tags/$slug")
        }.distinctBy { it.slug }
        if (tags.isNotEmpty()) groups["标签"] = tags
        val related = doc.select("#main a[href*=/posts/]").mapNotNull { card(it, site) }
            .distinctBy { it.id }.filter { it.id != id }
        val note = if (direct.isEmpty() && embeds.isNotEmpty()) {
            val hosts = embeds.map { it.substringAfter("://").substringBefore('/') }.distinct()
            "该站的播放器由第三方托管（${hosts.joinToString(" / ")}），只允许在站内嵌入，应用无法直接取流。" +
                "请使用「在浏览器打开 KBJ」，在原站选择播放线路。"
        } else ""
        return Detail(
            video = Video(
                id = id, title = heading, url = url,
                thumb = direct(doc.selectFirst("#main img[src]")?.absUrl("src").orEmpty()),
                views = date, site = site.id,
            ),
            streams = direct,
            groups = groups, description = note, related = related,
            comments = emptyList(), downloads = emptyList(),
            externalPlayers = if (embeds.isEmpty()) emptyList() else listOf(Link("browser", "在浏览器打开 KBJ", url)),
        )
    }

    fun lastPage(doc: Document): Int? = doc.select("a[href*=page=]").mapNotNull {
        Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull()

    fun looksEmpty(doc: Document) = doc.select("a[href*=/posts/]").isEmpty() &&
        (doc.text().contains("No posts", true) || doc.text().contains("没有", true))

    /**
     * 只识别「能变成列表」的地址：搜索词和标签。
     *
     * 帖子地址必须返回 null，交给详情页处理。之前把 `/posts/<id>` 当成搜索词，
     * 分享链接进来会打开一个标题是哈希值的列表页，看起来像站点没有内容。
     */
    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        url.queryParameter("kw")?.takeIf { it.isNotBlank() }?.let { return FeedRequest(FeedKind.SEARCH, it) }
        val parts = url.pathSegments.filter { it.isNotBlank() }
        return when (parts.firstOrNull()) {
            "tags" -> parts.getOrNull(1)?.let { FeedRequest(FeedKind.TAG, tagPath.find("/$it/")?.groupValues?.get(1) ?: it) }
            else -> null
        }
    }

    private fun clean(raw: String) = MissavProtocol.clean(raw)
}
