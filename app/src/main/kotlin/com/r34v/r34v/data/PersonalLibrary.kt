package com.r34v.r34v.data

import androidx.core.content.edit

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

val Video.entryKey: String get() = if (site == SiteRegistry.IWARA.id && mediaKind == MediaKind.GALLERY) "$site:gallery:$id" else "$site:$id"
data class ShelfEntry(val video: Video, val completed: Boolean = false, val at: Long = System.currentTimeMillis(), val addedAt: Long = at)
data class Shelf(val id: String = UUID.randomUUID().toString(), val name: String = "", val entries: List<ShelfEntry> = emptyList(),
    val note: String = "", val pinned: Boolean = false, val coverKey: String = "", val modifiedAt: Long = 0)
data class PageBookmark(val video: Video, val page: Int, val offset: Int = 0, val at: Long = System.currentTimeMillis()) {
    val key get() = "${video.entryKey}:$page"
}
enum class ReaderMode(val title: String) {
    STRIP("上下连续"), LEFT_TO_RIGHT("向左翻页"), RIGHT_TO_LEFT("向右翻页"), SPREAD("横屏双页")
}
data class InboxEntry(val video: Video, val read: Boolean = false, val at: Long = System.currentTimeMillis())
data class SubscriptionInbox(val known: Map<String, List<String>> = emptyMap(), val entries: List<InboxEntry> = emptyList())
internal data class SubscriptionTagRefresh(val baselineCreated: Boolean, val newUnread: Int)
data class SavedSearch(val id: String = UUID.randomUUID().toString(), val name: String = "", val query: String = "",
    val sources: List<String> = emptyList(), val category: String = "", val languages: Map<String, String> = emptyMap(),
    val includeTags: List<String> = emptyList(), val excludeTags: List<String> = emptyList(), val filter: AdvancedFilter = AdvancedFilter(),
    val singleSiteScope: FeedKind? = null) {
    /** Gson allocates legacy objects without invoking constructor defaults for absent fields. */
    internal fun restored(): SavedSearch = copy(
        id = id?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(), name = name ?: "", query = query ?: "",
        sources = sources ?: emptyList(), category = category ?: "", languages = languages ?: emptyMap(),
        includeTags = includeTags ?: emptyList(), excludeTags = excludeTags ?: emptyList(), filter = filter ?: AdvancedFilter(),
    )
    val condition: SearchCondition get() = restored().let { SearchCondition(it.query, it.includeTags, it.excludeTags, it.filter) }
    val singleSite: SiteContext? get() = when (singleSiteScope) {
        FeedKind.SEARCH, FeedKind.TAG, FeedKind.MODEL -> sources.singleOrNull()?.let(SiteRegistry::byId)
        else -> null
    }
    fun matchingSources(): List<SiteContext> {
        val saved = restored()
        return saved.sources.mapNotNull(SiteRegistry::byId).filter { saved.category.isBlank() || it.category.name == saved.category }
    }
}
enum class SmartRule(val title: String) { UNFINISHED("未看完"), OFFLINE_UNSEEN("已离线未看"), RECENT("最近加入") }
data class SmartShelf(val id: String = UUID.randomUUID().toString(), val name: String = "", val rule: SmartRule = SmartRule.UNFINISHED,
    val category: String = "", val source: String = "")
