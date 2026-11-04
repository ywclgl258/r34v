package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A capability a site may or may not offer. The UI dims what is missing. */
enum class Capability { FEED, SEARCH, TAG, MODEL, CATEGORY, RANKING, LOGIN, DOWNLOAD, SESSION_IMPORT }

/** One entry in the site's own browse menu. */
data class Section(val key: String, val title: String, val subtitle: String = "", val feed: FeedRequest? = null)

/** Everything the browsing UI needs from a site without knowing which site it is. */
interface SiteAdapter {
    val site: SiteContext
    val capabilities: Set<Capability>
    val sections: List<Section>
    val sorts: List<Sort>

    fun resolve(request: FeedRequest): String
    fun homeUrl(): String
    fun homeRequest(page: Int = 1) = FeedRequest(page = page)
    fun feedTitle(request: FeedRequest) = request.target.ifBlank { request.kind.title }
    fun sortTitle(sort: Sort) = sort.title
    val catalogueTitle: String get() = "影片類型"
    val creatorTitle: String get() = if (site.mediaKind == MediaKind.GALLERY) "上传者" else "作者"
    fun searchRequest(query: String, kind: FeedKind) = FeedRequest(
        kind, if (kind == FeedKind.SEARCH) query else query.lowercase().replace(' ', '-'),
        if (kind == FeedKind.SEARCH) Sort.RELEVANCE else Sort.NEWEST,
    )

    fun linkRequest(url: String): FeedRequest? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!site.isSite(parsed.host)) return null
        val parts = parsed.pathSegments.filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val kind = when (parts[0]) {
            "tags", "characters" -> FeedKind.TAG
            "models" -> FeedKind.MODEL
            "categories" -> FeedKind.CATEGORY
            else -> return null
        }
        return FeedRequest(kind, parts[1])
    }

    suspend fun feed(request: FeedRequest): FeedPage
    suspend fun detail(url: String): Detail
    suspend fun index(section: String): List<Link>
    suspend fun gallery(url: String): GalleryDetail = error("当前站点不支持漫画阅读")
    suspend fun galleryImage(url: String): GalleryImage = error("当前站点不支持漫画阅读")
    suspend fun galleryPageLink(detail: GalleryDetail, page: Int): String = detail.pageLinks.firstOrNull { it.index == page }?.url ?: error("没有找到该页")
    suspend fun importSession(raw: String): Unit = error("当前站点不支持会话导入")

    /** Signs in and persists the session cookies for this site only. */
    suspend fun login(network: Network, username: String, password: String)

    /** Absolute URL for a catalogue entry such as a tag or a work. */
    fun sectionUrl(key: String): String? = null
}
