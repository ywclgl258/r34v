@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import android.provider.Settings
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

@Composable fun Modifier.cinemaGlass(radius: Int = 16): Modifier = liquidSurface(radius)

@Composable fun BrandWordmark() {
    Text("R34V", color = BrandColor, fontSize = if (LocalUiDensity.current.compact) 18.sp else 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
}

@Composable fun ProfileAction(click: () -> Unit) {
    val compact = LocalUiDensity.current.compact
    IconButton(click, Modifier.minimumInteractiveComponentSize().size(if (compact) 40.dp else 48.dp).semantics { contentDescription = "设置" }) {
        Box(Modifier.size(if (compact) 30.dp else 34.dp).cinemaGlass(17), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_profile, null, Modifier.size(if (compact) 17.dp else 19.dp))
        }
    }
}

@Composable fun CinemaNavigation(selectedTab: String, choose: (String) -> Unit) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val compact = LocalUiDensity.current.compact
    val tabs = listOf(Triple("home", "首页", R.drawable.ic_home), Triple("explore", "发现", R.drawable.ic_explore),
        Triple("search", "搜索", R.drawable.ic_search), Triple("library", "片库", R.drawable.ic_bookmark))
    val selectedIndex by animateFloatAsState(tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0).toFloat(), tween(220), label = "navigationIndicator")
    val tabHeight = ((if (compact) 48 else if (landscape) 52 else 60) + ((LocalDensity.current.fontScale - 1f).coerceAtLeast(0f) * 20)).dp
    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 10.dp).widthIn(max = if (compact) 520.dp else 620.dp).fillMaxWidth()
            .liquidSurface(34).padding(if (compact) 5.dp else 6.dp)) {
            val tabWidth = maxWidth / 4
            Box(Modifier.offset(x = tabWidth * selectedIndex).width(tabWidth).height(tabHeight).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = .15f)))
            Row(Modifier.fillMaxWidth().height(tabHeight)) {
            tabs.forEach { (key, name, icon) ->
                val selected = selectedTab == key
                val content by animateColorAsState(if (selected) Color.White else MutedColor, label = "tabContent")
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                    .selectable(selected, role = Role.Tab) { choose(key) }) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
                        CompositionLocalProvider(LocalContentColor provides content) {
                            Glyph(icon, null, Modifier.size(if (compact) 19.dp else 22.dp))
                            Text(name, color = content, fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 14.sp else 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
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
    list: LazyListState,
    automatic: Boolean,
    loadAutomatically: () -> Unit,
    toggleWaiting: (Video) -> Unit,
) {
    LaunchedEffect(list) {
        var previous = 0
        snapshotFlow { list.firstVisibleItemIndex * 100000 + list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { value ->
            scroll((value - previous).toFloat(), value == 0); previous = value
        }
    }
    val nearEnd by remember { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } == true
    } }
    LaunchedEffect(nearEnd, videos.size, busy, error, hasNext, automatic) { if (nearEnd && hasNext && automatic && !busy && error == null) loadAutomatically() }
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "内容列表" }, state = list,
        contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(LocalUiDensity.current.sectionGap)) {
        if (continuing.isNotEmpty()) item("resume-hero") {
            val watch = continuing.first()
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = LocalSourceTopInset.current + 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Title("继续观看", large = true)
                MediaResumeHero(watch.video(), "${watch.position / 60} 分钟处继续", "继续观看", play)
            }
        } else if (features.isNotEmpty()) item("hero") { CinemaHero(features, saved, play, toggleWaiting) }
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
        if (hasNext && !automatic && !busy && error == null) item("continue-empty") { Action("继续加载") { load() } }
        if (error != null) item("error") { EmptyState("下一页加载失败", error, click = load) }
        if (videos.isEmpty() && !busy && error == null) item("empty") {
            EmptyState("暂时没有可显示的视频", "试试其他分类或查看热门内容", "浏览热门榜") { browse(FeedRequest(FeedKind.POPULAR)) }
        }
        if (!hasNext && videos.isNotEmpty()) item("end") { Text("你已看完本次更新", Modifier.fillMaxWidth().padding(12.dp), color = MutedColor, fontSize = 12.sp, textAlign = TextAlign.Center) }
    }
}

