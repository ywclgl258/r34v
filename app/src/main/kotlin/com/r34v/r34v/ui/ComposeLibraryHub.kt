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
    listState: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    menu: (Video) -> List<Pair<String, () -> Unit>> = { emptyList() }) {
    val wide = windowSizeDp().width >= 840
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = entries.firstOrNull { it.video.entryKey == selected }
    BackHandler(current != null) { selected = null }
    val compact = LocalUiDensity.current.compact
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)) {
        if (wide || current == null) LazyColumn(Modifier.weight(1f).testTag("catalog-list"), state = listState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
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
                SiteImage(current.video.thumb, SiteRegistry.byIdOrDefault(current.video.site), Modifier.fillMaxWidth().height((windowSizeDp().height * .24f).coerceIn(120f, if (compact) 200f else 240f).dp), true)
                Title(current.video.title, large = true); Copy(current.progress); Copy(SiteRegistry.byIdOrDefault(current.video.site).name)
                Action(if (current.video.mediaKind == MediaKind.GALLERY) "打开阅读器" else "打开播放器", primary = true, modifier = Modifier.fillMaxWidth()) { play(current.video) }
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
    var section by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(selected.isNotBlank()) { selected = "" }
    val shelf = snapshot.shelves.firstOrNull { it.id == selected }
    val smart = snapshot.smartShelves.firstOrNull { it.id == selected }
    val configuration = LocalConfiguration.current
    val shelfColumns = if (windowSizeDp().width >= 840 && configuration.fontScale <= 1.3f) 2 else 1
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
    } else LazyColumn(Modifier.fillMaxSize().testTag("library-hub"), contentPadding = PaddingValues(LocalUiDensity.current.pagePadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("shortcuts") {
            val shortcuts = listOf("记录" to R.drawable.ic_history, "收藏" to R.drawable.ic_bookmark,
                "下载" to R.drawable.ic_download, "更新" to R.drawable.ic_updates)
            val columns = if (configuration.fontScale > 1.3f || windowSizeDp().width < 360) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, icon) ->
                        val target = Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable { task(label) }
                        if (configuration.fontScale > 1.3f) Row(target.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Glyph(icon, null, Modifier.size(24.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        } else Column(target.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(36.dp).contentPanel(10), contentAlignment = Alignment.Center) { Glyph(icon, null, Modifier.size(22.dp)) }
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } }
            }
        }
        item("sections") { ContentTabs(listOf("我的片单", "智能片单", "本地内容"), section) { section = it } }
        when (section) {
            0 -> {
                item("shelf-heading") { Row(verticalAlignment = Alignment.CenterVertically) {
                    Copy("${snapshot.shelves.size} 个片单", Modifier.weight(1f))
                    TextAction({ dialogs.edit("新建合集", validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { selected = app.library.personal.createShelf(it) } }) { Text("新建") }
                } }
                items(snapshot.shelves.displayOrder().chunked(shelfColumns), key = { "shelf:${it.first().id}" }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { value ->
                            val subtitle = if (value.entries.isEmpty()) "还没有添加作品" else
                                "${value.entries.size} 部作品${if (configuration.fontScale > 1.3f) "\n" else " · "}${value.entries.count { !it.completed }} 部未完成"
                            ShelfCover(value.name, subtitle, value.coverVideos(), Modifier.weight(1f), value.note, value.pinned) { selected = value.id }
                        }
                        if (row.size < shelfColumns) Spacer(Modifier.weight(1f))
                    }
                }
                item("manage-shelves") { TextAction({ task("片单") }) { Text("管理全部片单") } }
            }
            1 -> {
                item("smart-heading") { Row(verticalAlignment = Alignment.CenterVertically) {
                    Copy("按规则自动整理", Modifier.weight(1f))
                    TextAction({ newSmartShelf(dialogs, app) }) { Text("新建智能片单") }
                } }
                items(snapshot.smartShelves.chunked(shelfColumns), key = { "smart:${it.first().id}" }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { value -> val matches = value.select(catalog); ShelfCover(value.name, "${value.rule.title} · ${matches.size} 部", matches.take(3).map { it.video }, Modifier.weight(1f)) { selected = value.id } }
                        if (row.size < shelfColumns) Spacer(Modifier.weight(1f))
                    }
                }
                if (snapshot.smartShelves.isEmpty()) item("smart-empty") {
                    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Title("让片单自己整理")
                        Copy("按未看完、已离线未看或最近加入，自动归集已有作品。")
                    }
                }
            }
            else -> {
                val local = catalog.filter { it.video.site == SiteRegistry.LOCAL.id }
                item("local-heading") { Copy("${local.size} 本已导入 · 可离线阅读") }
                items(local, key = { "local:${it.video.entryKey}" }) { entry ->
                    ShelfCover(entry.video.title, entry.progress, listOf(entry.video)) { play(entry.video) }
                }
                if (local.isEmpty()) item("local-empty") {
                    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Title("把喜欢的内容带在身边")
                        Copy("导入 CBZ、ZIP 或图片目录，整理成自己的离线书库。")
                    }
                }
                item("local-all") { NavigationRow("本地导入 · ${local.size} 本", R.drawable.ic_download) { selected = "local" } }
            }
        }
        item("other-heading") { HorizontalDivider(color = RaisedColor); Spacer(Modifier.height(18.dp)); Title("更多内容") }
        item("images") { NavigationRow("单图收藏 · ${snapshot.imageFavorites.size} 张", R.drawable.ic_bookmark) { selected = "images" } }
        item("subscriptions") { NavigationRow("标签订阅", R.drawable.ic_updates) { task("标签订阅") } }
        item("import") { NavigationRow("导入 CBZ / ZIP / 图片目录", R.drawable.ic_add, click = importLocal) }
        item("link") { NavigationRow("从链接打开作品", R.drawable.ic_explore, click = openLink) }
    }
}

@Composable private fun ShelfCover(title: String, subtitle: String, covers: List<Video>, modifier: Modifier = Modifier, note: String = "", pinned: Boolean = false, click: () -> Unit) {
    val compact = LocalUiDensity.current.compact
    Row(modifier.fillMaxWidth().contentPanel(12).clickable(onClick = click).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (covers.isEmpty()) 56.dp else if (compact) 72.dp else 80.dp), contentAlignment = Alignment.Center) {
            if (covers.isEmpty()) Box(Modifier.fillMaxSize().contentPanel(8).background(RaisedColor), contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_bookmark, null, Modifier.size(28.dp))
            }
            covers.reversed().forEachIndexed { i, video -> SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.padding(start = (i * 18).dp).width(56.dp).height(if (compact) 72.dp else 80.dp).clip(RoundedCornerShape(6.dp)), true) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pinned) Copy("已置顶")
            Title(title); Copy(subtitle)
            if (note.isNotBlank()) Text(note, color = MutedColor, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
    AlertDialog(done, title = { Text("移除本地副本") }, text = { if (error.isBlank()) Loading(title = "正在移除") else Text(error) }, confirmButton = { if (error.isNotBlank()) TextAction(done) { Text("关闭") } })
}
