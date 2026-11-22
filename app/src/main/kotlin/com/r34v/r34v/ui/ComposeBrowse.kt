@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    openDetails: (Video) -> Unit = play, resumeGallery: (Video) -> Unit = play,
    onArtwork: ((ArtworkPalette) -> Unit)? = null,
    subscribe: ((Subscription) -> Unit)? = null, subscribed: Boolean = false,
    browseToolbar: (@Composable (refresh: () -> Unit) -> Unit)? = null, sort: (FeedRequest) -> Unit) {
    val scope = rememberCoroutineScope(); val adapter = remember(site) { AdapterRegistry.adapter(site, app.network) }
    var busy by remember { mutableStateOf(false) }; var error by state::error
    var loading by remember { mutableStateOf<Job?>(null) }
    var videos by state::videos
    var next by state::next; var total by state::total; var cursor by state::cursor
    var cached by state::cached; var loaded by state::loaded
    val gridInset = if (browseToolbar != null) 16.dp else 14.dp
    fun load(automatic: Boolean = false) {
        if (busy || loaded && next > total || automatic && state.emptyPages >= 3) return
        if (!automatic) state.emptyPages = 0
        busy = true; error = null
        val generation = ++state.generation
        val requested = request.copy(page = next, cursor = cursor)
        loading = scope.launch {
            val first = !loaded
            try {
                val result = app.repository.feed(requested, site)
                ensureActive()
                if (generation != state.generation) return@launch
                val allowed = result.videos.filter { app.library.allowed(it, site.id) }
                if (first) {
                    state.loadedAt = System.currentTimeMillis()
                    cached = result.cached
                }
                videos = (videos + allowed).distinctBy(Video::entryKey); loaded = true
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
            } finally { if (generation == state.generation) busy = false }
        }
    }
    fun refresh() {
        loading?.cancel(); state.reset(); busy = false
        state.grid.requestScrollToItem(0); state.list.requestScrollToItem(0); load()
    }
    LaunchedEffect(Unit) { if (!loaded) load() }
    val cinemaHome = home && site.mediaKind == MediaKind.VIDEO
    val refreshCount = state.refreshCount
    var rankingsReady by remember(state, refreshCount) { mutableStateOf(!cinemaHome) }
    if (cinemaHome) {
        LaunchedEffect(state, refreshCount) {
            val latest = FeedRequest(language = request.language)
            coroutineScope {
                for (kind in listOf(FeedKind.POPULAR, FeedKind.RATED)) {
                    if (kind in state.homeRankings) continue
                    launch {
                        val ranking = latest.copy(kind = kind)
                        try {
                            // Some sites map unsupported rankings straight back to their latest feed.
                            if (adapter.resolve(ranking) == adapter.resolve(latest)) return@launch
                            val entries = app.repository.feed(ranking, site).videos.filter { app.library.allowed(it, site.id) }
                            ensureActive()
                            if (refreshCount == state.refreshCount) state.homeRankings[kind] = entries.take(10)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Leave the slot unset so the next visit can retry just this ranking.
                        }
                    }
                }
            }
            rankingsReady = true
        }
    }
    val position = state.restorePosition
    val rankingPosition = cinemaHome && position?.shelf in listOf("popular", "rated")
    val waitingForRanking = rankingPosition && !rankingsReady
    val needsRestorePage = position != null && loaded && !waitingForRanking && !state.restartRequired &&
        next <= total && next.toLong() <= position.throughPage.toLong() + 1 && videos.none { it.entryKey == position.work } &&
        (!rankingPosition || state.homeRankings.values.none { shelf -> shelf.any { it.entryKey == position.work } })
    LaunchedEffect(state, position, needsRestorePage, next, busy, error) {
        if (needsRestorePage && !busy && error == null) load()
    }
    // Create the lazy layout with its complete first page. Inserting a hero before an
    // already visible heading makes LazyGrid preserve that heading and skip the hero.
    if (!loaded || needsRestorePage || waitingForRanking) {
        val failure = error
        Column(Modifier.fillMaxSize()) {
            if (browseToolbar != null) Box(Modifier.padding(horizontal = gridInset)) { browseToolbar(::refresh) }
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = if (home && site.mediaKind == MediaKind.VIDEO) 120.dp else 0.dp),
                contentAlignment = Alignment.Center) {
                if (failure == null) Loading() else EmptyState("内容加载失败", failure) { load() }
            }
        }
        return
    }
    if (cinemaHome) {
        val popularVideos = state.homeRankings[FeedKind.POPULAR].orEmpty()
        val ratedVideos = state.homeRankings[FeedKind.RATED].orEmpty()
        val ranked = remember(popularVideos, ratedVideos, videos) {
            val candidates = (popularVideos.take(3).mapIndexed { index, video -> video to "热门榜 · 第 ${index + 1} 位" } +
                ratedVideos.take(3).mapIndexed { index, video -> video to "高分榜 · 第 ${index + 1} 位" }).distinctBy { it.first.entryKey }
            val firstPopular = popularVideos.firstOrNull()?.entryKey
            candidates.filterNot { it.first.entryKey == firstPopular }.ifEmpty {
                videos.firstOrNull { it.entryKey != firstPopular }?.let { listOf(it to "最新精选") }
                    ?: candidates.ifEmpty { videos.take(1).map { it to "最新精选" } }
            }
        }
        val waitingRevision by app.library.personal.changes.collectAsState()
        val waiting = remember(waitingRevision) { app.library.personal.shelves().first { it.id == PersonalLibrary.WATCH_LATER }.entries.map { it.video.entryKey }.toSet() }
        var featured by remember(state) { mutableStateOf<Video?>(null) }
        if (onArtwork != null) {
            val cover = featured?.thumb ?: ranked.firstOrNull()?.first?.thumb.orEmpty()
            val palette = rememberArtworkPalette(site, cover)
            LaunchedEffect(palette) { onArtwork(palette) }
        }
        CinemaHome(videos, ranked, popularVideos, ratedVideos, continuing, waiting, cached, busy, error,
            next <= total, { if (state.restartRequired) refresh() else load() }, play, favorite, browse, scroll, state, state.emptyPages < 3, { load(true) },
            toggleWaiting = { video -> if (app.library.personal.contains(video)) app.library.personal.remove(video) else app.library.personal.add(video) },
            openDetails = openDetails, onFeatured = { featured = it })
        return
    }
    val grid = state.grid
    val hasGalleryStage = home && site.mediaKind == MediaKind.GALLERY && videos.isNotEmpty()
    val progress = if (hasGalleryStage) app.library.readings(site.id)
        .firstOrNull { it.page < it.pages && app.library.allowed(it.entry, site.id) } else null
    if (home && onArtwork != null) {
        val cover = (if (site.category == SiteCategory.COMIC) progress?.entry else null)?.thumb
            ?: videos.firstOrNull()?.thumb.orEmpty()
        val palette = rememberArtworkPalette(site, cover)
        LaunchedEffect(palette) { onArtwork(palette) }
    }
    LaunchedEffect(grid, position) {
        if (position != null) {
            val index = videos.indexOfFirst { it.entryKey == position.work }
            if (index >= 0) grid.scrollToItem(index + 1)
            state.finishRestoring(found = index >= 0)
        }
    }
    LaunchedEffect(grid, state.remembersPosition) {
        if (!state.remembersPosition) return@LaunchedEffect
        snapshotFlow {
            val visible = grid.layoutInfo.visibleItemsInfo
            if (state.restorePosition != null || grid.isScrollInProgress || visible.isEmpty()) null
            else {
                val atTop = grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
                // Reflow or a new neighbour in the same row should keep the restored work.
                val kept = !atTop && visible.any { it.key == state.restoredWork }
                if (kept) null else {
                    val work = if (atTop) null else visible.firstNotNullOfOrNull { (it.key as? String)?.takeIf { key -> ':' in key } }
                    FeedPosition(work.orEmpty(), state.next - 1)
                }
            }
        }.collect { settled -> if (settled != null) state.savePosition(settled.takeIf { it.work.isNotEmpty() }) }
    }
    LaunchedEffect(grid) {
        var previous = 0
        snapshotFlow { grid.firstVisibleItemIndex * 100000 + grid.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { value ->
            scroll((value - previous).toFloat(), value == 0); previous = value
        }
    }
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 7 } == true } }
    LaunchedEffect(nearEnd, next, busy, position) { if (position == null && nearEnd && loaded && next <= total && !busy && error == null) load(true) }
    val width = windowSizeDp().width
    val columns = when {
        LocalConfiguration.current.fontScale > 1.5f && width < 400 -> 1
        width >= 1280 -> 6
        width >= 1024 -> 5
        width >= 840 -> 4
        width >= 600 || home && site.category == SiteCategory.COMIC && width >= 360 -> 3
        else -> 2
    }
    val gridModifier = Modifier.fillMaxSize()
        .semantics { contentDescription = "内容列表" }
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns),
        if (browseToolbar != null) gridModifier.testTag("discover-feed") else gridModifier,
        state = grid, contentPadding = PaddingValues(start = gridInset, end = gridInset,
            top = if (browseToolbar != null) 0.dp else 8.dp, bottom = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalItemSpacing = 10.dp) {
        item(span = StaggeredGridItemSpan.FullLine, key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (browseToolbar != null) browseToolbar(::refresh)
                else if (hasGalleryStage) {
                    val comic = site.category == SiteCategory.COMIC
                    Column(Modifier.testTag(if (comic) "comic-stage" else "photo-stage")) {
                        if (!adapter.supportsLanguage) {
                            val shortcuts = remember(adapter, request) { adapter.sections.mapNotNull { section ->
                                section.feed?.takeIf { it != request && adapter.indexSection(it) == null }?.let { section.title to it }
                            }.take(2) }
                            if (shortcuts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                ArtworkShortcut("全部")
                                shortcuts.forEach { (title, feed) -> ArtworkShortcut(title) { browse(feed) } }
                            }
                        }
                        if (progress != null) GalleryResumeCard(progress.entry, "继续${site.category.verb}",
                            "第 ${progress.page} / ${progress.pages} ${if (comic) "页" else "张"}") { resumeGallery(progress.entry) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Title("最新${adapter.itemNoun}", Modifier.weight(1f))
                            if (progress == null) TextAction({ play(videos.first()) }) { Text("开始${site.category.verb}") }
                        }
                    }
                } else Title(if (home) "最新${adapter.itemNoun}" else adapter.feedTitle(request))
                if (cached) Copy("当前显示离线列表缓存")
                if (!home && browseToolbar == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        items(videos, key = Video::entryKey, contentType = { "media" }) { video ->
            MediaCard(video, menu = listOf("收藏 / 取消收藏" to { favorite(video) }), quietSleeve = browseToolbar != null) { play(video) }
        }
        if (busy) item(span = StaggeredGridItemSpan.FullLine, key = "loading") { Loading(title = if (loaded) "正在加载下一页" else "正在加载内容") }
        error?.let { message -> item(span = StaggeredGridItemSpan.FullLine, key = "error") { EmptyState(if (loaded) "下一页加载失败" else "内容加载失败", message) { if (state.restartRequired) refresh() else load() } } }
        if (!busy && next <= total && state.emptyPages >= 3) item(span = StaggeredGridItemSpan.FullLine, key = "continue-empty") {
            Action("继续加载") { load() }
        }
        if (loaded && videos.isEmpty() && !busy) item(span = StaggeredGridItemSpan.FullLine) { Copy(if (site.mediaKind == MediaKind.GALLERY) "当前条件下暂无${adapter.itemNoun}，可调整筛选或修改搜索条件" else "暂时没有可显示的视频") }
    }
}

@Composable fun ExploreScreen(app: R34Application, adapter: SiteAdapter, request: FeedRequest, state: FeedState,
    dialogs: ComposeDialogs, play: (Video) -> Unit, favorite: (Video) -> Unit, scroll: (Float, Boolean) -> Unit,
    changeFeed: (FeedRequest) -> Unit, changeLanguage: (GalleryLanguage) -> Unit, browse: (String) -> Unit,
    permissionDenied: () -> Unit = {}) {
    key(state) {
        FeedScreen(app, adapter.site, request, false, state, dialogs, play, favorite, scroll,
            permissionDenied = permissionDenied,
            browseToolbar = { refresh -> ExploreToolbar(adapter, request, dialogs, changeFeed, changeLanguage, browse, refresh) },
            sort = changeFeed)
    }
}

@Composable private fun ExploreToolbar(adapter: SiteAdapter, request: FeedRequest, dialogs: ComposeDialogs,
    changeFeed: (FeedRequest) -> Unit, changeLanguage: (GalleryLanguage) -> Unit, browse: (String) -> Unit,
    refresh: () -> Unit) {
    val sections = remember(adapter) { adapter.sections.filter { it.feed != null || adapter.sectionUrl(it.key) != null } }
    val active = sections.indexOfFirst { it.feed?.copy(language = request.language) == request }.takeIf { it >= 0 }
        ?: sections.indexOfFirst { it.feed?.sort == Sort.NEWEST && it.feed.copy(sort = request.sort, language = request.language) == request }
    val title = sections.getOrNull(active)?.title ?: adapter.feedTitle(request)
    val sorts = adapter.sortsFor(request)
    val default = adapter.homeRequest()
    val selectedLanguage = request.language.takeIf { adapter.supportsLanguage && it != default.language }
    val hasFeedSelection = request.copy(language = default.language) != default
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sections.size > 1) {
                BrowseSelector(title, Modifier.weight(1f, fill = false).semantics { contentDescription = "内容分类，当前 $title" }) {
                    dialogs.choose("内容分类", sections.map { it.title }, active) { index ->
                        val section = sections[index]
                        val feed = section.feed
                        val directory = if (feed == null) section.key else adapter.indexSection(feed)
                        if (directory != null) browse(directory)
                        else if (feed != null) changeFeed(feed.copy(language = request.language))
                    }
                }
            } else Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sorts.size > 1) {
                val selectedSort = sorts.indexOf(request.sort).coerceAtLeast(0)
                val sortTitle = adapter.sortTitle(sorts[selectedSort])
                BrowseSelector(sortTitle, Modifier.weight(1f, fill = false).semantics { contentDescription = "排序方式，当前 $sortTitle" }) {
                    dialogs.choose("排序方式", sorts.map(adapter::sortTitle), selectedSort) {
                        changeFeed(request.copy(sort = sorts[it], page = 1, cursor = ""))
                    }
                }
            }
            if (adapter.supportsLanguage) {
                BrowseSelector("语言", Modifier.semantics { contentDescription = "漫画语言，当前 ${request.language.title}" }) {
                    dialogs.choose("漫画语言", GalleryLanguage.entries.map { it.title }, request.language.ordinal) { index ->
                        changeLanguage(GalleryLanguage.entries[index])
                    }
                }
            }
            TextAction(refresh) { Text("刷新", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (hasFeedSelection || selectedLanguage != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            val summary = buildList {
                if (request.kind != default.kind || request.target != default.target) add(title)
                if (request.sort != default.sort) add(adapter.sortTitle(request.sort))
                if (selectedLanguage != null) add(selectedLanguage.title)
            }.joinToString(" · ")
            Text(summary, Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextAction({
                if (selectedLanguage != null) changeLanguage(default.language)
                changeFeed(default)
            }) { Text("清除筛选", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .55f))
    }
}

@Composable private fun BrowseSelector(title: String, modifier: Modifier, click: () -> Unit) {
    val palette = LocalArtworkPalette.current
    val shape = if (palette == null) RentalShape else ArtworkPill
    TextButton(onClick = click, modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp), shape = shape,
        colors = ButtonDefaults.textButtonColors(contentColor = palette?.ink ?: RentalPaper),
        contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.clip(shape).background(palette?.surface ?: RentalSurface).heightIn(min = 36.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Glyph(R.drawable.ic_chevron_down, null, Modifier.size(13.dp))
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
    else androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LocalUiDensity.current.pagePadding, end = LocalUiDensity.current.pagePadding, top = 8.dp, bottom = 24.dp)) {
        val entries = links.orEmpty()
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Title(when (segment) {
                    "works" -> "选择作品"
                    "artists" -> "作者排行"
                    else -> adapter.sections.firstOrNull { it.key == segment }?.title ?: "浏览索引"
                }, large = true)
                if (segment == "tags" || segment.startsWith("work:")) Copy("订阅内容只在当前站点内汇总")
                if (entries.isEmpty()) Copy("没有找到条目")
            }
        }
        items(entries.size, key = { "${entries[it].slug}:$it" }) { i ->
            val link = entries[i]
            val isTag = segment == "tags" || segment.startsWith("work:")
            val followed = isTag && link.slug in followedSlugs
            Column {
                if (i > 0) HorizontalDivider(color = RaisedColor)
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button) {
                    if (segment == "works") browse("work:${link.slug}") else feed(adapter.linkRequest(link.url)
                        ?: FeedRequest(when { segment == "categories" -> FeedKind.CATEGORY; segment == "models" || segment == "artists" -> FeedKind.MODEL; else -> FeedKind.TAG }, link.slug))
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (link.thumb.isNotBlank()) SiteImage(link.thumb, site, Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (segment == "artists" || segment.startsWith("work:")) "${i + 1}. ${link.label}" else link.label,
                            style = MaterialTheme.typography.titleMedium)
                        if (link.subtitle.isNotBlank()) Copy(link.subtitle)
                    }
                    if (isTag) SubscribeAction(if (followed) "已订阅" else "订阅", followed) {
                        subscribe(Subscription(link.slug, link.label, link.url, site.id))
                        followedSlugs = app.library.subscriptions(site.id).map { it.slug }.toSet()
                    } else CompositionLocalProvider(LocalContentColor provides MutedColor) { Glyph(R.drawable.ic_chevron_right, null) }
                }
            }
        }
    }
}
data class LibraryCard(val video: Video, val menuLabel: String, val remove: () -> Unit, val read: Boolean = false, val progress: Float? = null)
@Composable fun MediaLibrary(cards: List<LibraryCard>, clear: (() -> Unit)?, empty: @Composable () -> Unit, play: (LibraryCard) -> Unit) {
    val width = windowSizeDp().width
    val columns = if (width >= 1280) 6 else if (width >= 1024) 5 else if (width >= 840) 4 else if (width >= 600) 3 else 2
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(LocalUiDensity.current.cardGap), contentPadding = PaddingValues(top = 12.dp), verticalItemSpacing = 8.dp) {
        if (clear != null) item(span = StaggeredGridItemSpan.FullLine) { TextAction(clear) { Text("清空记录") } }
        if (cards.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) { empty() }
        items(cards, key = { "${it.video.site}:${it.video.id}" }) { card -> MediaCard(card.video, menu = listOf(card.menuLabel to card.remove), progress = card.progress) { play(card) } }
    }
}
