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

@Composable fun Modifier.cinemaGlass(radius: Int = 16): Modifier = liquidSurface(radius)

@Composable fun BrandWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("R34V", color = RentalPaper, fontSize = if (LocalUiDensity.current.compact) 24.sp else 29.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontWeight = FontWeight.Black, letterSpacing = (-1.3).sp)
        RentalStripes(Modifier.size(24.dp, 16.dp))
    }
}

@Composable fun ProfileAction(click: () -> Unit) {
    IconAction(R.drawable.ic_settings, "设置", click = click)
}

@Composable fun CinemaNavigation(selectedTab: String, choose: (String) -> Unit) {
    val landscape = windowSizeDp().width > windowSizeDp().height
    val compact = LocalUiDensity.current.compact
    val motion = LocalMotionEnabled.current
    val tabs = listOf(Triple("siteHome", "首页", R.drawable.ic_home), Triple("explore", "发现", R.drawable.ic_explore),
        Triple("search", "搜索", R.drawable.ic_search), Triple("library", "片库", R.drawable.ic_bookmark), Triple("home", "我的", R.drawable.ic_person))
    val tabHeight = ((if (compact || landscape) 54 else 66) + ((LocalDensity.current.fontScale - 1f).coerceAtLeast(0f) * 20)).dp
    Column(Modifier.fillMaxWidth().background(RentalInk).navigationBarsPadding().testTag("primary-navigation"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = RentalEdge.copy(alpha = .55f))
        BoxWithConstraints(Modifier.widthIn(max = 620.dp).fillMaxWidth().height(tabHeight)) {
            val slot = maxWidth / tabs.size
            val index = tabs.indexOfFirst { it.first == selectedTab }
            if (index >= 0) {
                val markWidth = 26.dp
                val left = animateDpAsState(slot * index + (slot - markWidth) / 2,
                    if (motion) tween(210, easing = FastOutSlowInEasing) else snap(), label = "navigationPosition")
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(left.value.roundToPx(), 0) }
                    .size(markWidth, 3.dp).background(RentalRed, RentalShape))
            }
            Row(Modifier.fillMaxSize().selectableGroup()) {
                tabs.forEach { (key, name, icon) ->
                    val selected = selectedTab == key
                    val ink by animateColorAsState(if (selected) RentalPaper else RentalMuted,
                        if (motion) tween(140) else snap(), label = "tabInk")
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab) { choose(key) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(if (compact) 18.dp else 21.dp),
                            tint = if (selected) RentalRed else ink)
                        Text(name, color = ink, style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
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
            val visible = list.layoutInfo.visibleItemsInfo
            if (state.restorePosition != null || list.isScrollInProgress || visible.isEmpty()) null
            else {
                val atTop = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
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
    LazyColumn(Modifier.fillMaxSize().background(RentalInk).semantics { contentDescription = "内容列表" }, state = list,
        contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.key }) { row ->
            when (row.key) {
                "hero" -> CinemaHero(features, saved, play, toggleWaiting)
                "home-inset" -> Spacer(Modifier.height(LocalSourceTopInset.current))
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
                        "popular" -> PosterShelf("人气榜 Top 10", row.works, true, { browse(FeedRequest(FeedKind.POPULAR)) }, play, favorite, shelf)
                        "latest-0" -> PosterShelf("最新更新", row.works, false, { browse(FeedRequest()) }, play, favorite, shelf)
                        "rated" -> PosterShelf("高分佳作", row.works, false, { browse(FeedRequest(FeedKind.RATED)) }, play, favorite, shelf)
                        else -> PosterRow(row.works, false, play, favorite, shelf)
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.size(4.dp, 16.dp).background(RentalRed))
        Text(title, Modifier.semantics { heading() }, color = RentalPaper, fontSize = 19.sp, lineHeight = 23.sp,
            fontWeight = FontWeight.Black)
    }
}

@Composable private fun ShelfHeading(title: String, more: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CinemaShelfTitle(title, Modifier.weight(1f))
        TextAction(more, Modifier.semantics { contentDescription = "查看全部$title" }) {
            Text("查看全部", color = RentalMuted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            CompositionLocalProvider(LocalContentColor provides RentalMuted) {
                Glyph(R.drawable.ic_chevron_right, null, Modifier.size(12.dp))
            }
        }
    }
}

@Composable private fun PosterShelf(title: String, videos: List<Video>, ranked: Boolean, more: () -> Unit,
    play: (Video) -> Unit, favorite: (Video) -> Unit, state: LazyListState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ShelfHeading(title, more = more)
        PosterRow(videos, ranked, play, favorite, state)
    }
}

@Composable private fun PosterRow(videos: List<Video>, ranked: Boolean, play: (Video) -> Unit, favorite: (Video) -> Unit, state: LazyListState) {
    val gap = 8.dp
    val columns = if (ranked) 3 else 2
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = ((maxWidth - 32.dp - gap * (columns - 1)) / columns).coerceAtMost(if (ranked) 154.dp else 248.dp)
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            itemsIndexed(videos, key = { _, video -> video.entryKey }) { index, video ->
                val shape = RoundedCornerShape(if (ranked) 3.dp else 4.dp)
                Box(Modifier.width(cardWidth).aspectRatio(if (ranked) .72f else 1.5f)
                    .testTag("poster-${video.site}-${video.id}").clip(shape)
                    .combinedClickable(onClickLabel = "播放 ${video.title}", onLongClickLabel = "收藏或取消收藏 ${video.title}",
                        onClick = { play(video) }, onLongClick = { favorite(video) })
                    .border(1.dp, RentalEdge.copy(alpha = .4f), shape)) {
                    if (ranked) RankedSleeve(video, index) else LandscapeSleeve(video)
                }
            }
        }
    }
}

