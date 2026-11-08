@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.r34v.r34v.preview

import com.r34v.r34v.ui.windowSizeDp

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R
import kotlinx.coroutines.delay

class GlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(darkColorScheme(primary = PreviewRed, background = PreviewCanvas, surface = PreviewMatte)) {
                Surface(Modifier.fillMaxSize(), color = PreviewCanvas) { GlassPreviewScreen() }
            }
        }
    }
}

private data class PreviewAssets(
    val hero: ImageBitmap,
    val dark: ImageBitmap,
    val glory: ImageBitmap,
    val robots: ImageBitmap,
    val queen: ImageBitmap,
    val wednesday: ImageBitmap,
)

private data class PreviewMovie(val title: String, val image: ImageBitmap)

@Composable
private fun rememberPreviewAssets(): PreviewAssets {
    val context = LocalContext.current
    return remember {
        fun load(name: String) = context.assets.open("preview/$name").use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "Unable to decode $name" }.asImageBitmap()
        }
        PreviewAssets(load("stranger.jpg"), load("dark.jpg"), load("glory.jpg"),
            load("robots.jpg"), load("queen.jpg"), load("wednesday.jpg"))
    }
}

@Composable
private fun GlassPreviewScreen() {
    val assets = rememberPreviewAssets()
    val list = rememberLazyListState()

    val fontScale = LocalDensity.current.fontScale
    val landscape = windowSizeDp().width > windowSizeDp().height
    val dockHeight = if (landscape) 56.dp else 66.dp
    val contentDockInset = if (landscape || fontScale > 1.3f) dockHeight + 24.dp else 0.dp
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 180 } }
    var saved by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var selectedFeed by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf("home") }
    var quality by remember { mutableStateOf("1080p") }
    var speed by remember { mutableFloatStateOf(1f) }
    var progress by remember { mutableFloatStateOf(.29f) }
    var showQuality by remember { mutableStateOf(false) }
    var noticeId by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    fun notify(text: String) { message = text; noticeId++ }
    LaunchedEffect(noticeId) {
        if (message.isNotBlank()) {
            delay(2200)
            message = ""
        }
    }
    val movies = listOf(
        PreviewMovie("怪奇物语", assets.hero), PreviewMovie("黑暗荣耀", assets.glory),
        PreviewMovie("爱，死亡和机器人", assets.robots), PreviewMovie("星期三", assets.wednesday),
        PreviewMovie("后翼弃兵", assets.queen),
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(bottom = contentDockInset),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item("hero") {
                PreviewHero(assets.hero, saved, playing, selectedFeed, windowSizeDp().height, fontScale,
                    onFeed = { selectedFeed = it },
                    onPlay = { playing = !playing; notify(if (playing) "开始播放" else "已暂停") },
                    onSave = { saved = !saved; notify(if (saved) "已加入我的片单" else "已移出我的片单") })
            }
            item("continue") {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    SectionHeading("继续观看", "从上次停下的地方继续")
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        item { ContinueCard("暗黑", assets.dark, .38f) { playing = true; notify("继续观看 暗黑") } }
                        item { ContinueCard("后翼弃兵", assets.queen, .64f) { playing = true; notify("继续观看 后翼弃兵") } }
                        item { ContinueCard("黑暗荣耀", assets.glory, .21f) { playing = true; notify("继续观看 黑暗荣耀") } }
                    }
                }
            }
            item("popular") {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    SectionHeading("人气榜 Top 10", "今天大家都在看")
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(movies, key = { _, movie -> movie.title }) { index, movie ->
                            PosterCard(movie.title, movie.image, index + 1) { playing = true; notify("播放 ${movie.title}") }
                        }
                    }
                }
            }
            item("latest") {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    SectionHeading("最新视频", "刚刚上新，发现下一部心动")
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(movies.reversed(), key = { it.title }) { movie ->
                            PosterCard(movie.title, movie.image) { playing = true; notify("播放 ${movie.title}") }
                        }
                    }
                }
            }
            item("end") {
                Text("你已看完本次更新", Modifier.fillMaxWidth().padding(vertical = 12.dp), color = PreviewMuted,
                    fontSize = 10.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(112.dp))
            }
        }
        PreviewTopBar(scrolled, assets.hero,
            onSearch = { selectedTab = "search"; notify("搜索") },
            onSettings = { showQuality = true })
        AnimatedVisibility(playing, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 88.dp),
            enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
            PlaybackPanel(assets.hero, quality, speed, progress,
                onProgress = { progress = it },
                onPause = { playing = false; notify("已暂停") },
                onQuality = { showQuality = true },
                onSpeed = {
                    speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
                    notify("播放速度 ${speed}×")
                })
        }
        AnimatedVisibility(message.isNotBlank(), Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = if (playing) 202.dp else 94.dp),
            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut()) {
            GlassPanel(Modifier.widthIn(max = 340.dp).heightIn(min = 42.dp), assets.hero, radius = 16.dp, elevated = false) {
                Text(message, Modifier.align(Alignment.Center).padding(horizontal = 18.dp, vertical = 11.dp),
                    color = Color.White, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
            }
        }
        FloatingDock(
            items = listOf(
                DockItem("home", "首页", R.drawable.ic_home), DockItem("explore", "发现", R.drawable.ic_explore),
                DockItem("search", "搜索", R.drawable.ic_search), DockItem("library", "片库", R.drawable.ic_bookmark),
            ),
            selected = selectedTab,
            bitmap = assets.hero,
            onSelect = { selectedTab = it; notify(when (it) { "home" -> "首页"; "explore" -> "发现"; "search" -> "搜索"; else -> "片库" }) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().widthIn(max = 620.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            height = dockHeight,
        )
    }
    if (showQuality) QualitySheet(assets.hero, quality, { showQuality = false }) { quality = it; showQuality = false; notify("清晰度 $it") }
}

@Composable
private fun PreviewTopBar(scrolled: Boolean, bitmap: ImageBitmap, onSearch: () -> Unit, onSettings: () -> Unit) {
    val margin by animateDpAsState(if (scrolled) 12.dp else 0.dp, label = "topMargin")
    val alpha by animateFloatAsState(if (scrolled) 1f else 0f, label = "topGlass")
    Box(Modifier.fillMaxWidth().background(if (scrolled) PreviewCanvas else Color.Transparent)
        .statusBarsPadding().padding(horizontal = margin)) {
        if (alpha > 0f) GlassPanel(Modifier.fillMaxWidth().height(58.dp).graphicsLayer { this.alpha = alpha }, bitmap, radius = 19.dp) {}
        Row(Modifier.fillMaxWidth().height(58.dp).then(if (scrolled) Modifier else Modifier.background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .62f), Color.Transparent)))).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("R34V", color = PreviewRed, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            Spacer(Modifier.width(8.dp))
            Text("Rule34Video", color = PreviewMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PreviewIcon(R.drawable.ic_chevron_down, description = null, modifier = Modifier.size(11.dp))
            Spacer(Modifier.weight(1f))
            PreviewIconButton(R.drawable.ic_search, "搜索", onSearch)
            PreviewIconButton(R.drawable.ic_profile, "播放设置", onSettings)
        }
    }
}

