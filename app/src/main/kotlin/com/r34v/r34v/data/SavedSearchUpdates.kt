package com.r34v.r34v.data

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.adapter.Capability
import kotlinx.coroutines.*
import java.security.MessageDigest

val SavedSearch.conditionKey: String get() {
    val definition = copy(id = "", name = "", sources = sources.sorted(), languages = languages.toSortedMap())
    return MessageDigest.getInstance("SHA-256").digest(Gson().toJson(definition).toByteArray()).joinToString("") { "%02x".format(it) }
}
data class SearchCheck(val checkedAt: Long = 0, val successAt: Long = 0, val error: String = "", val cached: Boolean = false)
data class SavedSearchInbox(val conditionKey: String = "", val known: Map<String, List<String>> = emptyMap(),
    val entries: List<InboxEntry> = emptyList(), val checks: Map<String, SearchCheck> = emptyMap()) {
    fun refreshed(site: String, page: FeedPage?, error: String = "", now: Long = System.currentTimeMillis()): SavedSearchInbox {
        val live = page != null && !page.cached && error.isBlank()
        val check = SearchCheck(now, if (live) now else checks[site]?.successAt ?: 0, error.take(2000), page?.cached == true)
        if (!live) return copy(checks = checks + (site to check))
        val previous = known[site]
        val existing = entries.associateBy { it.video.entryKey }
        val additions = page!!.videos.filter { it.site == site && it.entryKey !in existing }.distinctBy { it.entryKey }
            .map { InboxEntry(it, read = previous == null || it.entryKey in previous, at = now) }
        return copy(known = known + (site to (page.videos.filter { it.site == site }.map { it.entryKey } + previous.orEmpty()).distinct().take(1000)),
            entries = (additions + entries).sortedByDescending { it.at }.take(1000), checks = checks + (site to check))
    }
    fun merge(other: SavedSearchInbox): SavedSearchInbox {
        if (conditionKey != other.conditionKey) return other
        return copy(known = (known.keys + other.known.keys).associateWith { (known[it].orEmpty() + other.known[it].orEmpty()).distinct().take(1000) },
            entries = (entries + other.entries).groupBy { it.video.entryKey }.values.map { rows -> rows.maxBy { it.at }.copy(read = rows.any { it.read }) }.sortedByDescending { it.at }.take(1000),
            checks = (checks.keys + other.checks.keys).associateWith { id -> listOfNotNull(checks[id], other.checks[id]).maxBy { it.checkedAt } })
    }
}

class SavedSearchUpdateModel(application: Application) : AndroidViewModel(application) {
    private val app = application as R34Application
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    private var job: Job? = null
    fun cancel() { job?.cancel() }
    fun check(searches: List<SavedSearch>) {
        if (busy || searches.isEmpty()) return
        busy = true
        job = viewModelScope.launch {
            val tasks = searches.flatMap { search -> search.sources.filter { search.category.isBlank() || SiteRegistry.byId(it)?.category?.name == search.category }.map { search to it } }
            var finished = 0
            try {
                progress = "正在检查 0 / ${tasks.size}"
                // Bound both active requests and coroutine allocation. Completed sources commit independently.
                tasks.chunked(3).forEach { batch -> coroutineScope {
                    batch.map { (search, id) -> async {
                        try {
                            val page = withTimeout(45_000) {
                                val site = SiteRegistry.byId(id) ?: error("来源不可用")
                                if (id == "exhentai" && !app.network.session.hasGallerySession(site)) error("需要登录并具备本站访问权限")
                                val adapter = AdapterRegistry.adapter(site, app.network)
                                check(Capability.SEARCH in adapter.capabilities) { "本站不支持搜索" }
                                val base = adapter.searchRequest(search.query, FeedKind.SEARCH)
                                val request = base.copy(sort = if (Sort.NEWEST in adapter.sortsFor(base)) Sort.NEWEST else base.sort,
                                    includeTags = search.includeTags, excludeTags = search.excludeTags, filter = search.filter,
                                    language = if (adapter.supportsLanguage) GalleryLanguage.entries.firstOrNull { it.name == search.languages[id] } ?: GalleryLanguage.ALL else GalleryLanguage.ALL)
                                app.repository.feed(request, site).let { result -> result.copy(videos = result.videos.filter { app.library.allowed(it, id) }.map { it.copy(site = id) }) }
                            }
                            app.library.personal.refreshSearch(search, id, page)
                        } catch (error: Exception) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            app.library.personal.refreshSearch(search, id, null, if (error is TimeoutCancellationException) "检查超时，可重试" else error.message ?: "检查失败")
                        }
                        finished++; progress = "已检查 $finished / ${tasks.size} 个来源"
                    } }.awaitAll()
                } }
            } catch (cancelled: CancellationException) { progress = "已取消，保留已完成的检查"; throw cancelled }
            finally { busy = false }
        }
    }
}
