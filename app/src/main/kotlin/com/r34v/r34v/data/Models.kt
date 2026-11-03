package com.r34v.r34v.data

import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.URLEncoder

object Site {
    const val BASE = "https://rule34video.com"
    const val HOST = "rule34video.com"
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    fun absolute(raw: String): String = if (raw.isBlank() || raw.startsWith("data:")) "" else
        runCatching { URI(BASE).resolve(raw.trim()).toString() }.getOrDefault("")
    fun isSite(host: String) = host == HOST || host == "www.$HOST"
}

enum class FeedKind(val title: String, val segment: String) {
    LATEST("最新更新", "latest-updates"), POPULAR("热门榜", "most-popular"), RATED("高分榜", "top-rated"),
    FOR_YOU("为你推荐", "for-you"), SEARCH("搜索", "search"), TAG("标签", "tags"),
    CHARACTER("角色", "tags"), CATEGORY("分类", "categories"), MODEL("作者", "models")
}
enum class Sort(val title: String, val param: String) {
    RELEVANCE("相关", ""), NEWEST("最新", "post_date"), VIEWS("播放量", "video_viewed"),
    RATING("评分", "rating"), DURATION("时长", "duration"), RANDOM("随机", "pseudo_rand")
}
data class FeedRequest(val kind: FeedKind = FeedKind.LATEST, val target: String = "", val sort: Sort = Sort.NEWEST, val page: Int = 1) {
    val path: String get() {
        val slug = URLEncoder.encode(target, "UTF-8").replace("+", "%20")
        val targetPart = if (kind in listOf(FeedKind.SEARCH, FeedKind.TAG, FeedKind.CHARACTER, FeedKind.CATEGORY, FeedKind.MODEL)) "$slug/" else ""
        val pagePart = if (page > 1 && kind != FeedKind.FOR_YOU) "$page/" else ""
        return "/${kind.segment}/$targetPart$pagePart"
    }
    val url: String get() = Site.BASE + path + if (sort.param.isNotEmpty() && (sort != Sort.NEWEST || kind == FeedKind.SEARCH)) "?sort_by=${sort.param}" else ""
}
data class Video(val id: String = "", val title: String = "", val url: String = "", val thumb: String = "", val duration: String = "", val views: String = "", val rating: String = "", val type: String = "", val preview: String = "")
data class FeedPage(val videos: List<Video>, val current: Int, val total: Int, val heading: String, val cached: Boolean = false) { val hasNext get() = current < total }
data class Link(val slug: String, val label: String, val url: String, val thumb: String = "", val subtitle: String = "")
data class Stream(val label: String, val url: String) { val height get() = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: 0 }
data class Comment(val author: String, val text: String, val date: String)
data class Detail(val video: Video, val streams: List<Stream>, val groups: Map<String, List<Link>>, val description: String, val related: List<Video>, val comments: List<Comment>, val downloads: List<Stream>)
data class Watch(val id: String = "", val title: String = "", val thumb: String = "", val path: String = "", @SerializedName("pos") val position: Long = 0, @SerializedName("dur") val duration: Long = 0, @SerializedName("at") val at: Long = System.currentTimeMillis(), @SerializedName("q") val quality: String = "") {
    val finished get() = duration > 0 && position >= duration - 20
    val resumable get() = position >= 15 && !finished
    fun video() = Video(id, title, Site.absolute(path), thumb)
}
data class Favorite(@SerializedName("k") val kind: String = "video", @SerializedName("t") val target: String = "", val title: String = "", val path: String = "", val thumb: String = "", @SerializedName("sub") val subtitle: String = "", @SerializedName("at") val at: Long = System.currentTimeMillis()) {
    val key get() = "$kind:$target"
}
data class DownloadMeta(val video: Video, val quality: String)
data class LocalVideo(val id: String = "", val title: String = "", val path: String = "", val thumb: String = "", @SerializedName("q") val quality: String = "", @SerializedName("dur") val duration: Long = 0)

fun durationSeconds(text: String): Long = text.split(':').takeIf { it.size in 1..3 && it.all { p -> p.toLongOrNull() != null } }?.fold(0L) { total, p -> total * 60 + p.toLong() } ?: 0
fun timeText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
