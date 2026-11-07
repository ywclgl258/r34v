@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import kotlinx.coroutines.*

@Composable fun AdaptiveCatalogList(app: R34Application, entries: List<CatalogEntry>, play: (Video) -> Unit, modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    menu: (Video) -> List<Pair<String, () -> Unit>> = { emptyList() }) {
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = entries.firstOrNull { it.video.entryKey == selected }
    BackHandler(current != null) { selected = null }
    val compact = LocalUiDensity.current.compact
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)) {
        if (wide || current == null) LazyColumn(Modifier.weight(1f).testTag("catalog-list"), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            if (header != null) item(key = "catalog-header") { header() }
            if (entries.isEmpty()) item { Copy("当前条件下暂无作品") }
            items(entries, key = { it.video.entryKey }) { entry ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected == entry.video.entryKey) ControlColor else SurfaceColor)
                    .clickable { if (wide) selected = entry.video.entryKey else play(entry.video) }.padding(if (compact) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteImage(entry.video.thumb, SiteRegistry.byIdOrDefault(entry.video.site), Modifier.width(if (compact) 48.dp else 56.dp).height(if (compact) 68.dp else 78.dp), true)
                    Column(Modifier.weight(1f).padding(horizontal = if (compact) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                        Text(entry.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Copy("${SiteRegistry.byIdOrDefault(entry.video.site).name} · ${entry.progress}")
                        if (entry.offline) Copy(entry.downloadState)
                    }
                    val actions = menu(entry.video)
                    if (actions.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Box { IconAction(com.r34v.r34v.R.drawable.ic_more, "管理 ${entry.video.title}") { expanded = true }
                            DropdownMenu(expanded, { expanded = false }) { actions.forEach { (label, action) -> DropdownMenuItem({ Text(label) }, { expanded = false; action() }) } }
                        }
                    }
                }
            }
        }
        if (wide || current != null) Column(Modifier.weight(if (wide) 1.2f else 1f).testTag("catalog-detail").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
            if (current == null) Copy("选择左侧作品查看详情") else {
                if (!wide) TextAction({ selected = null }) { Text("返回列表") }
                SiteImage(current.video.thumb, SiteRegistry.byIdOrDefault(current.video.site), Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .24f).coerceIn(120f, if (compact) 200f else 240f).dp), true)
                Title(current.video.title, large = true); Copy(current.progress); Copy(SiteRegistry.byIdOrDefault(current.video.site).name)
                Action(if (current.video.mediaKind == MediaKind.GALLERY) "打开阅读器" else "打开播放器", true, modifier = Modifier.fillMaxWidth()) { play(current.video) }
                val dialogs = remember { ComposeDialogs() }
                SaveForLater(current.video, app, dialogs); dialogs.Host()
            }
        }
    }
}