@Composable
private fun PreviewHero(
    bitmap: ImageBitmap,
    saved: Boolean,
    playing: Boolean,
    selectedFeed: Int,
    screenHeight: Int,
    fontScale: Float,
    onFeed: (Int) -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    val height = if (fontScale > 1.3f) 640.dp else (screenHeight * .58f).dp
    Box(Modifier.fillMaxWidth().height(height)) {
        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .28f), .34f to Color.Transparent,
            .62f to Color.Black.copy(alpha = .38f), 1f to PreviewCanvas)))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 62.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("为你推荐", "热门榜", "分类").forEachIndexed { index, label ->
                GlassChip(label, selectedFeed == index) { onFeed(index) }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(3.dp, 10.dp).background(PreviewRed, CircleShape))
                Text("今日推荐", color = Color.White, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            Text("怪奇物语", color = Color.White, fontSize = 26.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (fontScale > 1.3f) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("悬疑 · 科幻 · 剧情", color = PreviewMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("热门榜 · 第 1 位", color = PreviewMuted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            } else Text("悬疑 · 科幻 · 剧情   /   热门榜 · 第 1 位", color = PreviewMuted,
                fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            GlassPanel(Modifier.padding(top = 4.dp).widthIn(max = 350.dp), bitmap, radius = 22.dp) {
                FlowRow(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhysicalButton(if (playing) "暂停" else "播放", if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        onPlay, Modifier.widthIn(min = 128.dp), primary = true)
                    PhysicalButton(if (saved) "已加入片单" else "我的片单", if (saved) R.drawable.ic_check else R.drawable.ic_add,
                        onSave, Modifier.widthIn(min = 136.dp), selected = saved)
                }
            }
        }
    }
}

@Composable
private fun PlaybackPanel(
    bitmap: ImageBitmap,
    quality: String,
    speed: Float,
    progress: Float,
    onProgress: (Float) -> Unit,
    onPause: () -> Unit,
    onQuality: () -> Unit,
    onSpeed: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth().heightIn(min = 94.dp), bitmap, radius = 22.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .13f)).clickable(onClick = onPause),
                    contentAlignment = Alignment.Center) {
                    PreviewIcon(R.drawable.ic_pause, description = "暂停", modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("正在播放", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("怪奇物语 · 第 1 集", color = PreviewMuted, fontSize = 10.sp)
                }
                Text(quality, Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onQuality)
                    .padding(horizontal = 9.dp, vertical = 14.dp), color = Color.White, fontSize = 11.sp)
                Text("${speed}×", Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onSpeed)
                    .padding(horizontal = 9.dp, vertical = 14.dp), color = Color.White, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("12:08", color = PreviewMuted, fontSize = 9.sp)
                Slider(progress, onProgress, Modifier.weight(1f).padding(horizontal = 7.dp),
                    thumb = { Box(Modifier.size(12.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)) },
                    track = { state ->
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .17f))) {
                            Box(Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).height(3.dp).background(PreviewRed, CircleShape))
                        }
                    })
                Text("42:10", color = PreviewMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun QualitySheet(bitmap: ImageBitmap, selected: String, dismiss: () -> Unit, choose: (String) -> Unit) {
    ModalBottomSheet(dismiss, containerColor = Color.Transparent, scrimColor = Color.Black.copy(alpha = .68f), dragHandle = null) {
        GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 10.dp), bitmap, radius = 28.dp, elevated = false) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).background(Color.White.copy(alpha = .3f), CircleShape))
                Text("播放设置", Modifier.padding(top = 16.dp, bottom = 4.dp), color = Color.White,
                    fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("默认清晰度", color = PreviewMuted, fontSize = 11.sp)
                listOf("480p", "720p", "1080p", "2160p").forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (value == selected) Color.White.copy(alpha = .09f) else Color.Transparent)
                        .clickable { choose(value) }.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(value, Modifier.weight(1f), color = Color.White, fontSize = 13.sp)
                        if (value == selected) PreviewIcon(R.drawable.ic_check, description = "已选择", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
