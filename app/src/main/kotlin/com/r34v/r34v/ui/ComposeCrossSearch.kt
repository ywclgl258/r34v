package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
    // 来源筛选：空集=全部来源。分组标题会让同一关键词的结果被切成十几段，
    // 混合瀑布流更接近“一次搜索看全部来源”的预期。
    var only by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query, sites, rulesVersion, languages, condition) { state.search(app, query, sites, languageOverrides = languages, condition = condition) }
    val columns = when {
        LocalConfiguration.current.fontScale > 1.5f && windowSizeDp().width < 400 -> 1
        windowSizeDp().width >= 1280 -> 6
        windowSizeDp().width >= 1024 -> 5
        windowSizeDp().width >= 840 -> 4
        windowSizeDp().width >= 600 -> 3
        else -> 2
    }
    val visible = sites.filter { category == 0 || SiteRegistry.byIdOrDefault(it).category == SiteCategory.entries[category - 1] }
    val merged = remember(state.buckets, visible, only) {
        visible.filter { only.isBlank() || only == it }
            .flatMap { id -> (state.buckets[id]?.videos.orEmpty()).map { it.copy(site = id) } }
            .distinctBy { it.entryKey }
    }
    val failures = visible.mapNotNull { id -> state.buckets[id]?.takeIf { it.error != null }?.let { SiteRegistry.byIdOrDefault(id).name to it } }
    val busy = visible.any { state.buckets[it]?.busy == true }
    val density = LocalUiDensity.current
    val grid = rememberScrollState()
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize().testTag("cross-search-results"),
        contentPadding = PaddingValues(start = density.pagePadding, end = density.pagePadding, top = 12.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(density.cardGap), verticalItemSpacing = 10.dp) {
        item(span = StaggeredGridItemSpan.FullLine, key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Title(if (query.isBlank()) "高级条件搜索" else "搜索：$query")
                if (condition != null && !condition.isEmpty) Copy("已应用组合条件；各来源的实际筛选范围如下。")
                Choices(listOf("全部") + SiteCategory.entries.map { it.title }, category) { category = it; categoryChanged(if (it == 0) "" else SiteCategory.entries[it - 1].name) }
                if (visible.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("全部来源 · ${visible.sumOf { state.buckets[it]?.videos?.size ?: 0 }}", only.isBlank()) { only = "" }
                    visible.forEach { id ->
                        val bucket = state.buckets[id]
                        Choice("${SiteRegistry.byIdOrDefault(id).name} · ${bucket?.videos?.size ?: 0}", only == id) { only = if (only == id) "" else id }
                    }
                }
                Copy("结果按来源混合排列；点上方站点只显示该站。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextAction({ state.search(app, query, sites, force = true, languageOverrides = languages, condition = condition) }) { Text("刷新全部来源") }
                    if (save != null) TextAction(save) { Text("保存搜索方案") }
                }
                if (failures.isNotEmpty()) failures.forEach { (name, bucket) -> Copy("$name：${bucket.error}") }
            }
        }
        items(merged.size, key = { merged[it].entryKey }, contentType = { "result" }) { index ->
            val video = merged[index]
            MediaCard(video, menu = listOf("收藏 / 取消收藏" to { favorite(video) })) { play(video) }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) Loading(title = "正在搜索 ${visible.size} 个来源")
                if (!busy && merged.isEmpty() && failures.isEmpty()) Copy("当前条件下暂无结果")
                if (!busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    visible.filter { only.isBlank() || only == it }
                        .filter { state.buckets[it]?.more == true && state.buckets[it]?.busy != true && state.buckets[it]?.error == null }
                        .forEach { id -> Action("加载 ${SiteRegistry.byIdOrDefault(id).name} 更多") { state.load(app, query, id) } }
                }
            }
        }
    }
}
