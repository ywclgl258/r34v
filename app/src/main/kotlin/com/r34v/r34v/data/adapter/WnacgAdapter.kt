package com.r34v.r34v.data.adapter

import com.google.gson.JsonParser
import com.r34v.r34v.data.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Collections

class WnacgAdapter(override val site: SiteContext, private val network: Network) : SiteAdapter {
    override val capabilities = setOf(Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.DOWNLOAD)
    override val sections = listOf(Section("latest", "最新画廊", feed = FeedRequest()))
    override val sorts = listOf(Sort.NEWEST)
    override val supportsLanguage = false

    private val images = Collections.synchronizedMap(object : LinkedHashMap<String, List<String>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 16
    })

    override fun resolve(request: FeedRequest) = WnacgProtocol.resolve(site, request)
    override fun homeUrl() = resolve(homeRequest())
    override fun feedTitle(request: FeedRequest) = request.target.ifBlank { "最新画廊" }
    override fun linkRequest(url: String) = WnacgProtocol.linkRequest(site, url)
    override fun searchRequest(query: String, kind: FeedKind) =
        FeedRequest(if (kind == FeedKind.TAG) FeedKind.TAG else FeedKind.SEARCH, query.trim())

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        return WnacgProtocol.feed(network.html(url, site), url, site, request, feedTitle(request))
    }

    override suspend fun gallery(url: String): GalleryDetail {
        val canonical = WnacgProtocol.canonical(site, url) ?: error("无法识别 WNACG 画廊地址")
        val html = network.html(canonical, site)
        val pages = loadImages(canonical)
        return WnacgProtocol.gallery(html, canonical, site, pages).also { images[canonical] = pages }
    }

    override suspend fun galleryImage(url: String): GalleryImage {
        val page = WnacgProtocol.pageIdentity(site, url) ?: error("无法识别 WNACG 图页地址")
        val urls = images[page.canonical] ?: loadImages(page.canonical).also { images[page.canonical] = it }
        return WnacgProtocol.image(page, urls)
    }

    private suspend fun loadImages(canonical: String): List<String> {
        val url = WnacgProtocol.readerUrl(canonical)
        return WnacgProtocol.imageUrls(network.html(url, site), url, site)
    }

    override suspend fun detail(url: String): Detail = error("请使用漫画阅读器打开此画廊")
    override suspend fun index(section: String): List<Link> = emptyList()
    override suspend fun login(network: Network, username: String, password: String): Unit = error("WNACG 支持公开浏览")
}

/** Public routes and markup documented by the Keiyoushi WNACG extension. */
internal object WnacgProtocol {
    private val galleryPath = Regex("/photos-(?:index|gallery|slide|list)(?:-page-[0-9]+)?-aid-([1-9][0-9]*)(?:-page-[0-9]+)?\\.html")
    private val catalogPath = Regex("/albums-index-page-([1-9][0-9]*)\\.html")
    private val tagPath = Regex("/albums-index(?:-page-([1-9][0-9]*))?-tag-(.+)\\.html")
    private val cardCount = Regex("([0-9][0-9,]*)\\s*[張张][圖图]片")
    private val detailCount = Regex("^([0-9][0-9,]*)")
    private val quoted = """(?:"(?:[^"\\]|\\.)*+"|'(?:[^'\\]|\\.)*+')"""
    private val imageList = Regex("""\b(?:var|let|const)\s+imglist\s*=\s*\[((?:$quoted|[^\]"'])*+)\]""")
    private val writtenImageList = Regex("""(?m)^[ \t]*document\.writeln\((".*\bvar imglist\s*=.*")\);[ \t]*$""")
    private val imageEntry = Regex("""\{((?:$quoted|[^{}"'])*+)\}""")
    private val imageField = Regex("""(?:^|,)\s*(?:url|'url'|"url")\s*:\s*(?:fast_img_host\s*\+\s*)?($quoted)|$quoted""")
    private const val favoriteReminder = "/themes/weitu/images/bg/shoucang.jpg"
    // Covers and full-size pages use distinct hosts. Keep the exact hosts observed in public captures.
    private val coverHosts = setOf("t2.wnimg1.ru", "t3.wnimg1.ru", "t4.qy0.ru")
    private val imageHosts = setOf("img2.wnimg1.ru", "img5.wnimg1.ru", "img5.qy0.ru")
    private val imageExtension = Regex("""\.(?:jpe?g|png|webp|gif|avif)$""", RegexOption.IGNORE_CASE)

    data class PageIdentity(val canonical: String, val index: Int)

