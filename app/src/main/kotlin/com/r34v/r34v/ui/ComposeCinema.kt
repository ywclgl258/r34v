@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch

/** A translucent surface with a restrained edge highlight; works down to Android 7. */
fun Modifier.cinemaGlass(radius: Int = 16): Modifier = clip(RoundedCornerShape(radius.dp))
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.White.copy(alpha = .035f))))
    .border(0.7.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .25f), Color.White.copy(alpha = .04f))), RoundedCornerShape(radius.dp))

@Composable fun BrandWordmark() {
    Text("R34V", color = BrandColor, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
}

@Composable fun ProfileAction(click: () -> Unit) {
    IconButton(click, Modifier.size(48.dp).semantics { contentDescription = "设置" }) {
        Box(Modifier.size(30.dp).cinemaGlass(7), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_profile, null, Modifier.size(21.dp))
        }
    }
}

@Composable fun CinemaNavigation(selectedTab: String, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xff0b0b0b)).navigationBarsPadding()) {
        listOf(Triple("home", "首页", R.drawable.ic_home), Triple("explore", "发现", R.drawable.ic_explore),
            Triple("search", "搜索", R.drawable.ic_search), Triple("library", "片库", R.drawable.ic_bookmark)).forEach { (key, name, icon) ->
            val selected = selectedTab == key
            Column(Modifier.weight(1f).selectable(selected, role = Role.Tab) { choose(key) }
                .heightIn(min = 60.dp).padding(top = 5.dp, bottom = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(16.dp, 2.dp).clip(CircleShape).background(if (selected) BrandColor else Color.Transparent))
                CompositionLocalProvider(LocalContentColor provides if (selected) Color.White else MutedColor) {
                    Glyph(icon, null, Modifier.size(21.dp))
                    Text(name, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable fun CinemaHome(
    videos: List<Video>, features: List<Pair<Video, String>>, popular: List<Video>, rated: List<Video>,
    continuing: List<Watch>, saved: Set<String>, cached: Boolean, busy: Boolean, error: String?,
    hasNext: Boolean, load: () -> Unit, play: (Video) -> Unit, favorite: (Video) -> Unit,
    browse: (FeedRequest) -> Unit, scroll: (Float, Boolean) -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        var previous = 0
        snapshotFlow { list.firstVisibleItemIndex * 100000 + list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { value ->
            scroll((value - previous).toFloat(), value == 0); previous = value
        }
    }
    val nearEnd by remember { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } == true
    } }
    LaunchedEffect(nearEnd, videos.size, busy, error, hasNext) { if (nearEnd && hasNext && !busy && error == null) load() }
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "内容列表" }, state = list,
        contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (features.isNotEmpty()) item("hero") { CinemaHero(features, saved, play, favorite) }
        if (cached) item("cached") { Copy("当前显示离线列表缓存", Modifier.padding(horizontal = 16.dp)) }
        if (continuing.isNotEmpty()) item("continue") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShelfHeading("继续观看", "从上次停下的地方继续")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(continuing, key = { "${it.site}:${it.id}" }) { watch -> ContinueCard(watch) { play(watch.video()) } }
                }
            }
        }
        if (popular.isNotEmpty()) item("popular") { PosterShelf("人气榜 Top 10", popular.take(10), true, { browse(FeedRequest(FeedKind.POPULAR)) }, play, favorite) }
        if (rated.isNotEmpty()) item("rated") { PosterShelf("高分佳作", rated.take(10), false, { browse(FeedRequest(FeedKind.RATED)) }, play, favorite) }
        item("latest-heading") { ShelfHeading("最新视频", "刚刚上新，发现下一部心动", { browse(FeedRequest()) }) }
        videos.chunked(8).forEachIndexed { index, page ->
            item("latest-$index") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (index > 0) Text("更多新片 · ${index + 1}", Modifier.padding(horizontal = 16.dp), color = MutedColor, fontSize = 12.sp)
                    PosterRow(page, false, play, favorite)
                }
            }
        }
        if (busy) item("loading") { Loading("正在加载下一页") }
        if (error != null) item("error") { EmptyState("下一页加载失败", error, click = load) }
        if (videos.isEmpty() && !busy && error == null) item("empty") {
            EmptyState("暂时没有可显示的视频", "试试其他分类或查看热门内容", "浏览热门榜") { browse(FeedRequest(FeedKind.POPULAR)) }
        }
        if (!hasNext && videos.isNotEmpty()) item("end") { Text("你已看完本次更新", Modifier.fillMaxWidth().padding(12.dp), color = MutedColor, fontSize = 12.sp, textAlign = TextAlign.Center) }
    }
}

@Composable private fun ShelfHeading(title: String, subtitle: String = "", more: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Title(title)
            if (subtitle.isNotBlank()) Text(subtitle, color = MutedColor, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (more != null) IconButton(more, Modifier.size(48.dp).semantics { contentDescription = "查看全部$title" }) {
            Glyph(R.drawable.ic_chevron_right, null, Modifier.size(18.dp))
        }
    }
}

