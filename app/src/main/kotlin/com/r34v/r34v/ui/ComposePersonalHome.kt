@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
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
    val later = snapshot.shelves.firstOrNull { it.id == PersonalLibrary.WATCH_LATER }?.entries.orEmpty().filterNot { it.completed }
    val continuing = catalog.filter { it.started && !it.completed }
    val hero = continuing.firstOrNull() ?: later.firstOrNull()?.video?.let { v -> catalog.firstOrNull { it.video.entryKey == v.entryKey } ?: CatalogEntry(v) }
    val unread = snapshot.inboxes.values.sumOf { box -> box.entries.count { !it.read } }
    LazyColumn(Modifier.fillMaxSize().testTag("personal-home"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = (132 + ((androidx.compose.ui.platform.LocalConfiguration.current.fontScale - 1f).coerceAtLeast(0f) * 20)).dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Title(if (continuing.isNotEmpty()) "接着看" else "为今晚准备", large = true)
            Spacer(Modifier.height(12.dp))
            if (hero != null) {
                val source = SiteRegistry.byIdOrDefault(hero.video.site)
                MediaResumeHero(hero.video, "${source.name} · ${hero.progress}",
                    if (hero.started) "继续${source.category.verb}" else "开始${source.category.verb}", play)
                if (hero.fraction > 0) LinearProgressIndicator(progress = { hero.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp), color = BrandColor, trackColor = ControlColor)
            } else EmptyState("从喜欢的内容开始", "浏览来源，或把作品加入稍后看。", "去发现", discover)
        }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Action("稍后看 · ${later.size}", click = waiting); Action("订阅更新 · $unread", click = updates) } }
        if (snapshot.savedSearches.isNotEmpty()) item { Title("常用搜索"); Spacer(Modifier.height(8.dp)); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { snapshot.savedSearches.take(12).forEach { saved -> Choice(saved.name) { search(saved) } }; Choice("去搜索") { search(null) } } }
        if (snapshot.channels.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) { Title("我的频道", Modifier.weight(1f)); TextAction(manageChannels) { Text("管理频道") } }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.channels.filterNot { it.hidden }.sortedBy { it.order }.take(12).forEach { saved -> Choice(saved.name) { channel(saved) } }
            }
        }
        if (continuing.size > 1) item { Title("继续观看与阅读"); Spacer(Modifier.height(10.dp)); LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(continuing.drop(1).take(12), key = { it.video.entryKey }) { entry -> Column(Modifier.width(130.dp)) { MediaCard(entry.video) { play(entry.video) }; Copy(entry.progress) } } } }
        if (later.isNotEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically) { Title("下一部，已经选好", Modifier.weight(1f)); TextAction(waiting) { Text("查看片单") } }; LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(later.take(12), key = { it.video.entryKey }) { value -> MediaCard(value.video, Modifier.width(130.dp)) { play(value.video) } } } }
        item { Action("浏览当前来源的最新内容", modifier = Modifier.fillMaxWidth(), click = discover) }
    }
}
