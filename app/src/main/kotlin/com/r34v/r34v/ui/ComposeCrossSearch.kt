package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SearchBucket(val videos: List<Video> = emptyList(), val next: Int = 1, val cursor: String = "", val more: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val cached: Boolean = false, val directory: FeedRequest? = null)

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
        sites.forEach { load(app, it) }
    }
    fun refreshSource(app: R34Application, site: String) {
        jobs[site]?.cancel(); buckets = buckets + (site to SearchBucket()); load(app, site)
    }
    fun load(app: R34Application, siteId: String) {
        val bucket = buckets[siteId] ?: return
        if (bucket.busy || !bucket.more) return
        val site = SiteRegistry.byId(siteId) ?: return
        val epoch = generation
        val language = GalleryLanguage.entries.firstOrNull { it.name == languages[siteId] } ?: app.library.galleryLanguage(siteId)
        val adapter = AdapterRegistry.adapter(site, app.network)
        val request = condition.feedRequest(adapter, language = language).copy(page = bucket.next, cursor = bucket.cursor)
        if (adapter.indexSection(request) != null) {
            buckets = buckets + (siteId to SearchBucket(more = false, directory = request))
            return
        }
        buckets = buckets + (siteId to bucket.copy(busy = true, error = null))
        jobs[siteId] = viewModelScope.launch {
            try {
                val page = permits.withPermit {
                    if (siteId == "exhentai" && !app.network.session.hasGallerySession(site)) error("本站需要登录，请在站点设置中完成认证")
                    check(Capability.SEARCH in adapter.capabilities) { "本站不支持关键词搜索" }
                    app.repository.feed(request, site)
                }
                ensureActive()
                if (generation != epoch) return@launch
                val repeated = page.nextCursor != null && page.nextCursor == bucket.cursor && bucket.cursor.isNotBlank()
                val videos = (bucket.videos + page.videos.map { it.copy(site = siteId) }
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
        AlertDialog(::dismiss, title = { Title("搜索来源", large = true) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Copy("已选择 ${checked.size} 个站点")
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    sites.forEach { site ->
                        CheckRow("${site.name} · ${site.category.title}", site.id in checked) {
                            checked = if (it) checked + site.id else checked - site.id
                        }
                    }
                }
            }
        }, shape = ArtworkShape, containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant, tonalElevation = 0.dp, confirmButton = {
            Row {
                TextAction({ checked = sites.map { it.id }.toSet() }) { Text("全选") }
                Action("确定", primary = true, enabled = checked.isNotEmpty()) {
                    dismiss()
                    action(sites.filter { it.id in checked }.map { it.id })
                }
            }
        }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    }
}

@Composable fun CrossSearchScreen(app: R34Application, state: CrossSearchState, query: String, sites: List<String>,
    play: (Video) -> Unit, favorite: (Video) -> Unit, rulesVersion: Int, initialCategory: String = "", languages: Map<String, String> = emptyMap(),
    categoryChanged: (String) -> Unit = {}, save: (() -> Unit)? = null, condition: SearchCondition? = null,
    openDirectory: (FeedRequest, SiteContext) -> Unit) {
    val category = SiteCategory.entries.indexOfFirst { it.name == initialCategory } + 1
    var only by rememberSaveable { mutableStateOf("") }
    val visible = sites.filter { category == 0 || SiteRegistry.byId(it)?.category == SiteCategory.entries[category - 1] }
    LaunchedEffect(query, sites, rulesVersion, languages, condition) { state.search(app, query, sites, languageOverrides = languages, condition = condition) }
    val columns = when {
        LocalConfiguration.current.fontScale > 1.5f && windowSizeDp().width < 400 -> 1
        windowSizeDp().width >= 1280 -> 6
        windowSizeDp().width >= 1024 -> 5
        windowSizeDp().width >= 840 -> 4
        windowSizeDp().width >= 600 -> 3
        else -> 2
    }
    val activeOnly = only.takeIf { it in visible }.orEmpty()
    val activeSites = if (activeOnly.isBlank()) visible else listOf(activeOnly)
    val merged = remember(state.buckets, activeSites) {
        activeSites.flatMap { state.buckets[it]?.videos.orEmpty() }.distinctBy { it.entryKey }
    }
    val directories = activeSites.mapNotNull { id ->
        val site = SiteRegistry.byId(id) ?: return@mapNotNull null
        state.buckets[id]?.directory?.let { site to it }
    }
    val failures = activeSites.mapNotNull { id -> state.buckets[id]?.error?.let { id to it } }
    val pending = activeSites.count { state.buckets[it]?.busy != false }
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize().testTag("cross-search-results"),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalItemSpacing = 10.dp) {
        item(span = StaggeredGridItemSpan.FullLine, key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Title(if (query.isBlank()) "高级条件搜索" else "搜索：$query", large = true, maxLines = 3)
                if (condition != null && !condition.isEmpty && query.isNotBlank()) Copy("已应用高级筛选")
                ContentTabs(listOf("全部") + SiteCategory.entries.map { it.title }, category) { index ->
                    categoryChanged(if (index == 0) "" else SiteCategory.entries[index - 1].name)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (visible.size > 1 || activeOnly.isNotBlank()) SearchSourceFilter(visible, activeOnly, state.buckets) { only = it }
                    else if (visible.size == 1) Copy(SiteRegistry.byIdOrDefault(visible.first()).name)
                }
                if (merged.isNotEmpty()) {
                    val status = "已加载 ${merged.size} 项" + if (pending > 0) " · 正在搜索 $pending 个来源" else ""
                    Copy(status, Modifier.padding(vertical = 3.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (save != null) TextAction(save) {
                        Glyph(R.drawable.ic_bookmark, null)
                        Spacer(Modifier.width(6.dp))
                        Text("保存搜索方案")
                    }
                    TextAction({ state.search(app, query, sites, force = true, languageOverrides = languages, condition = condition) }) {
                        Text("刷新全部来源", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        directories.forEach { (site, request) ->
            item(span = StaggeredGridItemSpan.FullLine, key = "directory:${site.id}") {
                SearchDirectoryEntry(site) { openDirectory(request, site) }
            }
        }
        items(merged.size, key = { merged[it].entryKey }, contentType = { "result" }) { index ->
            val video = merged[index]
            MediaCard(video, menu = listOf("收藏 / 取消收藏" to { favorite(video) })) { play(video) }
        }
        if (failures.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "source-errors") {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Title("来源状态")
                failures.forEach { (id, message) ->
                    Row(Modifier.fillMaxWidth().artworkPanel()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(SiteRegistry.byIdOrDefault(id).name, style = MaterialTheme.typography.titleMedium)
                            Copy(message)
                        }
                        TextAction({ state.refreshSource(app, id) }) { Text("刷新来源") }
                    }
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending > 0 && merged.isEmpty()) Loading(title = "正在搜索 $pending 个来源")
                if (pending == 0 && merged.isEmpty() && failures.isEmpty() && (activeSites.isEmpty() || activeSites.size != directories.size)) {
                    Copy(if (activeSites.isEmpty()) "此分类没有已选的搜索来源" else "当前条件下暂无结果",
                        Modifier.fillMaxWidth().artworkPanel().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (pending == 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeSites.filter { state.buckets[it]?.more == true && state.buckets[it]?.error == null }
                        .forEach { id -> Action("加载 ${SiteRegistry.byIdOrDefault(id).name} 下一页") { state.load(app, id) } }
                }
            }
        }
    }
}

@Composable internal fun SearchDirectoryEntry(site: SiteContext, description: String = "搜索结果位于站点目录", open: () -> Unit) {
    val label = if (site.id == SiteRegistry.GALLERYEPIC.id) "Coser 目录" else "搜索目录"
    Row(Modifier.fillMaxWidth().artworkPanel().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(site.name, style = MaterialTheme.typography.titleMedium)
            Copy(description)
        }
        TextAction(open) { Text("打开 $label") }
    }
}

@Composable
private fun SearchSourceFilter(sites: List<String>, active: String, buckets: Map<String, SearchBucket>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Selector(if (active.isBlank()) "全部来源" else SiteRegistry.byIdOrDefault(active).name) { open = true }
        DropdownMenu(open, { open = false }) {
            (listOf("") + sites).forEach { id ->
                val name = if (id.isBlank()) "全部来源" else SiteRegistry.byIdOrDefault(id).name
                val status = when {
                    id.isBlank() -> sites.sumOf { buckets[it]?.videos?.size ?: 0 }.toString()
                    buckets[id]?.directory != null -> "目录"
                    else -> (buckets[id]?.videos?.size ?: 0).toString()
                }
                DropdownMenuItem(
                    text = { Text("$name · $status") },
                    onClick = { open = false; select(id) },
                    trailingIcon = if (active == id) ({ Glyph(R.drawable.ic_check, null) }) else null,
                )
            }
        }
    }
}