@Composable private fun PosterShelf(title: String, videos: List<Video>, ranked: Boolean, more: () -> Unit,
    play: (Video) -> Unit, favorite: (Video) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShelfHeading(title, more = more)
        PosterRow(videos, ranked, play, favorite)
    }
}

@Composable private fun PosterRow(videos: List<Video>, ranked: Boolean, play: (Video) -> Unit, favorite: (Video) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(videos, key = { _, video -> "${video.site}:${video.id}" }) { index, video ->
            Column(Modifier.width(if (ranked) 140.dp else 120.dp)
                .testTag("poster-${video.site}-${video.id}")
                .combinedClickable(onClickLabel = "播放 ${video.title}", onLongClickLabel = "收藏或取消收藏 ${video.title}",
                    onClick = { play(video) }, onLongClick = { favorite(video) }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(if (ranked) 140f / 180f else 2f / 3f)) {
                    SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.align(Alignment.TopEnd).width(120.dp).fillMaxHeight().clip(RoundedCornerShape(5.dp)))
                    if (ranked) {
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent))))
                        Text("${index + 1}", Modifier.align(Alignment.BottomStart).offset(x = (-2).dp, y = 9.dp), color = Color.White,
                            fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.displayLarge.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 8f)))
                    }
                    if (video.duration.isNotBlank()) Text(video.duration, Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 10.sp, color = Color.White)
                }
                Text(video.title, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (video.rating.isNotBlank()) Text(video.rating, fontSize = 11.sp, color = MatchColor, maxLines = 1)
            }
        }
    }
}

@Composable private fun ContinueCard(watch: Watch, play: () -> Unit) {
    Column(Modifier.width(188.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceColor).clickable(onClickLabel = "继续观看 ${watch.title}", onClick = play)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            SiteImage(watch.thumb, SiteRegistry.byIdOrDefault(watch.site), Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)))
            Box(Modifier.align(Alignment.Center).size(36.dp).border(1.dp, Color.White.copy(alpha = .8f), CircleShape)
                .background(Color.Black.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_play, null, Modifier.size(20.dp)) }
        }
        LinearProgressIndicator(progress = { if (watch.duration > 0) (watch.position.toFloat() / watch.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = BrandColor, trackColor = Color.White.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {})
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(watch.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            Text("已观看 ${watch.position / 60} 分钟", color = MutedColor, fontSize = 11.sp)
        }
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
    LaunchedEffect(paused, active, touchExploration, pager.isScrollInProgress, pager.currentPage, entries) {
        val motion = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(false)
        if (entries.size > 1 && !paused && active && !touchExploration && motion && !pager.isScrollInProgress) {
            delay(6500); pager.animateScrollToPage((pager.currentPage + 1) % entries.size)
        }
    }
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val heroHeight = if (landscape && fontScale <= 1.3f) (configuration.screenHeightDp - 100).dp.coerceAtLeast(280.dp)
        else (configuration.screenHeightDp * .59f).dp.coerceAtLeast(if (fontScale > 1.3f) 540.dp else 420.dp)
    Column(Modifier.testTag("home-carousel")) {
        HorizontalPager(pager, Modifier.fillMaxWidth().semantics { contentDescription = "首页精选轮播" },
            key = { "${entries[it].first.site}:${entries[it].first.id}:$it" }) { index ->
            val (video, ranking) = entries[index]
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .35f), .35f to Color.Transparent, .62f to Color.Black.copy(alpha = .2f), 1f to Color.Black)))
                Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(3.dp, 11.dp).background(BrandColor, CircleShape))
                        Text("今日推荐", fontSize = 11.sp, color = Color.White, letterSpacing = 2.sp)
                    }
                    Text(video.title, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineLarge.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 16f)))
                    Text(listOf(video.type, ranking).filter(String::isNotBlank).joinToString("  •  "), fontSize = 12.sp, color = MutedColor,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (video.rating.isNotBlank()) Text(video.rating, color = MatchColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val isSaved = "${video.site}:${video.id}" in saved
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ play(video) }, Modifier.widthIn(min = 136.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)) {
                            Glyph(R.drawable.ic_play, null, Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text("播放", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton({ favorite(video) }, Modifier.widthIn(min = 136.dp).heightIn(min = 48.dp).cinemaGlass(4)
                            .semantics { contentDescription = if (isSaved) "从我的片单移除 ${video.title}" else "加入我的片单 ${video.title}" },
                            shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xff333333).copy(alpha = .6f), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                            Glyph(if (isSaved) R.drawable.ic_check else R.drawable.ic_add, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(if (isSaved) "已加入片单" else "我的片单")
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                entries.indices.forEach { index ->
                    Box(Modifier.size(48.dp).clickable {
                        paused = true
                        scope.launch { pager.animateScrollToPage(index) }
                    }.semantics { selected = pager.currentPage == index; contentDescription = "精选第 ${index + 1} 页" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (pager.currentPage == index) 16.dp else 5.dp, 3.dp).clip(CircleShape)
                            .background(if (pager.currentPage == index) BrandColor else Color.White.copy(alpha = .35f)))
                    }
                }
            }
            IconAction(if (paused) R.drawable.ic_play else R.drawable.ic_pause, if (paused) "继续轮播" else "暂停轮播") { paused = !paused }
        }
    }
}