@Composable private fun RankedSleeve(video: Video, index: Int) {
    Box(Modifier.fillMaxSize()) {
        SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            .38f to Color.Transparent, 1f to RentalInk
        )))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((index + 1).toString().padStart(2, '0'), color = RentalPaper, fontSize = 23.sp,
                lineHeight = 24.sp, fontWeight = FontWeight.Black)
            Text(video.title, color = RentalPaper, fontSize = 13.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun LandscapeSleeve(video: Video) {
    Box(Modifier.fillMaxSize().padding(1.dp)) {
        SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .35f to Color.Transparent, 1f to RentalInk.copy(alpha = .98f)
        )))
        if (video.duration.isNotBlank()) Text(video.duration, Modifier.align(Alignment.TopEnd).padding(5.dp)
            .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color.White, fontSize = 10.sp, lineHeight = 13.sp)
        Column(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
            Text(video.title, color = Color.White, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (video.rating.isNotBlank()) Text(video.rating, color = RentalPaper, fontSize = 10.sp, lineHeight = 13.sp)
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
            modifier = Modifier.fillMaxWidth().height(2.dp), color = BrandColor, trackColor = Color.White.copy(alpha = .13f), gapSize = 0.dp, drawStopIndicator = {})
        Column(Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(watch.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp)
            Text("已观看 ${watch.position / 60} 分钟", color = MutedColor, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable private fun HeroAction(text: String, icon: Int, primary: Boolean, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val light = primary && !focused
    Button(click, modifier.minimumInteractiveComponentSize().heightIn(min = 48.dp).rentalKeyPress(interaction).semantics { if (!primary) this.selected = selected },
        shape = RoundedCornerShape(4.dp), interactionSource = interaction, elevation = null,
        border = if (primary) null else BorderStroke(1.dp, if (selected) RentalRed else RentalEdge),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) ControlFocusColor else if (light) RentalPaper else if (selected) RentalRed else Color.Transparent,
            contentColor = if (light || selected && !focused) RentalInk else RentalPaper)) {
        Glyph(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable fun CinemaHero(entries: List<Pair<Video, String>>, saved: Set<String>, play: (Video) -> Unit, favorite: (Video) -> Unit) {
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
    LaunchedEffect(paused, active, motion, touchExploration, pager.isScrollInProgress, pager.currentPage, entries) {
        if (entries.size > 1 && !paused && active && !touchExploration && motion && !pager.isScrollInProgress) {
            delay(6500); pager.animateScrollToPage((pager.currentPage + 1) % entries.size)
        }
    }

    val window = windowSizeDp()
    val artworkHeight = (window.height * .19f).dp.coerceIn(112.dp, 170.dp)
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.testTag("home-carousel").padding(start = 16.dp, end = 16.dp, top = LocalSourceTopInset.current + 10.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().semantics { contentDescription = "首页精选轮播" },
            key = { entries[it].first.entryKey }) { index ->
            val (video, ranking) = entries[index]
            val site = SiteRegistry.byIdOrDefault(video.site)
            Column(Modifier.fillMaxWidth().clip(shape).background(RentalSurface).border(1.dp, RentalEdge.copy(alpha = .55f), shape)) {
                SiteImage(video.thumb, site, Modifier.fillMaxWidth().height(artworkHeight).testTag("featured-artwork"))
                Column(Modifier.fillMaxWidth().rentalStock().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(listOf(ranking, video.duration).filter(String::isNotBlank).joinToString(" · "),
                            Modifier.weight(1f), color = RentalMuted, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RentalStripes(Modifier.size(26.dp, 10.dp))
                    }
                    Text(video.title, Modifier.semantics { heading() }, color = RentalPaper,
                        fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val isSaved = "${video.site}:${video.id}" in saved
                    Row(Modifier.widthIn(max = 290.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeroAction("播放", R.drawable.ic_play, true, false, Modifier.weight(1f)) { play(video) }
                        HeroAction(if (isSaved) "已加入" else "稍后看",
                            if (isSaved) R.drawable.ic_check else R.drawable.ic_bookmark, false, isSaved,
                            Modifier.weight(1f).semantics {
                                contentDescription = if (isSaved) "从我的片单移除 ${video.title}" else "加入我的片单 ${video.title}"
                            }) { favorite(video) }
                    }
                }
            }
        }
        if (entries.size > 1) Row(Modifier.align(Alignment.TopEnd).padding(top = artworkHeight - 48.dp).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, RentalInk.copy(alpha = .84f)))),
            verticalAlignment = Alignment.CenterVertically) {
            val markers = rememberLazyListState()
            LaunchedEffect(pager.currentPage) {
                if (markers.layoutInfo.visibleItemsInfo.none { it.index == pager.currentPage }) {
                    markers.scrollToItem(pager.currentPage)
                }
            }
            LazyRow(Modifier.weight(1f), state = markers) {
                items(entries.size) { index ->
                    val selected = pager.currentPage == index
                    val markWidth by animateDpAsState(if (selected) 16.dp else 7.dp,
                        if (motion) tween(180) else snap(), label = "carouselMarker")
                    Box(Modifier.size(48.dp).selectable(selected, role = Role.Tab) {
                        paused = true
                        scope.launch { if (motion) pager.animateScrollToPage(index) else pager.scrollToPage(index) }
                    }.semantics { contentDescription = "精选第 ${index + 1} 页" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(markWidth, 3.dp).background(if (selected) RentalRed else RentalPaper))
                    }
                }
            }
            IconButton({ paused = !paused }, Modifier.size(48.dp)) {
                CompositionLocalProvider(LocalContentColor provides RentalPaper) {
                    Glyph(if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                        if (paused) "继续轮播" else "暂停轮播", Modifier.size(17.dp))
                }
            }
        }
    }
}
