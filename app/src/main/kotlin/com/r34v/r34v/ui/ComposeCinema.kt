@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.r34v.r34v.R
import com.r34v.r34v.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable fun BrandWordmark() {
    val palette = LocalArtworkPalette.current ?: artworkPalette(SiteCategory.VIDEO)
    Text(androidx.compose.ui.text.buildAnnotatedString {
        append("R34")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = palette.accent))
        append("V")
        pop()
    }, color = palette.ink, fontSize = if (LocalUiDensity.current.compact) 24.sp else 29.sp,
        fontWeight = FontWeight.Black, letterSpacing = (-1.6).sp)
}

@Composable fun ProfileAction(click: () -> Unit) {
    IconAction(R.drawable.ic_settings, "设置", click = click)
}

@Composable fun CinemaNavigation(selectedTab: String, choose: (String) -> Unit) {
    val compact = LocalUiDensity.current.compact
    val motion = LocalMotionEnabled.current
    val palette = LocalArtworkPalette.current ?: artworkPalette(SiteCategory.VIDEO)
    val animation = if (motion) tween<Color>(330, easing = FastOutSlowInEasing) else snap()
    val shellTop = animateColorAsState(lerp(palette.raised, palette.ink, .08f),
        animation, label = "dockShellTop")
    val shellBase = animateColorAsState(lerp(palette.surface, palette.bottom, .45f),
        animation, label = "dockShellBase")
    val trayTop = animateColorAsState(lerp(palette.surface, palette.bottom, .56f),
        animation, label = "dockTrayTop")
    val trayBase = animateColorAsState(lerp(palette.surface, palette.bottom, .75f),
        animation, label = "dockTrayBase")
    val activeTop = animateColorAsState(lerp(palette.top, palette.accent, .40f),
        animation, label = "dockActiveTop")
    val activeBase = animateColorAsState(lerp(palette.middle, palette.accent, .27f),
        animation, label = "dockActiveBase")
    val tabs = listOf(Triple("siteHome", "首页", R.drawable.ic_dock_home), Triple("explore", "发现", R.drawable.ic_dock_discover),
        Triple("search", "搜索", R.drawable.ic_dock_search), Triple("library", "片库", R.drawable.ic_dock_library), Triple("home", "我的", R.drawable.ic_person))
    val tabHeight = (48 + ((LocalDensity.current.fontScale - 1f).coerceAtLeast(0f) * 20)).dp
    val shape = ArtworkPill
    val reduced = LocalReduceTransparency.current
    val edge = if (reduced) palette.edge else palette.ink.copy(alpha = .16f)
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp)
        .padding(top = 8.dp, bottom = 7.dp).testTag("primary-navigation"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 444.dp).fillMaxWidth()
            .shadow(if (reduced) 0.dp else 3.dp, shape).clip(shape)
            .drawWithCache {
                val shell = Brush.verticalGradient(listOf(shellTop.value, shellBase.value))
                onDrawBehind { drawRect(shell) }
            }.border(1.dp, edge, shape).padding(4.dp)) {
            Box(Modifier.matchParentSize().clip(shape).drawWithCache {
                val tray = Brush.verticalGradient(listOf(trayTop.value, trayBase.value))
                onDrawBehind { drawRect(tray) }
            })
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(tabHeight)) {
                val slot = maxWidth / tabs.size
                val index = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)
                val left = animateDpAsState(slot * index,
                    if (motion) tween(200, easing = FastOutSlowInEasing) else snap(), label = "navigationPosition")
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(left.value.roundToPx(), 0) }.size(slot, tabHeight)
                    .padding(horizontal = 4.dp, vertical = 3.dp).clip(shape)
                    .drawWithCache {
                        val fill = Brush.verticalGradient(listOf(activeTop.value, activeBase.value))
                        onDrawBehind { drawRect(fill) }
                    })
                Row(Modifier.fillMaxSize().selectableGroup()) {
                    tabs.forEach { (key, name, icon) ->
                        val selected = selectedTab == key
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        val ink = if (selected) palette.ink else palette.muted
                        Column(Modifier.weight(1f).fillMaxHeight().clip(shape)
                            .selectable(selected, interactionSource = interaction, indication = LocalIndication.current, role = Role.Tab) { choose(key) }
                            .then(if (focused) Modifier.border(2.dp, ink, shape) else Modifier)
                            .rentalKeyPress(interaction), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                            Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(if (compact) 18.dp else 20.dp), tint = ink)
                            Text(name, color = ink, style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private data class HomeRow(val key: String, val works: List<Video> = emptyList())

@Composable fun CinemaHome(
    videos: List<Video>, features: List<Pair<Video, String>>, popular: List<Video>, rated: List<Video>,
    continuing: List<Watch>, saved: Set<String>, cached: Boolean, busy: Boolean, error: String?,
    hasNext: Boolean, load: () -> Unit, play: (Video) -> Unit, favorite: (Video) -> Unit,
    browse: (FeedRequest) -> Unit, scroll: (Float, Boolean) -> Unit,
    state: FeedState,
    automatic: Boolean,
    loadAutomatically: () -> Unit,
    toggleWaiting: (Video) -> Unit,
    openDetails: (Video) -> Unit,
    onFeatured: (Video) -> Unit,
) {
    val list = state.list
    LaunchedEffect(list) {
        var previous = 0
        snapshotFlow { list.firstVisibleItemIndex * 100000 + list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { value ->
            scroll((value - previous).toFloat(), value == 0); previous = value
        }
    }
    val nearEnd by remember { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } == true
    } }
    val pagePadding = 16.dp
    val latestRows = remember(videos) { videos.chunked(8) }
    val rows = remember(features.isNotEmpty(), cached, popular, rated, latestRows, continuing.isNotEmpty()) {
        buildList {
            add(HomeRow(if (features.isNotEmpty()) "hero" else "home-inset"))
            if (cached) add(HomeRow("cached"))
            if (popular.isNotEmpty()) add(HomeRow("popular", popular.take(10)))
            latestRows.firstOrNull()?.let { add(HomeRow("latest-0", it)) }
            if (rated.isNotEmpty()) add(HomeRow("rated", rated.take(10)))
            if (continuing.isNotEmpty()) add(HomeRow("continue"))
            latestRows.drop(1).forEachIndexed { index, row -> add(HomeRow("latest-${index + 1}", row)) }
        }
    }
    val shelves = remember(state, state.refreshCount) { mutableStateMapOf<String, LazyListState>() }
    val currentRows by rememberUpdatedState(rows)
    val position = state.restorePosition
    LaunchedEffect(position, rows) {
        if (position != null) {
            val targets = rows.withIndex().filter { (_, row) -> row.works.any { it.entryKey == position.work } }
            val target = targets.firstOrNull { it.value.key == position.shelf }
                ?: targets.firstOrNull { it.value.key.startsWith("latest-") } ?: targets.firstOrNull()
            if (target != null) {
                list.scrollToItem(target.index)
                val shelf = snapshotFlow { shelves[target.value.key] }.first { it != null }
                shelf?.scrollToItem(target.value.works.indexOfFirst { it.entryKey == position.work })
            }
            state.finishRestoring(found = target != null)
        }
    }
    LaunchedEffect(state, shelves) {
        snapshotFlow {
            // The list includes items drawn only in its top content padding. Those
            // must not take over the work anchor from the first visible shelf.
            val firstVisible = list.firstVisibleItemIndex
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index >= firstVisible }
            if (state.restorePosition != null || list.isScrollInProgress || visible.isEmpty()) null
            else {
                val atTop = firstVisible == 0 && list.firstVisibleItemScrollOffset == 0
                val kept = !atTop && state.restoredWork != null && visible.any { item ->
                    (item.key as? String)?.let { shelves[it] }?.layoutInfo?.visibleItemsInfo?.any { it.key == state.restoredWork } == true
                }
                val row = if (atTop) null else visible.firstNotNullOfOrNull { item ->
                    currentRows.getOrNull(item.index)?.takeIf { it.key == item.key && it.works.isNotEmpty() }
                }
                val shelf = row?.let { shelves[it.key] }
                if (kept || shelf?.isScrollInProgress == true) null else {
                    val work = row?.works?.getOrNull(shelf?.firstVisibleItemIndex ?: 0)?.entryKey.orEmpty()
                    FeedPosition(work, state.next - 1, row?.key.orEmpty())
                }
            }
        }.collect { settled -> if (settled != null) state.savePosition(settled.takeIf { it.work.isNotEmpty() }) }
    }
    LaunchedEffect(nearEnd, videos.size, busy, error, hasNext, automatic, position) {
        if (position == null && nearEnd && hasNext && automatic && !busy && error == null) loadAutomatically()
    }
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "内容列表" }, state = list,
        contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.key }) { row ->
            when (row.key) {
                "hero" -> Column {
                    Row(Modifier.padding(horizontal = pagePadding), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ArtworkShortcut("精选")
                        if (popular.isNotEmpty()) ArtworkShortcut("人气") { browse(FeedRequest(FeedKind.POPULAR)) }
                        ArtworkShortcut("最新") { browse(FeedRequest()) }
                    }
                    CinemaHero(features, saved, play, toggleWaiting, openDetails, onFeatured)
                }
                "home-inset" -> Spacer(Modifier.height(4.dp))
                "cached" -> Copy("当前显示离线列表缓存", Modifier.padding(horizontal = pagePadding))
                "continue" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CinemaShelfTitle("继续观看", Modifier.padding(horizontal = pagePadding))
                    LazyRow(contentPadding = PaddingValues(horizontal = pagePadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(continuing, key = { "${it.site}:${it.id}" }) { watch -> ContinueCard(watch) { play(watch.video()) } }
                    }
                }
                else -> {
                    val shelf = rememberLazyListState()
                    DisposableEffect(shelves, row.key, shelf) {
                        shelves[row.key] = shelf
                        onDispose { if (shelves[row.key] == shelf) shelves.remove(row.key) }
                    }
                    when (row.key) {
                        "popular" -> PosterShelf("人气榜 Top 10", row.works, true, { browse(FeedRequest(FeedKind.POPULAR)) }, openDetails, favorite, shelf)
                        "latest-0" -> PosterShelf("最新更新", row.works, false, { browse(FeedRequest()) }, openDetails, favorite, shelf)
                        "rated" -> PosterShelf("高分佳作", row.works, false, { browse(FeedRequest(FeedKind.RATED)) }, openDetails, favorite, shelf)
                        else -> PosterRow(row.works, false, openDetails, favorite, shelf)
                    }
                }
            }
        }
        if (busy) item("loading") { Loading(title = "正在加载下一页") }
        if (hasNext && !automatic && !busy && error == null) item("continue-empty") {
            Action("继续加载", Modifier.padding(horizontal = pagePadding)) { load() }
        }
        if (error != null) item("error") { EmptyState("下一页加载失败", error, click = load) }
        if (videos.isEmpty() && !busy && error == null) item("empty") {
            EmptyState("暂时没有可显示的视频", "试试其他分类或查看热门内容", "浏览热门榜") { browse(FeedRequest(FeedKind.POPULAR)) }
        }
        if (!hasNext && videos.isNotEmpty()) item("end") { Text("你已看完本次更新", Modifier.fillMaxWidth().padding(12.dp), color = MutedColor, fontSize = 12.sp, textAlign = TextAlign.Center) }
    }
}

