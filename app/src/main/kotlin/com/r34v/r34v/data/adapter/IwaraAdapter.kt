package com.r34v.r34v.data.adapter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Iwara's public JSON API. Protocol reference: LoveIwara, see docs/dev/iwara-adapter.md. */
class IwaraAdapter(override val site: SiteContext, private val network: Network) : SiteAdapter {
    override val capabilities = setOf(Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL, Capability.RANKING, Capability.DOWNLOAD)
    override val sorts = listOf(Sort.RELEVANCE, Sort.NEWEST, Sort.TRENDING, Sort.POPULARITY, Sort.RATING, Sort.VIEWS)
    override val sections = listOf(
        Section("trending", "趋势榜", "看看最近受到关注的作品", FeedRequest(sort = Sort.TRENDING)),
        Section("latest", "最新上传", feed = FeedRequest()),
        Section("images", "最新图库", "图片与图集，使用独立图库接口", FeedRequest(contentKind = MediaKind.GALLERY)),
        Section("image-likes", "图库点赞榜", feed = FeedRequest(sort = Sort.RATING, contentKind = MediaKind.GALLERY)),
        Section("popular", "热门榜", feed = FeedRequest(sort = Sort.POPULARITY)),
        Section("liked", "点赞榜", feed = FeedRequest(sort = Sort.RATING)),
        Section("views", "播放榜", feed = FeedRequest(sort = Sort.VIEWS)),
    )
    private val signature = IwaraSignature(network)
    private val galleryDetails = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    override fun homeUrl() = resolve(homeRequest())
    override fun resolve(request: FeedRequest) = IwaraProtocol.resolve(site, request)
    override fun sortsFor(request: FeedRequest) = IwaraProtocol.availableSorts(request)
    override fun sortTitle(sort: Sort) = if (sort == Sort.RATING) "点赞" else sort.title
    override fun feedTitle(request: FeedRequest) = request.target.ifBlank {
        when (request.kind) {
            FeedKind.POPULAR -> "热门榜"
            FeedKind.RATED -> "点赞榜"
            else -> "${sortTitle(request.sort)}${if (request.contentKind == MediaKind.GALLERY) "图库" else "视频"}"
        }
    }

    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(
        kind, query, if (kind == FeedKind.SEARCH) Sort.RELEVANCE else Sort.NEWEST,
    )

    override fun linkRequest(url: String): FeedRequest? = IwaraProtocol.linkRequest(site, url)

    override suspend fun feed(request: FeedRequest): FeedPage {
        val resolved = if (request.kind == FeedKind.MODEL && !IwaraProtocol.isUserId(request.target)) {
            val profileUrl = site.apiBase.toHttpUrl().newBuilder().addPathSegment("profile").addPathSegment(request.target).build()
            val profile = network.json(profileUrl.toString()).asJsonObject
            val user = profile.obj("user")?.string("id").orEmpty()
            check(user.isNotBlank()) { "没有找到该作者，请输入准确的用户名" }
            request.copy(target = user)
        } else request
        val page = if (request.contentKind == MediaKind.GALLERY) IwaraProtocol.imageFeed(network.json(resolve(resolved)), site, request)
            else IwaraProtocol.feed(network.json(resolve(resolved)), site, request)
        return if (request.contentKind == MediaKind.VIDEO && (request.filter.minDurationSec > 0 || request.filter.maxDurationSec > 0 || request.filter.minRating > 0))
            page.copy(videos = page.videos.filter(request.filter::matchesLoaded)) else page
    }

    override suspend fun detail(url: String): Detail = coroutineScope {
        val id = IwaraProtocol.videoId(site, url) ?: error("无法识别 Iwara 视频地址")
        val endpoint = site.apiBase.toHttpUrl().newBuilder().addPathSegment("video").addPathSegment(id).build().toString()
        val metadata = network.json(endpoint).asJsonObject
        check(metadata.string("embedUrl").isBlank()) { "该视频为外站嵌入，暂不支持在应用内播放" }
        val fileUrl = metadata.string("fileUrl")
        check(fileUrl.isNotBlank()) {
            if (metadata.get("private")?.toString() == "true") "该视频需要登录或作者授权，当前仅支持公开视频"
            else "视频没有可用片源，可能尚在转码或已被移除"
        }
        val related = async { optional { network.json("$endpoint/related").asJsonObject.array("results") } }
        val comments = async { optional { network.json("$endpoint/comments?limit=12&page=0").asJsonObject.array("results") } }
        val sources = signature.sources(fileUrl)
        IwaraProtocol.detail(metadata, sources, site, related.await(), comments.await())
    }

