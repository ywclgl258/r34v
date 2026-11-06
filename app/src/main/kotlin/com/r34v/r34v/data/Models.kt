package com.r34v.r34v.data

import com.google.gson.annotations.SerializedName
import java.net.URLEncoder

/**
 * Backwards-compatible facade over [SiteRegistry.current].
 *
 * Every existing call site keeps working unchanged, but the values are now resolved at
 * runtime from the active site instead of being compiled-in constants.
 */
object Site {
    val BASE: String get() = SiteRegistry.current.base
    val HOST: String get() = SiteRegistry.current.host
    val UA: String get() = SiteRegistry.current.userAgent
    val CURRENT: SiteContext get() = SiteRegistry.current

    fun absolute(raw: String): String = SiteRegistry.current.absolute(raw)
    fun isSite(host: String): Boolean = SiteRegistry.isRegistered(host)
}

enum class FeedKind(val title: String, val segment: String) {
    LATEST("最新更新", "latest-updates"), POPULAR("热门榜", "most-popular"), RATED("高分榜", "top-rated"),
    FOR_YOU("为你推荐", "for-you"), SEARCH("搜索", "search"), TAG("标签", "tags"),
    CHARACTER("角色", "tags"), CATEGORY("分类", "categories"), MODEL("作者", "models")
}

enum class Sort(val title: String, val param: String, val hanime: String) {
    RELEVANCE("相关", "", ""),
    NEWEST("最新", "post_date", "最新上傳"),
    VIEWS("播放量", "video_viewed", "觀看次數"),
    RATING("评分", "rating", "讚好比例"),
    DURATION("时长", "duration", "時長最長"),
    RANDOM("随机", "pseudo_rand", "本日排行"),
    TRENDING("趋势", "", ""), POPULARITY("热门", "", "")
}

enum class MediaKind { VIDEO, GALLERY }

enum class GalleryLanguage(val title: String, val languages: List<String>) {
    ALL("全部显示", emptyList()),
    CHINESE("仅中文", listOf("chinese")),
    JAPANESE("仅日文", listOf("japanese")),
    CHINESE_JAPANESE("中文＋日文", listOf("chinese", "japanese")),
}

data class FeedRequest(
    val kind: FeedKind = FeedKind.LATEST,
    val target: String = "",
    val sort: Sort = Sort.NEWEST,
    val page: Int = 1,
    val cursor: String = "",
    val language: GalleryLanguage = GalleryLanguage.ALL,
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val filter: AdvancedFilter = AdvancedFilter(),
    val contentKind: MediaKind = MediaKind.VIDEO,
) {
    val slug: String get() = URLEncoder.encode(target, "UTF-8").replace("+", "%20")

    /** Legacy KVS path. Kept for the rule34video adapter and its tests. */
    val path: String get() {
        val targetPart = if (kind in listOf(FeedKind.SEARCH, FeedKind.TAG, FeedKind.CHARACTER, FeedKind.CATEGORY, FeedKind.MODEL)) "$slug/" else ""
        val pagePart = if (page > 1 && kind != FeedKind.FOR_YOU) "$page/" else ""
        return "/${kind.segment}/$targetPart$pagePart"
    }

    val id: String get() = "${kind.name}|${target}|${sort.name}|$page|${contentKind.name}|${includeTags.joinToString(",")}|${excludeTags.joinToString(",")}|$filter" + if (language == GalleryLanguage.ALL) "" else "|${language.name}"
}

data class Video(
    val id: String = "", val title: String = "", val url: String = "", val thumb: String = "",
    val duration: String = "", val views: String = "", val rating: String = "", val type: String = "",
    val preview: String = "",
    /** Which site produced this record. Filled in by the repository. */
    @SerializedName("site") val site: String = SiteRegistry.current.id,
    val mediaKind: MediaKind = MediaKind.VIDEO,
    val thumbWidth: Int = 0,
    val thumbHeight: Int = 0,
) {
    val hasThumbDimensions get() = thumbWidth > 0 && thumbHeight > 0
    val thumbAspectRatio get() = if (hasThumbDimensions) (thumbWidth.toFloat() / thumbHeight).coerceIn(.45f, 1.8f) else 2f / 3f
}

data class FeedPage(val videos: List<Video>, val current: Int, val total: Int, val heading: String, val cached: Boolean = false, val nextCursor: String? = null) {
    val hasNext get() = current < total
}

data class Link(val slug: String, val label: String, val url: String, val thumb: String = "", val subtitle: String = "")
data class Stream(val label: String, val url: String) { val height get() = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: 0 }
data class Comment(val author: String, val text: String, val date: String)

data class Detail(
    val video: Video, val streams: List<Stream>, val groups: Map<String, List<Link>>,
    val description: String, val related: List<Video>, val comments: List<Comment>,
    val downloads: List<Stream>,
)

data class GalleryPageLink(val index: Int, val url: String)
data class GalleryDetail(
    val entry: Video,
    val pageCount: Int,
    val pageLinks: List<GalleryPageLink>,
    val previewPageSize: Int,
    val alternativeTitle: String = "",
    val uploader: String = "",
    val language: String = "",
    val description: String = "",
    val groups: Map<String, List<Link>> = emptyMap(),
    val cached: Boolean = false,
    val downloadedPages: List<Int> = emptyList(),
    val requestedPages: List<Int> = emptyList(),
    val videoEntries: List<Video>? = emptyList(),
) {
    val embeddedVideos: List<Video> get() = videoEntries.orEmpty()
}
data class GalleryImage(val index: Int, val pageUrl: String, val imageUrl: String, val previousUrl: String?, val nextUrl: String?, val reloadUrl: String? = null)
data class ReadingProgress(val entry: Video, val page: Int, val pages: Int, val pageUrl: String, val at: Long = System.currentTimeMillis(), val offset: Int = 0)

data class Watch(
    val id: String = "", val title: String = "", val thumb: String = "", val path: String = "",
    @SerializedName("pos") val position: Long = 0, @SerializedName("dur") val duration: Long = 0,
    @SerializedName("at") val at: Long = System.currentTimeMillis(),
    @SerializedName("q") val quality: String = "",
    @SerializedName("site") val site: String = SiteRegistry.current.id,
) {
    val finished get() = duration > 0 && position >= duration - 20
    val resumable get() = position >= 15 && !finished

    fun video(): Video {
        val site = SiteRegistry.byId(this.site) ?: SiteRegistry.current
        val absolute = if (path.startsWith("http")) path else site.absolute(path)
        return Video(id, title, absolute, thumb, site = site.id)
    }
}

data class Favorite(
    @SerializedName("k") val kind: String = "video", @SerializedName("t") val target: String = "",
    val title: String = "", val path: String = "", val thumb: String = "",
    @SerializedName("sub") val subtitle: String = "",
    @SerializedName("at") val at: Long = System.currentTimeMillis(),
    @SerializedName("site") val site: String = SiteRegistry.current.id,
) {
    val key get() = "$site:$kind:$target"
}

data class DownloadMeta(val video: Video, val quality: String, val site: String = SiteRegistry.current.id)

data class LocalVideo(
    val id: String = "", val title: String = "", val path: String = "", val thumb: String = "",
    @SerializedName("q") val quality: String = "", @SerializedName("dur") val duration: Long = 0,
    @SerializedName("site") val site: String = SiteRegistry.current.id,
)

fun durationSeconds(text: String): Long =
    text.split(':').takeIf { it.size in 1..3 && it.all { p -> p.toLongOrNull() != null } }
        ?.fold(0L) { total, p -> total * 60 + p.toLong() } ?: 0

fun timeText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
