package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * kbjfree.com — Next.js 版 BJ 录播站。
 *
 * 列表数据不在 HTML 里，而在 RSC 的 `self.__next_f.push([1,"…"])` 增量片段中：把片段拼起来、
 * 反转义之后就是一段完整的 props 树，`videos:[{id,name,coverUrl,previewMediaUrl,duration…}]`。
 * 卡片链接是 `/watch/<uuid>`。
 *
 * 完整片源按站点的积分（credits）规则解锁，未解锁时页面只给一段静音预览 mp4。这里如实按
 * 「预览」标注，不把预览冒充成整片。
 */
class KbjFreeAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.CATEGORY, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.NEWEST, Sort.POPULARITY)
    override fun sortsFor(request: FeedRequest) = when (request.kind) {
        FeedKind.LATEST -> sorts
        FeedKind.POPULAR -> listOf(Sort.POPULARITY)
        else -> listOf(Sort.NEWEST)
    }
    override val creatorTitle = "主播"

    override val sections = listOf(
        Section("recent", "最新上传", "刚发布的录制", FeedRequest(FeedKind.LATEST, "recent")),
        Section("trending", "热门", "", FeedRequest(FeedKind.POPULAR, "popular")),
        Section("for-you", "为你推荐", "", FeedRequest(FeedKind.CATEGORY, "for-you")),
    )

    override fun homeUrl() = "${site.base}/videos?sort=recent"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, "recent", page = page)

    override fun resolve(request: FeedRequest) = KbjFreeProtocol.resolve(site, request)

    override fun feedTitle(request: FeedRequest) = KbjFreeProtocol.title(request)

    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(FeedKind.SEARCH, query.trim())

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val html = network.html(url, site)
        val videos = KbjFreeProtocol.cards(html, site)
        check(videos.isNotEmpty() || KbjFreeProtocol.looksEmpty(html)) { "KBJFree 未返回可识别的列表，站点可能已改版" }
        return FeedPage(videos, request.page, KbjFreeProtocol.lastPage(html, request.page), feedTitle(request))
    }

    override suspend fun detail(url: String): Detail {
        val absolute = KbjFreeProtocol.canonical(site, url) ?: error("无法识别 KBJFree 作品地址")
        val html = network.html(absolute, site)
        val doc = Jsoup.parse(html, absolute)
        val streams = KbjFreeProtocol.streams(doc)
        check(streams.isNotEmpty()) { "详情页没有可播放的地址，站点可能已改版" }
        return KbjFreeProtocol.detail(doc, absolute, site, streams)
    }

    override suspend fun index(section: String): List<Link> = emptyList()

    override fun linkRequest(url: String): FeedRequest? = KbjFreeProtocol.linkRequest(site, url)

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("KBJFree 的完整片源需要在站点内用积分解锁，不支持应用内登录")
}

