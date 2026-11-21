package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedPage
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.GalleryDetail
import com.r34v.r34v.data.GalleryImage
import com.r34v.r34v.data.GalleryPageLink
import com.r34v.r34v.data.GalleryResponseException
import com.r34v.r34v.data.Link
import com.r34v.r34v.data.MediaKind
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Sort
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.readResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class JmcomicAdapter(override val site: SiteContext, private val network: Network) : SiteAdapter {
    override val capabilities = setOf(Capability.FEED, Capability.SEARCH, Capability.DOWNLOAD)
    override val sections = listOf(Section("latest", "最新漫画", feed = FeedRequest()))
    override val sorts = listOf(Sort.NEWEST, Sort.RELEVANCE)
    override val supportsLanguage = false

    private val mirrorUrls = JmcomicProtocol.hosts.map { host -> resolve(homeRequest()).toHttpUrl().newBuilder().host(host).build().toString() }
    private val router = JmcomicMirrorRouter(
        JmcomicProtocol.hosts,
        routeKey = { network.systemRouteKey(mirrorUrls) },
        probe = { host ->
            val document = document(resolve(homeRequest()), host)
            JmcomicProtocol.siteUrl(document.location())?.host?.takeIf { JmcomicProtocol.hasCatalogue(document) }
        },
    )
    private data class ChapterCache(val route: JmcomicMirrorRouter.Route, val chapter: JmcomicProtocol.Chapter)
    private val chapters = object : LinkedHashMap<String, ChapterCache>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterCache>?) = size > 16
    }

    override fun homeUrl() = resolve(homeRequest())
    override fun resolve(request: FeedRequest) = JmcomicProtocol.resolve(site, request)
    override fun linkRequest(url: String) = JmcomicProtocol.linkRequest(url)
    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(FeedKind.SEARCH, query.trim(), Sort.RELEVANCE)
    override fun sortsFor(request: FeedRequest) = when (request.kind) {
        FeedKind.SEARCH -> listOf(Sort.RELEVANCE)
        FeedKind.CATEGORY -> emptyList()
        else -> listOf(Sort.NEWEST)
    }
    override fun feedTitle(request: FeedRequest) = when (request.kind) {
        FeedKind.CATEGORY -> "全部章节"
        FeedKind.SEARCH -> request.target.trim().ifEmpty { "最新漫画" }
        else -> "最新漫画"
    }

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        return router.load { route ->
            val document = document(url, route.host)
            if (request.kind == FeedKind.CATEGORY) JmcomicProtocol.chapterFeed(site, JmcomicProtocol.album(site, document, request.target))
            else JmcomicProtocol.feed(site, document, request, feedTitle(request))
        }
    }

    override suspend fun gallery(url: String): GalleryDetail {
        val identity = JmcomicProtocol.identity(url) ?: error("无法识别禁漫天堂漫画地址")
        val album = if (identity.kind == "album") router.load { route ->
            JmcomicProtocol.album(site, document(JmcomicProtocol.canonical(site, identity), route.host), identity.id)
        } else null
        val chapter = loadChapter(album?.readingId ?: identity.id)
        return JmcomicProtocol.gallery(site, album, chapter)
    }

    override suspend fun galleryImage(url: String): GalleryImage {
        val parsed = JmcomicProtocol.siteUrl(url) ?: error("无法识别禁漫天堂图页地址")
        val identity = JmcomicProtocol.identity(parsed) ?: error("无法识别禁漫天堂图页地址")
        val index = JmcomicProtocol.number(parsed.fragment) ?: error("无法识别禁漫天堂图页编号")
        require(identity.kind == "photo") { "无法识别禁漫天堂图页地址" }
        val chapter = loadChapter(identity.id, force = parsed.queryParameter("reload") == "1")
        return JmcomicProtocol.image(site, chapter, index)
    }

    private suspend fun loadChapter(id: String, force: Boolean = false): JmcomicProtocol.Chapter = router.load { route ->
        if (!force) synchronized(chapters) { chapters[id]?.takeIf { it.route === route } }?.let { return@load it.chapter }
        val url = JmcomicProtocol.canonical(site, JmcomicProtocol.Identity("photo", id))
        val chapter = JmcomicProtocol.chapter(document(url, route.host), id)
        synchronized(chapters) { chapters[id] = ChapterCache(route, chapter) }
        chapter
    }

    private suspend fun document(canonical: String, host: String): Document {
        val initial = canonical.toHttpUrl().newBuilder().host(host).build()
        val client = network.client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
            .build()
        return withTimeoutOrNull(5_500L) {
            var url = initial
            repeat(3) {
                val request = Request.Builder().url(url).tag(SiteContext::class.java, site)
                    .header("Referer", "https://${url.host}/").build()
                val (redirect, body) = client.readResponse(request) { response ->
                    if (response.header("cf-mitigated") == "challenge") {
                        throw GalleryResponseException(response.code, message = "禁漫天堂要求完成浏览器验证")
                    }
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        val target = response.header("Location")?.let(url::resolve)
                            ?.takeIf { JmcomicProtocol.redirectAllowed(initial, it) }
                            ?: throw GalleryResponseException(response.code, message = "禁漫天堂页面发生非预期跳转")
                        target to null
                    } else {
                        val status = response.code
                        if (!response.isSuccessful) {
                            if (status == 408 || status in 500..599) throw IOException("禁漫天堂请求失败：HTTP $status")
                            throw GalleryResponseException(status, response.header("Retry-After"), "禁漫天堂请求失败：HTTP $status")
                        }
                        val source = response.body?.source() ?: throw IOException("禁漫天堂返回空响应")
                        if (source.request(4L * 1024 * 1024 + 1)) throw IOException("禁漫天堂页面过大")
                        null to source.readUtf8()
                    }
                }
                if (body != null) return@withTimeoutOrNull Jsoup.parse(body, url.toString())
                url = checkNotNull(redirect)
            }
            throw GalleryResponseException(302, message = "禁漫天堂页面跳转次数过多")
        } ?: throw IOException("禁漫天堂页面请求超时")
    }

    override suspend fun detail(url: String) = error("请使用漫画阅读器打开此画廊")
    override suspend fun index(section: String): List<Link> = emptyList()
    override suspend fun login(network: Network, username: String, password: String): Unit = error("禁漫天堂支持公开浏览")
}

