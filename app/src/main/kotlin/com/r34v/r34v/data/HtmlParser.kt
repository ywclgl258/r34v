package com.r34v.r34v.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Only parses markup. Never executes scripts or loads a WebView. */
object HtmlParser {
    private fun String.clean() = replace("\u200b", "").replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun Element?.textOrEmpty() = this?.text()?.clean().orEmpty()
    private fun thumb(el: Element?, site: SiteContext): String = el?.let {
        listOf(it.attr("data-rd-src"), it.attr("src"), it.attr("data-rd-jpg"), it.attr("data-src"))
            .firstOrNull { v -> v.isNotBlank() && !v.startsWith("data:") }?.let(site::absolute)
    }.orEmpty()
    private fun scope(doc: Element) = doc.selectFirst("#custom_list_videos_latest_videos_list_items, .thumbs[id], .thumbs") ?: doc
    private fun cards(doc: Element, site: SiteContext): List<Video> {
        val root = scope(doc)
        val items = root.select("div[data-video-card-id]").ifEmpty { root.select("div.item.thumb") }
        return items.mapNotNull { item ->
            val a = item.selectFirst("a.th, a[href*=/video/]") ?: return@mapNotNull null
            val url = site.absolute(a.attr("href"))
            val id = Regex("/video/(\\d+)/").find(url)?.groupValues?.get(1) ?: return@mapNotNull null
            val img = item.selectFirst("img.thumb, img")
            Video(id, listOf(item.selectFirst(".thumb_title").textOrEmpty(), img?.attr("alt").orEmpty(), a.attr("title")).firstOrNull { it.isNotBlank() }.orEmpty(),
                url, thumb(img, site), item.selectFirst(".time").textOrEmpty(), item.selectFirst(".video-views-count").textOrEmpty(),
                item.selectFirst(".video-card-meta__rating, .rating").textOrEmpty(), item.selectFirst(".futa").textOrEmpty(),
                site.absolute(item.selectFirst("[data-preview]")?.attr("data-preview").orEmpty()), site.id)
        }.distinctBy { it.id }
    }
    fun feed(html: String, request: FeedRequest, site: SiteContext): FeedPage {
        val doc = Jsoup.parse(html)
        val videos = cards(doc, site)
        if (videos.isEmpty() && doc.selectFirst(".thumbs") == null) error("列表页结构无法识别，请检查登录状态或站点是否改版")
        if (videos.isEmpty() && scope(doc).select("div.item").isNotEmpty()) error("列表结构已变化：没有可识别的视频链接")
        val pager = doc.select("button.page-of, [data-rd-jump], nav.fs-pager button").firstNotNullOfOrNull { el ->
            Regex("Page\\s+([\\d,]+)\\s+of\\s+([\\d,]+)").find(el.text())
        }
        val current = pager?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: request.page
        // Restrict fallback to pagination, otherwise numeric video IDs look like page numbers.
        val total = pager?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()
            ?: doc.select("nav a[data-page], .pagination a, .fs-pager a").mapNotNull { a ->
                a.attr("data-page").toIntOrNull() ?: Regex("/(\\d+)/?(?:\\?.*)?$").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: request.page
        return FeedPage(videos, current, total, doc.selectFirst("h1").textOrEmpty())
    }
    fun index(html: String, segment: String, site: SiteContext): List<Link> {
        val doc = Jsoup.parse(html)
        val root = doc.selectFirst("#list_categories_categories_list_items, #list_tags_tags_list_items, .thumbs") ?: doc
        return root.select("a[href]").mapNotNull { a ->
            val slug = Regex("/$segment/([^/?#]+)/?").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            Link(slug, listOf(a.selectFirst(".thumb_title").textOrEmpty(), a.attr("title"), a.text().clean(), slug.replace('-', ' ')).first { it.isNotBlank() }, site.absolute(a.attr("href")), thumb(a.selectFirst("img"), site))
        }.distinctBy { it.slug }
    }
    fun works(html: String, site: SiteContext): List<Link> = Jsoup.parse(html).select("a[href]").mapNotNull { a ->
        val slug = Regex("^/top-characters/([^/?#]+)/$").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        val label = a.text().clean()
        if (label.isEmpty() || Regex("^All \\d+", RegexOption.IGNORE_CASE).containsMatchIn(label)) return@mapNotNull null
        Link(slug, label, site.absolute(a.attr("href")))
    }.distinctBy { it.slug }
    fun ranking(html: String, site: SiteContext): List<Link> {
        val doc = Jsoup.parse(html)
        val rows = doc.select("li.cs-row")
        if (rows.isNotEmpty()) return rows.mapNotNull { row ->
            val a = row.selectFirst("a[href*=/tags/], a[href*=/characters/]") ?: return@mapNotNull null
            val slug = a.attr("href").trimEnd('/').substringAfterLast('/')
            Link(slug, row.selectFirst(".cs-row__t").textOrEmpty().ifBlank { a.text() }, site.absolute(a.attr("href")), thumb(row.selectFirst("img"), site), row.selectFirst(".cs-row__v").textOrEmpty())
        }.distinctBy { it.slug }
        val title = doc.select("h2.title").firstOrNull { it.text().equals("Top Artists", true) }
        val root = title?.parent()?.parent()?.selectFirst(".aside_wrap") ?: return emptyList()
        return root.select("a.item[href*=/models/]").map { a ->
            Link(a.attr("href").trimEnd('/').substringAfterLast('/'), a.selectFirst(".name, .title, .thumb_title").textOrEmpty().ifBlank { a.text() }, site.absolute(a.attr("href")), thumb(a.selectFirst("img"), site), a.selectFirst(".rating").textOrEmpty())
        }.distinctBy { it.slug }
    }
    fun detail(html: String, fallbackUrl: String = "", site: SiteContext): Detail {
        val doc = Jsoup.parse(html)
        val fv = flashvars(doc.select("script").map { it.data() })
        val id = fv["video_id"].orEmpty().ifBlank { Regex("/video/(\\d+)/").find(fallbackUrl)?.groupValues?.get(1).orEmpty() }
        require(id.isNotEmpty()) { "详情页无法识别：缺少视频 ID" }
        val streams = (listOf("video_url", "video_alt_url") + (2..4).map { "video_alt_url$it" }).mapNotNull { key ->
            fv[key]?.takeIf { it.isNotBlank() }?.let { Stream(fv["${key}_text"].orEmpty().ifBlank { "原画" }, site.absolute(it)) }
        }.distinctBy { it.label }.sortedByDescending { it.height }
        require(streams.isNotEmpty()) { "详情页没有播放地址，可能需要登录或站点已改版" }
        val groups = linkedMapOf<String, MutableList<Link>>()
        val names = mapOf("characters" to "角色", "categories" to "分类", "artists" to "作者", "artist" to "作者", "tags" to "标签")
        for (heading in doc.select(".vp-group__title")) {
            val name = names[heading.text().lowercase().trim()] ?: continue
            var next = heading.nextElementSibling()
            val group = groups.getOrPut(name) { mutableListOf() }
            while (next != null && !next.hasClass("vp-group__title")) {
                val links = next.select("a[href]").toMutableList()
                if (next.tagName() == "a") links.add(next)
                for (a in links) {
                    val m = Regex("/(characters|categories|models|tags)/([^/?#]+)/?").find(a.attr("href")) ?: continue
                    val slug = m.groupValues[2]
                    if (group.none { it.slug == slug }) group.add(Link(slug, a.text().clean().ifBlank { slug.replace('-', ' ') }, site.absolute(a.attr("href").replace("/characters/", "/tags/"))))
                }
                next = next.nextElementSibling()
            }
        }
        if (groups.values.all { it.isEmpty() }) for ((type, segment, label) in listOf(Triple("tag", "tags", "标签"), Triple("category", "categories", "分类"), Triple("model", "models", "作者"))) {
            val section = doc.select("[data-suggest-type=$type]")
            val links = section.select("a[href*=/" + segment + "/]").map { a -> Link(a.attr("href").trimEnd('/').substringAfterLast('/'), a.text(), site.absolute(a.attr("href"))) }
            if (links.isNotEmpty()) groups[label] = links.distinctBy { it.slug }.toMutableList()
        }
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty().ifBlank { doc.selectFirst("meta[property=og:url]")?.attr("content").orEmpty() }.ifBlank { fallbackUrl }
        val duration = doc.select("span.item_info").map { it.text().clean() }.firstOrNull { Regex("\\d+:\\d{2}(:\\d{2})?").matches(it) }.orEmpty()
        val description = doc.selectFirst(".vp-desc, .js-vp-desc")?.wholeText()?.trim().orEmpty()
        val related = doc.selectFirst("#custom_list_videos_related_videos_items")?.let { cards(Jsoup.parse("<div class='thumbs'>${it.html()}</div>"), site) }.orEmpty()
        val comments = doc.select("#tab_comments .comment_item, #tab_comments .comment-item, #tab_comments .comment").mapNotNull { item ->
            val author = item.selectFirst("a[href*=/members/], .comment_author, .author").textOrEmpty()
            val text = item.selectFirst(".comment_text, .comment-text, .text, .message").textOrEmpty()
            if (author.isBlank() || text.isBlank()) null else Comment(author, text, item.selectFirst(".comment_date, .date, time").textOrEmpty())
        }.take(100)
        val downloads = doc.select("a[href*=download=true]").map { a ->
            val label = Regex("(\\d{3,4})p?\\.mp4").find(a.attr("href"))?.groupValues?.get(1)?.let { "${it}p" } ?: Regex("\\d{3,4}p").find(a.text())?.value ?: "原画"
            Stream(label, site.absolute(a.attr("href")))
        }.distinctBy { it.label }.sortedByDescending { it.height }
        return Detail(Video(id, doc.selectFirst("h1").textOrEmpty().ifBlank { fv["video_title"].orEmpty() }, site.absolute(canonical), site.absolute(fv["preview_url"].orEmpty()), duration, site = site.id), streams, groups, description, related, comments, downloads)
    }

    internal fun flashvars(scripts: List<String>): Map<String, String> {
        for (script in scripts) {
            val start = script.indexOf("flashvars").takeIf { it >= 0 } ?: continue
            val open = script.indexOf('{', start).takeIf { it >= 0 } ?: continue
            var depth = 0; var quote: Char? = null; var escaped = false; var end = -1
            for (i in open until script.length) {
                val ch = script[i]
                if (escaped) { escaped = false; continue }
                if (quote != null) {
                    if (ch == '\\') escaped = true else if (ch == quote) quote = null
                } else when (ch) {
                    '\'', '"' -> quote = ch
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            if (end < 0) continue
            val pattern = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(['"])((?:\\.|(?!\2).)*)\2""")
            val fields = pattern.findAll(script.substring(open + 1, end)).associate { m -> m.groupValues[1] to unescape(m.groupValues[3]) }
            if (fields.isNotEmpty()) return fields
        }
        return emptyMap()
    }
    private fun unescape(raw: String) = Regex("""\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)""").replace(raw) { m ->
        when (val v = m.groupValues[1]) { "n" -> "\n"; "r" -> "\r"; "t" -> "\t"; else -> if (v.startsWith("u") || v.startsWith("x")) v.drop(1).toInt(16).toChar().toString() else v }
    }
}
