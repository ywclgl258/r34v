package com.r34v.r34v.data.adapter

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.r34v.r34v.data.Detail
import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedPage
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.Link
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.Sort
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.timeText
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

class NetflavAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {
    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL,
        Capability.CATEGORY, Capability.RANKING,
    )
    override val sorts = listOf(Sort.NEWEST, Sort.RELEVANCE, Sort.TRENDING)
    override val creatorTitle = "演员"
    override val sections = NetflavProtocol.sections

    override fun homeUrl() = "${site.base.trimEnd('/')}/"
    override fun resolve(request: FeedRequest) = NetflavProtocol.resolve(site, request)
    override fun feedTitle(request: FeedRequest) = NetflavProtocol.title(request)
    override fun sortsFor(request: FeedRequest) = listOf(NetflavProtocol.sort(request))

    override fun searchRequest(query: String, kind: FeedKind): FeedRequest {
        val request = FeedRequest(kind, query.trim())
        return request.copy(sort = NetflavProtocol.sort(request))
    }

    override fun linkRequest(url: String) = NetflavProtocol.linkRequest(site, url)
    override fun indexSection(request: FeedRequest) = NetflavProtocol.indexSection(request)
    override fun sectionUrl(key: String) = NetflavProtocol.sectionUrl(site, key)

    override suspend fun feed(request: FeedRequest): FeedPage =
        NetflavProtocol.feed(network.html(resolve(request), site), site, request)

    override suspend fun detail(url: String): Detail {
        val canonical = NetflavProtocol.canonicalVideo(site, url) ?: error("无法识别 Netflav 视频地址")
        return NetflavProtocol.detail(network.html(canonical, site), site, canonical)
    }

    override suspend fun index(section: String): List<Link> {
        val url = sectionUrl(section) ?: return emptyList()
        return NetflavProtocol.index(network.html(url, site), site, section)
    }

    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("Netflav 支持公开浏览，无需登录")
}

internal object NetflavProtocol {
    private enum class Listing(val key: String, val path: String, val title: String) {
        ALL("all", "/all", "全部视频"),
        CENSORED("censored", "/censored", "有码视频"),
        UNCENSORED("uncensored", "/uncensored", "无码视频"),
        CHINESE("chinese", "/chinese-sub", "中文字幕"),
        TRENDING("trending", "/trending", "热门趋势"),
        SEARCH("search", "/search", "搜索"),
    }

    val sections = Listing.entries.filter { it != Listing.SEARCH }.map { listing ->
        val request = if (listing == Listing.TRENDING) FeedRequest(FeedKind.POPULAR, sort = Sort.TRENDING)
        else FeedRequest(target = listing.key)
        Section(listing.key, listing.title, feed = request)
    } + listOf(Section("categories", "分类目录"), Section("models", "演员目录"))

    private val videoId = Regex("[A-Za-z0-9_-]+")
    private val minutes = Regex("([0-9]{1,9})\\s*(?:分鐘|分钟|分|min)", RegexOption.IGNORE_CASE)

    private fun listing(request: FeedRequest): Listing = when (request.kind) {
        FeedKind.SEARCH -> Listing.SEARCH
        FeedKind.CATEGORY, FeedKind.TAG, FeedKind.CHARACTER, FeedKind.MODEL -> Listing.ALL
        FeedKind.POPULAR -> Listing.TRENDING
        FeedKind.LATEST -> Listing.entries.firstOrNull { it.key == request.target && it != Listing.SEARCH } ?: Listing.CENSORED
        else -> error("Netflav 暂无${request.kind.title}列表")
    }

    fun title(request: FeedRequest): String = when (request.kind) {
        FeedKind.SEARCH, FeedKind.CATEGORY, FeedKind.TAG, FeedKind.CHARACTER, FeedKind.MODEL ->
            request.target.ifBlank { request.kind.title }
        FeedKind.LATEST -> listing(request).title
        FeedKind.POPULAR -> Listing.TRENDING.title
        else -> request.kind.title
    }

    fun sort(request: FeedRequest): Sort = when {
        request.kind == FeedKind.SEARCH -> Sort.RELEVANCE
        request.kind == FeedKind.POPULAR || request.kind == FeedKind.LATEST && request.target == Listing.TRENDING.key -> Sort.TRENDING
        else -> Sort.NEWEST
    }