/** A lease is local to the current Android route and expires independently of feed storage. */
internal class JmcomicMirrorRouter(
    private val hosts: List<String>,
    private val routeKey: () -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val probe: suspend (String) -> String?,
) {
    data class Route(val host: String, val key: String, val expiresAt: Long)

    private val mutex = Mutex()
    private var cached: Route? = null

    suspend fun <T> load(request: suspend (Route) -> T): T {
        val attempted = mutableSetOf<String>()
        var failure: IOException? = null
        repeat(hosts.size) {
            val route = select(attempted) ?: throw failure ?: IOException("当前系统网络未找到可用的禁漫天堂公开镜像")
            try {
                return request(route)
            } catch (error: IOException) {
                failure = error
                attempted.add(route.host)
                mutex.withLock { if (cached === route) cached = null }
            }
        }
        throw failure ?: IOException("当前系统网络未找到可用的禁漫天堂公开镜像")
    }

    private suspend fun select(attempted: MutableSet<String>): Route? = mutex.withLock {
        val key = routeKey()
        cached?.takeIf { it.key == key && it.expiresAt > now() && it.host !in attempted }?.let { return@withLock it }
        for (candidate in hosts) {
            if (candidate in attempted) continue
            val healthy = try { probe(candidate) } catch (_: IOException) { null } catch (_: GalleryResponseException) { null }
            val eligible = healthy != null && healthy in hosts && healthy !in attempted && routeKey() == key
            attempted.add(candidate)
            if (eligible) return@withLock Route(checkNotNull(healthy), key, now() + ttlMs).also { cached = it }
        }
        null
    }
}

