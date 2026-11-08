package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * missav — 番号式 JAV 站：一个作品就是一页 `/dldss-584`。
 *
 * 列表页每张卡片是 `div.thumbnail`，标题、时长、缩略图都在卡片内；详情页把播放地址压在
 * 一段 packer 混淆脚本里，解出来是 `https://<cdn>/<uuid>/playlist.m3u8`，一份带 360/480/720/1080
 * 四档的 master 清单，Media3 直接吃。
 *
 * 站点按地区轮换域名，Cloudflare 会对部分域名直接挑战；调用方通过 `SiteContext.mirrors`
 * 换域，选择器本身不变。
 */
class MissavAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL,
        Capability.CATEGORY, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.NEWEST, Sort.VIEWS, Sort.POPULARITY)
    override val creatorTitle = "女优"
    override val catalogueTitle = "类型"

    override val sections = listOf(
        Section("new", "最近更新", "刚上架的作品", FeedRequest(FeedKind.LATEST, "new")),
        Section("release", "新作上市", "", FeedRequest(FeedKind.LATEST, "release")),
        Section("uncensored-leak", "无码流出", "", FeedRequest(FeedKind.CATEGORY, "uncensored-leak")),
        Section("chinese-subtitle", "中文字幕", "", FeedRequest(FeedKind.CATEGORY, "chinese-subtitle")),
        Section("genres", "类型索引", "按题材浏览"),
        Section("actresses", "女优列表", ""),
        Section("popular", "人气排行", "", FeedRequest(FeedKind.POPULAR, "new")),
    )

    override fun homeUrl() = "${site.base}/new"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, "new", page = page)

    override fun sortsFor(request: FeedRequest) = MissavProtocol.sorts(request, this)

    override fun resolve(request: FeedRequest) = MissavProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = MissavProtocol.title(request, sections)

    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(
        if (kind == FeedKind.SEARCH) FeedKind.SEARCH else kind, query.trim(),
        if (kind == FeedKind.SEARCH) Sort.NEWEST else Sort.NEWEST,
    )

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url, site), url)
        val videos = MissavProtocol.cards(doc, site)
        check(videos.isNotEmpty() || MissavProtocol.looksEmpty(doc)) { "MissAV 未返回可识别的列表，站点可能已改版" }
        val total = MissavProtocol.lastPage(doc)?.coerceAtLeast(request.page) ?: request.page
        return FeedPage(videos, request.page, total, feedTitle(request))
    }

    override suspend fun detail(url: String): Detail {
        val absolute = MissavProtocol.canonical(site, url) ?: error("无法识别 MissAV 作品地址")
        val doc = Jsoup.parse(network.html(absolute, site), absolute)
        val streams = MissavProtocol.streams(doc)
        check(streams.isNotEmpty()) { "详情页没有播放地址，可能需要登录或站点已改版" }
        return MissavProtocol.detail(doc, absolute, site, streams)
    }

    override suspend fun index(section: String): List<Link> {
        val url = when (section) {
            "genres" -> "${site.base}/genres"
            "actresses" -> "${site.base}/actresses"
            else -> return emptyList()
        }
        val doc = Jsoup.parse(network.html(url, site), url)
        val segment = if (section == "genres") "/genres/" else "/actresses/"
        return doc.select("a[href*=$segment]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter(segment).trimEnd('/').substringBefore('?')
            if (slug.isBlank() || slug == "ranking") return@mapNotNull null
            val label = MissavProtocol.clean(a.text()).ifBlank { slug }
            Link(slug, label, site.absolute(a.attr("href")))
        }.distinctBy { it.slug }
    }

    override fun sectionUrl(key: String): String? = when (key) {
        "genres" -> "${site.base}/genres"
        "actresses" -> "${site.base}/actresses"
        else -> null
    }

    override fun linkRequest(url: String): FeedRequest? = MissavProtocol.linkRequest(site, url)

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("MissAV 支持公开浏览，无需登录")
}

/** MissAV 的路由、列表与播放源解析，全部是纯函数，方便单测。 */
object MissavProtocol {
    private val videoPath = Regex("^/([a-z0-9]+(?:-[a-z0-9]+)*-[a-z]*\\d+)/?$", RegexOption.IGNORE_CASE)
    private val dvdCode = Regex("^([A-Za-z]+-?\\d+)")
    private val streamHash = Regex("""https://([a-z0-9.-]+)/[^'"\s]*?/([0-9a-f-]{32,36})/""")