    fun resolve(site: SiteContext, request: FeedRequest): String {
        request.filter.validate()
        val listing = listing(request)
        val page = request.page.coerceAtLeast(1)
        val onHome = request.kind == FeedKind.LATEST && request.target.isBlank() && page == 1
        val url = urlBuilder(site, if (onHome) "/" else listing.path)
        val target = request.target.trim()
        when (request.kind) {
            FeedKind.SEARCH -> url.addQueryParameter("keyword", target)
            FeedKind.CATEGORY, FeedKind.TAG, FeedKind.CHARACTER -> if (target.isNotEmpty()) url.addQueryParameter("genre", target)
            FeedKind.MODEL -> if (target.isNotEmpty()) url.addQueryParameter("actress", target)
            else -> Unit
        }
        if (page > 1 || listing == Listing.SEARCH) url.addQueryParameter("page", page.toString())
        return url.build().toString()
    }

    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = siteUrl(site, raw) ?: return null
        val page = url.queryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return when (val path = url.encodedPath.trimEnd('/')) {
            "" -> FeedRequest()
            Listing.ALL.path -> {
                val genre = url.queryParameter("genre").orEmpty()
                val actress = url.queryParameter("actress").orEmpty()
                when {
                    genre.isNotBlank() -> FeedRequest(FeedKind.CATEGORY, genre, page = page)
                    actress.isNotBlank() -> FeedRequest(FeedKind.MODEL, actress, page = page)
                    else -> FeedRequest(target = Listing.ALL.key, page = page)
                }
            }
            Listing.SEARCH.path -> FeedRequest(FeedKind.SEARCH, url.queryParameter("keyword").orEmpty(), Sort.RELEVANCE, page)
            "/genre" -> FeedRequest(FeedKind.CATEGORY)
            "/actress" -> FeedRequest(FeedKind.MODEL)
            Listing.TRENDING.path -> FeedRequest(FeedKind.POPULAR, sort = Sort.TRENDING, page = page)
            else -> Listing.entries.firstOrNull { it.path == path }?.let { FeedRequest(target = it.key, page = page) }
        }
    }

    fun indexSection(request: FeedRequest): String? = when {
        request.target.isNotBlank() -> null
        request.kind == FeedKind.CATEGORY -> "categories"
        request.kind == FeedKind.MODEL -> "models"
        else -> null
    }

    fun sectionUrl(site: SiteContext, section: String): String? {
        val path = when (section) {
            "categories" -> "/genre"
            "models" -> "/actress"
            else -> return null
        }
        return urlBuilder(site, path).build().toString()
    }

    fun index(html: String, site: SiteContext, section: String): List<Link> {
        val (selector, parameter) = when (section) {
            "categories" -> "a.genre_item[href^='/all?genre=']" to "genre"
            "models" -> "a[href^='/all?actress=']" to "actress"
            else -> return emptyList()
        }
        val links = Jsoup.parse(html).select(selector).mapNotNull { anchor ->
            val name = siteUrl(site, anchor.attr("href"))?.queryParameter(parameter)?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val image = anchor.selectFirst("img[src]")
            val label = anchor.text().ifBlank { image?.attr("alt").orEmpty().ifBlank { name } }
            filterLink(site, parameter, name, label, imageUrl(site, image?.attr("src").orEmpty()))
        }.distinctBy { it.slug }
        check(links.isNotEmpty()) { "Netflav 未返回可识别的目录" }
        return links
    }

    fun feed(html: String, site: SiteContext, request: FeedRequest): FeedPage {
        val source = listing(request)
        val state = initialState(html).obj(source.key)
        check(state != null && !state.flag("isError")) { "Netflav 未返回可识别的列表" }
        val docs = state.array("docs") ?: error("Netflav 列表缺少视频数据")
        val current = state.text("page").toIntOrNull()?.takeIf { it > 0 } ?: error("Netflav 列表缺少有效页码")
        val pages = state.text("pages").toIntOrNull()?.takeIf { it >= 0 } ?: error("Netflav 列表缺少有效总页数")
        check(current == request.page.coerceAtLeast(1)) { "Netflav 返回的页码与请求不符" }
        val videos = cards(site, docs)
        check(docs.isEmpty() || videos.isNotEmpty()) { "Netflav 列表没有可识别的视频" }
        val filtered = if (request.filter.isEmpty) videos else videos.filter(request.filter::matchesLoaded)
        return FeedPage(filtered, current, pages.coerceAtLeast(current), title(request))
    }

    fun canonicalVideo(site: SiteContext, raw: String): String? =
        requestedVideoId(site, raw)?.let { videoUrl(site, it) }

    fun detail(html: String, site: SiteContext, rawUrl: String): Detail {
        val requestedId = requestedVideoId(site, rawUrl) ?: error("无法识别 Netflav 视频地址")
        val state = initialState(html).obj("video")
        val data = state?.obj("data")
        check(state != null && !state.flag("isError") && data != null && !data.flag("isDeleted")) { "Netflav 视频详情不可用" }
        val video = card(site, data) ?: error("Netflav 详情缺少视频信息")
        check(video.id == requestedId) { "Netflav 返回的视频与请求不符" }
        val groups = buildMap {
            val actors = names(data, "actors").map { filterLink(site, "actress", it) }
            val tags = names(data, "tags").map { filterLink(site, "genre", it) }
            if (actors.isNotEmpty()) put("演员", actors)
            if (tags.isNotEmpty()) put("标签", tags)
        }
        val related = cards(site, data.array("related1").orEmpty() + data.array("related2").orEmpty())
            .filter { it.id != video.id }
        // Keep playback on the official page. The SSR markup can contain advertising frames.
        return Detail(
            video = video,
            streams = emptyList(),
            groups = groups,
            description = data.text("description"),
            related = related,
            comments = emptyList(),
            downloads = emptyList(),
            externalPlayers = listOf(Link("browser", "在浏览器打开 Netflav", video.url)),
        )
    }

    private fun initialState(html: String): JsonObject {
        val payload = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()
        check(!payload.isNullOrBlank()) { "Netflav 页面缺少公开数据，请检查站点是否可访问" }
        val root = try {
            JsonParser.parseString(payload).objectOrNull()
        } catch (_: JsonParseException) {
            null
        }
        return root?.obj("props")?.obj("initialState") ?: error("Netflav 页面数据格式无法识别")
    }

    private fun cards(site: SiteContext, docs: Iterable<JsonElement>): List<Video> = docs.mapNotNull { item ->
        val data = item.objectOrNull() ?: return@mapNotNull null
        card(site, data) ?: data.obj("video")?.let { card(site, it) }
    }.distinctBy { it.id }

    private fun card(site: SiteContext, data: JsonObject): Video? {
        val id = data.text("videoId").takeIf(videoId::matches) ?: return null
        val title = data.text("title").ifBlank { data.text("title_zh") }.ifBlank { data.text("title_en") }
        if (title.isBlank()) return null
        val duration = data.text("duration")
        return Video(
            id = id,
            title = title,
            url = videoUrl(site, id),
            thumb = imageUrl(site, data.text("preview")).ifBlank { imageUrl(site, data.text("preview_hp")) },
            duration = minutes.matchEntire(duration)?.let { timeText(it.groupValues[1].toLong() * 60_000) } ?: duration,
            views = data.text("views"),
            site = site.id,
        )
    }

    private fun requestedVideoId(site: SiteContext, raw: String): String? {
        val url = siteUrl(site, raw) ?: return null
        if (url.encodedPath.trimEnd('/') != "/video") return null
        return url.queryParameterValues("id").singleOrNull()?.takeIf(videoId::matches)
    }

    private fun videoUrl(site: SiteContext, id: String) =
        urlBuilder(site, "/video").addQueryParameter("id", id).build().toString()

    private fun filterLink(site: SiteContext, parameter: String, name: String, label: String = name, thumb: String = "") =
        Link(name, label, urlBuilder(site, Listing.ALL.path).addQueryParameter(parameter, name).build().toString(), thumb)

    private fun urlBuilder(site: SiteContext, path: String) = site.base.toHttpUrl().newBuilder().encodedPath(path)

    private fun siteUrl(site: SiteContext, raw: String): HttpUrl? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { site.base.toHttpUrl().resolve(it) }?.takeIf {
            site.isSite(it.host) && it.username.isEmpty() && it.password.isEmpty() && it.port == HttpUrl.defaultPort(it.scheme)
        }

    private fun imageUrl(site: SiteContext, raw: String): String =
        raw.takeIf { it.isNotBlank() }?.let { site.base.toHttpUrl().resolve(it) }
            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString().orEmpty()

    private fun names(data: JsonObject, key: String): List<String> = data.array(key).orEmpty()
        .map { it.string() }.filter { it.isNotBlank() }.distinct()

    private fun JsonElement.objectOrNull() = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.obj(key: String) = get(key)?.objectOrNull()
    private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.asList()
    private fun JsonElement.string() = takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    private fun JsonObject.text(key: String) = get(key)?.string().orEmpty()
    private fun JsonObject.flag(key: String) = text(key).equals("true", ignoreCase = true)
}
