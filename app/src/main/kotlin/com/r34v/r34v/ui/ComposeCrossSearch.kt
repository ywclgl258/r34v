package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SearchBucket(val videos: List<Video> = emptyList(), val next: Int = 1, val cursor: String = "", val more: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val cached: Boolean = false)

class CrossSearchState : ViewModel() {
    var buckets by mutableStateOf<Map<String, SearchBucket>>(emptyMap()); private set
    private var signature = ""
    private var generation = 0
    private val jobs = mutableMapOf<String, Job>()
    private val permits = Semaphore(3)
    private var languages: Map<String, String> = emptyMap()
    private var condition = SearchCondition()
    fun search(app: R34Application, query: String, sites: List<String>, force: Boolean = false, languageOverrides: Map<String, String> = emptyMap(), condition: SearchCondition? = null) {
        val effective = (condition ?: SearchCondition(query)).copy(query = query).normalized()
        val key = "$effective|${sites.joinToString()}|$languageOverrides|" + sites.joinToString { "${app.library.galleryLanguage(it)}:${app.library.blockedTags(it)}" }
        if (!force && key == signature) return
        this.condition = effective
        languages = languageOverrides
        jobs.values.forEach { it.cancel() }; jobs.clear(); generation++; signature = key
        buckets = sites.associateWith { SearchBucket() }
        sites.forEach { load(app, query, it) }
    }
    fun refreshSource(app: R34Application, query: String, site: String) {
        jobs[site]?.cancel(); buckets = buckets + (site to SearchBucket()); load(app, query, site)
    }
    fun load(app: R34Application, query: String, siteId: String) {
        val bucket = buckets[siteId] ?: return
        if (bucket.busy || !bucket.more) return
        val site = SiteRegistry.byId(siteId) ?: return
        val epoch = generation
        buckets = buckets + (siteId to bucket.copy(busy = true, error = null))
        jobs[siteId] = viewModelScope.launch {
            try {
                val page = permits.withPermit {
                    if (siteId == "exhentai" && !app.network.session.hasGallerySession(site)) error("本站需要登录，请在站点设置中完成认证")
                    val adapter = AdapterRegistry.adapter(site, app.network)
                    check(Capability.SEARCH in adapter.capabilities) { "本站不支持关键词搜索" }
                    app.repository.feed(adapter.searchRequest(condition.query, FeedKind.SEARCH).copy(page = bucket.next, cursor = bucket.cursor,
                        includeTags = condition.includeTags, excludeTags = condition.excludeTags, filter = condition.filter,
                        language = if (adapter.supportsLanguage) GalleryLanguage.entries.firstOrNull { it.name == languages[siteId] } ?: app.library.galleryLanguage(siteId) else GalleryLanguage.ALL), site)
                }
                ensureActive()
                if (generation != epoch) return@launch
                val repeated = page.nextCursor != null && page.nextCursor == bucket.cursor && bucket.cursor.isNotBlank()
                val videos = (bucket.videos + page.videos.map { it.copy(site = siteId, mediaKind = site.mediaKind) }
                    .filter { app.library.allowed(it, siteId) }).distinctBy { it.entryKey }
                buckets = buckets + (siteId to SearchBucket(videos, bucket.next + 1, page.nextCursor.orEmpty(), page.hasNext && !repeated,
                    error = if (repeated) "站点返回了重复分页，请刷新搜索" else null, cached = page.cached))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == epoch) buckets = buckets + (siteId to bucket.copy(error = e.message ?: "搜索失败"))
            }
        }
    }
}

fun searchableSiteIds(network: Network): List<String> =
    SiteRegistry.all.filter { Capability.SEARCH in AdapterRegistry.adapter(it, network).capabilities }.map { it.id }

fun ComposeDialogs.searchSources(app: R34Application, selected: List<String>, action: (List<String>) -> Unit) {
    val sites = searchableSiteIds(app.network).mapNotNull(SiteRegistry::byId)
    content = {
        var checked by remember { mutableStateOf(selected.toSet().ifEmpty { sites.map { it.id }.toSet() }) }
        AlertDialog(::dismiss, title = { Text("搜索来源") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sites.forEach { site -> CheckRow("${site.name} · ${site.category.title}", site.id in checked) {
                    checked = if (it) checked + site.id else checked - site.id
                } }
            }
        }, confirmButton = {
            Row {
                TextAction({ checked = sites.map { it.id }.toSet() }) { Text("全选") }
                TextAction({ dismiss(); action(sites.filter { it.id in checked }.map { it.id }) }, enabled = checked.isNotEmpty()) { Text("确定") }
            }
        }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    }
}

@Composable fun CrossSearchScreen(app: R34Application, state: CrossSearchState, query: String, sites: List<String>,
    play: (Video) -> Unit, favorite: (Video) -> Unit, rulesVersion: Int, initialCategory: String = "", languages: Map<String, String> = emptyMap(),
    categoryChanged: (String) -> Unit = {}, save: (() -> Unit)? = null, condition: SearchCondition? = null) {
    var category by rememberSaveable(initialCategory) { mutableIntStateOf(SiteCategory.entries.indexOfFirst { it.name == initialCategory } + 1) }
    LaunchedEffect(query, sites, rulesVersion, languages, condition) { state.search(app, query, sites, languageOverrides = languages, condition = condition) }
    val columns = when {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1280 -> 4
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 -> 3
        else -> 2
    }
    LazyColumn(Modifier.fillMaxSize().testTag("cross-search-results"), contentPadding = PaddingValues(LocalUiDensity.current.pagePadding), verticalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap)) {
        item {
            Title(if (query.isBlank()) "高级条件搜索" else "搜索：$query")
            if (condition != null && !condition.isEmpty) Copy("已应用组合条件；各来源的实际筛选范围如下。")
            Choices(listOf("全部") + SiteCategory.entries.map { it.title }, category) { category = it; categoryChanged(if (it == 0) "" else SiteCategory.entries[it - 1].name) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction({ state.search(app, query, sites, force = true, languageOverrides = languages, condition = condition) }) { Text("刷新全部来源") }
                if (save != null) TextAction(save) { Text("保存搜索方案") }
            }
        }
        sites.filter { category == 0 || SiteRegistry.byIdOrDefault(it).category == SiteCategory.entries[category - 1] }.forEach { id ->
            val site = SiteRegistry.byIdOrDefault(id)
            val bucket = state.buckets[id] ?: SearchBucket(busy = true)
            item(key = "$id:title") {
                Title("${site.name} · ${bucket.videos.size} 项")
                if (condition != null && !condition.isEmpty) Copy(filterScope(id))
                if (bucket.cached) Copy("离线缓存结果")
            }
            items(bucket.videos.chunked(columns), key = { "$id:${it.first().id}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap)) {
                    row.forEach { video -> MediaCard(video, Modifier.weight(1f), menu = listOf("收藏 / 取消收藏" to { favorite(video) })) { play(video) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item(key = "$id:status") {
                when {
                    bucket.busy -> Loading("正在搜索 ${site.name}")
                    bucket.error != null -> { Copy(bucket.error); Action("重试 ${site.name}") {
                        if (bucket.more) state.load(app, query, id) else state.refreshSource(app, query, id)
                    } }
                    bucket.videos.isEmpty() -> Copy("当前条件下暂无结果")
                }
                if (!bucket.busy && bucket.error == null && bucket.more) Action("加载 ${site.name} 下一页") { state.load(app, query, id) }
            }
        }
    }
}
