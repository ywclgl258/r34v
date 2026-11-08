package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * rou.video — 短视频与 AI 短剧站。
 *
 * 页面由 TanStack Start 服务端渲染：列表卡片是 `a[href^='/v/']`，标题 `h3`，时长与播放量在同一张
 * 卡的角标里。详情页把播放地址放进 `ev:{d:"…",k:N}` 载荷——base64 之后逐字节减去 k，得到
 * `/api/hls/<id>`，也就是站点自己的播放器交给 shaka 的同一个地址。这里不做二次解码：
 * 那个地址本身就是清单入口（站点 302 后返回 master）。
 */
class RouVideoAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.CATEGORY, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.NEWEST)
    override val catalogueTitle = "类型"

    override val sections = listOf(
        Section("home", "为你推荐", "站点自己排的首页流", FeedRequest(FeedKind.LATEST, "home")),
        Section("v", "全部影片", "", FeedRequest(FeedKind.LATEST, "v")),
        Section("category", "探索分类", "按题材与片商浏览"),
        Section("series", "系列", ""),
    )

    override fun homeUrl() = "${site.base}/home"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, "home", page = page)

    override fun resolve(request: FeedRequest) = RouVideoProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = RouVideoProtocol.title(request, sections)

    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(FeedKind.SEARCH, query.trim())

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url, site), url)
        val videos = RouVideoProtocol.cards(doc, site)
        check(videos.isNotEmpty() || RouVideoProtocol.looksEmpty(doc)) { "rou.video 未返回可识别的列表，站点可能已改版" }
        return FeedPage(videos, request.page, RouVideoProtocol.total(doc, request.page), feedTitle(request))
    }

    override suspend fun detail(url: String): Detail {
        val absolute = RouVideoProtocol.canonical(site, url) ?: error("无法识别 rou.video 作品地址")
        val doc = Jsoup.parse(network.html(absolute, site), absolute)
        val streams = RouVideoProtocol.streams(doc, site)
        check(streams.isNotEmpty()) { "详情页没有播放地址，站点可能已改版或该片需要会员" }
        return RouVideoProtocol.detail(doc, absolute, site, streams)
    }

    override suspend fun index(section: String): List<Link> {
        if (section == "series") return seriesIndex()
        if (section != "category") return emptyList()
        val url = "${site.base}/cat"
        val doc = Jsoup.parse(network.html(url, site), url)
        val tags = doc.select("a[href*=series?tag=]").mapNotNull { a ->
            val encoded = a.attr("href").substringAfter("tag=", "").substringBefore('&')
            if (encoded.isBlank()) return@mapNotNull null
            val tag = RouVideoProtocol.decode(encoded)
            Link(tag, MissavProtocol.clean(a.text()).ifBlank { tag }, "${site.base}/v?tag=$encoded")
        }.distinctBy { it.slug }
        if (tags.isNotEmpty()) return tags
        // 分类页把题材写成 `/v?tag=` 链接，站点改版时退回按钮文本，避免整页空白。
        return doc.select("a[href*=tag=]").mapNotNull { a ->
            val slug = RouVideoProtocol.decode(a.attr("href").substringAfter("tag=", "").substringBefore('&'))
            if (slug.isBlank()) null else Link(slug, MissavProtocol.clean(a.text()).ifBlank { slug }, a.absUrl("href"))
        }.distinctBy { it.slug }
    }

    private suspend fun seriesIndex(): List<Link> {
        val url = "${site.base}/cat"
        val doc = Jsoup.parse(network.html(url, site), url)
        return doc.select("a[href^='/s/']").mapNotNull { a ->
            val href = a.attr("href")
            Link(href.substringAfterLast('/'), MissavProtocol.clean(a.text()).ifBlank { "系列" }, site.absolute(href))
        }.distinctBy { it.slug }
    }

    override fun sectionUrl(key: String): String? = when (key) {
        "category" -> "${site.base}/cat"
        "v" -> "${site.base}/v"
        "series" -> "${site.base}/cat"
        else -> null
    }

    override fun linkRequest(url: String): FeedRequest? = RouVideoProtocol.linkRequest(site, url)

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("rou.video 支持公开浏览，无需登录")
}

