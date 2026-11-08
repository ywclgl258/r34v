package com.r34v.r34v.data

import java.util.Locale

enum class ShelfOrder(val title: String) { MANUAL("手动顺序"), NEWEST("最近加入"), OLDEST("最早加入"), TITLE("标题 A–Z") }

/** A local view over one shelf. It never rewrites the user's manual order. */
data class ShelfQuery(val text: String = "", val status: Int = 0, val category: String = "", val source: String = "",
    val addedDays: Int = 0, val order: ShelfOrder = ShelfOrder.MANUAL) {
    val filtered get() = text.isNotBlank() || status != 0 || category.isNotBlank() || source.isNotBlank() || addedDays > 0
    fun select(shelf: Shelf, now: Long = System.currentTimeMillis()): List<ShelfEntry> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cutoff = if (addedDays > 0) now - addedDays.toLong() * 86_400_000 else Long.MIN_VALUE
        val selected = shelf.entries.filter { entry ->
            val site = SiteRegistry.byIdOrDefault(entry.video.site)
            val searchable = "${entry.video.title} ${site.name} ${site.id}"
            words.all { searchable.contains(it, ignoreCase = true) } &&
                (status == 0 || entry.completed == (status == 2)) &&
                (category.isBlank() || site.category.name == category) &&
                (source.isBlank() || entry.video.site == source) && entry.joinedAt >= cutoff
        }
        return when (order) {
            ShelfOrder.MANUAL -> selected
            ShelfOrder.NEWEST -> selected.sortedByDescending { it.joinedAt }
            ShelfOrder.OLDEST -> selected.sortedBy { it.joinedAt }
            ShelfOrder.TITLE -> selected.sortedBy { it.video.title.lowercase(Locale.ROOT) }
        }
    }
}

private val ShelfEntry.joinedAt get() = addedAt.takeIf { it > 0 } ?: at
fun List<Shelf>.displayOrder(): List<Shelf> = sortedByDescending { it.pinned }
fun Shelf.coverVideos(): List<Video> = entries.firstOrNull { it.video.entryKey == coverKey }?.let { listOf(it.video) }
    ?: entries.take(3).map { it.video }

fun Shelf.edited(name: String = this.name, note: String = this.note, pinned: Boolean = this.pinned,
    coverKey: String = this.coverKey, now: Long = System.currentTimeMillis()): Shelf {
    require(name.trim().length in 1..80 && note.trim().length <= 1000) { "名称需为 1–80 字，备注最多 1000 字" }
    require(id != PersonalLibrary.WATCH_LATER || name.trim() == this.name) { "稍后看的名称保持不变" }
    require(coverKey.isBlank() || entries.any { it.video.entryKey == coverKey }) { "封面作品已移出合集，请重新选择" }
    return copy(name = name.trim(), note = note.trim(), pinned = pinned, coverKey = coverKey,
        modifiedAt = maxOf(now, modifiedAt.coerceAtMost(Long.MAX_VALUE - 1) + 1))
}
