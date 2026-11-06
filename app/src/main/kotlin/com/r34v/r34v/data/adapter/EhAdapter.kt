package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

open class GalleryAccessException(message: String) : IllegalStateException(message)
class GalleryLoginRequiredException(message: String) : GalleryAccessException(message)

/** Independent parser for the live gallery HTML; no EhViewer application code is bundled. */
class EhAdapter(override val site: SiteContext, private val network: Network) : SiteAdapter {
    override val capabilities = setOf(Capability.DOWNLOAD, Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL, Capability.CATEGORY, Capability.RANKING, Capability.SESSION_IMPORT)
    override val sorts = listOf(Sort.NEWEST)
    override val catalogueTitle = "画廊分类"
    override val sections = listOf(
        Section("latest", "最新画廊", feed = FeedRequest()),
        Section("popular", "热门画廊", feed = FeedRequest(FeedKind.POPULAR)),
    ) + EhProtocol.categories.map { (key, name) -> Section(key, name, feed = FeedRequest(FeedKind.CATEGORY, key)) }
    private val previewPages = java.util.concurrent.ConcurrentHashMap<String, List<GalleryPageLink>>()

    override fun homeUrl() = resolve(homeRequest())
    override fun resolve(request: FeedRequest) = EhProtocol.resolve(site, request)
    override fun feedTitle(request: FeedRequest) = when (request.kind) {
        FeedKind.LATEST -> "最新画廊"
        FeedKind.POPULAR -> "热门画廊"
        FeedKind.CATEGORY -> EhProtocol.categories[request.target] ?: "画廊分类"
        else -> request.target.ifBlank { "搜索画廊" }
    }
    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(kind, query)
    override fun linkRequest(url: String): FeedRequest? = EhProtocol.linkRequest(site, url)

    private fun requireAccess() {
        if (site.id == SiteRegistry.EXHENTAI.id && !network.session.hasGallerySession(site))
            throw GalleryLoginRequiredException("ExHentai 必须登录并具备账号权限；未登录可能只显示白屏")
    }
    private suspend fun html(url: String): String = try { network.html(url, site) }
    catch (error: Exception) {
        if (site.id == SiteRegistry.EXHENTAI.id && (error is java.net.ProtocolException && error.message.orEmpty().contains("follow-up", true) || error.message == "请求失败：HTTP 403"))
            throw GalleryLoginRequiredException("ExHentai 会话已失效或需要官方验证，请重新登录")
        throw error
    }
    override suspend fun feed(request: FeedRequest): FeedPage {
        requireAccess()
        val url = resolve(request)
        return EhProtocol.feed(html(url), url, site, request.page,
            if (request.kind == FeedKind.POPULAR) request.language else GalleryLanguage.ALL)
    }
    override suspend fun detail(url: String): Detail = error("请使用漫画阅读器打开此画廊")
    override suspend fun index(section: String): List<Link> = emptyList()
    override suspend fun login(network: Network, username: String, password: String): Unit = error("请在设置中导入本站会话")

    override suspend fun gallery(url: String): GalleryDetail {
        requireAccess()
        val canonical = EhProtocol.galleryUrl(site, url) ?: error("无法识别画廊地址")
        val detail = EhProtocol.gallery(html(canonical), canonical, site)
        previewPages[canonical] = detail.pageLinks
        return detail
    }
    override suspend fun galleryImage(url: String): GalleryImage {
        requireAccess()
        require(EhProtocol.pageIndex(site, url) != null) { "无法识别漫画页地址" }
        return EhProtocol.image(html(url), url, site)
    }
    override suspend fun galleryPageLink(detail: GalleryDetail, page: Int): String {
        require(page in 1..detail.pageCount) { "页码超出范围" }
        detail.pageLinks.firstOrNull { it.index == page }?.let { return it.url }
        val index = (page - 1) / detail.previewPageSize.coerceAtLeast(1)
        val url = detail.entry.url.toHttpUrl().newBuilder().setQueryParameter("p", index.toString()).build().toString()
        val links = previewPages[url] ?: EhProtocol.pageLinks(Jsoup.parse(html(url), url), site).also { previewPages[url] = it }
        return links.firstOrNull { it.index == page }?.url ?: error("原站没有返回该页，请刷新画廊后重试")
    }

