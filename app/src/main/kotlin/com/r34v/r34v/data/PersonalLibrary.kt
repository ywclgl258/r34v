package com.r34v.r34v.data

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

val Video.entryKey: String get() = "$site:$id"
data class ShelfEntry(val video: Video, val completed: Boolean = false, val at: Long = System.currentTimeMillis(), val addedAt: Long = at)
data class Shelf(val id: String = UUID.randomUUID().toString(), val name: String, val entries: List<ShelfEntry> = emptyList())
data class PageBookmark(val video: Video, val page: Int, val offset: Int = 0, val at: Long = System.currentTimeMillis()) {
    val key get() = "${video.entryKey}:$page"
}
enum class ReaderMode(val title: String) {
    STRIP("上下连续"), LEFT_TO_RIGHT("向左翻页"), RIGHT_TO_LEFT("向右翻页"), SPREAD("横屏双页")
}
data class InboxEntry(val video: Video, val read: Boolean = false, val at: Long = System.currentTimeMillis())
data class SubscriptionInbox(val known: Map<String, List<String>> = emptyMap(), val entries: List<InboxEntry> = emptyList())
data class SavedSearch(val id: String = UUID.randomUUID().toString(), val name: String = "", val query: String = "",
    val sources: List<String> = emptyList(), val category: String = "", val languages: Map<String, String> = emptyMap())
enum class SmartRule(val title: String) { UNFINISHED("未看完"), OFFLINE_UNSEEN("已离线未看"), RECENT("最近加入") }
data class SmartShelf(val id: String = UUID.randomUUID().toString(), val name: String = "", val rule: SmartRule = SmartRule.UNFINISHED,
    val category: String = "", val source: String = "")
