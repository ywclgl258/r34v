@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable fun FeedScreen(app: R34Application, site: SiteContext, request: FeedRequest, home: Boolean, state: FeedState,
    dialogs: ComposeDialogs, play: (Video) -> Unit, favorite: (Video) -> Unit, scroll: (Float, Boolean) -> Unit,
    permissionDenied: () -> Unit = {}, saved: Set<String> = emptySet(), continuing: List<Watch> = emptyList(),
    browse: (FeedRequest) -> Unit = {},
    subscribe: ((Subscription) -> Unit)? = null, subscribed: Boolean = false, sort: (FeedRequest) -> Unit) {
    val scope = rememberCoroutineScope(); val adapter = remember(site) { AdapterRegistry.adapter(site, app.network) }
    var busy by remember { mutableStateOf(false) }; var error by state::error
    var loading by remember { mutableStateOf<Job?>(null) }
    var videos by state::videos; var ranked by state::ranked
    var popularVideos by state::popular; var ratedVideos by state::rated
    var next by state::next; var total by state::total; var cursor by state::cursor
    var cached by state::cached; var loaded by state::loaded
    fun load(automatic: Boolean = false) {
        if (busy || loaded && next > total || automatic && state.emptyPages >= 3) return
        if (!automatic) state.emptyPages = 0
        busy = true; error = null
        val generation = ++state.generation
        val requested = request.copy(page = next, cursor = cursor)
        loading = scope.launch {
            val first = !loaded
            val popular: Deferred<List<Video>>? = if (home && first && site.mediaKind == MediaKind.VIDEO) async { try { withTimeoutOrNull(10000) { app.repository.feed(FeedRequest(FeedKind.POPULAR), site) }?.videos.orEmpty() } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() } } else null
            val rated: Deferred<List<Video>>? = if (popular != null) async { try { withTimeoutOrNull(10000) { app.repository.feed(FeedRequest(FeedKind.RATED), site) }?.videos.orEmpty() } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() } } else null
            try {
                val result = app.repository.feed(requested, site)
                ensureActive()
                if (generation != state.generation) return@launch
                val allowed = result.videos.filter { app.library.allowed(it, site.id) }
                if (first) {
                    state.loadedAt = System.currentTimeMillis()
                    cached = result.cached
                    val p = withTimeoutOrNull(600) { popular?.await() }.orEmpty().filter { app.library.allowed(it, site.id) }
                    val r = withTimeoutOrNull(600) { rated?.await() }.orEmpty().filter { app.library.allowed(it, site.id) }
                    popularVideos = p.take(10); ratedVideos = r.take(10)
                    ranked = (p.take(3).mapIndexed { i, v -> v to "热门榜 · 第 ${i + 1} 位" } + r.take(3).mapIndexed { i, v -> v to "高分榜 · 第 ${i + 1} 位" }).distinctBy { "${it.first.site}:${it.first.id}" }
                    if (ranked.isEmpty()) ranked = allowed.take(1).map { it to "最新精选" }
                }
                videos = (videos + allowed).distinctBy { "${it.site}:${it.id}" }; loaded = true
                next = result.current + 1; total = if (request.kind == FeedKind.FOR_YOU) result.current else result.total
                if (!result.nextCursor.isNullOrBlank() && (result.nextCursor == requested.cursor || !state.cursors.add(result.nextCursor))) {
                    total = result.current; error = "原站返回了重复的翻页地址，请刷新列表"
                    state.restartRequired = true
                }
                state.emptyPages = if (allowed.isEmpty() && next <= total) state.emptyPages + 1 else 0
                cursor = result.nextCursor.orEmpty()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is GalleryLoginRequiredException) { app.network.session.clear(site); permissionDenied() }
                error = e.message ?: "加载失败，请检查网络后重试"
            } finally { popular?.cancel(); rated?.cancel(); if (generation == state.generation) busy = false }
        }
    }
    fun refresh() {
        loading?.cancel(); state.reset(); busy = false
        state.grid.requestScrollToItem(0); state.list.requestScrollToItem(0); load()
    }
    LaunchedEffect(Unit) { if (!loaded) load() }
    // Create the lazy layout with its complete first page. Inserting a hero before an
    // already visible heading makes LazyGrid preserve that heading and skip the hero.
    if (!loaded) {
        val failure = error
        Box(Modifier.fillMaxSize().padding(top = if (home && site.mediaKind == MediaKind.VIDEO) 120.dp else 0.dp), contentAlignment = Alignment.Center) {
            if (failure == null) Loading() else EmptyState("内容加载失败", failure) { load() }
        }
        return
    }
    if (home && site.mediaKind == MediaKind.VIDEO) {
        val waitingRevision by app.library.personal.changes.collectAsState()
        val waiting = remember(waitingRevision) { app.library.personal.shelves().first { it.id == PersonalLibrary.WATCH_LATER }.entries.map { it.video.entryKey }.toSet() }
        CinemaHome(videos, ranked, popularVideos, ratedVideos, continuing, waiting, cached, busy, error,
            next <= total, { if (state.restartRequired) refresh() else load() }, play, favorite, browse, scroll, state.list, state.emptyPages < 3, { load(true) },
            toggleWaiting = { video -> if (app.library.personal.contains(video)) app.library.personal.remove(video) else app.library.personal.add(video) })
        return
    }
    val grid = state.grid
    LaunchedEffect(grid) {
        var previous = 0
        snapshotFlow { grid.firstVisibleItemIndex * 100000 + grid.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { value ->
            scroll((value - previous).toFloat(), value == 0); previous = value
        }
    }
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 7 } == true } }
    LaunchedEffect(nearEnd, next, busy) { if (nearEnd && loaded && next <= total && !busy && error == null) load(true) }
    val width = LocalConfiguration.current.screenWidthDp
    val columns = if (LocalConfiguration.current.fontScale > 1.5f && width < 400) 1 else if (width >= 1280) 6 else if (width >= 1024) 5 else if (width >= 840) 4 else if (width >= 600) 3 else 2
    val density = LocalUiDensity.current
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize().semantics { contentDescription = "内容列表" }, state = grid,
        contentPadding = PaddingValues(start = density.pagePadding, end = density.pagePadding, top = 16.dp, bottom = 132.dp),
        horizontalArrangement = Arrangement.spacedBy(density.cardGap), verticalItemSpacing = if (density.compact) 10.dp else 14.dp) {
        if (home && site.mediaKind == MediaKind.GALLERY && videos.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "gallery-stage") {
            val progress = app.library.readings(site.id).firstOrNull { it.page < it.pages && app.library.allowed(it.entry, site.id) }
            val entry = progress?.entry ?: videos.first()
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Title(if (progress != null) "继续${site.category.verb}" else if (site.category == SiteCategory.PHOTO) "打开一组新视角" else "翻开下一本", large = true)
                MediaResumeHero(entry, if (progress != null) "第 ${progress.page} / ${progress.pages} ${if (site.category == SiteCategory.PHOTO) "张" else "页"}" else site.name,
                    if (progress != null) "继续${site.category.verb}" else "开始${site.category.verb}", play)
                Spacer(Modifier.height(8.dp))
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Title(if (home) { if (site.mediaKind == MediaKind.GALLERY) "最新${adapter.itemNoun}" else if (site.id == "r34v") "最新视频" else "最新上传" } else adapter.feedTitle(request))
                if (cached) Copy("当前显示离线列表缓存")
                if (!home) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = adapter.sortsFor(request)
                    if (options.size > 1) Selector(adapter.sortTitle(request.sort.takeIf { it in options } ?: options.first())) {
                        dialogs.choose("排序方式", options.map(adapter::sortTitle), options.indexOf(request.sort).coerceAtLeast(0)) {
                            sort(request.copy(sort = options[it], page = 1, cursor = ""))
                        }
                    }
                    Action("刷新") { refresh() }
                    // Following is offered right where the tag is being read, not behind a menu.
                    if (subscribe != null && request.kind == FeedKind.TAG && request.target.isNotBlank())
                        SubscribeAction(if (subscribed) "已订阅" else "订阅标签", subscribed) {
                            subscribe(Subscription(slug = request.target, label = request.target, url = adapter.resolve(request), site = site.id))
                        }
                }
            }
        }
        items(videos, key = { "${it.site}:${it.id}" }, contentType = { "media" }) { video ->
            MediaCard(video, menu = listOf("收藏 / 取消收藏" to { favorite(video) })) { play(video) }
        }
        if (busy) item(span = StaggeredGridItemSpan.FullLine, key = "loading") { Loading(if (loaded) "正在加载下一页" else "正在加载内容") }
        error?.let { message -> item(span = StaggeredGridItemSpan.FullLine, key = "error") { EmptyState(if (loaded) "下一页加载失败" else "内容加载失败", message) { if (state.restartRequired) refresh() else load() } } }
        if (!busy && next <= total && state.emptyPages >= 3) item(span = StaggeredGridItemSpan.FullLine, key = "continue-empty") {
            Action("继续加载") { load() }
        }
        if (loaded && videos.isEmpty() && !busy) item(span = StaggeredGridItemSpan.FullLine) { Copy(if (site.mediaKind == MediaKind.GALLERY) "当前条件下暂无${adapter.itemNoun}，可调整筛选或修改搜索条件" else "暂时没有可显示的视频") }
    }
}