    override suspend fun importSession(raw: String) {
        val candidate = EhProtocol.sessionCookies(site, raw).associateBy { it.name }.toMutableMap()
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = if (site.isSite(url.host)) candidate.values.filter { it.matches(url) } +
                Cookie.Builder().name("nw").value("1").hostOnlyDomain(url.host).secure().build() else emptyList()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (site.isSite(url.host)) cookies.filter { site.isSite(it.domain) }.forEach { candidate[it.name] = it }
            }
        }
        val client = network.client.newBuilder().cookieJar(jar).cache(null).build()
        val url = homeUrl()
        client.readResponse(Request.Builder().url(url).tag(SiteContext::class.java, site).build()) { response ->
            check(response.isSuccessful) { "会话验证失败：HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            EhProtocol.feed(body, url, site, 1)
        }
        network.session.replace(site, candidate.values.toList())
    }
}

object EhProtocol {
    val categories = linkedMapOf("2" to "同人志", "4" to "漫画", "8" to "画师 CG", "16" to "游戏 CG", "512" to "西方作品", "256" to "非成人", "32" to "图集", "64" to "Cosplay", "128" to "真人", "1" to "其他")
    private val galleryPath = Regex("^/g/(\\d+)/([a-fA-F0-9]+)/?$")
    private val imagePath = Regex("^/s/[a-fA-F0-9]+/(\\d+)-(\\d+)/?$")