    private suspend fun optional(load: suspend () -> JsonArray): JsonArray = try {
        withTimeoutOrNull(1200) { load() } ?: JsonArray()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        JsonArray()
    }

    private suspend fun galleryMetadata(id: String, refresh: Boolean = false): JsonObject {
        if (!refresh) galleryDetails[id]?.let { return it }
        if (galleryDetails.size > 16) galleryDetails.clear()
        val endpoint = site.apiBase.toHttpUrl().newBuilder().addPathSegment("image").addPathSegment(id).build().toString()
        return network.json(endpoint).asJsonObject.also { galleryDetails[id] = it }
    }
    override suspend fun gallery(url: String): GalleryDetail {
        val id = IwaraProtocol.imageId(site, url) ?: error("无法识别 Iwara 图库地址")
        return IwaraProtocol.gallery(galleryMetadata(id), site).also {
            check(it.pageCount > 0) { "该图库没有可阅读的图片文件" }
        }
    }
    override suspend fun galleryImage(url: String): GalleryImage {
        val parsed = url.toHttpUrl()
        val id = IwaraProtocol.imageId(site, url) ?: error("无法识别 Iwara 图库页地址")
        val page = (parsed.fragment?.removePrefix("page=")?.toIntOrNull() ?: parsed.queryParameter("page")?.toIntOrNull()) ?: error("Iwara 图库页码缺失")
        return IwaraProtocol.image(galleryMetadata(id, parsed.queryParameter("reload") == "1"), site, page)
    }

    override suspend fun index(section: String): List<Link> = emptyList()
    override suspend fun login(network: Network, username: String, password: String): Unit =
        error("Iwara 当前支持公开浏览，账号登录尚未接入")
}

