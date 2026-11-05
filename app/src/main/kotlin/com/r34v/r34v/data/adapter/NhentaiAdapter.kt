package com.r34v.r34v.data.adapter

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.r34v.r34v.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Uses the site's documented public v2 API, not the retired /api/gallery endpoint. */
class NhentaiAdapter(private val network: Network) : SiteAdapter {
    override val site = SiteRegistry.NHENTAI
    override val capabilities = setOf(Capability.DOWNLOAD, Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL, Capability.CATEGORY, Capability.RANKING)
    override val creatorTitle = "画师"
    override val catalogueTitle = "画廊分类"
    override val sorts = listOf(Sort.NEWEST, Sort.POPULARITY)
    override val sections = listOf(
        Section("latest", "最新画廊", feed = FeedRequest()),
        Section("popular", "今日热门", feed = FeedRequest(FeedKind.POPULAR)),
    ) + listOf("doujinshi" to "同人志", "manga" to "漫画", "artistcg" to "画师 CG", "gamecg" to "游戏 CG").map {
        Section(it.first, it.second, feed = FeedRequest(FeedKind.CATEGORY, it.first))
    }
    private val mutex = Mutex()
    private var cdn: Pair<Long, NhentaiProtocol.Cdn>? = null
    private val details = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JsonObject>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > 32
    })
    private val languagePages = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Long, FeedPage>>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, FeedPage>>?) = size > 12
    })
    private suspend fun json(url: String): JsonElement = network.client.readResponse(Request.Builder().url(url).tag(SiteContext::class.java, site).header("Cache-Control", "no-cache").build()) { response ->
        when (response.code) {
            429 -> throw GalleryAccessException("nhentai 请求频率已达限制，请稍后重试${response.header("Retry-After")?.toLongOrNull()?.let { "（约 $it 秒）" }.orEmpty()}")
            401, 403 -> throw GalleryAccessException("nhentai 暂时拒绝访问，请检查网络或稍后重试")
            404 -> throw GalleryAccessException("该画廊已被原站移除或不存在")
        }
        check(response.isSuccessful) { "nhentai 读取失败：HTTP ${response.code}" }
        JsonParser.parseString(response.body?.string().orEmpty())
    }
    private suspend fun servers(refresh: Boolean = false): NhentaiProtocol.Cdn = mutex.withLock {
        cdn?.takeIf { !refresh && System.currentTimeMillis() - it.first < 30 * 60 * 1000 }?.second
            ?: NhentaiProtocol.cdn(json("${site.base}/api/v2/cdn").asJsonObject).also { cdn = System.currentTimeMillis() to it }
    }
    private suspend fun info(id: String, refresh: Boolean = false) = if (!refresh) details[id] ?: json("${site.base}/api/v2/galleries/$id").asJsonObject.also { details[id] = it }
        else json("${site.base}/api/v2/galleries/$id").asJsonObject.also { details[id] = it }

    override fun resolve(request: FeedRequest) = NhentaiProtocol.resolve(request)
    override fun sortsFor(request: FeedRequest) = NhentaiProtocol.availableSorts(request)
    override fun homeUrl() = resolve(homeRequest())
    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(kind, query)
    override fun linkRequest(url: String) = NhentaiProtocol.linkRequest(url)
    override fun feedTitle(request: FeedRequest) = if (request.kind == FeedKind.POPULAR) "今日热门" else if (request.kind == FeedKind.LATEST) "最新画廊" else request.target
    override suspend fun feed(request: FeedRequest): FeedPage {
        if (request.language == GalleryLanguage.CHINESE_JAPANESE) return combinedLanguages(request)
        val result = json(resolve(request))
        return NhentaiProtocol.feed(result, request.page, servers())
    }

    /** The public API has no documented OR operator. Merge two disjoint, paginated queries. */
    private suspend fun combinedLanguages(request: FeedRequest): FeedPage {
        require(request.page in 1..1_000_000) { "页码无效" }
        val positions = if (request.cursor.isBlank()) intArrayOf(1, 0, 1, 0) else
            request.cursor.split(',').map { it.toIntOrNull() ?: error("语言翻页状态无效，请刷新列表") }.toIntArray()
        require(positions.size == 4 && positions.all { it in 0..1_000_000 }) { "语言翻页状态无效，请刷新列表" }
        val loaded = mutableMapOf<String, FeedPage>()
        suspend fun head(stream: Int): Video? {
            val slot = stream * 2
            while (positions[slot] > 0) {
                val language = if (stream == 0) GalleryLanguage.CHINESE else GalleryLanguage.JAPANESE
                val builder = resolve(request.copy(language = language, page = positions[slot], cursor = "")).toHttpUrl().newBuilder()
                if (stream == 1) builder.setQueryParameter("query", builder.build().queryParameter("query") + " -language:chinese")
                val url = builder.build().toString()
                val page = loaded[url] ?: languagePages[url]?.takeIf { System.currentTimeMillis() - it.first < 3 * 60 * 1000 }?.second
                    ?: NhentaiProtocol.feed(json(url), positions[slot], servers()).also { languagePages[url] = System.currentTimeMillis() to it }
                loaded[url] = page
                page.videos.getOrNull(positions[slot + 1])?.let { return it }
                if (!page.hasNext || page.videos.isEmpty()) { positions[slot] = 0; break }
                positions[slot]++; positions[slot + 1] = 0
            }
            return null
        }
        val cards = mutableListOf<Video>()
        while (cards.size < 24) {
            val chinese = head(0); val japanese = head(1)
            if (chinese == null && japanese == null) break
            val stream = when {
                chinese == null -> 1
                japanese == null -> 0
                request.kind == FeedKind.POPULAR || request.sort == Sort.POPULARITY -> cards.size % 2
                else -> if ((chinese.id.toLongOrNull() ?: 0) >= (japanese.id.toLongOrNull() ?: 0)) 0 else 1
            }
            cards.add(if (stream == 0) chinese!! else japanese!!)
            positions[stream * 2 + 1]++
        }
        val hasMore = head(0) != null || head(1) != null
        return FeedPage(cards, request.page, request.page + if (hasMore) 1 else 0, "画廊", nextCursor = if (hasMore) positions.joinToString(",") else null)
    }
    override suspend fun gallery(url: String): GalleryDetail {
        val id = NhentaiProtocol.galleryId(url) ?: error("无法识别 nhentai 画廊地址")
        return NhentaiProtocol.gallery(info(id), servers())
    }
    override suspend fun galleryImage(url: String): GalleryImage {
        val (id, page) = NhentaiProtocol.pageIdentity(url) ?: error("无法识别 nhentai 页码")
        val refresh = url.toHttpUrl().queryParameter("reload") == "1"
        return NhentaiProtocol.image(info(id, refresh), page, servers(refresh))
    }
    override suspend fun index(section: String): List<Link> = emptyList()
    override suspend fun detail(url: String): Detail = error("请使用漫画阅读器")
    override suspend fun login(network: Network, username: String, password: String): Unit = error("nhentai 当前支持公开浏览")
}

