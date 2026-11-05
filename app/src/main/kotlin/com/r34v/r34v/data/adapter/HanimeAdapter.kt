package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * hanime1.me — a Laravel server-rendered hentai tube.
 *
 * Structurally unrelated to KVS: routes are query parameters, the player is a plain
 * `<video>` element, and the site sits behind Cloudflare with a blank-UA rejection.
 * Selectors were verified against the live markup on 2026-11-03; the previously published
 * `card-mobile-panel` class no longer exists on the site.
 */
class HanimeAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL,
        Capability.CATEGORY, Capability.LOGIN, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.NEWEST, Sort.VIEWS, Sort.RATING, Sort.DURATION)
    override val sections = listOf(
        Section("recent", "最新上市", "刚上架的作品"),
        Section("upload", "最新上傳", ""),
        Section("today", "本日排行", ""),
        Section("week", "本週排行", ""),
        Section("month", "本月排行", ""),
        Section("popular", "觀看次數", ""),
        Section("liked", "讚好比例", ""),
        Section("裏番", "裏番", ""),
        Section("泡麵番", "泡麵番", ""),
        Section("Motion Anime", "Motion Anime", ""),
        Section("3DCG", "3DCG", ""),
        Section("2.5D", "2.5D", ""),
        Section("2D動畫", "2D動畫", ""),
        Section("AI生成", "AI生成", ""),
        Section("MMD", "MMD", ""),
        Section("Cosplay", "Cosplay", ""),
    ).map { section ->
        section.copy(feed = FeedRequest(
            if (section.key in setOf("recent", "upload", "today", "week", "month", "popular", "liked")) FeedKind.LATEST else FeedKind.CATEGORY,
            section.key,
        ))
    }

    override fun sortsFor(request: FeedRequest) = availableSorts(request)
    override fun resolve(request: FeedRequest) = resolve(site, request)

    override fun homeUrl() = "${site.base}/"

    override fun homeRequest(page: Int) = FeedRequest(FeedKind.LATEST, "recent", page = page)

    override fun linkRequest(url: String): FeedRequest? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!site.isSite(parsed.host)) return null
        parsed.queryParameter("tags[]")?.let { return FeedRequest(FeedKind.TAG, it) }
        parsed.queryParameter("genre")?.let { return FeedRequest(FeedKind.CATEGORY, it) }
        parsed.queryParameter("query")?.let { return FeedRequest(FeedKind.SEARCH, it, Sort.RELEVANCE) }
        return null
    }

    override suspend fun feed(request: FeedRequest): FeedPage {
        val url = resolve(request)
        val doc = Jsoup.parse(network.html(url))
        val videos = parseCards(doc, site)
        // The listing is a plain infinite-ish pager; the pagination block exposes the last page.
        val total = doc.select("ul.pagination a[href*=page]").mapNotNull {
            Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: request.page
        return FeedPage(videos, request.page, total, doc.selectFirst("h1, .horizontal-row-title h3")?.text()?.trim().orEmpty())
    }

    override suspend fun detail(url: String): Detail {
        val absolute = site.absolute(url)
        val doc = Jsoup.parse(network.html(absolute))
        val parsed = parseDetail(doc, absolute, site)
        // Related items live in the tab content and reuse the same card markup.
        return parsed
    }

    override suspend fun index(section: String): List<Link> = emptyList()

    override fun sectionUrl(key: String): String? = null

    /**
     * Laravel CSRF form login. Success is detected by the site's own convention: a signed-in
     * request for `/login` answers 404 instead of showing the form again.
     */
    override suspend fun login(network: Network, username: String, password: String) {
        val token = Jsoup.parse(network.html("${site.base}/login"))
            .selectFirst("input[name=_token]")?.attr("value")
            ?: error("无法读取登录令牌，站点可能要求浏览器验证")
        val form = okhttp3.FormBody.Builder()
            .add("_token", token)
            .add("email", username.trim())
            .add("password", password)
            .build()
        network.post("${site.base}/login", form)
        check(network.statusOrNull("${site.base}/login") == 404) { "邮箱或密码错误，或站点要求完成验证" }
    }

    companion object {
        private val sortKey = mapOf(
            "recent" to "最新上市", "upload" to "最新上傳", "today" to "本日排行",
            "week" to "本週排行", "month" to "本月排行", "popular" to "觀看次數",
            "liked" to "讚好比例", "longest" to "時長最長", "watching" to "他們在看",
        )
        private val genreKeys = setOf(
            "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D", "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告",
        )

        fun availableSorts(request: FeedRequest): List<Sort> = when {
            request.kind == FeedKind.POPULAR -> listOf(Sort.RANDOM)
            request.kind == FeedKind.RATED -> listOf(Sort.RATING)
            request.kind in listOf(FeedKind.SEARCH, FeedKind.MODEL) -> listOf(Sort.RELEVANCE)
            request.kind != FeedKind.LATEST || request.target !in listOf("", "recent") -> listOf(Sort.NEWEST)
            else -> listOf(Sort.NEWEST, Sort.VIEWS, Sort.RATING, Sort.DURATION)
        }

        /** hanime1 routes everything through `/search?...`, so the KVS path builder must not be used. */
        fun resolve(site: SiteContext, request: FeedRequest): String {
            val params = linkedMapOf<String, String>()
            when (request.kind) {
                FeedKind.SEARCH -> if (request.target.isNotBlank()) params["query"] = request.target
                FeedKind.TAG -> if (request.target.isNotBlank()) params["tags[]"] = request.target
                FeedKind.CATEGORY -> if (request.target.isNotBlank()) params["genre"] = request.target
                FeedKind.MODEL -> if (request.target.isNotBlank()) params["query"] = request.target
                FeedKind.POPULAR -> params["sort"] = "本日排行"
                FeedKind.RATED -> params["sort"] = "讚好比例"
                else -> {
                    val key = request.target.ifBlank { "recent" }
                    if (key in genreKeys) params["genre"] = key
                    else sortKey[key]?.let { params["sort"] = it }
                }
            }
            // A sort only makes sense together with a plain listing; the site ignores it otherwise.
            if (params["genre"] == null && params["query"] == null && params["tags[]"] == null) {
                if (request.kind == FeedKind.LATEST && request.target in listOf("", "recent") && request.sort in listOf(Sort.VIEWS, Sort.RATING, Sort.DURATION)) params["sort"] = request.sort.hanime
                if (params["sort"] == null) params["sort"] = request.sort.hanime.ifBlank { "最新上市" }
            }
            if (request.page > 1) params["page"] = request.page.toString()
            val query = params.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            return "${site.base}/search?$query"
        }

        private val videoHref = Regex("[?&]v=(\\d+)")

        fun videoId(url: String): String? = videoHref.find(url)?.groupValues?.get(1)

        /**
         * hanime1 wraps adverts in the same container as real videos, so every card is filtered
         * by requiring a `watch?v=` link. Unparsable cards are skipped rather than thrown so one
         * unexpected block cannot blank the whole page.
         */
        fun parseCards(doc: Document, site: SiteContext): List<Video> =
            doc.select("div.video-item-container").mapNotNull { card -> card(card, site) }
                .distinctBy { it.id }

        private fun card(card: Element, site: SiteContext): Video? {
            val link = card.selectFirst("a.video-link[href*=watch]") ?: return null
            val raw = link.absUrl("href").ifBlank { link.attr("href") }
            val id = videoId(raw) ?: return null
            val stats = card.select(".stats-container .stat-item")
            val title = card.selectFirst(".title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: card.attr("title").trim()
            if (title.isBlank()) return null
            return Video(
                id = id,
                title = title,
                url = if (raw.startsWith("http")) raw else "${site.base}/watch?v=$id",
                thumb = card.selectFirst("img.main-thumb")?.absUrl("src").orEmpty(),
                duration = card.selectFirst(".duration")?.text()?.trim().orEmpty(),
                rating = stats.getOrNull(0)?.text()?.trim().orEmpty(),
                views = stats.getOrNull(1)?.text()?.trim().orEmpty(),
                site = site.id,
            )
        }

        fun parseDetail(doc: Document, fallbackUrl: String, site: SiteContext): Detail {
            val id = videoId(fallbackUrl) ?: error("详情页无法识别：缺少视频 ID")

            // `<source>` list first; some pages only expose the URL inside a script literal.
            val streams = doc.select("video#player source[src]").map { src ->
                val size = src.attr("size").trim()
                Stream(if (size.isBlank()) "原画" else "${size}p",
                    src.absUrl("src").ifBlank { src.attr("src") })
            }.ifEmpty {
                Regex("""const source\s*=\s*'([^']+)'""").find(doc.outerHtml())
                    ?.groupValues?.get(1)?.let { listOf(Stream("原画", it)) }.orEmpty()
            }.filter { it.url.startsWith("http") }
                .distinctBy { it.label }
                .sortedByDescending { it.height }

            require(streams.isNotEmpty()) { "详情页没有播放地址，可能需要登录或站点已改版" }

            val heading = doc.getElementById("shareBtn-title")?.text()?.trim().orEmpty()
            val poster = doc.selectFirst("video#player")?.absUrl("poster").orEmpty()

            val metaText = doc.select("div.video-details-wrapper")
                .firstOrNull { it.text().contains("觀看次數") }?.text().orEmpty()
            val views = Regex("觀看次數：([^\\s]+)").find(metaText)?.groupValues?.get(1).orEmpty()
            val date = Regex("(\\d{4}-\\d{2}-\\d{2})").find(metaText)?.groupValues?.get(1).orEmpty()

            val tags = doc.select("div.single-video-tag > a")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

            val groups = linkedMapOf<String, List<Link>>()
            if (tags.isNotEmpty()) groups["标签"] = tags.map { tag ->
                Link(slug(tag), tag, "${site.base}/search?query=${java.net.URLEncoder.encode(tag, "UTF-8")}")
            }

            val artistEl = doc.getElementById("video-artist-name")
            val artist = artistEl?.text()?.trim().orEmpty()
            if (artist.isNotBlank()) {
                groups["作者"] = listOf(
                    Link(slug(artist), artist,
                        artistEl?.absUrl("href").orEmpty().ifBlank {
                            "${site.base}/search?query=${java.net.URLEncoder.encode(artist, "UTF-8")}"
                        })
                )
            }

            val description = doc.selectFirst("div[class^=video-caption-text]")?.ownText()?.trim().orEmpty()

            val related = doc.getElementById("related-tabcontent")
                ?.select("div.video-item-container").orEmpty()
                .mapNotNull { card(it, site) }
                .distinctBy { it.id }

            val duration = doc.selectFirst("meta[property=og:video:duration]")?.attr("content").orEmpty()

            return Detail(
                video = Video(
                    id = id, title = heading.ifBlank { "视频 $id" }, url = fallbackUrl,
                    thumb = poster, duration = duration,
                    views = listOf(views, date).filter { it.isNotBlank() }.joinToString(" · "),
                    site = site.id,
                ),
                streams = streams, groups = groups, description = description,
                related = related, comments = emptyList(), downloads = emptyList(),
            )
        }

        private fun slug(text: String) = text.lowercase().replace(Regex("\\s+"), "-")
    }
}
