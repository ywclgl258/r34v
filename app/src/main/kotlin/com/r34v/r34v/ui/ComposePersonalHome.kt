@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r34v.r34v.R34Application
import com.r34v.r34v.R
import com.r34v.r34v.data.*
import kotlinx.coroutines.*

@Composable fun rememberPersonalCatalog(app: R34Application, revision: Int, resumed: Boolean): List<CatalogEntry>? {
    val personal by app.library.personal.changes.collectAsState()
    val local by app.localLibrary.changes.collectAsState()
    val value by produceState<List<CatalogEntry>?>(null, app, revision, personal, local, resumed) {
        val scanned = app.index.scan(app.playback::cached)
        do { value = personalCatalog(app, scanned); if (resumed) delay(3000) } while (resumed && currentCoroutineContext().isActive)
    }
    return value
}

@Composable fun PersonalHomeScreen(app: R34Application, catalog: List<CatalogEntry>?, play: (Video) -> Unit,
    waiting: () -> Unit, updates: () -> Unit, search: (SavedSearch?) -> Unit, discover: () -> Unit,
    channel: (DiscoveryChannel) -> Unit = {}, manageChannels: () -> Unit = {}) {
    val personal by app.library.personal.changes.collectAsState()
    val snapshot = remember(personal) { app.library.personal.snapshot() }
    var categoryIndex by rememberSaveable { mutableIntStateOf(0) }
    val category = SiteCategory.entries.getOrNull(categoryIndex - 1)
    fun matches(video: Video) = category == null || SiteRegistry.byIdOrDefault(video.site).category == category
    val later = snapshot.shelves.firstOrNull { it.id == PersonalLibrary.WATCH_LATER }?.entries.orEmpty().filter { !it.completed && matches(it.video) }
    val continuing = catalog.orEmpty().filter { it.started && !it.completed && matches(it.video) }
    val queued = later.map { item -> catalog?.firstOrNull { it.video.entryKey == item.video.entryKey } ?: CatalogEntry(item.video) }
    val hero = continuing.firstOrNull() ?: queued.firstOrNull { !it.completed }
    val unread = snapshot.inboxes.values.sumOf { box -> box.entries.count { !it.read } } +
        snapshot.searchUpdates.values.sumOf { box -> box.entries.count { !it.read } }
    val padding = 14.dp
    val largeText = LocalConfiguration.current.fontScale > 1.3f
    LazyColumn(Modifier.fillMaxSize().background(RentalInk).testTag("personal-home"),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("types") { ContentTabs(listOf("全部") + SiteCategory.entries.map { it.title }, categoryIndex, Modifier.padding(horizontal = padding)) { categoryIndex = it } }
        if (catalog == null) {
            item("loading") { Loading(title = "正在读取片库") }
            return@LazyColumn
        }
        item("feature") {
            if (hero != null) PersonalFeature(hero, play)
            else PersonalHomeEmpty(category, discover) { categoryIndex = 0 }
        }
        item("shortcuts") {
            Column(Modifier.padding(horizontal = padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (largeText) {
                    NavigationRow("稍后看 · ${later.size}", R.drawable.ic_bookmark, click = waiting)
                    NavigationRow("订阅更新 · $unread", R.drawable.ic_updates, click = updates)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NavigationRow("稍后看 · ${later.size}", R.drawable.ic_bookmark, Modifier.weight(1f), waiting)
                    NavigationRow("订阅更新 · $unread", R.drawable.ic_updates, Modifier.weight(1f), updates)
                }
            }
        }
        if (continuing.size > 1) item("continue") { PersonalRail("继续观看与阅读", continuing.drop(1).take(12), play = play) }
        if (queued.isNotEmpty()) item("later") { PersonalRail("我的稍后看", queued.take(12), "查看片单", waiting, play) }
        if (snapshot.savedSearches.isNotEmpty()) item("searches") {
            Column(Modifier.padding(horizontal = padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Title("常用搜索")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { snapshot.savedSearches.take(12).forEach { saved -> Choice(saved.name) { search(saved) } }; Choice("去搜索") { search(null) } }
            }
        }
        if (snapshot.channels.isNotEmpty()) item("channels") {
            Column(Modifier.padding(horizontal = padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Title("我的频道", Modifier.weight(1f)); TextAction(manageChannels) { Text("管理频道") } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.channels.filterNot { it.hidden }.sortedBy { it.order }.take(12).forEach { saved -> Choice(saved.name) { channel(saved) } }
                }
            }
        }
        if (hero != null || category != null) item("source") {
            NavigationRow("浏览当前来源的最新内容", R.drawable.ic_explore, Modifier.padding(horizontal = padding), discover)
        }
    }
}

@Composable private fun PersonalHomeEmpty(category: SiteCategory?, discover: () -> Unit, clearCategory: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(shape)
        .background(RentalSurface).rentalStock().border(1.dp, RentalEdge, shape).padding(3.dp)) {
        Row(Modifier.fillMaxWidth().background(RentalPaper).rentalStock().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (category == null) "发现下一部" else "还没有${category.title}内容",
                Modifier.weight(1f).semantics { heading() }, color = RentalInk,
                fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
            RentalStripes(Modifier.size(31.dp, 13.dp))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (category == null) "把想看的作品加入稍后看，回来就能接着上次的进度。"
                else "${category.title}的进度和稍后看作品会出现在这里。",
                color = RentalMuted, style = MaterialTheme.typography.bodyMedium)
            if (category == null)
                Action("去发现", primary = true, icon = R.drawable.ic_explore, click = discover)
            else Action("查看全部内容", primary = true, click = clearCategory)
        }
    }
}

@Composable private fun PersonalRail(title: String, entries: List<CatalogEntry>, action: String = "", more: (() -> Unit)? = null, play: (Video) -> Unit) {
    val padding = 14.dp
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = padding), verticalAlignment = Alignment.CenterVertically) {
            Title(title, Modifier.weight(1f))
            if (more != null) TextAction(more) { Text(action) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(entries, key = { it.video.entryKey }) { entry ->
                val gallery = SiteRegistry.byIdOrDefault(entry.video.site).mediaKind == MediaKind.GALLERY
                val unfinished = entry.started && !entry.completed
                MediaCard(entry.video, Modifier.width(if (gallery) 132.dp else 178.dp),
                    progress = entry.fraction.takeIf { unfinished }, progressLabel = entry.progress.takeIf { unfinished }) {
                    play(entry.video)
                }
            }
        }
    }
}