/** Pure protocol functions shared by the adapter and its contract tests. */
object IwaraProtocol {
    fun availableSorts(request: FeedRequest): List<Sort> = when (request.kind) {
        FeedKind.POPULAR -> listOf(Sort.POPULARITY)
        FeedKind.RATED -> listOf(Sort.RATING)
        else -> listOf(Sort.RELEVANCE, Sort.NEWEST, Sort.TRENDING, Sort.POPULARITY, Sort.RATING, Sort.VIEWS)
            .filter { request.kind == FeedKind.SEARCH || it != Sort.RELEVANCE }
    }
    fun isUserId(value: String) = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}").matches(value)

    fun resolve(site: SiteContext, request: FeedRequest): String {
        require(request.page > 0) { "页码必须大于零" }
        val search = request.kind == FeedKind.SEARCH
        val gallery = request.contentKind == MediaKind.GALLERY
        val url = site.apiBase.toHttpUrl().newBuilder().addPathSegment(if (search) "search" else if (gallery) "images" else "videos")
            .addQueryParameter("page", (request.page - 1).toString()).addQueryParameter("limit", "20")
        if (search) url.addQueryParameter("type", if (gallery) "images" else "videos").addQueryParameter("query", request.target)
        val tags = (listOf(request.target.takeIf { request.kind == FeedKind.TAG || request.kind == FeedKind.CHARACTER }.orEmpty()) + request.includeTags).filter(String::isNotBlank)
        if (tags.isNotEmpty()) url.addQueryParameter("tags", tags.joinToString(","))
        if (request.kind == FeedKind.MODEL) url.addQueryParameter("user", request.target)
        val sort = when {
            request.kind == FeedKind.POPULAR -> "popularity"
            request.kind == FeedKind.RATED -> "likes"
            request.sort == Sort.RELEVANCE && search -> null
            else -> when (request.sort) {
                Sort.TRENDING -> "trending"
                Sort.POPULARITY -> "popularity"
                Sort.VIEWS -> "views"
                Sort.RATING -> "likes"
                else -> "date"
            }
        }
        sort?.let { url.addQueryParameter("sort", it) }
        return url.build().toString()
    }

    fun videoId(site: SiteContext, url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!site.isSite(parsed.host)) return null
        val parts = parsed.pathSegments
        return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "video" && Regex("[A-Za-z0-9_-]+").matches(it) }
    }
    fun imageId(site: SiteContext, url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!site.isSite(parsed.host)) return null
        val parts = parsed.pathSegments
        return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "image" && Regex("[A-Za-z0-9_-]+").matches(it) }
    }

    fun linkRequest(site: SiteContext, url: String): FeedRequest? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!site.isSite(parsed.host)) return null
        val parts = parsed.pathSegments
        if (parts.firstOrNull() in setOf("profile", "user")) {
            return parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let { FeedRequest(FeedKind.MODEL, it) }
        }
        val content = if (parts.firstOrNull() == "images") MediaKind.GALLERY else MediaKind.VIDEO
        parsed.queryParameter("user")?.takeIf(String::isNotBlank)?.let { return FeedRequest(FeedKind.MODEL, it, contentKind = content) }
        return parsed.queryParameter("tags")?.takeIf(String::isNotBlank)?.let { FeedRequest(FeedKind.TAG, it, contentKind = content) }
    }

    fun feed(data: JsonElement, site: SiteContext, request: FeedRequest): FeedPage {
        require(data.isJsonObject && data.asJsonObject.get("results")?.isJsonArray == true) { "Iwara 列表响应格式已变化" }
        val root = data.asJsonObject
        val current = (root.number("page", request.page - 1).toInt() + 1).coerceAtLeast(1)
        val limit = root.number("limit", 20).coerceAtLeast(1)
        val videos = root.array("results").mapNotNull { if (it.isJsonObject) video(it.asJsonObject, site) else null }.distinctBy { it.id }
        val count = root.number("count", 0).coerceAtLeast(0)
        val total = ((count + limit - 1) / limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(current)
        return FeedPage(videos, current, total, request.target)
    }

    fun imageFeed(data: JsonElement, site: SiteContext, request: FeedRequest): FeedPage {
        require(data.isJsonObject && data.asJsonObject.get("results")?.isJsonArray == true) { "Iwara 图库列表响应格式已变化" }
        val root = data.asJsonObject
        val current = (root.number("page", request.page - 1).toInt() + 1).coerceAtLeast(1)
        val limit = root.number("limit", 20).coerceAtLeast(1)
        val entries = root.array("results").mapNotNull { if (it.isJsonObject) imageEntry(it.asJsonObject, site) else null }.distinctBy { it.id }
        val count = root.number("count", 0).coerceAtLeast(0)
        val total = ((count + limit - 1) / limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(current)
        return FeedPage(entries, current, total, request.target)
    }

    fun imageEntry(data: JsonObject, site: SiteContext): Video? {
        val id = data.string("id")
        if (id.isBlank() || !Regex("[A-Za-z0-9_-]+").matches(id)) return null
        val title = data.string("title").ifBlank { "图库 $id" }
        val thumbnail = data.obj("thumbnail")
        val name = thumbnail?.string("name").orEmpty()
        val poster = if (name.contains('.')) name.substringBeforeLast('.') + ".jpg" else "$name.jpg"
        val thumb = if (thumbnail?.string("id")?.isNotBlank() == true && name.isNotBlank()) imageUrl("thumbnail", thumbnail.string("id"), poster) else ""
        val canonical = site.base.toHttpUrl().newBuilder().addPathSegment("image").addPathSegment(id)
            .apply { data.string("slug").takeIf(String::isNotBlank)?.let(::addPathSegment) }.build().toString()
        return Video(id, title, canonical, thumb, "${data.number("numImages", 0)} 张", "${data.number("numViews", 0)} 次浏览",
            "${data.number("numLikes", 0)} 赞", "图库", site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = thumbnail?.number("width", 0)?.toInt() ?: 0, thumbHeight = thumbnail?.number("height", 0)?.toInt() ?: 0)
    }

    private fun galleryFiles(data: JsonObject, site: SiteContext): Pair<List<GalleryImage>, Int> {
        val entry = imageEntry(data, site) ?: error("Iwara 图库缺少有效身份")
        data class FilePart(val id: String, val name: String, val width: Int, val height: Int)
        var skipped = 0
        val files = data.array("files").mapNotNull { raw ->
            if (!raw.isJsonObject) return@mapNotNull null
            val file = raw.asJsonObject
            val type = file.string("type"); val mime = file.string("mime"); val name = file.string("name")
            val video = type.equals("video", true) || mime.startsWith("video/", true) || name.substringAfterLast('.').lowercase() in setOf("mp4", "webm", "mov", "avi", "mkv", "flv", "wmv", "m4v")
            if (video) { skipped++; return@mapNotNull null }
            val id = file.string("id")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            FilePart(id, name, file.number("width", 0).toInt(), file.number("height", 0).toInt())
        }
        fun pageUrl(page: Int) = entry.url.toHttpUrl().newBuilder().fragment("page=$page").build().toString()
        val images = files.mapIndexed { offset, file ->
            val page = offset + 1
            GalleryImage(page, pageUrl(page), imageUrl("original", file.id, file.name), if (page > 1) pageUrl(page - 1) else null,
                if (page < files.size) pageUrl(page + 1) else null, pageUrl(page).toHttpUrl().newBuilder().setQueryParameter("reload", "1").build().toString())
        }
        return images to skipped
    }

    fun gallery(data: JsonObject, site: SiteContext): GalleryDetail {
        val rawEntry = imageEntry(data, site) ?: error("Iwara 图库缺少有效身份")
        val (images, skipped) = galleryFiles(data, site)
        val entry = rawEntry.copy(duration = if (skipped > 0) "${images.size} 张可读图片" else "${images.size} 张",
            thumbWidth = rawEntry.thumbWidth.takeIf { it > 0 } ?: data.array("files").firstOrNull()?.asJsonObject?.number("width", 0)?.toInt() ?: 0,
            thumbHeight = rawEntry.thumbHeight.takeIf { it > 0 } ?: data.array("files").firstOrNull()?.asJsonObject?.number("height", 0)?.toInt() ?: 0)
        val groups = linkedMapOf<String, List<Link>>()
        data.array("tags").mapNotNull { raw ->
            if (!raw.isJsonObject) return@mapNotNull null
            val tag = raw.asJsonObject.string("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            Link(tag, tag, site.base.toHttpUrl().newBuilder().addPathSegment("images").addQueryParameter("tags", tag).build().toString())
        }.takeIf { it.isNotEmpty() }?.let { groups["标签"] = it }
        data.obj("user")?.let { user ->
            val id = user.string("id")
            if (id.isNotBlank()) groups["作者"] = listOf(Link(id, user.string("name").ifBlank { user.string("username") }.ifBlank { id },
                site.base.toHttpUrl().newBuilder().addPathSegment("images").addQueryParameter("user", id).build().toString()))
        }
        val note = if (skipped > 0) "此图集另有 $skipped 个视频文件，未计入图片阅读页数。" else ""
        return GalleryDetail(entry, images.size, images.map { GalleryPageLink(it.index, it.pageUrl) }, images.size,
            description = listOf(data.string("body"), note).filter(String::isNotBlank).joinToString("\n"), groups = groups)
    }

    fun image(data: JsonObject, site: SiteContext, page: Int): GalleryImage {
        val (images, _) = galleryFiles(data, site)
        return images.getOrNull(page - 1) ?: error("Iwara 图库没有第 $page 张可读图片")
    }

    fun video(data: JsonObject, site: SiteContext): Video? {
        val id = data.string("id"); val title = data.string("title")
        if (id.isBlank() || title.isBlank()) return null
        val file = data.obj("file")
        val fileId = file?.string("id").orEmpty()
        val custom = data.obj("customThumbnail")
        val thumb = when {
            custom?.string("id")?.isNotBlank() == true && custom.string("name").isNotBlank() ->
                imageUrl("thumbnail", custom.string("id"), custom.string("name"))
            fileId.isNotBlank() -> imageUrl("thumbnail", fileId, "thumbnail-${data.number("thumbnail", 0).toString().padStart(2, '0')}.jpg")
            else -> ""
        }
        val seconds = file?.number("duration", 0) ?: 0
        val duration = if (seconds <= 0) "" else if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
        val canonical = site.base.toHttpUrl().newBuilder().addPathSegment("video").addPathSegment(id)
            .apply { data.string("slug").takeIf(String::isNotBlank)?.let(::addPathSegment) }.build().toString()
        return Video(id, title, canonical, thumb, duration, "${data.number("numViews", 0)} 次观看",
            "${data.number("numLikes", 0)} 赞", if (data.string("embedUrl").isNotBlank()) "站外视频" else "",
            if (fileId.isNotBlank()) imageUrl("original", fileId, "preview.webp") else "", site.id)
    }

    private fun imageUrl(size: String, id: String, name: String) = "https://i.iwara.tv".toHttpUrl().newBuilder()
        .addPathSegment("image").addPathSegment(size).addPathSegment(id).addPathSegment(name).build().toString()

    fun detail(metadata: JsonObject, sources: JsonArray, site: SiteContext, related: JsonArray = JsonArray(), comments: JsonArray = JsonArray()): Detail {
        val video = video(metadata, site) ?: error("Iwara 详情缺少视频信息")
        val originalHeight = metadata.obj("file")?.number("height", 0)?.toInt() ?: 0
        fun streams(download: Boolean) = sources.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val source = item.asJsonObject; val name = source.string("name")
            if (name.equals("preview", true)) return@mapNotNull null
            val height = if (name.equals("Source", true)) originalHeight else name.removeSuffix("p").toIntOrNull() ?: return@mapNotNull null
            val src = source.obj("src") ?: return@mapNotNull null
            val raw = (if (download) src.string("download") else src.string("view")).ifBlank { src.string("view") }
            val url = (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@mapNotNull null
            val label = if (name.equals("Source", true)) if (height > 0) "${height}p 原画" else "原画" else "${height}p"
            Stream(label, url.toString())
        }.distinctBy { it.label }.sortedByDescending { it.height }
        val playback = streams(false)
        check(playback.isNotEmpty()) { "Iwara 没有可用的 HTTPS 视频源，请稍后重试" }
        val groups = linkedMapOf<String, List<Link>>()
        metadata.array("tags").mapNotNull { item ->
            val tag = if (item.isJsonObject) item.asJsonObject.string("id") else ""
            if (tag.isBlank()) null else Link(tag, tag, site.base.toHttpUrl().newBuilder().addPathSegment("videos").addQueryParameter("tags", tag).build().toString())
        }.takeIf { it.isNotEmpty() }?.let { groups["标签"] = it }
        metadata.obj("user")?.let { user ->
            val id = user.string("id"); val username = user.string("username")
            if (id.isNotBlank()) groups["作者"] = listOf(Link(id, user.string("name").ifBlank { username }.ifBlank { id },
                site.base.toHttpUrl().newBuilder().addPathSegment(if (username.isBlank()) "user" else "profile").addPathSegment(username.ifBlank { id }).build().toString()))
        }
        val discussion = comments.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val comment = item.asJsonObject; val body = comment.string("body")
            if (body.isBlank()) null else Comment(comment.obj("user")?.string("name").orEmpty().ifBlank { "用户" }, body, comment.string("createdAt").substringBefore('T'))
        }
        return Detail(video, playback, groups, metadata.string("body"),
            related.mapNotNull { if (it.isJsonObject) video(it.asJsonObject, site) else null }.filter { it.id != video.id }.distinctBy { it.id },
            discussion, streams(true))
    }
}

/** Public frontend signature. Only a verified replacement salt is retained in memory. */
internal class IwaraSignature(private val network: Network) {
    @Volatile private var salt = DEFAULT_SALT
    private var checkedAt = 0L
    private val mutex = Mutex()

    suspend fun sources(fileUrl: String): JsonArray {
        val used = salt
        val first = network.json(fileUrl, headers(fileUrl, used)).asJsonArray
        if (first.isEmpty || hasOriginal(first)) return first
        val replacement = mutex.withLock {
            if (salt != used) return@withLock salt
            if (System.currentTimeMillis() - checkedAt < 10 * 60 * 1000) return@withLock null
            checkedAt = System.currentTimeMillis()
            try {
                withTimeoutOrNull(5000) {
                    val script = extractScript(network.text(SiteRegistry.IWARA.base)) ?: return@withTimeoutOrNull null
                    extractSalt(network.text(SiteRegistry.IWARA.absolute(script)))?.takeIf { it != used }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        } ?: return first
        val retried = try { network.json(fileUrl, headers(fileUrl, replacement)).asJsonArray }
        catch (error: Exception) { if (error is CancellationException) throw error; return first }
        if (!hasOriginal(retried)) return first
        mutex.withLock { salt = replacement }
        return retried
    }

    companion object {
        const val DEFAULT_SALT = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
        fun headers(fileUrl: String, salt: String): Map<String, String> {
            val url = fileUrl.toHttpUrl()
            require(url.isHttps && url.host in setOf("files.iwara.tv", "filesq.iwara.tv") && url.pathSegments.firstOrNull() == "file") { "Iwara 返回了无法识别的文件地址" }
            val id = requireNotNull(url.pathSegments.last().takeIf(String::isNotBlank)) { "Iwara 文件 ID 缺失" }
            val expires = requireNotNull(url.queryParameter("expires")?.takeIf { it.toLongOrNull() != null }) { "Iwara 文件地址缺少有效期" }
            val digest = MessageDigest.getInstance("SHA-1").digest("${id}_${expires}_$salt".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return mapOf("X-Version" to digest)
        }
        fun hasOriginal(sources: JsonArray) = sources.any { it.isJsonObject && it.asJsonObject.string("name").equals("Source", true) }
        fun extractScript(html: String) = Regex("""src=["'](/main\.[a-fA-F0-9]+\.js)["']""").find(html)?.groupValues?.get(1)
        fun extractSalt(script: String) = Regex(""""_"\+[\w$.]+expires\+"_([A-Za-z0-9]{16,})"""").find(script)?.groupValues?.get(1)
    }
}

private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
private fun JsonObject.number(name: String, fallback: Int): Long = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() ?: fallback.toLong()
private fun JsonObject.obj(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.array(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