@Composable private fun CinemaShelfTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, color = MaterialTheme.colorScheme.onSurface,
        fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun ShelfHeading(title: String, more: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CinemaShelfTitle(title, Modifier.weight(1f))
        TextAction(more, Modifier.semantics { contentDescription = "查看全部$title" }) {
            Text("查看全部", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                Glyph(R.drawable.ic_chevron_right, null, Modifier.size(12.dp))
            }
        }
    }
}

@Composable private fun PosterShelf(title: String, videos: List<Video>, ranked: Boolean, more: () -> Unit,
    openDetails: (Video) -> Unit, favorite: (Video) -> Unit, state: LazyListState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ShelfHeading(title, more = more)
        PosterRow(videos, ranked, openDetails, favorite, state)
    }
}

@Composable private fun PosterRow(videos: List<Video>, ranked: Boolean, openDetails: (Video) -> Unit, favorite: (Video) -> Unit, state: LazyListState) {
    val gap = 8.dp
    val columns = if (ranked) 3 else 2
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = ((maxWidth - 32.dp - gap * (columns - 1)) / columns).coerceAtMost(if (ranked) 154.dp else 248.dp)
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            itemsIndexed(videos, key = { _, video -> video.entryKey }) { index, video ->
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.width(cardWidth).aspectRatio(if (ranked) .72f else 1.5f)
                    .testTag("poster-${video.site}-${video.id}").clip(shape)
                    .combinedClickable(onClickLabel = "查看 ${video.title} 详情", onLongClickLabel = "收藏或取消收藏 ${video.title}",
                        onClick = { openDetails(video) }, onLongClick = { favorite(video) })
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .35f), shape)) {
                    if (ranked) RankedSleeve(video, index) else LandscapeSleeve(video)
                }
            }
        }
    }
}