@Composable fun ExploreScreen(adapter: SiteAdapter, feed: (FeedRequest) -> Unit, browse: (String) -> Unit) {
    val density = LocalUiDensity.current
    val columns = if (density.compact) 3 else 2
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(density.pagePadding), verticalArrangement = Arrangement.spacedBy(if (density.compact) 12.dp else 14.dp)) {
        Title("找到下一部想看的", large = true); Copy("从榜单、分类和创作者开始")
        val genres = adapter.sections.filter { it.feed?.kind == FeedKind.CATEGORY }
        val ranked = adapter.sections.filter { it.feed != null && it !in genres }.sortedByDescending { it.feed?.kind == FeedKind.POPULAR }
        if (ranked.isNotEmpty()) {
            Title("精选榜单")
            ranked.chunked(columns).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { section ->
                        val popular = section.feed?.kind == FeedKind.POPULAR
                        Column(Modifier.weight(1f).contentPanel(20).clickable { feed(section.feed!!) }
                            .then(if (popular) Modifier.background(BrandColor.copy(alpha = .12f)) else Modifier)
                            .padding(if (density.compact) 12.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Glyph(R.drawable.ic_chevron_right, null, Modifier.size(14.dp))
                            }
                            if (popular) Text("本周焦点 · 先看大家都在看的内容", color = MutedColor, fontSize = 11.sp)
                        }
                    }
                    repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (genres.isNotEmpty()) { Title(adapter.catalogueTitle); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { genres.forEach { section -> Choice(section.title) { feed(section.feed!!) } } } }
        adapter.sections.filter { it.feed == null && adapter.sectionUrl(it.key) != null }.forEach { section ->
            Row(Modifier.fillMaxWidth().contentPanel(20).clickable { browse(section.key) }.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(section.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (section.subtitle.isNotBlank()) Copy(section.subtitle)
                }
                Glyph(R.drawable.ic_chevron_right, null, Modifier.size(16.dp))
            }
        }
    }
}
@Composable fun IndexScreen(app: R34Application, site: SiteContext, segment: String, dialogs: ComposeDialogs, feed: (FeedRequest) -> Unit, browse: (String) -> Unit,
    subscribe: (Subscription) -> Unit = {}) {
    val adapter = remember(site) { AdapterRegistry.adapter(site, app.network) }
    var links by remember { mutableStateOf<List<Link>?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var retry by remember { mutableIntStateOf(0) }
    var followedSlugs by remember(site.id) { mutableStateOf(app.library.subscriptions(site.id).map { it.slug }.toSet()) }
    LaunchedEffect(site, segment, retry) { try { links = app.repository.index(segment) } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "索引加载失败" } }
    if (links == null && error == null) Loading()
    else if (error != null) EmptyState("索引加载失败", error.orEmpty()) { error = null; retry++ }
    else androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Title(when (segment) { "works" -> "选择作品"; "artists" -> "作者排行"; else -> "浏览索引" }) }
        if (segment == "tags" || segment.startsWith("work:")) item { Copy("点标签进入浏览，右侧按钮可直接订阅；订阅内容只在当前站点内汇总") }
        if (links.isNullOrEmpty()) item { Copy("没有找到条目") }
        items(links!!.size, key = { "${links!![it].slug}:$it" }) { i -> val link = links!![i]
            val isTag = segment == "tags" || segment.startsWith("work:")
            val followed = isTag && link.slug in followedSlugs
            Row(Modifier.fillMaxWidth().background(SurfaceColor).clickable {
                if (segment == "works") browse("work:${link.slug}") else feed(adapter.linkRequest(link.url)
                    ?: FeedRequest(when { segment == "categories" -> FeedKind.CATEGORY; segment == "models" || segment == "artists" -> FeedKind.MODEL; else -> FeedKind.TAG }, link.slug))
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (link.thumb.isNotBlank()) SiteImage(link.thumb, site, Modifier.size(66.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(if (segment == "artists" || segment.startsWith("work:")) "${i + 1}. ${link.label}" else link.label); if (link.subtitle.isNotBlank()) Copy(link.subtitle) }
                // A swipe-free, always-visible control: long-press is undiscoverable for something
                // that changes what the whole subscription page shows.
                if (isTag) SubscribeAction(if (followed) "已订阅" else "订阅", followed) {
                    subscribe(Subscription(link.slug, link.label, link.url, site.id))
                    followedSlugs = app.library.subscriptions(site.id).map { it.slug }.toSet()
                }
            }
        }
    }
}
data class LibraryCard(val video: Video, val menuLabel: String, val remove: () -> Unit, val read: Boolean = false, val progress: Float? = null)
@Composable fun MediaLibrary(cards: List<LibraryCard>, clear: (() -> Unit)?, empty: @Composable () -> Unit, play: (LibraryCard) -> Unit) {
    val width = LocalConfiguration.current.screenWidthDp
    val columns = if (width >= 1280) 6 else if (width >= 1024) 5 else if (width >= 840) 4 else if (width >= 600) 3 else 2
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap), contentPadding = PaddingValues(top = 12.dp), verticalItemSpacing = 8.dp) {
        if (clear != null) item(span = StaggeredGridItemSpan.FullLine) { TextAction(clear) { Text("清空记录") } }
        if (cards.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) { empty() }
        items(cards, key = { "${it.video.site}:${it.video.id}" }) { card -> MediaCard(card.video, menu = listOf(card.menuLabel to card.remove), progress = card.progress) { play(card) } }
    }
}
