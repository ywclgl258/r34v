@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.R
import com.r34v.r34v.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable fun rememberPersonalCatalog(app: R34Application, revision: Int, resumed: Boolean): List<CatalogEntry>? {
    val personal by app.library.personal.changes.collectAsState()
    val historyAndFavorites by app.library.catalogChanges.collectAsState()
    val local by app.localLibrary.changes.collectAsState()
    val videoDownloads by app.playback.downloadChanges.collectAsState()
    val galleryDownloads by app.galleryDownloads.changes.collectAsState()
    val scanned by produceState<List<LibraryRecord>?>(null, app, revision, resumed) {
        if (!resumed) return@produceState
        var scannedSignature: Pair<Long, Set<String>>? = null
        combine(app.index.revision, app.playback.downloadChanges) { disk, _ -> disk }.conflate().collect { disk ->
            val updated = withContext(Dispatchers.IO) {
                val known = app.playback.downloads().mapNotNull { app.playback.libraryRecord(it) }
                val completed = known.asSequence().filter { it.state == LibraryRecord.STATE_COMPLETED }.map { it.cacheKey }.toSet()
                val signature = disk to completed
                if (scannedSignature == signature) null else app.index.scan(app.playback::cached, known).also { scannedSignature = signature }
            }
            if (updated != null) value = updated
        }
    }
    val value by produceState<List<CatalogEntry>?>(null, app, resumed, scanned, personal, historyAndFavorites, local, videoDownloads, galleryDownloads) {
        if (resumed) scanned?.let { value = personalCatalog(app, it) }
    }
    return value
}

@Composable fun PersonalHomeScreen(app: R34Application, catalog: List<CatalogEntry>?, play: (Video) -> Unit,
    waiting: () -> Unit, updates: () -> Unit, search: (SavedSearch?) -> Unit, discover: () -> Unit,
    channel: (DiscoveryChannel) -> Unit = {}, manageChannels: () -> Unit = {}) {
    val store = app.library
    val personal by store.personal.changes.collectAsState()
    val blocked by store.blockedTagChanges.collectAsState()
    val snapshot = remember(personal) { store.personal.snapshot() }
    var categoryIndex by rememberSaveable { mutableIntStateOf(0) }
    val category = SiteCategory.entries.getOrNull(categoryIndex - 1)
    fun matches(video: Video) = category == null || video.workCategory == category
    val later = snapshot.shelves.firstOrNull { it.id == PersonalLibrary.WATCH_LATER }?.entries.orEmpty().filter { !it.completed && matches(it.video) }
    val continuing = catalog.orEmpty().filter { it.started && !it.completed && matches(it.video) }
    val queued = later.map { item -> catalog?.firstOrNull { it.video.entryKey == item.video.entryKey } ?: CatalogEntry(item.video) }
    val hero = continuing.firstOrNull() ?: queued.firstOrNull { !it.completed }
    val continuingRail = continuing.drop(1).take(12)
    val continuingKeys = continuingRail.mapTo(mutableSetOf()) { it.video.entryKey }
    val queuedRail = queued.filter { it.video.entryKey != hero?.video?.entryKey && it.video.entryKey !in continuingKeys }
    val unread = remember(snapshot, blocked) {
        store.personal.visibleSubscriptionEntries(data = snapshot).count { !it.read } +
            snapshot.savedSearches.sumOf { search ->
                store.personal.visibleSearchEntries(search, snapshot, search.directoryRequests(app.network).keys).count { !it.read }
            }
    }
    val padding = 14.dp
    LazyColumn(Modifier.fillMaxSize().testTag("personal-home"),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("types") { ContentTabs(listOf("全部") + SiteCategory.entries.map { it.title }, categoryIndex, Modifier.padding(horizontal = padding)) { categoryIndex = it } }
        if (catalog == null) {
            item("loading") { Loading(title = "正在读取片库") }
            return@LazyColumn
        }
        item("feature") {
            Box(Modifier.artworkReveal(hero?.video?.entryKey ?: category?.name.orEmpty())) {
                if (hero != null) PersonalFeature(hero, play)
                else PersonalHomeEmpty(category, discover) { categoryIndex = 0 }
            }
        }
        if (continuingRail.isNotEmpty()) item("continue") { PersonalRail("继续观看与阅读", continuingRail, play) }
        if (queuedRail.isNotEmpty()) item("later") { PersonalRail("我的稍后看", queuedRail.take(12), play = play) }
        item("shortcuts") {
            FlowRow(Modifier.padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonalShortcut("稍后看 · ${later.size}", R.drawable.ic_bookmark, waiting)
                PersonalShortcut("订阅更新 · $unread", R.drawable.ic_updates, updates)
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
        if (hero != null || category != null) item("source") {
            Box(Modifier.padding(horizontal = padding)) {
                PersonalShortcut("浏览当前来源的最新内容", R.drawable.ic_explore, discover)
            }
        }
    }
}

@Composable private fun PersonalHomeEmpty(category: SiteCategory?, discover: () -> Unit, clearCategory: () -> Unit) {
    Box(Modifier.padding(horizontal = 14.dp)) {
        LibraryNotice(if (category == null) "发现下一部" else "还没有${category.title}内容",
            if (category == null) "把想看的作品加入稍后看，回来就能接着上次的进度。"
            else "${category.title}的进度和稍后看作品会出现在这里。") {
            if (category == null)
                Action("去发现", primary = true, icon = R.drawable.ic_explore, click = discover)
            else Action("查看全部内容", primary = true, click = clearCategory)
        }
    }
}

@Composable private fun PersonalShortcut(label: String, icon: Int, click: () -> Unit) {
    TextAction(click) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PersonalRail(title: String, entries: List<CatalogEntry>, play: (Video) -> Unit) {
    val padding = 14.dp
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Title(title, Modifier.padding(horizontal = padding))
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