internal object JmcomicProtocol {
    val hosts = listOf(SiteRegistry.JMCOMIC.host) + SiteRegistry.JMCOMIC.mirrors
    private val cdnHosts = SiteRegistry.JMCOMIC.mediaDomains
    private val resource = Regex("/(album|photo)/([1-9][0-9]*)(?:/[^/]+)?/?")
    private val idPattern = Regex("[1-9][0-9]*")
    private val mediaFile = Regex("[0-9]+\\.(?:jpe?g|png|gif|webp|avif)", RegexOption.IGNORE_CASE)
    private val coverFile = Regex("[0-9]+(?:_[0-9]+x[0-9]+)?\\.(?:jpe?g|png|gif|webp|avif)", RegexOption.IGNORE_CASE)
    private val scriptValue = Regex("(?:^|(?<=[;\\r\\n]))\\s*(?:var|let|const)\\s+(scramble_id|aid|speed)\\s*=\\s*(['\"]?)([0-9]*)\\2\\s*;")
    private const val cataloguePath = "/albums"
    private const val searchPath = "/search/photos"

    data class Identity(val kind: String, val id: String)
    data class Episode(val id: String, val title: String)
    data class Album(val entry: Video, val readingId: String, val episodes: List<Episode>, val description: String)
    data class Page(val url: String, val strips: Int)
    data class Chapter(val id: String, val title: String, val referer: String, val pages: List<Page>)

    fun resolve(site: SiteContext, request: FeedRequest): String {
        val base = site.base.toHttpUrl().newBuilder()
        return when {
            request.kind == FeedKind.CATEGORY -> {
                require(validId(request.target)) { "无法识别禁漫天堂漫画编号" }
                canonical(site, Identity("album", request.target))
            }
            request.kind == FeedKind.SEARCH && request.target.isNotBlank() -> base.addPathSegments("search/photos")
                .addQueryParameter("search_query", request.target.trim()).withPage(request.page).build().toString()
            else -> base.addPathSegment("albums").addQueryParameter("o", "mr").withPage(request.page).build().toString()
        }
    }

    private fun HttpUrl.Builder.withPage(page: Int) = apply {
        require(page > 0) { "页码无效" }
        if (page > 1) addQueryParameter("page", page.toString())
    }

    fun linkRequest(raw: String): FeedRequest? {
        val url = siteUrl(raw) ?: return null
        val page = url.queryParameter("page")?.let { number(it) ?: return null } ?: 1
        return when (url.encodedPath) {
            searchPath -> url.queryParameter("search_query")?.takeIf { it.isNotBlank() }
                ?.let { FeedRequest(FeedKind.SEARCH, it, Sort.RELEVANCE, page) }
            cataloguePath -> if (url.queryParameter("o") == "mr") FeedRequest(page = page) else null
            else -> identity(url)?.takeIf { it.kind == "album" && url.fragment == "chapters" }
                ?.let { FeedRequest(FeedKind.CATEGORY, it.id) }
        }
    }

    fun siteUrl(raw: String) = raw.toHttpUrlOrNull()?.takeIf(::isSite)
    fun identity(raw: String) = siteUrl(raw)?.let(::identity)
    fun identity(url: HttpUrl): Identity? {
        if (!isSite(url)) return null
        val (kind, id) = resource.matchEntire(url.encodedPath)?.destructured ?: return null
        return Identity(kind, id).takeIf { validId(id) }
    }

    fun canonical(site: SiteContext, identity: Identity) = "${site.base.trimEnd('/')}/${identity.kind}/${identity.id}"

    fun redirectAllowed(initial: HttpUrl, target: HttpUrl): Boolean {
        if (!isSite(target) || target.fragment != null) return false
        val identity = identity(initial)
        return if (identity != null) identity(target) == identity && target.query == initial.query
        else target.encodedPath == initial.encodedPath && linkRequest(target.toString()) == linkRequest(initial.toString())
    }

    fun hasCatalogue(document: Document) = cards(document).isNotEmpty()

    fun feed(site: SiteContext, document: Document, request: FeedRequest, heading: String): FeedPage {
        val base = document.location().toHttpUrl()
        val emptySearch = base.encodedPath == searchPath && document.select("span.text-danger").any { it.text() == "查無A漫." } &&
            document.select("h3").any { it.text().startsWith("找不到和") && it.text().endsWith("相符的內容") }
        val entries = if (emptySearch) emptyList() else cards(document).map { (identity, title, cover) ->
            video(site, identity, title, cover)
        }.distinctBy(Video::id)
        if (entries.isEmpty() && !emptySearch) throw IOException("禁漫天堂未返回可识别的漫画列表")

        val active = linkRequest(base.toString()) ?: error("无法识别禁漫天堂列表地址")
        val next = if (request.page < Int.MAX_VALUE) request.page + 1 else null
        val hasNext = entries.isNotEmpty() && next != null && document.select(".pagination a[href], .pagination option[value]").any { item ->
            val target = base.resolve(item.attr(if (item.tagName() == "option") "value" else "href")) ?: return@any false
            linkRequest(target.toString()) == active.copy(page = next)
        }
        return FeedPage(entries, request.page, if (hasNext) checkNotNull(next) else request.page, heading)
    }