    fun resolve(site: SiteContext, request: FeedRequest): String {
        require(request.page > 0) { "页码无效" }
        val base = site.base.toHttpUrl().newBuilder()
        return when {
            request.kind == FeedKind.SEARCH && request.target.isNotBlank() -> base.addPathSegments("search/index.php")
                .addQueryParameter("s", "create_time_DESC")
                .addQueryParameter("q", request.target.trim())
                .addQueryParameter("p", request.page.toString())
            request.kind == FeedKind.TAG && request.target.isNotBlank() ->
                base.addPathSegment("albums-index-page-${request.page}-tag-${request.target.trim()}.html")
            else -> base.addPathSegment("albums-index-page-${request.page}.html")
        }.build().toString()
    }

    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { isSite(site, it) } ?: return null
        return if (url.encodedPath == "/search/index.php") {
            val query = url.queryParameter("q")?.takeIf { it.isNotBlank() } ?: return null
            val page = url.queryParameter("p")?.let { it.toIntOrNull()?.takeIf { number -> number > 0 } ?: return null }
            FeedRequest(FeedKind.SEARCH, query, page = page ?: 1)
        } else {
            val path = url.pathSegments.singleOrNull()?.let { "/$it" } ?: return null
            val (number, tag) = tagPath.matchEntire(path)?.destructured ?: return null
            val page = number.ifEmpty { "1" }.toIntOrNull() ?: return null
            FeedRequest(FeedKind.TAG, tag, page = page)
        }
    }

    fun canonical(site: SiteContext, raw: String): String? {
        val url = raw.toHttpUrlOrNull()?.takeIf { isSite(site, it) } ?: return null
        val id = galleryPath.matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return null
        return site.base.toHttpUrl().newBuilder().addPathSegment("photos-index-aid-$id.html").build().toString()
    }

    fun readerUrl(canonical: String): String {
        val url = canonical.toHttpUrl()
        val id = galleryId(canonical)
        return url.newBuilder().setPathSegment(0, "photos-gallery-aid-$id.html").build().toString()
    }

    fun pageIdentity(site: SiteContext, raw: String): PageIdentity? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val index = url.fragment?.toIntOrNull()?.takeIf { it > 0 && it.toString() == url.fragment } ?: return null
        return canonical(site, raw)?.let { PageIdentity(it, index) }
    }

    fun feed(body: String, url: String, site: SiteContext, request: FeedRequest, heading: String): FeedPage {
        val base = url.toHttpUrl()
        val doc = Jsoup.parse(body, url)
        val entries = doc.select(".gallary_item").mapNotNull { card ->
            val link = card.selectFirst(".title > a[href]") ?: return@mapNotNull null
            val canonical = base.resolve(link.attr("href"))?.let { canonical(site, it.toString()) } ?: return@mapNotNull null
            val title = link.text().ifBlank { link.attr("title").trim() }
            if (title.isBlank()) return@mapNotNull null
            val cover = card.selectFirst("img")
            val count = cardCount.find(card.selectFirst(".info_col")?.text().orEmpty())?.groupValues?.get(1)
                ?.let { "$it 页" }.orEmpty()
            Video(galleryId(canonical), title, canonical, cover(site, base, cover), duration = count,
                site = site.id, mediaKind = MediaKind.GALLERY)
        }.distinctBy { it.id }
        val noResults = base.encodedPath == "/search/index.php" && doc.select("#bodywrap .result > b")
            .any { it.text().replace(",", "").toLongOrNull() == 0L }
        check(entries.isNotEmpty() || noResults) { "WNACG 未返回可识别的画廊列表" }

        val page = request.page
        val next = page.takeIf { it < Int.MAX_VALUE }?.plus(1)
        val hasNext = entries.isNotEmpty() && next != null && doc.select("span.thispage + a[href]").any { anchor ->
            val target = base.resolve(anchor.attr("href"))?.takeIf { isSite(site, it) } ?: return@any false
            when (base.encodedPath) {
                "/search/index.php" -> target.encodedPath == base.encodedPath &&
                    target.queryParameter("p")?.toIntOrNull() == next &&
                    target.queryParameter("q").let { it == null || it == base.queryParameter("q") }
                else -> {
                    val currentTag = linkRequest(site, url)
                    if (currentTag != null) linkRequest(site, target.toString()) == currentTag.copy(page = next)
                    else catalogPath.matchEntire(target.encodedPath)?.groupValues?.get(1)?.toIntOrNull() == next
                }
            }
        }
        return FeedPage(entries, page, if (hasNext) page + 1 else page, heading)
    }

    fun gallery(body: String, canonical: String, site: SiteContext, urls: List<String>): GalleryDetail {
        val doc = Jsoup.parse(body, canonical)
        val base = canonical.toHttpUrl()
        val title = doc.selectFirst("h2")?.text().orEmpty()
        check(title.isNotBlank()) { "WNACG 未返回画廊详情" }
        val info = doc.select(".uwconn label").map(Element::text)
        val count = info.firstOrNull { it.startsWith("頁數：") }?.removePrefix("頁數：")?.trim()
            ?.let { detailCount.find(it)?.value?.replace(",", "")?.toIntOrNull() }
        check(urls.isNotEmpty() && (count == null || count == urls.size)) { "WNACG 图页数量不完整，请刷新画廊" }
        val category = info.firstOrNull { it.startsWith("分類：") }?.removePrefix("分類：")?.trim().orEmpty()
        val description = doc.select(".uwconn p").firstOrNull { it.text().startsWith("簡介：") }
            ?.wholeText()?.trim()?.removePrefix("簡介：")?.trim().orEmpty()
        val uploader = doc.selectFirst(".uwuinfo p")?.text().orEmpty()
        val tags = doc.select(".addtags a.tagshow[href]").mapNotNull { anchor ->
            val label = anchor.text()
            val href = base.resolve(anchor.attr("href"))?.toString() ?: return@mapNotNull null
            val tag = linkRequest(site, href)?.takeIf { it.kind == FeedKind.TAG } ?: return@mapNotNull null
            label.takeIf { it.isNotBlank() }?.let { Link(tag.target, it, resolve(site, tag.copy(page = 1))) }
        }.distinctBy { it.slug }
        val links = urls.indices.map { GalleryPageLink(it + 1, pageUrl(canonical, it + 1)) }
        val cover = cover(site, base, doc.selectFirst(".uwthumb img")).ifBlank { urls.first() }
        val entry = Video(galleryId(canonical), title, canonical, cover, duration = "${links.size} 页",
            type = category, site = site.id, mediaKind = MediaKind.GALLERY)
        return GalleryDetail(entry, links.size, links, links.size, uploader = uploader, description = description,
            groups = if (tags.isEmpty()) emptyMap() else mapOf("标签" to tags))
    }

    fun imageUrls(body: String, reader: String, site: SiteContext): List<String> {
        // The reader can wrap the array declaration in a quoted document.writeln argument.
        val script = writtenImageList.find(body)?.groupValues?.get(1)?.let { JsonParser.parseString(it).asString } ?: body
        val list = imageList.find(script)?.groupValues?.get(1) ?: error("WNACG 未返回可识别的图页列表")
        val rawUrls = imageEntry.findAll(list).map { entry ->
            val token = imageField.findAll(entry.groupValues[1]).mapNotNull { it.groups[1]?.value }.singleOrNull()
                ?: error("WNACG 图页列表含有无效的图片地址")
            token.substring(1, token.length - 1)
        }.toList()
        val pages = if (rawUrls.lastOrNull() == favoriteReminder) rawUrls.dropLast(1) else rawUrls
        check(pages.isNotEmpty()) { "WNACG 图页列表为空" }
        val base = reader.toHttpUrl()
        return pages.map { raw ->
            mediaUrl(site, base, raw, imageHosts)?.takeUnless { raw == favoriteReminder }
                ?: error("WNACG 图页列表含有无效的图片地址")
        }
    }

    fun image(page: PageIdentity, urls: List<String>): GalleryImage {
        val index = page.index
        require(index in 1..urls.size) { "页码超出范围" }
        val canonical = page.canonical
        return GalleryImage(index, pageUrl(canonical, index), urls[index - 1],
            if (index > 1) pageUrl(canonical, index - 1) else null,
            if (index < urls.size) pageUrl(canonical, index + 1) else null)
    }

    private fun galleryId(canonical: String) = canonical.substringAfterLast("-aid-").removeSuffix(".html")
    private fun pageUrl(canonical: String, index: Int) = "$canonical#$index"
    private fun isSite(site: SiteContext, url: HttpUrl) = isHttps(url) &&
        url.host.removePrefix("www.") == site.host.removePrefix("www.")

    private fun isHttps(url: HttpUrl) = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    private fun cover(site: SiteContext, base: HttpUrl, image: Element?): String = image?.let {
        mediaUrl(site, base, it.attr("src"), coverHosts)
    }.orEmpty()

    private fun mediaUrl(site: SiteContext, base: HttpUrl, raw: String, hosts: Set<String>): String? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val url = if (value.startsWith("//")) "https://${value.trimStart('/')}".toHttpUrlOrNull() else base.resolve(value)
        return url?.takeIf { isHttps(it) && (isSite(site, it) || it.host in hosts) && imageExtension.containsMatchIn(it.encodedPath) }
            ?.toString()
    }
}