object KbjFreeProtocol {
    private val watchPath = Regex("^/watch/([0-9a-f-]{16,40})/?$")
    private val flight = Regex("self\\.__next_f\\.push\\(\\[1,\"(.*?)\"\\]\\)", RegexOption.DOT_MATCHES_ALL)

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val page = request.page.coerceAtLeast(1)
        val paged = { url: String -> if (page <= 1) url else if (url.contains('?')) "$url&page=$page" else "$url?page=$page" }
        return when (request.kind) {
            FeedKind.SEARCH -> paged("${site.base}/search?q=${MissavProtocol.encode(request.target.trim())}")
            FeedKind.POPULAR -> paged("${site.base}/videos?sort=popular")
            FeedKind.CATEGORY -> paged(
                request.target.trim().takeIf { it.isNotBlank() }?.let { "${site.base}/videos?filter=${MissavProtocol.encode(it)}" }
                    ?: "${site.base}/videos",
            )
            else -> paged("${site.base}/videos?sort=${if (request.sort == Sort.POPULARITY) "popular" else "recent"}")
        }
    }

    fun title(request: FeedRequest): String = when (request.kind) {
        FeedKind.SEARCH -> request.target.ifBlank { "搜索录制" }
        FeedKind.POPULAR -> "热门"
        FeedKind.CATEGORY -> "为你推荐"
        else -> "最新上传"
    }

    /**
     * RSC 片段里每个视频是一段 JSON 对象；用宽松字段抽取比整树反序列化稳，
     * 因为 Next 的 props 树里混着 `$undefined` 之类非 JSON 字面量。
     */
    fun cards(html: String, site: SiteContext): List<Video> {
        val payload = flightPayload(html)
        if (payload.isBlank()) return emptyList()
        val videos = linkedMapOf<String, Video>()
        for (block in Regex("\\{\"id\":\"([0-9a-f-]{16,40})\",\"name\":\"(.*?)\",\"categoryId\"").findAll(payload)) {
            val id = block.groupValues[1]
            if (videos.containsKey(id)) continue
            val window = payload.substring(block.range.first, minOf(payload.length, block.range.first + 2600))
            val title = unescape(block.groupValues[2]).ifBlank { id }
            videos[id] = Video(
                id = id, title = title, url = "${site.base}/watch/$id",
                thumb = field(window, "mainThumbUrl").ifBlank { field(window, "coverUrl") },
                preview = field(window, "previewMediaUrl"),
                duration = field(window, "duration").substringBefore('.').take(8),
                site = site.id, thumbWidth = 480, thumbHeight = 270,
            )
        }
        return videos.values.toList()
    }

    private fun field(window: String, name: String): String =
        Regex("\"$name\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
            ?.replace("\\/", "/").orEmpty()

    /** 把所有 push 片段拼接后反转义成可搜索的文本。 */
    internal fun flightPayload(html: String): String {
        val raw = flight.findAll(html).joinToString("") { it.groupValues[1] }
        return unescape(raw)
    }

    private fun unescape(raw: String) = raw
        .replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")
        .replace("\\/", "/").replace("\\\\", "\\")

    fun canonical(site: SiteContext, raw: String): String? {
        if (raw.isBlank()) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        val match = watchPath.find(url.encodedPath) ?: return null
        return "${site.base}/watch/${match.groupValues[1]}"
    }

    /**
     * 详情页给的是 LD-JSON 里的 `contentUrl`（静音预览）与「用积分解锁」按钮。
     * 只把真实存在的地址交给播放器，并在标签里说清这是预览。
     */
    fun streams(doc: Document): List<Stream> {
        val streams = linkedMapOf<String, Stream>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val json = runCatching { JsonParser.parseString(script.data()).asJsonObject }.getOrNull() ?: continue
            if (json.get("contentUrl")?.asString?.isNotBlank() == true) {
                streams["预览"] = Stream("预览", json.get("contentUrl").asString)
            }
        }
        doc.select("video[src]").forEach { video ->
            val src = video.absUrl("src").ifBlank { video.attr("src") }
            if (src.startsWith("http")) streams.putIfAbsent("预览", Stream("预览", src))
        }
        return streams.values.toList()
    }

    fun detail(doc: Document, url: String, site: SiteContext, streams: List<Stream>): Detail {
        val id = url.substringAfterLast('/')
        var payload: JsonObject? = null
        for (script in doc.select("script[type=application/ld+json]")) {
            val json = runCatching { JsonParser.parseString(script.data()).asJsonObject }.getOrNull() ?: continue
            if (json.get("name")?.asString?.isNotBlank() == true) { payload = json; break }
        }
        val heading = payload?.get("name")?.asString?.let(::unescape)
            ?: doc.selectFirst("h1")?.text().orEmpty().let(::unescape)
        val poster = payload?.get("thumbnailUrl")?.let { element ->
            if (element.isJsonArray) element.asJsonArray.firstOrNull()?.asString else element.asString
        }.orEmpty()
        val duration = isoDuration(payload?.get("duration")?.asString.orEmpty())
        val groups = linkedMapOf<String, List<Link>>()
        val actor = payload?.getAsJsonArray("actor")?.mapNotNull { entry ->
            val obj = runCatching { entry.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: return@mapNotNull null
            val href = obj.get("url")?.asString.orEmpty()
            Link(name, name, href.ifBlank { "${site.base}/model/$name" })
        }.orEmpty()
        if (actor.isNotEmpty()) groups["主播"] = actor
        val related = cards(doc.outerHtml(), site).filter { it.id != id }
        return Detail(
            video = Video(
                id = id, title = heading.ifBlank { "录制 $id" }, url = url,
                thumb = site.absolute(poster), duration = duration, site = site.id,
            ),
            streams = streams, groups = groups,
            description = "完整片源需要在 KBJFree 站内用积分解锁；这里播放的是站点公开的预览片段。",
            related = related, comments = emptyList(), downloads = emptyList(),
        )
    }

    fun lastPage(html: String, page: Int): Int = Jsoup.parse(html).select("a[href*=page=]")
        .mapNotNull { Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()?.coerceAtLeast(page) ?: page

    fun looksEmpty(html: String) = flightPayload(html).contains("\"videos\":[]")

    /**
     * 详情页的结构化数据用 ISO 8601 时长（`PT1H31M0S`），列表用的是 `01:31:00`。
     * 直接显示 ISO 串在播放器副标题里会像一串乱码，这里统一成 `H:MM:SS`。
     */
    internal fun isoDuration(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith("PT", true)) return value
        val hours = Regex("(\\d+)H").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)M").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(\\d+)S").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hours == 0 && minutes == 0 && seconds == 0) return value
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!site.isSite(url.host)) return null
        url.queryParameter("q")?.takeIf { it.isNotBlank() }?.let { return FeedRequest(FeedKind.SEARCH, it) }
        url.queryParameter("sort")?.let { sort ->
            return FeedRequest(if (sort == "popular") FeedKind.POPULAR else FeedKind.LATEST, if (sort == "popular") "popular" else "recent")
        }
        return null
    }
}