    private data class Card(val identity: Identity, val title: String, val cover: String)

    private fun cards(document: Document): List<Card> {
        val base = document.location().toHttpUrl()
        return document.select(".list-col").mapNotNull { card ->
            val overlay = card.selectFirst(".thumb-overlay-albums, .thumb-overlay") ?: return@mapNotNull null
            val link = overlay.selectFirst("a[href]") ?: return@mapNotNull null
            val identity = base.resolve(link.attr("href"))?.let(::identity)?.takeIf { it.kind == "album" } ?: return@mapNotNull null
            val title = card.selectFirst(".video-title")?.text().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            Card(identity, title, cover(base, overlay.selectFirst("img")))
        }
    }

    fun album(site: SiteContext, document: Document, id: String): Album {
        val base = document.location().toHttpUrl()
        val title = document.selectFirst("#book-name")?.text().orEmpty()
        if (title.isBlank()) throw IOException("禁漫天堂未返回可识别的漫画详情")
        val album = Identity("album", id)
        check(identity(base) == album) { "禁漫天堂返回了其他漫画" }
        val episodes = document.select("#episode-block .episode a[href]").mapNotNull { anchor ->
            val chapterId = chapterId(base, anchor) ?: return@mapNotNull null
            Episode(chapterId, anchor.text().ifBlank { "章节 $chapterId" })
        }.distinctBy(Episode::id)
        val readingId = document.select("a.reading[href]").firstNotNullOfOrNull { chapterId(base, it) } ?: episodes.firstOrNull()?.id
            ?: error("禁漫天堂未提供公开阅读入口")
        val thumbnail = document.select("img[itemprop=image]").asSequence().map { cover(base, it) }
            .firstOrNull(String::isNotEmpty).orEmpty()
        val entry = video(site, album, title, thumbnail)
        return Album(entry, readingId, episodes, document.selectFirst("meta[name=description]")?.attr("content").orEmpty())
    }

    fun chapterFeed(site: SiteContext, album: Album): FeedPage = FeedPage(
        album.episodes.map { chapter -> video(site, Identity("photo", chapter.id), "${album.entry.title} · ${chapter.title}", album.entry.thumb) },
        1, 1, "全部章节",
    )

    fun chapter(document: Document, id: String): Chapter {
        val base = document.location().toHttpUrl()
        check(identity(base) == Identity("photo", id)) { "禁漫天堂返回了其他章节" }
        val title = document.selectFirst("h1.pull-left")?.text().orEmpty()
        val images = document.select("img[id^=album_photo]")
        check(title.isNotBlank() && images.isNotEmpty()) { "禁漫天堂未返回可识别的公开图页" }
        val vars = document.select("script:not([src])").flatMap { scriptValue.findAll(it.data()).toList() }.associate { match ->
            match.groupValues[1] to match.groupValues[3]
        }
        check(vars["aid"] == null || vars["aid"] == id) { "禁漫天堂返回了其他章节" }
        val imagesByPage = images.map { image ->
            val index = image.attr("data-page").toIntOrNull()?.takeIf { it >= 0 } ?: error("禁漫天堂图页编号无效")
            val raw = image.attr("data-original")
            val url = mediaUrl(base, raw) ?: error("JMComic 第 ${index + 1} 页返回未知图源（${base.resolve(raw)?.host ?: "地址无效"}）")
            check(url.pathSegments.size == 4 && url.pathSegments.take(3) == listOf("media", "photos", id) && mediaFile.matches(url.pathSegments.last())) {
                "JMComic 第 ${index + 1} 页的图片路径与当前章节不匹配（${url.host}）"
            }
            val container = image.parent()?.takeIf { it.hasClass("scramble-page") }
            val filename = url.pathSegments.last()
            val strips = if (container == null || vars["speed"] == "1" || filename.endsWith(".gif", true)) 0 else {
                val scrambleId = vars["scramble_id"]?.toLongOrNull() ?: error("禁漫天堂图页缺少公开显示参数")
                check(vars["aid"] == id && container.id() == filename) { "禁漫天堂图页显示参数与图片地址不符" }
                stripCount(id.toLong(), scrambleId, filename.substringBefore('.'))
            }
            index to Page(url.toString(), strips)
        }.distinct().sortedBy { it.first }
        check(imagesByPage.withIndex().all { it.index == it.value.first }) { "禁漫天堂图页列表不完整或编号重复" }
        return Chapter(id, title, base.toString(), imagesByPage.map { it.second })
    }