data class PersonalLibrary(
    val shelves: List<Shelf> = listOf(Shelf(WATCH_LATER, "稍后看")),
    val bookmarks: List<PageBookmark> = emptyList(),
    val readerModes: Map<String, String> = emptyMap(),
    val inboxes: Map<String, SubscriptionInbox> = emptyMap(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val smartShelves: List<SmartShelf> = emptyList(),
) {
    companion object { const val WATCH_LATER = "watch-later" }
}

/** Successful live tag responses advance their own baseline; failures and snapshots do not. */
fun SubscriptionInbox.refreshed(feeds: List<TagFeed>, now: Long = System.currentTimeMillis()): SubscriptionInbox {
    val nextKnown = known.toMutableMap()
    val nextEntries = entries.associateBy { it.video.entryKey }.toMutableMap()
    val existing = nextEntries.keys.toSet()
    feeds.filter { it.error == null && !it.cached }.forEach { feed ->
        val slug = feed.subscription.slug
        val previous = known[slug]
        feed.videos.forEach { video ->
            if (video.entryKey !in existing) {
                val baseline = previous == null || video.id in previous
                val earlier = nextEntries[video.entryKey]
                nextEntries[video.entryKey] = InboxEntry(video, read = baseline && (earlier?.read ?: true), at = now)
            }
        }
        nextKnown[slug] = (feed.videos.map { it.id } + previous.orEmpty()).distinct().take(1000)
    }
    return SubscriptionInbox(nextKnown, nextEntries.values.sortedByDescending { it.at }.take(1000))
}

/** Uses the library's one preferences transaction, including during backup restore. */
class PersonalStore(private val library: LibraryStore) {
    companion object { const val KEY = "personal_library_v1" }
    val changes = MutableStateFlow(0)
    private var cachedRaw: String? = null
    private var cachedSnapshot: PersonalLibrary? = null
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY) changes.update { it + 1 }
    }
    init { library.prefs.registerOnSharedPreferenceChangeListener(listener) }
    fun snapshot(): PersonalLibrary = synchronized(library) {
        val raw = library.prefs.getString(KEY, null)
        if (cachedSnapshot != null && raw == cachedRaw) return@synchronized cachedSnapshot!!
        (raw?.let { runCatching { library.gson.fromJson(it, PersonalLibrary::class.java) }.getOrNull() } ?: PersonalLibrary()).also {
            cachedRaw = raw; cachedSnapshot = it
        }
    }
    private fun change(block: (PersonalLibrary) -> PersonalLibrary) = synchronized(library) {
        val previous = snapshot(); val next = block(previous)
        if (next != previous) {
            val raw = library.gson.toJson(next); cachedRaw = raw; cachedSnapshot = next
            library.prefs.edit().putString(KEY, raw).apply()
        }
    }
    fun shelves() = snapshot().shelves
    fun saveSearch(value: SavedSearch) {
        require(value.name.trim().length in 1..80 && value.query.trim().length in 1..2048)
        require(value.sources.isNotEmpty() && value.sources.all { id -> SiteRegistry.all.any { it.id == id } })
        change { it.copy(savedSearches = it.savedSearches.filterNot { p -> p.id == value.id } + value.copy(name = value.name.trim(), query = value.query.trim(), sources = value.sources.distinct())) }
    }
    fun removeSearch(id: String) = change { it.copy(savedSearches = it.savedSearches.filterNot { p -> p.id == id }) }
    fun saveSmart(value: SmartShelf) {
        require(value.name.trim().length in 1..80 && (value.source.isBlank() || SiteRegistry.byId(value.source) != null))
        change { it.copy(smartShelves = it.smartShelves.filterNot { p -> p.id == value.id } + value.copy(name = value.name.trim())) }
    }
    fun removeSmart(id: String) = change { it.copy(smartShelves = it.smartShelves.filterNot { p -> p.id == id }) }
    fun contains(video: Video, shelf: String = PersonalLibrary.WATCH_LATER) = shelves().firstOrNull { it.id == shelf }?.entries?.any { it.video.entryKey == video.entryKey } == true
    fun createShelf(name: String): String {
        require(name.trim().isNotEmpty() && name.trim().length <= 80) { "合集名称需要 1–80 个字" }
        val id = UUID.randomUUID().toString()
        change { it.copy(shelves = it.shelves + Shelf(id, name.trim())) }; return id
    }
    fun renameShelf(id: String, name: String) {
        require(id != PersonalLibrary.WATCH_LATER && name.trim().isNotEmpty() && name.trim().length <= 80)
        change { data -> data.copy(shelves = data.shelves.map { if (it.id == id) it.copy(name = name.trim()) else it }) }
    }
    fun removeShelf(id: String) {
        require(id != PersonalLibrary.WATCH_LATER)
        change { data -> data.copy(shelves = data.shelves.filter { it.id != id }) }
    }
    fun add(video: Video, shelf: String = PersonalLibrary.WATCH_LATER) {
        require(video.id.isNotBlank() && SiteRegistry.byId(video.site) != null)
        change { data -> data.copy(shelves = data.shelves.map { value ->
            if (value.id != shelf || value.entries.any { it.video.entryKey == video.entryKey }) value
            else value.copy(entries = value.entries + ShelfEntry(video))
        }) }
    }
    fun remove(video: Video, shelf: String = PersonalLibrary.WATCH_LATER) = change { data ->
        data.copy(shelves = data.shelves.map { if (it.id == shelf) it.copy(entries = it.entries.filter { e -> e.video.entryKey != video.entryKey }) else it })
    }
    fun complete(video: Video, shelf: String, completed: Boolean) = change { data ->
        data.copy(shelves = data.shelves.map { if (it.id == shelf) it.copy(entries = it.entries.map { e -> if (e.video.entryKey == video.entryKey) e.copy(completed = completed, at = System.currentTimeMillis(), addedAt = e.addedAt.takeIf { time -> time > 0 } ?: e.at) else e }) else it })
    }
    fun move(video: Video, shelf: String, delta: Int) = change { data ->
        data.copy(shelves = data.shelves.map { value ->
            if (value.id != shelf) value else {
                val all = value.entries.toMutableList(); val from = all.indexOfFirst { it.video.entryKey == video.entryKey }; val to = from + delta
                if (from in all.indices && to in all.indices) all.add(to, all.removeAt(from))
                value.copy(entries = all)
            }
        })
    }
    fun bookmarks(video: Video) = snapshot().bookmarks.filter { it.video.entryKey == video.entryKey }.sortedBy { it.page }
    fun toggleBookmark(video: Video, page: Int, offset: Int = 0) {
        require(page > 0)
        val mark = PageBookmark(video, page, offset)
        change { data -> data.copy(bookmarks = if (data.bookmarks.any { it.key == mark.key }) data.bookmarks.filter { it.key != mark.key } else data.bookmarks + mark) }
    }
    fun readerMode(site: String) = ReaderMode.entries.firstOrNull { it.name == snapshot().readerModes[site] }
        ?: if (SiteRegistry.byId(site)?.category == SiteCategory.PHOTO) ReaderMode.LEFT_TO_RIGHT else ReaderMode.STRIP
    fun setReaderMode(site: String, mode: ReaderMode) = change { it.copy(readerModes = it.readerModes + (site to mode.name)) }
    fun inbox(site: String) = snapshot().inboxes[site] ?: SubscriptionInbox()
    fun refreshInbox(site: String, feeds: List<TagFeed>) = change { it.copy(inboxes = it.inboxes + (site to inbox(site).refreshed(feeds))) }
    fun markRead(site: String, keys: Set<String>) = change { data ->
        val box = data.inboxes[site]
        if (box == null || box.entries.none { !it.read && it.video.entryKey in keys }) data
        else data.copy(inboxes = data.inboxes + (site to box.copy(entries = box.entries.map { if (it.video.entryKey in keys) it.copy(read = true) else it })))
    }
}