data class PersonalLibrary(
    val shelves: List<Shelf> = listOf(Shelf(WATCH_LATER, "稍后看")),
    val bookmarks: List<PageBookmark> = emptyList(),
    val readerModes: Map<String, String> = emptyMap(),
    val inboxes: Map<String, SubscriptionInbox> = emptyMap(),
    val savedSearches: List<SavedSearch> = emptyList(),
    val searchUpdates: Map<String, SavedSearchInbox> = emptyMap(),
    val smartShelves: List<SmartShelf> = emptyList(),
    val timeBookmarks: List<TimeBookmark> = emptyList(),
    val playbackPreferences: Map<String, PlaybackPreferences> = emptyMap(),
    val readerPreferences: Map<String, ReaderPreferences> = emptyMap(),
    val readingBehaviors: Map<String, ReadingBehavior> = emptyMap(),
    val imageFavorites: List<ImageFavorite> = emptyList(),
    val channels: List<DiscoveryChannel> = emptyList(),
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
            val key = video.entryKey
            if (key in existing) {
                val saved = nextEntries.getValue(key)
                if (video.tagSlugs != null && video.tagSlugs != saved.video.tagSlugs) {
                    nextEntries[key] = saved.copy(video = saved.video.copy(tagSlugs = video.tagSlugs))
                }
            } else {
                val earlier = nextEntries[key]
                val baseline = previous == null || key in previous ||
                    video.mediaKind == MediaKind.VIDEO && video.id in previous
                val latest = video.copy(tagSlugs = video.tagSlugs ?: earlier?.video?.tagSlugs)
                nextEntries[key] = InboxEntry(latest, read = baseline && (earlier?.read ?: true), at = now)
            }
        }
        nextKnown[slug] = (feed.videos.map { it.entryKey } + previous.orEmpty()).distinct().take(1000)
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
        val parsed = raw?.let {
            runCatching { library.gson.fromJson(it, PersonalLibrary::class.java).restored() }.getOrNull()
        }
        // 解析失败时不能静默当作空库：那会在下一次编辑时把片单、书签、收藏整体覆盖掉。
        // 先把原始内容另存为损坏快照，再返回空库继续可用，用户仍可从备份键里找回。
        if (raw != null && parsed == null) preserveCorrupt(raw)
        (parsed ?: PersonalLibrary()).also {
            cachedRaw = raw; cachedSnapshot = it
        }
    }
    private fun preserveCorrupt(raw: String) {
        val backup = "$KEY.corrupt.${System.currentTimeMillis()}"
        library.prefs.edit { putString(backup, raw) }
        // 只保留最近三份损坏快照，避免历史坏数据无限堆积。
        library.prefs.all.keys.filter { it.startsWith("$KEY.corrupt.") }.sortedDescending().drop(3)
            .forEach { key -> library.prefs.edit { remove(key) } }
    }
    private fun change(block: (PersonalLibrary) -> PersonalLibrary) = synchronized(library) {
        val previous = snapshot(); val next = block(previous)
        if (next != previous) {
            val raw = library.gson.toJson(next); cachedRaw = raw; cachedSnapshot = next
            library.prefs.edit { putString(KEY, raw) }
        }
    }
    private fun PersonalLibrary.restored(): PersonalLibrary = copy(
        savedSearches = savedSearches.map { it.restored() },
        channels = channels.map { it.restored() },
    )

    fun shelves() = snapshot().shelves
    fun saveSearch(value: SavedSearch) {
        require(value.name.trim().length in 1..80 && value.query.length <= 2048 && !value.condition.isEmpty)
        value.filter.validate()
        require(value.sources.isNotEmpty() && value.sources.all { id -> SiteRegistry.all.any { it.id == id } })
        val saved = value.restored()
        val normalized = saved.copy(name = saved.name.trim(), query = saved.query.trim(), sources = saved.sources.distinct())
        change { it.copy(savedSearches = it.savedSearches.filterNot { p -> p.id == value.id } + normalized,
            searchUpdates = if (it.searchUpdates[value.id]?.conditionKey == normalized.conditionKey) it.searchUpdates else it.searchUpdates - value.id) }
    }
    fun removeSearch(id: String) = change { it.copy(savedSearches = it.savedSearches.filterNot { p -> p.id == id }, searchUpdates = it.searchUpdates - id) }
    fun renameSearch(id: String, name: String) {
        val normalized = name.trim()
        require(normalized.length in 1..80)
        change { data -> data.copy(savedSearches = data.savedSearches.map { search ->
            if (search.id == id) search.copy(name = normalized) else search
        }) }
    }
    fun refreshSearch(search: SavedSearch, site: String, page: FeedPage?, error: String = "", directory: Boolean = false) = change { data ->
        val current = data.savedSearches.firstOrNull { it.id == search.id }
        if (current == null || current.conditionKey != search.conditionKey || site !in current.sources) data
        else {
            val box = data.searchUpdates[search.id]?.takeIf { it.conditionKey == current.conditionKey } ?: SavedSearchInbox(current.conditionKey)
            data.copy(searchUpdates = data.searchUpdates + (search.id to box.refreshed(site, page, error, directory = directory)))
        }
    }
    internal fun visibleSearchEntries(search: SavedSearch, data: PersonalLibrary, directorySources: Set<String>): List<InboxEntry> {
        val sources = search.matchingSources().mapTo(mutableSetOf()) { it.id } - directorySources
        val entries = data.searchUpdates[search.id]?.entries.orEmpty().filter { it.video.site in sources }
        return library.filterAllowed(entries, InboxEntry::video)
    }
    fun markSearchRead(id: String, keys: Set<String>) = change { data ->
        val box = data.searchUpdates[id]
        if (box == null) data else data.copy(searchUpdates = data.searchUpdates + (id to box.copy(entries = box.entries.map { if (it.video.entryKey in keys) it.copy(read = true) else it })))
    }
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
        change { data -> data.copy(shelves = data.shelves.map { if (it.id == id) it.edited(name = name) else it }) }
    }
    fun updateShelf(id: String, name: String, note: String, pinned: Boolean, coverKey: String) = change { data ->
        data.copy(shelves = data.shelves.map { if (it.id == id) it.edited(name, note, pinned, coverKey) else it })
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
    fun addToExistingShelf(videos: List<Video>, shelf: String): Int {
        require(videos.all { it.id.isNotBlank() && SiteRegistry.byId(it.site) != null }) { "有作品的来源已不可用" }
        var added = 0
        change { data ->
            val target = data.shelves.firstOrNull { it.id == shelf } ?: error("该片单已移除")
            val known = target.entries.mapTo(mutableSetOf()) { it.video.entryKey }
            val additions = videos.filter { known.add(it.entryKey) }.map { ShelfEntry(it) }
            added = additions.size
            if (additions.isEmpty()) data else data.copy(shelves = data.shelves.map {
                if (it.id == shelf) it.copy(entries = it.entries + additions) else it
            })
        }
        return added
    }
    fun remove(video: Video, shelf: String = PersonalLibrary.WATCH_LATER) = change { data ->
        data.copy(shelves = data.shelves.map { value -> if (value.id == shelf) {
            val next = if (value.coverKey == video.entryKey) value.edited(coverKey = "") else value
            next.copy(entries = value.entries.filter { e -> e.video.entryKey != video.entryKey })
        } else value })
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
    /**
     * 未显式设置过的站点用默认模式。
     *
     * 写真与漫画都默认“上下连续”：写真集的图页竖向长图居多，左右翻页要一直点；
     * 用户反馈过写真集应当上下滑。显式设置过（[setReaderMode] 或本书覆盖）的站点
     * 仍按用户选择执行，不强制覆盖旧偏好。
     */
    fun readerMode(site: String) = ReaderMode.entries.firstOrNull { it.name == snapshot().readerModes[site] }
        ?: ReaderMode.STRIP
    fun setReaderMode(site: String, mode: ReaderMode) = change { it.copy(readerModes = it.readerModes + (site to mode.name)) }
    fun inbox(site: String) = snapshot().inboxes[site] ?: SubscriptionInbox()

    internal fun visibleSubscriptionEntries(site: String? = null, data: PersonalLibrary = snapshot()): List<InboxEntry> {
        val entries = if (site == null) data.inboxes.values.flatMap { it.entries } else data.inboxes[site]?.entries.orEmpty()
        return library.filterAllowed(entries, InboxEntry::video).distinctBy { it.video.entryKey }.sortedByDescending { it.at }
    }

    fun refreshInbox(site: String, feeds: List<TagFeed>) = change { it.copy(inboxes = it.inboxes + (site to inbox(site).refreshed(feeds))) }

    internal fun refreshSubscriptionTag(feed: TagFeed): SubscriptionTagRefresh? = synchronized(library) {
        val tag = feed.subscription
        if (feed.error != null || feed.cached || feed.videos.any { it.site != tag.site } ||
            library.subscriptions(tag.site).none { it.slug == tag.slug && it.at == tag.at }) return@synchronized null
        val before = inbox(tag.site)
        val after = before.refreshed(listOf(feed))
        change { it.copy(inboxes = it.inboxes + (tag.site to after)) }
        val knownWorks = before.entries.mapTo(mutableSetOf()) { it.video.entryKey }
        val newEntries = after.entries.filter { !it.read && it.video.entryKey !in knownWorks }
        SubscriptionTagRefresh(tag.slug !in before.known, library.filterAllowed(newEntries, InboxEntry::video).size)
    }

    fun markRead(site: String, keys: Set<String>) = change { data ->
        val box = data.inboxes[site]
        data.copy(inboxes = if (box == null) data.inboxes else data.inboxes + (site to box.copy(entries = box.entries.map { if (it.video.entryKey in keys) it.copy(read = true) else it })),
            searchUpdates = data.searchUpdates.mapValues { (_, value) -> value.copy(entries = value.entries.map { if (it.video.site == site && it.video.entryKey in keys) it.copy(read = true) else it }) })
    }
    fun timeBookmarks(video: Video) = snapshot().timeBookmarks.filter { it.video.entryKey == video.entryKey }.sortedBy { it.positionMs }
    fun saveTimeBookmark(value: TimeBookmark) {
        require(value.positionMs >= 0 && value.note.length <= 500 && value.group.length <= 80 && value.video.id.isNotBlank())
        change { it.copy(timeBookmarks = (it.timeBookmarks.filterNot { mark -> mark.id == value.id } + value).takeLast(5000)) }
    }
    fun removeTimeBookmark(id: String) = change { it.copy(timeBookmarks = it.timeBookmarks.filterNot { mark -> mark.id == id }) }
    fun playback(site: String) = snapshot().playbackPreferences[site] ?: PlaybackPreferences()
    fun setPlayback(site: String, value: PlaybackPreferences) {
        require(SiteRegistry.byId(site) != null)
        change { it.copy(playbackPreferences = it.playbackPreferences + (site to value.validated())) }
    }
    fun readerPreferences(video: Video) = snapshot().readerPreferences[video.entryKey] ?: ReaderPreferences()
    fun setReaderPreferences(video: Video, value: ReaderPreferences) {
        require(value.cropPercent in 0..20 && (value.mode.isBlank() || ReaderMode.entries.any { it.name == value.mode }))
        change { it.copy(readerPreferences = it.readerPreferences + (video.entryKey to value)) }
    }
    fun clearReaderPreferences(video: Video) = change { it.copy(readerPreferences = it.readerPreferences - video.entryKey) }
    fun readingBehavior(site: String) = snapshot().readingBehaviors[site] ?: ReadingBehavior()
    fun setReadingBehavior(site: String, value: ReadingBehavior) {
        require(SiteRegistry.byId(site) != null && value.autoPageSeconds in 0..300 && value.slideshowSeconds in 0..300 && value.brightnessPercent in -1..100)
        change { it.copy(readingBehaviors = it.readingBehaviors + (site to value)) }
    }
    fun imageFavorites() = snapshot().imageFavorites.sortedByDescending { it.at }
    fun isImageFavorite(video: Video, page: Int) = snapshot().imageFavorites.any { it.video.entryKey == video.entryKey && it.page == page }
    fun toggleImageFavorite(video: Video, page: Int, pageUrl: String, imageUrl: String = "") {
        require(page > 0 && video.id.isNotBlank())
        val favorite = ImageFavorite(video, page, pageUrl, imageUrl)
        change { data -> data.copy(imageFavorites = if (data.imageFavorites.any { it.key == favorite.key }) data.imageFavorites.filterNot { it.key == favorite.key }
            else (data.imageFavorites + favorite).takeLast(20000)) }
    }
    fun pinSearch(value: SavedSearch) {
        val order = (snapshot().channels.minOfOrNull { it.order } ?: 0) - 1
        change { it.copy(channels = it.channels.filterNot { channel -> channel.savedSearch?.id == value.id } + DiscoveryChannel(name = value.name, savedSearch = value, order = order)) }
    }
    fun pinFeed(site: String, request: FeedRequest, name: String) {
        require(SiteRegistry.byId(site) != null && name.trim().length in 1..80)
        change { data ->
            val channel = DiscoveryChannel(name = name.trim(), site = site, kind = request.kind, target = request.target,
                order = (data.channels.minOfOrNull { it.order } ?: 0) - 1, contentKind = request.contentKind)
            data.copy(channels = data.channels.filterNot { it.site == site && it.feedRequest == channel.feedRequest } + channel)
        }
    }
    fun moveChannel(id: String, delta: Int) = change { data ->
        val all = data.channels.sortedBy { it.order }.toMutableList(); val from = all.indexOfFirst { it.id == id }; val to = from + delta
        if (from in all.indices && to in all.indices) all.add(to, all.removeAt(from))
        data.copy(channels = all.mapIndexed { index, channel -> channel.copy(order = index) })
    }
    fun hideChannel(id: String, hidden: Boolean) = change { data -> data.copy(channels = data.channels.map { if (it.id == id) it.copy(hidden = hidden) else it }) }
    fun removeChannel(id: String) = change { data -> data.copy(channels = data.channels.filterNot { it.id == id }) }
}