    fun resolve(site: SiteContext, request: FeedRequest): String {
        fun filtered(url: HttpUrl.Builder): String {
            request.filter.validate()
            val requested = when (request.kind) {
                FeedKind.SEARCH, FeedKind.TAG, FeedKind.CHARACTER -> request.target
                FeedKind.MODEL -> "uploader:\"${request.target.replace("\"", "\\\"")}\""
                else -> ""
            }
            val query = requested.ifBlank { url.build().queryParameter("f_search").orEmpty() }
            val languages = request.language.languages.joinToString(" ") {
                (if (request.language.languages.size > 1) "~" else "") + "language:$it$"
            }
            val terms = listOf(query, languages) + request.includeTags + request.excludeTags.map { "-$it" }
            url.setQueryParameter("f_search", terms.filter { it.isNotBlank() }.joinToString(" "))
            if (request.language != GalleryLanguage.ALL) url.setQueryParameter("f_sfl", "on")
            if (request.filter.minPages > 0) url.setQueryParameter("f_spf", request.filter.minPages.toString())
            if (request.filter.maxPages > 0) url.setQueryParameter("f_spt", request.filter.maxPages.toString())
            if (request.filter.minRating > 0) {
                url.setQueryParameter("f_sr", "on")
                url.setQueryParameter("f_srdd", request.filter.minRating.toString())
            }
            if (request.filter.category.isNotBlank() && request.kind != FeedKind.CATEGORY) {
                val category = categories.entries.firstOrNull { it.key == request.filter.category || it.value == request.filter.category }?.key
                    ?: error("不支持的画廊分类")
                url.setQueryParameter("f_cats", (1023 xor category.toInt()).toString())
            }
            if (!request.filter.isEmpty || request.includeTags.isNotEmpty() || request.excludeTags.isNotEmpty()) url.setQueryParameter("advsearch", "1")
            return url.setQueryParameter("inline_set", "dm_l").build().toString()
        }
        if (request.cursor.isNotBlank()) {
            val cursor = request.cursor.toHttpUrl()
            require(cursor.isHttps && site.isSite(cursor.host) && cursor.encodedPath in setOf("/", "/popular", "/popular/")) { "原站翻页地址无效，请刷新列表" }
            return filtered(cursor.newBuilder())
        }
        val builder = site.base.toHttpUrl().newBuilder()
        if (request.kind == FeedKind.POPULAR) builder.addPathSegment("popular")
        when (request.kind) {
            FeedKind.CATEGORY -> builder.addQueryParameter("f_cats", (1023 xor (request.target.toIntOrNull()?.takeIf { request.target in categories } ?: error("不支持的画廊分类"))).toString())
            FeedKind.SEARCH, FeedKind.TAG, FeedKind.CHARACTER -> builder.addQueryParameter("f_search", request.target)
            FeedKind.MODEL -> builder.addQueryParameter("f_search", "uploader:\"${request.target.replace("\"", "\\\"")}\"")
            else -> Unit
        }
        return filtered(builder)
    }

    fun galleryUrl(site: SiteContext, raw: String): String? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        return if (galleryPath.matches(url.encodedPath)) url.newBuilder().query(null).fragment(null).build().toString() else null
    }
    fun pageIndex(site: SiteContext, raw: String): Int? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        return imagePath.find(url.encodedPath)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
    }
    fun linkRequest(site: SiteContext, raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        if (url.pathSegments.firstOrNull() == "tag") return FeedRequest(FeedKind.TAG, URLDecoder.decode(url.pathSegments.drop(1).joinToString("/"), "UTF-8"))
        url.queryParameter("f_search")?.let { return FeedRequest(FeedKind.SEARCH, it) }
        return null
    }
    private fun https(raw: String, base: String): String = base.toHttpUrlOrNull()?.resolve(raw)?.takeIf { it.isHttps }?.toString().orEmpty()
    private fun cssImage(style: String, base: String) = Regex("""url\(\s*['"]?([^)'"\s]+)""").find(style)?.groupValues?.get(1)?.let { https(it, base) }.orEmpty()

    fun checkAccess(body: String, site: SiteContext) {
        if (body.contains("Image View Limit Exceeded", true) || body.contains("temporarily banned", true) || body.contains("excessive pageloads", true))
            throw GalleryAccessException("原站图片配额或访问频率已受限，请稍后重试")
        if (body.contains("This gallery has been removed", true) || body.contains("pining for the fjords", true)) throw GalleryAccessException("该画廊已被原站移除")
        if (site.id == SiteRegistry.EXHENTAI.id && (!body.contains("<html", true) && !body.contains("class=\"itg", true) || body.contains("act=Login", true) && !body.contains("class=\"itg", true)))
            throw GalleryLoginRequiredException("ExHentai 会话已失效或账号暂无访问权限，请重新登录")
    }

    fun feed(body: String, base: String, site: SiteContext, page: Int, language: GalleryLanguage = GalleryLanguage.ALL): FeedPage {
        checkAccess(body, site)
        val doc = Jsoup.parse(body, base)
        val rows = (doc.select("table.itg tr") + doc.select(".itg .gl1t")).distinct()
        val entries = rows.mapNotNull { row ->
            val a = row.selectFirst(".glname a[href*=/g/], a[href*=/g/]") ?: return@mapNotNull null
            val url = galleryUrl(site, a.absUrl("href")) ?: return@mapNotNull null
            val id = galleryPath.find(url.toHttpUrl().encodedPath)!!.groupValues[1]
            val title = row.selectFirst(".glink")?.text()?.trim().orEmpty().ifBlank { a.attr("title") }.ifBlank { a.text() }
            if (title.isBlank()) return@mapNotNull null
            val image = row.selectFirst(".glthumb img, .gl3t img, img")
            val thumb = image?.let { https(it.attr("data-src").ifBlank { it.attr("src") }, base) }.orEmpty()
            val count = Regex("([\\d,]+)\\s+pages?", RegexOption.IGNORE_CASE).find(row.text())?.groupValues?.get(1)?.replace(",", "").orEmpty()
            fun dimension(name: String) = image?.attr(name)?.toIntOrNull() ?: image?.attr("style")?.let { Regex("$name\\s*:\\s*(\\d+)px").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            Video(id, title, url, thumb, if (count.isBlank()) "" else "$count 页", type = row.selectFirst(".cn, .cs")?.text().orEmpty(), site = site.id, mediaKind = MediaKind.GALLERY,
                thumbWidth = dimension("width"), thumbHeight = dimension("height")) to
                row.select("[title^=language:]").map { it.attr("title").substringAfter(':').lowercase() }
        }.distinctBy { it.first.id }
        val empty = body.contains("No hits found", true) || body.contains("No unfiltered results", true)
        check(entries.isNotEmpty() || empty) {
            if (site.id == SiteRegistry.EXHENTAI.id) "ExHentai 未返回画廊，请检查本站会话和账号权限" else "原站未返回可识别的画廊列表，请稍后重试"
        }
        val next = doc.selectFirst("a#unext[href]")?.absUrl("href")?.takeIf { candidate ->
            candidate.toHttpUrlOrNull()?.let { it.isHttps && site.isSite(it.host) } == true
        } ?: doc.select(".ptt a[href]").lastOrNull()?.takeIf { it.text().contains('>') }?.absUrl("href")?.takeIf { it != base }
        val filtered = entries.filter { language.languages.isEmpty() || it.second.any(language.languages::contains) }.map { it.first }
        return FeedPage(filtered, page, if (next.isNullOrBlank()) page else page + 1, "画廊", nextCursor = next)
    }

    fun pageLinks(doc: Document, site: SiteContext): List<GalleryPageLink> = doc.select("#gdt a[href]").mapNotNull { a ->
        val url = a.absUrl("href"); val index = pageIndex(site, url) ?: return@mapNotNull null
        GalleryPageLink(index, url)
    }.distinctBy { it.index }.sortedBy { it.index }

    fun gallery(body: String, url: String, site: SiteContext): GalleryDetail {
        checkAccess(body, site)
        val canonical = galleryUrl(site, url) ?: error("画廊地址无效")
        val doc = Jsoup.parse(body, canonical)
        val title = doc.selectFirst("#gn")?.text().orEmpty().ifBlank { doc.selectFirst("#gj")?.text().orEmpty() }
        check(title.isNotBlank()) { "原站未返回画廊详情，请检查会话或内容访问权限" }
        val info = doc.select("#gdd tr").mapNotNull { row -> row.select("td").takeIf { it.size >= 2 }?.let { it[0].text().trim().removeSuffix(":") to it[1].text() } }.toMap()
        val links = pageLinks(doc, site)
        val pages = info["Length"]?.let { Regex("[\\d,]+").find(it)?.value?.replace(",", "")?.toIntOrNull() } ?: links.lastOrNull()?.index ?: 0
        check(pages > 0 && links.isNotEmpty()) { "画廊没有可读取的页码，请检查账号权限或刷新重试" }
        val groups = linkedMapOf<String, List<Link>>()
        val tags = doc.select("#taglist a[href]").mapNotNull { a ->
            val target = linkRequest(site, a.absUrl("href")) ?: return@mapNotNull null
            Link(target.target, target.target, a.absUrl("href"))
        }.distinctBy { it.slug }
        if (tags.isNotEmpty()) groups["标签"] = tags
        val uploader = doc.selectFirst("#gdn a")?.text().orEmpty()
        if (uploader.isNotBlank()) groups["上传者"] = listOf(Link(uploader, uploader, site.base.toHttpUrl().newBuilder().addQueryParameter("f_search", "uploader:\"$uploader\"").build().toString()))
        val cover = doc.selectFirst("#gd1 [style]")?.let { cssImage(it.attr("style"), canonical) }.orEmpty()
        val category = doc.selectFirst("#gdc .cn, #gdc .cs")?.text().orEmpty()
        val id = galleryPath.find(canonical.toHttpUrl().encodedPath)!!.groupValues[1]
        val rating = doc.selectFirst("#rating_label")?.text().orEmpty()
        val entry = Video(id, title, canonical, cover, "$pages 页", info["Language"].orEmpty(), rating, category, site = site.id, mediaKind = MediaKind.GALLERY)
        return GalleryDetail(entry, pages, links, links.size, doc.selectFirst("#gj")?.text().orEmpty(), uploader, info["Language"].orEmpty(),
            listOf(category, info["Posted"].orEmpty(), info["File Size"].orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), groups)
    }

    fun image(body: String, pageUrl: String, site: SiteContext): GalleryImage {
        checkAccess(body, site)
        val doc = Jsoup.parse(body, pageUrl)
        val index = pageIndex(site, pageUrl) ?: error("页码地址无效")
        val image = doc.selectFirst("img#img")?.attr("src")?.let { https(it, pageUrl) }.orEmpty()
        if (image.contains("509.gif", true) || image.contains("509s.gif", true)) throw GalleryAccessException("原站图片配额已用尽，请稍后重试")
        check(image.isNotBlank()) { "原站未返回 HTTPS 图片地址，请重试此页" }
        fun adjacent(id: String, forward: Boolean) = doc.selectFirst("a#$id[href]")?.absUrl("href")?.takeIf {
            val other = pageIndex(site, it) ?: return@takeIf false
            if (forward) other > index else other < index
        }
        val reload = Regex("""nl\(['"]([A-Za-z0-9-]+)['"]\)""").find(body)?.groupValues?.get(1)?.let {
            pageUrl.toHttpUrl().newBuilder().setQueryParameter("nl", it).build().toString()
        }
        return GalleryImage(index, pageUrl, image, adjacent("prev", false), adjacent("next", true), reload)
    }

    fun sessionCookies(site: SiteContext, raw: String): List<Cookie> {
        val values = raw.trim().replaceFirst(Regex("^cookie:\\s*", RegexOption.IGNORE_CASE), "").split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0] in setOf("ipb_member_id", "ipb_pass_hash", "igneous")) pair[0] to pair[1].trim() else null
        }.toMap()
        require(values["ipb_member_id"]?.toLongOrNull()?.let { it > 0 } == true && values["ipb_pass_hash"]?.matches(Regex("[A-Za-z0-9_-]{16,128}")) == true) { "请填写有效的 ipb_member_id 和 ipb_pass_hash，ExHentai 可同时填写 igneous" }
        require(values.values.all { value -> value.isNotEmpty() && value.all { it.code in 33..126 && it != ';' } }) { "Cookie 字段格式无效，请重新复制" }
        return values.map { (name, value) -> Cookie.Builder().name(name).value(value).hostOnlyDomain(site.host).secure().httpOnly()
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000).build() }
    }
}