@Composable private fun ShelfHeading(title: String, subtitle: String = "", more: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Title(title)
            if (subtitle.isNotBlank()) Text(subtitle, color = MutedColor, fontSize = 11.sp, lineHeight = 15.sp)
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
    val compact = LocalUiDensity.current.compact
    LazyRow(contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
        itemsIndexed(videos, key = { _, video -> "${video.site}:${video.id}" }) { index, video ->
            val site = SiteRegistry.byIdOrDefault(video.site)
            Column(Modifier.width(if (compact) 96.dp else 110.dp)
                .testTag("poster-${video.site}-${video.id}")
                .combinedClickable(onClickLabel = "播放 ${video.title}", onLongClickLabel = "收藏或取消收藏 ${video.title}",
                    onClick = { play(video) }, onLongClick = { favorite(video) }), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).shadow(if (compact) 6.dp else 10.dp, RoundedCornerShape(if (compact) 10.dp else 14.dp), clip = false)
                    .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))) {
                    SiteImage(video.thumb, site, Modifier.fillMaxSize())
                    if (ranked) GlassSurface(modifier = Modifier.align(Alignment.TopStart).padding(if (compact) 5.dp else 7.dp).size(if (compact) 26.dp else 30.dp), radius = 10, opaque = true) {
                        Text("${index + 1}", Modifier.align(Alignment.Center), color = Color.White, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (video.duration.isNotBlank()) Text(video.duration, Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 10.sp, color = Color.White)
                }
                Text(video.title, fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 16.sp else 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (video.rating.isNotBlank()) Text(video.rating, fontSize = if (compact) 10.sp else 11.sp, color = MatchColor, maxLines = 1)
            }
        }
    }
}

@Composable private fun ContinueCard(watch: Watch, play: () -> Unit) {
    val site = SiteRegistry.byIdOrDefault(watch.site)
    Column(Modifier.width(184.dp).shadow(12.dp, RoundedCornerShape(17.dp), clip = false).clip(RoundedCornerShape(17.dp))
        .background(SurfaceColor)
        .clickable(onClickLabel = "继续观看 ${watch.title}", onClick = play)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            SiteImage(watch.thumb, site, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f)))
            GlassSurface(modifier = Modifier.align(Alignment.Center).size(38.dp), radius = 19, opaque = true) {
                Glyph(R.drawable.ic_play, null, Modifier.align(Alignment.Center).size(18.dp))
            }
        }
        LinearProgressIndicator(progress = { if (watch.duration > 0) (watch.position.toFloat() / watch.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = BrandColor, trackColor = Color.White.copy(alpha = .13f), gapSize = 0.dp, drawStopIndicator = {})
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(watch.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp)
            Text("已观看 ${watch.position / 60} 分钟", color = MutedColor, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable private fun HeroAction(text: String, icon: Int, primary: Boolean, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val light = primary && !focused
    val compact = LocalUiDensity.current.compact
    Button(click, modifier.minimumInteractiveComponentSize().heightIn(min = LocalUiDensity.current.controlHeight).semantics { this.selected = selected },
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp), interactionSource = interaction, elevation = null,
        contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 9.dp else 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (focused) ControlFocusColor else if (light) SelectedControlColor else if (selected) ActiveControlColor else ControlColor,
            contentColor = if (light && !focused) Color.Black else Color.White)) {
        Glyph(icon, null, Modifier.size(if (compact) 16.dp else 18.dp)); Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        Text(text, fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (compact) 17.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
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
    val compact = LocalUiDensity.current.compact
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val heroHeight = if (landscape && fontScale <= 1.3f) (configuration.screenHeightDp - if (compact) 156 else 100).dp.coerceAtLeast(280.dp)
        else (configuration.screenHeightDp * .59f).dp.coerceAtLeast(if (fontScale > 1.3f) 620.dp else 420.dp)
    Column(Modifier.testTag("home-carousel")) {
        HorizontalPager(pager, Modifier.fillMaxWidth().semantics { contentDescription = "首页精选轮播" },
            key = { "${entries[it].first.site}:${entries[it].first.id}:$it" }) { index ->
            val (video, ranking) = entries[index]
            val site = SiteRegistry.byIdOrDefault(video.site)
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                SiteImage(video.thumb, site, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .35f), .35f to Color.Transparent, .62f to Color.Black.copy(alpha = .38f), 1f to Color.Black)))
                Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(3.dp, 10.dp).background(BrandColor, CircleShape))
                        Text("今日推荐", fontSize = 10.sp, color = Color.White, letterSpacing = 2.sp)
                    }
                    Text(video.title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineLarge.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 16f)))
                    Text(listOf(video.type, ranking).filter(String::isNotBlank).joinToString("  •  "), fontSize = 12.sp, lineHeight = 18.sp, color = MutedColor,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (video.rating.isNotBlank()) Text(video.rating, color = MatchColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val isSaved = "${video.site}:${video.id}" in saved
                    Box(Modifier.padding(top = 4.dp).widthIn(max = 350.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HeroAction("播放", R.drawable.ic_play, true, false, Modifier.widthIn(min = 128.dp)) { play(video) }
                            HeroAction(if (isSaved) "已加入片单" else "我的片单", if (isSaved) R.drawable.ic_check else R.drawable.ic_add, false, isSaved,
                                Modifier.widthIn(min = 136.dp).semantics { contentDescription = if (isSaved) "从我的片单移除 ${video.title}" else "加入我的片单 ${video.title}" }) { favorite(video) }
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