@Composable private fun RankedSleeve(video: Video, index: Int) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            .38f to Color.Transparent, 1f to colors.background
        )))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((index + 1).toString().padStart(2, '0'), color = colors.primary, fontSize = 23.sp,
                lineHeight = 24.sp, fontWeight = FontWeight.Black)
            Text(video.title, color = colors.onBackground, fontSize = 13.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun LandscapeSleeve(video: Video) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(1.dp)) {
        SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .35f to Color.Transparent, 1f to colors.background.copy(alpha = .98f)
        )))
        if (video.duration.isNotBlank()) Text(video.duration, Modifier.align(Alignment.TopEnd).padding(5.dp)
            .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color.White, fontSize = 10.sp, lineHeight = 13.sp)
        Column(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
            Text(video.title, color = Color.White, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (video.rating.isNotBlank()) Text(video.rating, color = colors.onSurfaceVariant, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

@Composable private fun ContinueCard(watch: Watch, play: () -> Unit) {
    val site = SiteRegistry.byIdOrDefault(watch.site)
    Column(Modifier.width(170.dp).clip(RoundedCornerShape(7.dp))
        .clickable(onClickLabel = "继续观看 ${watch.title}", onClick = play)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            SiteImage(watch.thumb, site, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(36.dp).background(Color.Black.copy(alpha = .72f), CircleShape),
                contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_play, null, Modifier.size(18.dp))
            }
        }
        LinearProgressIndicator(progress = { if (watch.duration > 0) (watch.position.toFloat() / watch.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline, gapSize = 0.dp, drawStopIndicator = {})
        Column(Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(watch.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp)
            Text("已观看 ${watch.position / 60} 分钟", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable fun CinemaHero(entries: List<Pair<Video, String>>, saved: Set<String>, play: (Video) -> Unit,
    favorite: (Video) -> Unit, openDetails: (Video) -> Unit, onFeatured: (Video) -> Unit) {
    val pager = rememberPagerState { entries.size }
    val scope = rememberCoroutineScope()
    var paused by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val context = LocalContext.current
    val accessibility = context.getSystemService(AccessibilityManager::class.java)
    var touchExploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(owner, accessibility) {
        val observer = LifecycleEventObserver { _, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        owner.lifecycle.addObserver(observer); accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { owner.lifecycle.removeObserver(observer); accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    val motion = LocalMotionEnabled.current
    val palette = LocalArtworkPalette.current ?: artworkPalette(SiteCategory.VIDEO)
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) paused = true }
    LaunchedEffect(entries, pager.settledPage) { entries.getOrNull(pager.settledPage)?.let { onFeatured(it.first) } }
    LaunchedEffect(paused, active, motion, touchExploration, dragging, entries) {
        if (entries.size <= 1 || paused || !active || touchExploration || !motion || dragging) return@LaunchedEffect
        while (true) {
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            delay(6500)
            if (!pager.isScrollInProgress) {
                pager.animateScrollToPage((pager.settledPage + 1) % entries.size, animationSpec = tween(330, easing = FastOutSlowInEasing))
            }
        }
    }

    val window = windowSizeDp()
    val artworkHeight = (window.height * .33f).dp.coerceIn(168.dp, 305.dp)
    Column(Modifier.testTag("home-carousel").padding(horizontal = 16.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().semantics { contentDescription = "首页精选轮播" },
            key = { entries[it].first.entryKey }) { index ->
            val (video, ranking) = entries[index]
            val site = SiteRegistry.byIdOrDefault(video.site)
            Box(Modifier.fillMaxWidth().heightIn(min = artworkHeight).clip(ArtworkShape).background(palette.bottom)) {
                SiteImage(video.thumb, site, Modifier.matchParentSize().testTag("featured-artwork").then(
                    if (motion) Modifier.graphicsLayer {
                        val offset = (pager.currentPage - index + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                        alpha = 1f - kotlin.math.abs(offset) * .34f
                        translationX = size.width * offset * .12f
                        scaleX = 1.06f; scaleY = 1.06f
                    } else Modifier
                ))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    .20f to Color.Transparent, .62f to palette.bottom.copy(alpha = .70f), 1f to palette.bottom)))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 64.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.title, Modifier.semantics { heading() }, color = Color(0xfffff9fc),
                        fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(ranking, video.duration).filter(String::isNotBlank).joinToString(" · "),
                        color = Color(0xfff6e5ee), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val isSaved = video.entryKey in saved
                    Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkAction("播放", Modifier.weight(1f), R.drawable.ic_play) { play(video) }
                        ArtworkAction("详情", Modifier.weight(1f), primary = false) { openDetails(video) }
                        IconButton({ favorite(video) }, Modifier.size(48.dp).semantics { selected = isSaved }) {
                            Box(Modifier.size(41.dp).background(if (isSaved) palette.accent else palette.surface, CircleShape),
                                contentAlignment = Alignment.Center) {
                                CompositionLocalProvider(LocalContentColor provides if (isSaved) palette.onAccent else palette.ink) {
                                    Glyph(if (isSaved) R.drawable.ic_check else R.drawable.ic_bookmark,
                                        if (isSaved) "从我的片单移除 ${video.title}" else "加入我的片单 ${video.title}", Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (entries.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(entries.size) { index ->
                    val selected = pager.settledPage == index
                    val markWidth by animateDpAsState(if (selected) 16.dp else 7.dp,
                        if (motion) tween(180) else snap(), label = "carouselMarker")
                    Box(Modifier.widthIn(max = 48.dp).weight(1f, fill = false).size(48.dp).selectable(selected, role = Role.Tab) {
                        paused = true
                        scope.launch {
                            if (motion) pager.animateScrollToPage(index, animationSpec = tween(330, easing = FastOutSlowInEasing))
                            else pager.scrollToPage(index)
                        }
                    }.semantics { contentDescription = "精选第 ${index + 1} 页" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(markWidth, 4.dp).background(if (selected) palette.accent else palette.muted.copy(alpha = .65f), CircleShape))
                    }
                }
            }
            if (motion && !touchExploration) {
                IconButton({ paused = !paused }, Modifier.size(48.dp)) {
                    CompositionLocalProvider(LocalContentColor provides palette.ink) {
                        Glyph(if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                            if (paused) "继续轮播" else "暂停轮播", Modifier.size(17.dp))
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}