@Composable fun LibraryHubScreen(app: R34Application, dialogs: ComposeDialogs, catalog: List<CatalogEntry>, play: (Video) -> Unit,
    task: (String) -> Unit, search: () -> Unit, importLocal: () -> Unit, openLink: () -> Unit, download: (List<Video>) -> Unit,
    image: (ImageFavorite) -> Unit = { play(it.video) }) {
    val revision by app.library.personal.changes.collectAsState()
    val localRevision by app.localLibrary.changes.collectAsState()
    val snapshot = remember(revision) { app.library.personal.snapshot() }
    var selected by rememberSaveable { mutableStateOf("") }
    BackHandler(selected.isNotBlank()) { selected = "" }
    val shelf = snapshot.shelves.firstOrNull { it.id == selected }
    val smart = snapshot.smartShelves.firstOrNull { it.id == selected }
    val configuration = LocalConfiguration.current
    val shelfColumns = if (configuration.screenWidthDp >= 840 && configuration.fontScale <= 1.3f) 2 else 1
    if (shelf != null) Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ShelfScreen(app, dialogs, play, download, shelf.id, catalog, onBack = { selected = "" }, discover = search)
    } else if (selected == "images") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextAction({ selected = "" }) { Text("返回我的片库") }
            Title("单图收藏 · ${snapshot.imageFavorites.size}", large = true)
            if (snapshot.imageFavorites.isEmpty()) Copy("在漫画或图集阅读工具中收藏当前图片，之后可从这里回到原图集页。")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(snapshot.imageFavorites.chunked(shelfColumns), key = { it.first().key }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { favorite ->
                            val thumbnail by produceState("", favorite, localRevision) {
                                value = if (favorite.video.site == SiteRegistry.LOCAL.id) withContext(Dispatchers.IO) {
                                    app.localLibrary.imageUri(favorite.video.id, favorite.page).orEmpty()
                                } else favorite.imageUrl.ifBlank { favorite.video.thumb }
                            }
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(SurfaceColor).clickable { image(favorite) }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                SiteImage(thumbnail, SiteRegistry.byIdOrDefault(favorite.video.site), Modifier.fillMaxWidth().height(210.dp), true)
                                Text(favorite.video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Copy("${SiteRegistry.byIdOrDefault(favorite.video.site).name} · 第 ${favorite.page} 页")
                                TextAction({ app.library.personal.toggleImageFavorite(favorite.video, favorite.page, favorite.pageUrl, favorite.imageUrl) }) { Text("取消收藏") }
                            }
                        }
                        if (row.size < shelfColumns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    } else if (smart != null || selected == "local") {
        val entries = if (smart != null) smart.select(catalog) else catalog.filter { it.video.site == SiteRegistry.LOCAL.id }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextAction({ selected = "" }) { Text("返回我的片库") }
            Title(smart?.name ?: "本地导入", large = true)
            if (smart != null) {
                Copy("${smart.rule.title} · 动态更新 · ${entries.size} 部")
                TextAction({ dialogs.confirm("删除智能片单？", body = "作品和文件会保留。") { app.library.personal.removeSmart(smart.id); selected = "" } }) { Text("删除智能片单") }
            } else { Copy("媒体保存在应用内，卸载应用会移除导入的副本。原始文件不受影响。"); Action("继续导入", click = importLocal) }
            AdaptiveCatalogList(app, entries, play, Modifier.weight(1f), menu = { video ->
                if (video.site == SiteRegistry.LOCAL.id) listOf("移除本地副本" to { dialogs.confirm("移除「${video.title}」的本地副本？", body = "原始文件和阅读记录保留，重新导入同一内容可恢复。") { dialogs.content = { LocalRemoveDialog(app, video) { dialogs.dismiss() } } } }) else emptyList()
            })
        }
    } else LazyColumn(Modifier.fillMaxSize().testTag("library-hub"), contentPadding = PaddingValues(LocalUiDensity.current.pagePadding), verticalArrangement = Arrangement.spacedBy(if (LocalUiDensity.current.compact) 12.dp else 14.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Title("我的片库", Modifier.align(Alignment.CenterVertically), large = true)
                TextAction({ task("标签订阅") }) { Text("标签订阅") }
            }
            Spacer(Modifier.height(12.dp))
            val shortcuts = listOf("记录" to R.drawable.ic_history, "收藏" to R.drawable.ic_bookmark,
                "下载" to R.drawable.ic_download, "更新" to R.drawable.ic_updates)
            val columns = if (configuration.fontScale > 1.3f || configuration.screenWidthDp < 360) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, icon) -> Column(Modifier.weight(1f).contentPanel(10).clickable { task(label) }
                        .padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Glyph(icon, null, Modifier.size(24.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    } }
                } }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Title("我的片单", Modifier.weight(1f)); TextAction({ dialogs.edit("新建合集", validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { selected = app.library.personal.createShelf(it) } }) { Text("新建") } } }
        items(snapshot.shelves.chunked(shelfColumns), key = { it.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value ->
                    val subtitle = if (value.entries.isEmpty()) "还没有添加作品" else
                        "${value.entries.size} 部作品${if (configuration.fontScale > 1.3f) "\n" else " · "}${value.entries.count { !it.completed }} 部未完成"
                    ShelfCover(value.name, subtitle, value.entries.take(2).map { it.video }, Modifier.weight(1f)) { selected = value.id }
                }
                if (row.size < shelfColumns) Spacer(Modifier.weight(1f))
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Title("智能片单", Modifier.weight(1f)); TextAction({ newSmartShelf(dialogs, app) }) { Text("新建智能片单") } } }
        items(snapshot.smartShelves.chunked(shelfColumns), key = { it.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value -> val matches = value.select(catalog); ShelfCover(value.name, "${value.rule.title} · ${matches.size} 部", matches.take(2).map { it.video }, Modifier.weight(1f)) { selected = value.id } }
                if (row.size < shelfColumns) Spacer(Modifier.weight(1f))
            }
        }
        if (snapshot.smartShelves.isEmpty()) item { Copy("按未看完、已离线未看或最近加入，自动整理已有作品。") }
        item { NavigationRow("单图收藏 · ${snapshot.imageFavorites.size} 张", R.drawable.ic_bookmark) { selected = "images" } }
        item { Title("导入与打开") }
        item { NavigationRow("本地导入 · ${catalog.count { it.video.site == SiteRegistry.LOCAL.id }} 本", R.drawable.ic_download) { selected = "local" }; Spacer(Modifier.height(8.dp)); NavigationRow("导入 CBZ / ZIP / 图片目录", R.drawable.ic_add, click = importLocal) }
        item { NavigationRow("从链接打开作品", R.drawable.ic_explore, click = openLink) }
        item { TextAction({ task("片单") }) { Text("管理全部片单") } }
    }
}