    /** Public Canvas reader, jquery.photo-0.4.js: get_num takes the photo ID and image parent ID stem. */
    fun stripCount(id: Long, scrambleId: Long, filenameStem: String): Int {
        if (id < scrambleId) return 0
        if (id < 268850) return 10
        val lastByte = MessageDigest.getInstance("MD5").digest("$id$filenameStem".toByteArray(Charsets.UTF_8)).last().toInt()
        val lastHex = "0123456789abcdef"[lastByte and 15]
        return ((lastHex.code % if (id < 421926) 10 else 8) * 2) + 2
    }

    fun gallery(site: SiteContext, album: Album?, chapter: Chapter): GalleryDetail {
        val count = chapter.pages.size
        val entry = album?.entry ?: video(site, Identity("photo", chapter.id), chapter.title, "")
        val links = (1..count).map { GalleryPageLink(it, pageUrl(site, chapter.id, it)) }
        val groups = if (album != null && album.episodes.size > 1) {
            mapOf("章节" to listOf(Link(album.entry.id, "全部章节", "${album.entry.url}#chapters")))
        }
        else emptyMap()
        return GalleryDetail(entry.copy(duration = "$count 页"), count, links, count, description = album?.description.orEmpty(), groups = groups)
    }

    fun image(site: SiteContext, chapter: Chapter, index: Int): GalleryImage {
        require(index in 1..chapter.pages.size) { "页码超出范围" }
        val page = chapter.pages[index - 1]
        return GalleryImage(index, pageUrl(site, chapter.id, index), page.url,
            if (index > 1) pageUrl(site, chapter.id, index - 1) else null,
            if (index < chapter.pages.size) pageUrl(site, chapter.id, index + 1) else null,
            reloadUrl = "${canonical(site, Identity("photo", chapter.id))}?reload=1#$index",
            imageReferer = chapter.referer, imageStripCount = page.strips)
    }

    private fun pageUrl(site: SiteContext, id: String, index: Int) = "${canonical(site, Identity("photo", id))}#$index"
    private fun chapterId(base: HttpUrl, anchor: Element) = base.resolve(anchor.attr("href"))?.let(::identity)?.takeIf { it.kind == "photo" }?.id
    private fun validId(id: String) = idPattern.matches(id) && id.toLongOrNull() != null
    fun number(raw: String?) = raw?.takeIf(idPattern::matches)?.toIntOrNull()
    private fun isSite(url: HttpUrl) = url.host in hosts && https(url)
    private fun https(url: HttpUrl) = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    private fun video(site: SiteContext, identity: Identity, title: String, cover: String) = Video(
        if (identity.kind == "album") identity.id else "chapter-${identity.id}", title, canonical(site, identity), cover,
        site = site.id, mediaKind = MediaKind.GALLERY,
    )

    private fun cover(base: HttpUrl, image: Element?): String {
        if (image == null) return ""
        return listOf("data-original", "data-src", "src").firstNotNullOfOrNull { attribute ->
            mediaUrl(base, image.attr(attribute))?.takeIf {
                it.pathSegments.size == 3 && it.pathSegments.take(2) == listOf("media", "albums") && coverFile.matches(it.pathSegments.last())
            }?.toString()
        }.orEmpty()
    }

    private fun mediaUrl(base: HttpUrl, raw: String) = raw.trim().takeIf(String::isNotEmpty)?.let(base::resolve)
        ?.takeIf { https(it) && it.fragment == null && (it.host in cdnHosts || isSite(it)) }
}