object RouVideoProtocol {
    private val videoPath = Regex("^/v/([A-Za-z0-9]{16,40})/?$")
    private val seriesPath = Regex("^/s/([A-Za-z0-9]{16,40})/?$")

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        val paged = { url: String -> if (page <= 1) url else if (url.contains('?')) "$url&page=$page" else "$url?page=$page" }
        return when (request.kind) {
            FeedKind.TAG -> paged("${site.base}/s/${MissavProtocol.encode(request.target.trim())}")
            FeedKind.SEARCH -> paged("${site.base}/search?q=${MissavProtocol.encode(request.target.trim())}")
            FeedKind.CATEGORY -> paged(
                request.target.trim().takeIf { it.isNotBlank() }?.let { "${site.base}/v?tag=${MissavProtocol.encode(it)}" }
                    ?: "${site.base}/cat",
            )
            else -> paged("${site.base}/${request.target.ifBlank { "home" }.trim('/')}")
        }
    }

    fun title(request: FeedRequest, sections: List<Section>): String = when (request.kind) {
        FeedKind.TAG -> "系列影片"
        FeedKind.SEARCH -> request.target.ifBlank { "搜索" }
        FeedKind.CATEGORY -> request.target.ifBlank { "探索分类" }
        else -> sections.firstOrNull { it.key == request.target }?.title ?: "全部影片"
    }

    /** 首页与列表页共用 `a[href^='/v/']` 卡片；同一张卡在 hero 里会重复出现，按 id 去重。 */
    fun cards(doc: Document, site: SiteContext): List<Video> =
        doc.select("a[href^='/v/']").mapNotNull { card(it, site) }.distinctBy { it.id }

    private fun card(anchor: Element, site: SiteContext): Video? {
        val url = canonical(site, anchor.attr("href")) ?: return null
        val id = url.substringAfterLast('/')
        val image = anchor.selectFirst("img")
        val title = MissavProtocol.clean(
            anchor.selectFirst("h3")?.text().orEmpty().ifBlank { image?.attr("alt").orEmpty() },
        )
        if (title.isBlank()) return null
        val thumb = listOf(image?.attr("src"), image?.attr("data-src"))
            .firstOrNull { !it.isNullOrBlank() }?.let { site.absolute(it!!) }.orEmpty()
        val labels = anchor.select("span").map { MissavProtocol.clean(it.text()) }.filter { it.isNotBlank() }
        val duration = labels.firstOrNull { Regex("^(\\d+小時)?\\d+分\\d+秒$").containsMatchIn(it) }
            ?: labels.firstOrNull { Regex("^\\d+:\\d{2}(:\\d{2})?$").containsMatchIn(it) }.orEmpty()
        val views = labels.firstOrNull { it.contains("次觀看", true) || it.contains("次观看", true) }.orEmpty()
        return Video(
            id = id, title = title, url = url, thumb = thumb, duration = duration, views = views,
            site = site.id, thumbWidth = 1280, thumbHeight = 800,
        )
    }

    fun canonical(site: SiteContext, raw: String): String? {
        if (raw.isBlank()) return null
        // 站内卡片给的是 `/v/<id>` 相对路径，先补基址再判断主机。
        val candidate = if (raw.startsWith("/")) site.base.trimEnd('/') + raw else raw
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val match = videoPath.find(url.encodedPath) ?: return null
        return "${site.base}/v/${match.groupValues[1]}"
    }

    fun seriesId(site: SiteContext, raw: String): String? {
        if (raw.isBlank()) return null
        val candidate = if (raw.startsWith("/")) site.base.trimEnd('/') + raw else raw
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        return seriesPath.find(url.encodedPath)?.groupValues?.get(1)
    }

    /**
     * `ev` 载荷是「base64 之后每个字节减去 key」，解出来是
     * `{"videoUrl":"/api/hls/<id>","thumbVTTUrl":"…"}`。这里只取 videoUrl。
     */
    fun streams(doc: Document, site: SiteContext): List<Stream> {
        val html = doc.outerHtml()
        val evPattern = "ev:\\s*\\" + "\$" + "R\\[\\d+\\]\\s*=\\s*\\{d:\"([^\"]+)\",k:(\\d+)\\}"
        val packed = Regex(evPattern).find(html)
            ?: Regex("\\{d:\"([A-Za-z0-9+/=]{40,})\",k:(\\d+)\\}").find(html)
        if (packed != null) {
            val key = packed.groupValues[2].toIntOrNull() ?: 0
            videoUrl(packed.groupValues[1], key, site)?.let { return listOf(it) }
        }
        Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { match ->
            val value = match.groupValues[1].replace("\\/", "/")
            if (value.isNotBlank()) return listOf(Stream("自动", site.absolute(value)))
        }
        return emptyList()
    }

    private fun videoUrl(payload: String, key: Int, site: SiteContext): Stream? {
        val bytes = decodeBase64(payload) ?: return null
        val text = bytes.joinToString("") { byte -> ((byte.toInt() and 0xFF) - key).toChar().toString() }
        val value = Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
        val absolute = site.absolute(value)
        return if (absolute.startsWith("http")) Stream("自动", absolute) else null
    }

    /** 只依赖标准库：JVM 单测与 API 24 都能跑，不需要 android.util.Base64。 */
    internal fun decodeBase64(raw: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = raw.filter { it in alphabet || it == '=' }
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in clean) {
            if (char == '=') break
            val value = alphabet.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    fun detail(doc: Document, url: String, site: SiteContext, streams: List<Stream>): Detail {
        val id = url.substringAfterLast('/')
        val heading = MissavProtocol.clean(doc.selectFirst("h1")?.text().orEmpty())
            .ifBlank { MissavProtocol.clean(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()) }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val duration = doc.selectFirst("meta[property=og:video:duration]")?.attr("content").orEmpty()
        val groups = linkedMapOf<String, List<Link>>()
        val tags = doc.select("a[href*=series?tag=], a[href*=/v?tag=]").mapNotNull { a ->
            val encoded = a.attr("href").substringAfter("tag=", "").substringBefore('&')
            if (encoded.isBlank()) return@mapNotNull null
            val tag = decode(encoded)
            Link(tag, MissavProtocol.clean(a.text()).ifBlank { tag }, "${site.base}/v?tag=$encoded")
        }.distinctBy { it.slug }
        if (tags.isNotEmpty()) groups["类型"] = tags
        val seriesAnchor = doc.selectFirst("a[href^='/s/']")
        val series = seriesAnchor?.let { seriesId(site, site.absolute(it.attr("href"))) }
        if (series != null) groups["系列"] = listOf(
            Link(series, MissavProtocol.clean(seriesAnchor.text()).ifBlank { "系列" }, "${site.base}/s/$series"),
        )
        return Detail(
            video = Video(
                id = id, title = heading.ifBlank { "作品 $id" }, url = url, thumb = site.absolute(poster),
                duration = duration, site = site.id,
            ),
            streams = streams, groups = groups,
            description = MissavProtocol.clean(doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()),
            related = cards(doc, site).filter { it.id != id }, comments = emptyList(), downloads = emptyList(),
        )
    }

    fun total(doc: Document, page: Int): Int {
        val numbers = doc.select("a[href*=page=]").mapNotNull {
            Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        return (numbers.maxOrNull() ?: page).coerceAtLeast(page)
    }

    fun looksEmpty(doc: Document) = doc.select("a[href^='/v/']").isEmpty() &&
        (doc.text().contains("沒有結果", true) || doc.text().contains("没有结果", true) ||
            doc.text().contains("No results", true))

    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val first = url.pathSegments.firstOrNull { it.isNotBlank() }
        return when (first) {
            "s" -> seriesId(site, raw)?.let { FeedRequest(FeedKind.TAG, it) }
            "search" -> url.queryParameter("q")?.let { FeedRequest(FeedKind.SEARCH, it) }
            "v" -> url.queryParameter("tag")?.let { FeedRequest(FeedKind.CATEGORY, it) }
            "series" -> url.queryParameter("tag")?.let { FeedRequest(FeedKind.CATEGORY, it) }
            else -> null
        }
    }

    internal fun decode(raw: String): String = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