object NhentaiProtocol {
    fun availableSorts(request: FeedRequest) = if (request.kind == FeedKind.POPULAR) listOf(Sort.POPULARITY)
        else listOf(Sort.NEWEST, Sort.POPULARITY)
    private val site = SiteRegistry.NHENTAI
    data class Cdn(val images: List<String>, val thumbs: List<String>)
    private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.number(key: String) = text(key).toIntOrNull() ?: 0
    fun cdn(json: JsonObject): Cdn {
        fun hosts(key: String) = json.getAsJsonArray(key)?.map { it.asString.trimEnd('/') }?.filter { raw ->
            raw.toHttpUrlOrNull()?.let { it.isHttps && Regex("[it]\\d*\\.nhentai\\.net").matches(it.host) && it.encodedPath == "/" } == true
        }.orEmpty().also { check(it.isNotEmpty()) { "nhentai 未返回有效图片服务器" } }
        return Cdn(hosts("image_servers"), hosts("thumb_servers"))
    }
    fun resolve(request: FeedRequest): String {
        require(request.page in 1..1_000_000) { "页码无效" }
        val target = when (request.kind) {
            FeedKind.MODEL -> "artist:\"${request.target.replace("\"", "") }\""
            FeedKind.CATEGORY -> "category:${request.target}"
            else -> request.target
        }
        val query = listOf(target, request.language.languages.firstOrNull()?.let { "language:$it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        val builder = "${site.base}/api/v2".toHttpUrl().newBuilder()
        return when {
            request.kind == FeedKind.POPULAR && request.language == GalleryLanguage.ALL -> builder.addPathSegments("galleries/popular")
            query.isNotBlank() || request.sort == Sort.POPULARITY -> builder.addPathSegment("search")
                .addQueryParameter("query", query.ifBlank { "pages:>0" }).addQueryParameter("sort", when {
                    request.kind == FeedKind.POPULAR -> "popular-today"
                    request.sort == Sort.POPULARITY -> "popular"
                    else -> "date"
                }).addQueryParameter("page", request.page.toString())
            else -> builder.addPathSegment("galleries").addQueryParameter("page", request.page.toString())
        }.build().toString()
    }
    fun galleryId(raw: String): String? = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) }?.let { Regex("^/g/(\\d+)/?$").matchEntire(it.encodedPath)?.groupValues?.get(1) }
    fun pageIdentity(raw: String): Pair<String, Int>? = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) }?.let {
        Regex("^/g/(\\d+)/(\\d+)/?$").matchEntire(it.encodedPath)?.groupValues?.let { parts -> parts[2].toIntOrNull()?.takeIf { page -> page > 0 }?.let { page -> parts[1] to page } }
    }
    fun linkRequest(raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        url.queryParameter("q")?.let { return FeedRequest(FeedKind.SEARCH, it) }
        val parts = url.pathSegments.filter { it.isNotBlank() }
        if (parts.size != 2 || parts[0] !in setOf("tag", "artist", "group", "parody", "character", "language", "category")) return null
        return FeedRequest(FeedKind.SEARCH, "${parts[0]}:\"${parts[1].replace('-', ' ')}\"")
    }
    private fun media(host: String, path: String, mediaId: String): String {
        // The API occasionally repeats a cover extension; retain the supplied file type once.
        val clean = path.trimStart('/').replace(Regex("(\\.(?:webp|jpg|png|gif))\\1+$"), "$1")
        require(Regex("galleries/${Regex.escape(mediaId)}/[A-Za-z0-9_.-]+\\.(?:webp|jpg|png|gif)").matches(clean)) { "nhentai 图片地址无效" }
        return "$host/$clean"
    }
    private fun entry(json: JsonObject, cdn: Cdn): Video {
        val id = json.text("id"); val mediaId = json.text("media_id")
        require(id.matches(Regex("[0-9]+")) && mediaId.matches(Regex("[0-9]+"))) { "nhentai 画廊信息无效" }
        val title = json.text("english_title").ifBlank { json.getAsJsonObject("title")?.text("english").orEmpty() }.ifBlank { json.text("japanese_title") }
        check(title.isNotBlank()) { "nhentai 画廊缺少标题" }
        val thumbnail = json.get("thumbnail")?.let { if (it.isJsonObject) it.asJsonObject.text("path") else it.asString }.orEmpty()
        val dimensions = json.get("thumbnail")?.takeIf { it.isJsonObject }?.asJsonObject
        return Video(id, title, "${site.base}/g/$id/", media(cdn.thumbs.first(), thumbnail, mediaId), "${json.number("num_pages")} 页", rating = "${json.number("num_favorites")} 收藏", site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = json.number("thumbnail_width").takeIf { it > 0 } ?: dimensions?.number("width") ?: 0,
            thumbHeight = json.number("thumbnail_height").takeIf { it > 0 } ?: dimensions?.number("height") ?: 0)
    }
    fun feed(json: JsonElement, page: Int, cdn: Cdn): FeedPage {
        val items = if (json.isJsonArray) json.asJsonArray else json.asJsonObject.getAsJsonArray("result") ?: error("nhentai 未返回画廊列表")
        val total = if (json.isJsonArray) 1 else json.asJsonObject.number("num_pages").coerceAtLeast(1)
        return FeedPage(items.map { entry(it.asJsonObject, cdn) }, page, total, "画廊")
    }
    fun gallery(json: JsonObject, cdn: Cdn): GalleryDetail {
        val entry = entry(json, cdn)
        val pages = json.getAsJsonArray("pages") ?: error("nhentai 未返回图页")
        check(pages.size() > 0 && pages.size() == json.number("num_pages")) { "nhentai 图页数量不完整，请重试" }
        val groups = linkedMapOf<String, MutableList<Link>>()
        var language = ""
        json.getAsJsonArray("tags")?.forEach { raw ->
            val tag = raw.asJsonObject; val type = tag.text("type"); val name = tag.text("name")
            if (type == "language") language = listOf(language, name).filter { it.isNotBlank() }.joinToString(" / ")
            val query = "$type:\"${name.replace("\"", "")}\""
            val url = site.base.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("q", query).build().toString()
            groups.getOrPut(if (type == "artist") "画师" else "标签") { mutableListOf() }.add(Link(if (type == "artist") name else query, name, url))
        }
        return GalleryDetail(entry.copy(views = language), pages.size(), (1..pages.size()).map { GalleryPageLink(it, "${entry.url}$it/") }, pages.size(),
            json.getAsJsonObject("title")?.text("japanese").orEmpty(), language = language, description = "${json.number("num_favorites")} 人收藏", groups = groups)
    }
    fun image(json: JsonObject, page: Int, cdn: Cdn): GalleryImage {
        val pages = json.getAsJsonArray("pages") ?: error("nhentai 未返回图页")
        require(page in 1..pages.size()) { "页码超出范围" }
        val image = pages[page - 1].asJsonObject
        check(image.number("number") == page) { "原站页码顺序已变化，请刷新画廊" }
        val id = json.text("id"); val base = "${site.base}/g/$id/"
        return GalleryImage(page, "$base$page/", media(cdn.images[(page - 1) % cdn.images.size], image.text("path"), json.text("media_id")),
            if (page > 1) "$base${page-1}/" else null, if (page < pages.size()) "$base${page+1}/" else null, "$base$page/?reload=1")
    }
}