    fun sorts(request: FeedRequest, adapter: MissavAdapter): List<Sort> = when (request.kind) {
        FeedKind.SEARCH, FeedKind.MODEL, FeedKind.TAG, FeedKind.CATEGORY -> listOf(Sort.NEWEST)
        FeedKind.POPULAR -> listOf(Sort.POPULARITY)
        else -> listOf(Sort.NEWEST, Sort.VIEWS)
    }

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        fun paged(url: String): String {
            if (page <= 1) return url
            return if (url.contains('?')) "$url&page=$page" else "$url?page=$page"
        }
        val sort = when (request.sort) {
            Sort.VIEWS -> "views"
            Sort.POPULARITY -> "monthly_views"
            else -> null
        }
        return when (request.kind) {
            FeedKind.SEARCH -> paged("${site.base}/search/${encode(request.target.trim())}")
            FeedKind.TAG -> paged("${site.base}/genres/${encode(request.target.trim())}")
            FeedKind.MODEL -> paged("${site.base}/actresses/${encode(request.target.trim())}")
            FeedKind.CATEGORY -> paged("${site.base}/${request.target.trim()}")
            FeedKind.POPULAR -> paged("${site.base}/new?sort=monthly_views")
            else -> {
                val path = request.target.ifBlank { "new" }
                val base = "${site.base}/${path.trim('/')}"
                paged(if (sort == null) base else "$base?sort=$sort")
            }
        }
    }

    fun title(request: FeedRequest, sections: List<Section>): String = when (request.kind) {
        FeedKind.SEARCH -> request.target.ifBlank { "搜索作品" }
        FeedKind.TAG -> "类型 · ${request.target}"
        FeedKind.MODEL -> "女优 · ${request.target}"
        FeedKind.CATEGORY -> sections.firstOrNull { it.key == request.target }?.title ?: request.target
        FeedKind.POPULAR -> "人气排行"
        else -> sections.firstOrNull { it.key == request.target }?.title ?: "最近更新"
    }

    /** 列表卡片：`div.thumbnail.group`，标题在 `div.truncate a`。 */
    fun cards(doc: Document, site: SiteContext): List<Video> =
        doc.select("div.thumbnail.group").mapNotNull { card -> card(card, site) }.distinctBy { it.id }

    private fun card(card: Element, site: SiteContext): Video? {
        val link = card.selectFirst("a[href]") ?: return null
        val raw = link.attr("href")
        val url = canonical(site, raw) ?: return null
        val id = url.trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null
        val title = card.selectFirst("div.truncate a")?.text().orEmpty().ifBlank {
            card.selectFirst("img[alt]")?.attr("alt").orEmpty()
        }.let(::clean)
        if (title.isBlank()) return null
        val image = card.selectFirst("img")
        val thumb = image?.let { MissavProtocol.thumb(it, site) }.orEmpty()
        return Video(
            id = id, title = title, url = url, thumb = thumb,
            duration = card.selectFirst("span.absolute")?.text().orEmpty().let(::clean),
            site = site.id,
            preview = card.selectFirst("video[data-src]")?.absUrl("data-src").orEmpty(),
        )
    }

    /**
     * 列表里的缩略图是本文件的 1x1 占位，真地址在 `data-src`；预览视频同理。
     */
    internal fun thumb(image: Element, site: SiteContext): String =
        listOf(image.attr("data-src"), image.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { site.absolute(it) }
            .orEmpty()

    /** 作品地址：`https://<host>/<code>`，去掉查询串和语言前缀。 */
    fun canonical(site: SiteContext, raw: String): String? {
        val url = absolute(site, raw) ?: return null
        val segment = url.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
        if (!videoPath.matches("/$segment/")) return null
        return "${site.base}/$segment"
    }

    /**
     * 卡片里的 href 有时是 `/dldss-584` 这样的站内相对路径，直接 `toHttpUrlOrNull` 会失败，
     * 所以先补站点基址，再判断主机是否属于本站。
     */
    internal fun absolute(site: SiteContext, raw: String): okhttp3.HttpUrl? {
        if (raw.isBlank()) return null
        val candidate = if (raw.startsWith("/")) site.base.trimEnd('/') + raw else raw
        return candidate.toHttpUrlOrNull()?.takeIf { site.isSite(it.host) }
    }

    fun videoKey(url: String): String = url.trimEnd('/').substringAfterLast('/')

    fun looksEmpty(doc: Document) = doc.select("div.thumbnail.group").isEmpty() &&
        (doc.text().contains("沒有找到", true) || doc.text().contains("没有找到", true) ||
            doc.text().contains("No results", true))

    fun lastPage(doc: Document): Int? = doc.select("a[href*=page=]").mapNotNull {
        Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull()

    /**
     * 播放源：详情页把 `playlist.m3u8` 藏在 packer 混淆里，解出来的是该片的 master 清单。
     *
     * 单一清晰度不在这里拆档：master 自带 360/480/720/1080 四路，播放器按带宽自选，
     * 因此这里只给一条“自动”档，避免伪造一个站点并不提供的清晰度列表。
     */
    fun streams(doc: Document): List<Stream> {
        val html = doc.outerHtml()
        var from = 0
        while (true) {
            val start = html.indexOf("eval(function(p,a,c,k,e,", from)
            if (start < 0) break
            val decoded = runCatching { unpack(html.substring(start)) }.getOrNull().orEmpty()
            playlist(decoded)?.let { return listOf(Stream("自动", it)) }
            from = start + 20
        }
        playlist(html)?.let { return listOf(Stream("自动", it)) }
        return emptyList()
    }

    private fun playlist(text: String): String? {
        val match = Regex("""(https?://[^'"\s]+?/[0-9a-f-]{32,36}/playlist\.m3u8)""").find(text) ?: return null
        return match.groupValues[1]
    }

    /**
     * Packer 解码：`}(payload, base, count, 'w1|w2|…')` 把长字符串里的词换成 base 进制短 token。
     * 支持 2–62 进制；只替换原始载荷的 token，避免词典展开后的文本被再次替换。
     * 不是标准形态时返回原文，交给上层的兜底正则。
     */
    internal fun unpack(source: String): String {
        val open = source.indexOf("}(")
        if (open < 0) return source
        var cursor = open + 2
        fun quoted(): String? {
            if (cursor >= source.length || source[cursor] != '\'') return null
            cursor++
            val out = StringBuilder()
            while (cursor < source.length) {
                val ch = source[cursor]
                if (ch == '\\') {
                    cursor++
                    if (cursor >= source.length) return null
                    out.append(source[cursor])
                    cursor++
                    continue
                }
                if (ch == '\'') { cursor++; return out.toString() }
                out.append(ch)
                cursor++
            }
            return null
        }
        fun number(): Int? {
            while (cursor < source.length && !source[cursor].isDigit()) {
                if (source[cursor] == '\'') return null
                cursor++
            }
            val start = cursor
            while (cursor < source.length && source[cursor].isDigit()) cursor++
            return source.substring(start, cursor).toIntOrNull()
        }
        val payload = quoted() ?: return source
        val base = number() ?: return source
        val count = number() ?: return source
        while (cursor < source.length && source[cursor] != '\'') {
            if (source[cursor] == ',' && cursor + 1 < source.length && source[cursor + 1] == '\'') { cursor++; break }
            cursor++
        }
        val words = quoted()?.split('|') ?: return source
        if (words.size < count) return source
        val replacements = (0 until count).mapNotNull { index ->
            val token = baseToken(index, base) ?: return@mapNotNull null
            words[index].takeIf { it.isNotEmpty() }?.let { token to it }
        }.toMap()
        return Regex("\\b[0-9A-Za-z]+\\b").replace(payload) { replacements[it.value] ?: it.value }
    }

    private fun baseToken(value: Int, base: Int): String? {
        if (base < 2 || base > 62 || value < 0) return null
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value == 0) return "0"
        var remaining = value
        val out = StringBuilder()
        while (remaining > 0) { out.insert(0, digits[remaining % base]); remaining /= base }
        return out.toString()
    }

    fun detail(doc: Document, url: String, site: SiteContext, streams: List<Stream>): Detail {
        val id = videoKey(url)
        val heading = doc.selectFirst("h1")?.text().orEmpty().let(::clean)
        val image = doc.selectFirst("video.player")?.attr("data-poster").orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
        val duration = doc.selectFirst("meta[property=og:video:duration]")?.attr("content").orEmpty()
        val groups = linkedMapOf<String, List<Link>>()
        val actresses = doc.select("a[href*=/actresses/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/actresses/").trimEnd('/')
            if (slug.isBlank() || slug == "ranking") null else Link(slug, clean(a.text()), site.absolute(a.attr("href")))
        }.distinctBy { it.slug }
        if (actresses.isNotEmpty()) groups["女优"] = actresses
        val genres = doc.select("a[href*=/genres/]").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/genres/").trimEnd('/')
            if (slug.isBlank()) null else Link(slug, genslug(slug), "${site.base}/genres/$slug")
        }.distinctBy { it.slug }
        if (genres.isNotEmpty()) groups["类型"] = genres
        val related = doc.select("div.thumbnail.group").mapNotNull { card(it, site) }.distinctBy { it.id }
        return Detail(
            video = Video(
                id = id, title = heading.ifBlank { "作品 $id" }, url = url,
                thumb = site.absolute(image), duration = duration, site = site.id,
            ),
            streams = streams, groups = groups, description = doc.selectFirst("meta[property=og:description]")
                ?.attr("content").orEmpty().let(::clean),
            related = related, comments = emptyList(), downloads = emptyList(),
        )
    }

    /** 站内搜索/女优/类型地址折回同一个 feed，交给跨站链接识别使用。 */
    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val parts = url.pathSegments.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "search" -> parts.getOrNull(1)?.let { FeedRequest(FeedKind.SEARCH, it) }
            "genres" -> parts.getOrNull(1)?.let { FeedRequest(FeedKind.TAG, it) }
            "actresses" -> parts.getOrNull(1)?.takeIf { it != "ranking" }?.let { FeedRequest(FeedKind.MODEL, it) }
            else -> null
        }
    }

    private fun genslug(raw: String) = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    internal fun encode(raw: String) = URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    internal fun clean(raw: String) = raw.replace("\u200b", "").replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ").trim()
}