@Composable private fun ShelfCover(title: String, subtitle: String, covers: List<Video>, modifier: Modifier = Modifier, click: () -> Unit) {
    val compact = LocalUiDensity.current.compact
    Row(modifier.fillMaxWidth().contentPanel(12).clickable(onClick = click).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (covers.isEmpty()) 56.dp else if (compact) 72.dp else 80.dp), contentAlignment = Alignment.Center) {
            if (covers.isEmpty()) Box(Modifier.fillMaxSize().contentPanel(8).background(RaisedColor), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_bookmark, null, Modifier.size(28.dp))
            }
            covers.reversed().forEachIndexed { i, video -> SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.padding(start = (i * 18).dp).width(56.dp).height(if (compact) 72.dp else 80.dp).clip(RoundedCornerShape(6.dp)), true) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Title(title); Copy(subtitle) }
        Glyph(R.drawable.ic_chevron_right, null, Modifier.size(16.dp))
    }
}

private fun newSmartShelf(dialogs: ComposeDialogs, app: R34Application) {
    dialogs.choose("智能片单规则", SmartRule.entries.map { if (it == SmartRule.RECENT) "最近 30 天加入" else it.title }) { rule ->
        dialogs.choose("媒体类型", listOf("全部类型") + SiteCategory.entries.map { it.title }) { category ->
            dialogs.choose("来源", listOf("全部来源") + SiteRegistry.librarySources.map { it.name }) { source ->
                dialogs.edit("智能片单名称", SmartRule.entries[rule].title, validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { name -> app.library.personal.saveSmart(SmartShelf(name = name, rule = SmartRule.entries[rule], category = if (category == 0) "" else SiteCategory.entries[category-1].name, source = if (source == 0) "" else SiteRegistry.librarySources[source-1].id)) }
            }
        }
    }
}

@Composable private fun LocalRemoveDialog(app: R34Application, video: Video, done: () -> Unit) {
    var error by remember { mutableStateOf("") }
    LaunchedEffect(video.entryKey) { try { app.localLibrary.remove(video.id); done() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message.orEmpty() } }
    AlertDialog(done, title = { Text("移除本地副本") }, text = { if (error.isBlank()) Loading("正在移除") else Text(error) }, confirmButton = { if (error.isNotBlank()) TextAction(done) { Text("关闭") } })
}
