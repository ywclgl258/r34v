package com.r34v.r34v.data

import java.util.UUID

data class AdvancedFilter(
    val year: Int = 0,
    val month: Int = 0,
    val minDurationSec: Int = 0,
    val maxDurationSec: Int = 0,
    val brand: String = "",
    val category: String = "",
    val minRating: Int = 0,
    val minPages: Int = 0,
    val maxPages: Int = 0,
) {
    val isEmpty: Boolean get() = this == AdvancedFilter()
    fun validate() {
        require(year == 0 || year in 1900..2200) { "年份应为 1900–2200" }
        require(month == 0 || month in 1..12) { "月份应为 1–12" }
        require(minDurationSec >= 0 && maxDurationSec >= 0 && (maxDurationSec == 0 || minDurationSec <= maxDurationSec)) { "时长范围无效" }
        require(minRating in 0..5) { "最低评分应为 0–5 星" }
        require(minPages >= 0 && maxPages >= 0 && (maxPages == 0 || minPages <= maxPages)) { "页数范围无效" }
        require(brand.length <= 200 && category.length <= 200)
    }
    fun matchesLoaded(video: Video): Boolean {
        val seconds = durationSeconds(video.duration)
        val pages = Regex("(\\d+)").find(video.duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rating = Regex("(\\d+(?:\\.\\d+)?)").find(video.rating)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        return (minDurationSec == 0 || seconds >= minDurationSec) &&
            (maxDurationSec == 0 || seconds in 1..maxDurationSec) &&
            (minPages == 0 || pages >= minPages) && (maxPages == 0 || pages in 1..maxPages) &&
            (minRating == 0 || rating >= minRating || rating * 20 >= minRating)
    }
}

data class SearchCondition(
    val query: String = "",
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val filter: AdvancedFilter = AdvancedFilter(),
) {
    val isEmpty: Boolean get() = query.isBlank() && includeTags.isEmpty() && excludeTags.isEmpty() && filter.isEmpty
    fun normalized() = copy(query = query.trim(), includeTags = includeTags.map(String::trim).filter(String::isNotEmpty).distinct(),
        excludeTags = excludeTags.map(String::trim).filter(String::isNotEmpty).distinct(), filter = filter)
    fun serverQuery(siteId: String): String {
        val tags = includeTags.map(::sourceTag)
        val excluded = if (siteId in setOf("ehentai", "exhentai", "nhentai")) excludeTags.map { "-${sourceTag(it)}" } else emptyList()
        return (listOf(query.trim()) + tags + excluded).filter(String::isNotBlank).joinToString(" ")
    }
    private fun sourceTag(raw: String): String {
        val value = raw.trim()
        if (value.contains(Regex("\\s")) && !value.contains('"')) {
            val namespace = value.substringBefore(':', "")
            return if (namespace.isBlank()) "\"$value\"" else "$namespace:\"${value.substringAfter(':').trim()}\""
        }
        return value
    }
}

data class PlaybackPreferences(
    val defaultRate: Float = 1f,
    val holdRate: Float = 2f,
    val seekStepMs: Long = 10_000L,
    val gestureRangeMs: Long = 120_000L,
) {
    fun validated() = copy(defaultRate = defaultRate.coerceIn(.25f, 3f), holdRate = holdRate.coerceIn(1f, 4f),
        seekStepMs = seekStepMs.coerceIn(5_000L, 60_000L), gestureRangeMs = gestureRangeMs.coerceIn(30_000L, 600_000L))
}

data class TimeBookmark(
    val id: String = UUID.randomUUID().toString(),
    val video: Video,
    val positionMs: Long,
    val note: String = "",
    val group: String = "时间点",
    val at: Long = System.currentTimeMillis(),
)

enum class ReaderFit(val title: String) { WIDTH("适应宽度"), HEIGHT("适应高度"), ORIGINAL("原始尺寸") }
enum class ReaderStart(val title: String) { FIRST("从第一页"), LAST("从最后页"), RESUME("记住上次位置") }
data class ReaderPreferences(
    val mode: String = "",
    val fit: ReaderFit = ReaderFit.WIDTH,
    val start: ReaderStart = ReaderStart.RESUME,
    val cropPercent: Int = 0,
    val doubleCover: Boolean = true,
)
data class ReadingBehavior(
    val volumeKeys: Boolean = true,
    val reverseVolume: Boolean = false,
    val autoPageSeconds: Int = 0,
    val slideshowSeconds: Int = 0,
    val slideshowRandom: Boolean = false,
    val showClock: Boolean = false,
    val showBattery: Boolean = false,
    val showPage: Boolean = true,
    val showProgress: Boolean = false,
    val brightnessPercent: Int = -1,
    val keepAwake: Boolean = false,
)

/** A pending start survives seeks; an active range is inclusive at A and exclusive at B. */
data class VideoLoopRange(val startMs: Long = -1, val endMs: Long = -1) {
    val active: Boolean get() = startMs >= 0 && endMs - startMs >= 1000
    fun start(positionMs: Long) = VideoLoopRange(positionMs.coerceAtLeast(0))
    fun end(positionMs: Long): VideoLoopRange {
        require(startMs >= 0 && positionMs - startMs >= 1000) { "B 点需要比 A 点至少晚 1 秒" }
        return copy(endMs = positionMs)
    }
    fun validFor(durationMs: Long) = startMs < 0 || (durationMs > startMs && (endMs < 0 || active && endMs <= durationMs))
    fun contains(positionMs: Long) = !active || positionMs in startMs until endMs
    fun shouldRepeat(positionMs: Long, playing: Boolean) = active && playing && positionMs >= endMs
}

data class ImageFavorite(
    val video: Video,
    val page: Int,
    val pageUrl: String,
    val imageUrl: String = "",
    val at: Long = System.currentTimeMillis(),
) {
    val key: String get() = "${video.entryKey}:$page"
}

data class DiscoveryChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val site: String = "",
    val kind: FeedKind = FeedKind.SEARCH,
    val target: String = "",
    val savedSearch: SavedSearch? = null,
    val hidden: Boolean = false,
    val order: Int = 0,
)

data class TimestampSpan(val start: Int, val end: Int, val positionMs: Long)

fun timestampSpans(text: String, durationMs: Long): List<TimestampSpan> {
    if (text.isBlank() || durationMs <= 0) return emptyList()
    val regex = Regex("(?<![\\d:])(?:(\\d{1,2}):)?([0-5]?\\d):([0-5]\\d)(?![\\d:])")
    return regex.findAll(text).mapNotNull { match ->
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val total = (hours * 3600 + minutes * 60 + seconds) * 1000L
        if (total > durationMs) null else TimestampSpan(match.range.first, match.range.last + 1, total)
    }.toList()
}

fun parsePageSelection(raw: String, total: Int): List<Int> {
    require(total > 0) { "图集页数未知" }
    val pages = linkedSetOf<Int>()
    raw.split(',', '，', ' ').map(String::trim).filter(String::isNotEmpty).forEach { part ->
        val range = part.split('-', '–', '—', limit = 2)
        require(range.size <= 2 && range.all { it.toIntOrNull() != null }) { "页码格式示例：1,3,5-8" }
        val first = range[0].toInt(); val last = range.getOrElse(1) { range[0] }.toInt()
        require(first in 1..total && last in 1..total && first <= last && last - first <= total) { "页码应在 1–$total 内，且起始页不大于结束页" }
        pages += first..last
    }
    require(pages.isNotEmpty()) { "请至少选择一页" }
    return pages.sorted()
}
