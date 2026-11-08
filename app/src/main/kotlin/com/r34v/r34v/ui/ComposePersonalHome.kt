@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.R
import com.r34v.r34v.data.*
import kotlinx.coroutines.*

@Composable fun rememberPersonalCatalog(app: R34Application, revision: Int, resumed: Boolean): List<CatalogEntry> {
    val personal by app.library.personal.changes.collectAsState()
    val local by app.localLibrary.changes.collectAsState()
    val value by produceState<List<CatalogEntry>>(emptyList(), app, revision, personal, local, resumed) {
        val scanned = app.index.scan(app.playback::cached)
        do { value = personalCatalog(app, scanned); if (resumed) delay(3000) } while (resumed && currentCoroutineContext().isActive)
    }
    return value
}

@Composable fun PersonalHomeScreen(app: R34Application, catalog: List<CatalogEntry>, play: (Video) -> Unit,
    waiting: () -> Unit, updates: () -> Unit, search: (SavedSearch?) -> Unit, discover: () -> Unit,
    channel: (DiscoveryChannel) -> Unit = {}, manageChannels: () -> Unit = {}) {
    val personal by app.library.personal.changes.collectAsState()
    val snapshot = remember(personal) { app.library.personal.snapshot() }
    var categoryIndex by rememberSaveable { mutableIntStateOf(0) }
    val category = SiteCategory.entries.getOrNull(categoryIndex - 1)
    fun matches(video: Video) = category == null || SiteRegistry.byIdOrDefault(video.site).category == category
    val later = snapshot.shelves.firstOrNull { it.id == PersonalLibrary.WATCH_LATER }?.entries.orEmpty().filter { !it.completed && matches(it.video) }
    val continuing = catalog.filter { it.started && !it.completed && matches(it.video) }
    val queued = later.map { item -> catalog.firstOrNull { it.video.entryKey == item.video.entryKey } ?: CatalogEntry(item.video) }
    val hero = continuing.firstOrNull() ?: queued.firstOrNull { !it.completed }
    val unread = snapshot.inboxes.values.sumOf { box -> box.entries.count { !it.read } } +
        snapshot.searchUpdates.values.sumOf { box -> box.entries.count { !it.read } }
    val padding = LocalUiDensity.current.pagePadding
    val largeText = LocalConfiguration.current.fontScale > 1.3f
    LazyColumn(Modifier.fillMaxSize().testTag("personal-home"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("types") { ContentTabs(listOf("全部") + SiteCategory.entries.map { it.title }, categoryIndex, Modifier.padding(horizontal = padding)) { categoryIndex = it } }
        item("feature") {
            if (hero != null) PersonalFeature(hero, play)
            else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Copy(if (category == null) "视频 · 漫画 · 写真" else "我的${category.title}")
                Title(if (category == null) "发现下一部" else "还没有${category.title}内容", large = true)
                Copy(if (category == null) "收藏想看的，接着上次看。" else "${category.title}的观看与阅读进度，以及稍后看，会出现在这里。")
                if (category == null) Action("去发现", primary = true, icon = R.drawable.ic_explore, modifier = Modifier.fillMaxWidth(), click = discover)
                else Action("查看全部内容", primary = true, modifier = Modifier.fillMaxWidth()) { categoryIndex = 0 }
            }
        }
        if (continuing.size > 1) item("continue") { PersonalRail("继续观看与阅读", continuing.drop(1).take(12), play = play) }
        if (queued.isNotEmpty()) item("later") { PersonalRail("我的稍后看", queued.take(12), "查看片单", waiting, play) }
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
        item("source") { NavigationRow("浏览当前来源的最新内容", R.drawable.ic_explore, Modifier.padding(horizontal = padding), discover) }
    }
}

@Composable private fun PersonalRail(title: String, entries: List<CatalogEntry>, action: String = "", more: (() -> Unit)? = null, play: (Video) -> Unit) {
    val padding = LocalUiDensity.current.pagePadding
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = padding), verticalAlignment = Alignment.CenterVertically) {
            Title(title, Modifier.weight(1f))
            if (more != null) TextAction(more) { Text(action) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.video.entryKey }) { entry ->
                val gallery = SiteRegistry.byIdOrDefault(entry.video.site).mediaKind == MediaKind.GALLERY
                Column(Modifier.width(if (gallery) 144.dp else 208.dp)) {
                    MediaCard(entry.video, progress = entry.fraction.takeIf { entry.started && !entry.completed }) { play(entry.video) }
                    if (entry.started && !entry.completed) Copy(entry.progress)
                }
            }
        }
    }
}
