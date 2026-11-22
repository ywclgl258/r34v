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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp)) {
        if (wide || current == null) LazyColumn(Modifier.weight(1f).testTag("catalog-list"), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            if (header != null) item(key = "catalog-header") { header() }
            if (entries.isEmpty()) item { Copy("当前条件下暂无作品") }
            items(entries, key = { it.video.entryKey }) { entry ->
                val site = SiteRegistry.byIdOrDefault(entry.video.site)
                val coverShape = RoundedCornerShape(9.dp)
                Row(Modifier.fillMaxWidth().clip(ArtworkShape).background(if (selected == entry.video.entryKey) colors.surface else Color.Transparent)
                    .clickable(role = Role.Button) { if (wide) selected = entry.video.entryKey else play(entry.video) }
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val gallery = entry.video.mediaKind == MediaKind.GALLERY || site.mediaKind == MediaKind.GALLERY
                    val thumb = if (!gallery) Modifier.size(78.dp, 54.dp) else Modifier.size(56.dp, 72.dp)
                    SiteImage(entry.video.thumb, site, thumb.clip(coverShape)
                        .border(1.dp, colors.outline, coverShape), fit = gallery)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.video.title, color = colors.onSurface,
                            style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Copy("${site.name} · ${entry.progress}")
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
                HorizontalDivider(color = colors.outline.copy(alpha = .58f))
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
    val colors = MaterialTheme.colorScheme
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
                            Column(Modifier.weight(1f).artworkPanel()
                                .clickable(role = Role.Button) { image(favorite) }.padding(1.dp)) {
                                SiteImage(thumbnail, SiteRegistry.byIdOrDefault(favorite.video.site), Modifier.fillMaxWidth().height(210.dp), true)
                                Column(Modifier.fillMaxWidth().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(favorite.video.title, color = colors.onSurface, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${SiteRegistry.byIdOrDefault(favorite.video.site).name} · 第 ${favorite.page} 页",
                                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
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
    } else LazyColumn(
        Modifier.fillMaxSize().testTag("library-hub"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("shortcuts") {
            val shortcuts = listOf("记录" to R.drawable.ic_history, "收藏" to R.drawable.ic_bookmark,
                "下载" to R.drawable.ic_download, "更新" to R.drawable.ic_updates)
            val columns = if (configuration.fontScale > 1.3f || windowSizeDp().width < 360) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shortcuts.chunked(columns).forEach { row -> Row(
                    Modifier.fillMaxWidth().artworkPanel(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEachIndexed { index, (label, icon) ->
                        if (index > 0) Spacer(Modifier.size(1.dp, 28.dp).background(colors.outline.copy(alpha = .60f)))
                        val target = Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button) { task(label) }
                        if (configuration.fontScale > 1.3f) Row(target.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Glyph(icon, null, Modifier.size(24.dp))
                            Text(label, color = colors.onSurface, style = MaterialTheme.typography.labelLarge)
                        } else Column(target.padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Glyph(icon, null, Modifier.size(22.dp))
                            Text(label, color = colors.onSurface, style = MaterialTheme.typography.labelLarge)
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
                    Action("新建", primary = true, icon = R.drawable.ic_add) {
                        dialogs.edit("新建合集", validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) {
                            selected = app.library.personal.createShelf(it)
                        }
                    }
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
                    Action("新建智能片单", primary = true, icon = R.drawable.ic_add) { newSmartShelf(dialogs, app) }
                } }
                items(snapshot.smartShelves.chunked(shelfColumns), key = { "smart:${it.first().id}" }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { value -> val matches = value.select(catalog); ShelfCover(value.name, "${value.rule.title} · ${matches.size} 部", matches.take(3).map { it.video }, Modifier.weight(1f)) { selected = value.id } }
                        if (row.size < shelfColumns) Spacer(Modifier.weight(1f))
                    }
                }
                if (snapshot.smartShelves.isEmpty()) item("smart-empty") {
                    LibraryNotice("让片单自己整理", "按未看完、已离线未看或最近加入，自动归集已有作品。")
                }
            }
            else -> {
                val local = catalog.filter { it.video.site == SiteRegistry.LOCAL.id }
                item("local-heading") { Copy("${local.size} 部已导入 · 可离线打开") }
                items(local, key = { "local:${it.video.entryKey}" }) { entry ->
                    ShelfCover(entry.video.title, entry.progress, listOf(entry.video)) { play(entry.video) }
                }
                if (local.isEmpty()) item("local-empty") {
                    LibraryNotice("还没有本地作品", "导入视频、CBZ、ZIP 或图片目录后，可以在这里离线打开。")
                }
                item("local-all") { NavigationRow("本地导入 · ${local.size} 部", R.drawable.ic_download) { selected = "local" } }
            }
        }
        item("other-heading") { Title("更多内容", Modifier.padding(top = 12.dp)) }
        item("images") { NavigationRow("单图收藏 · ${snapshot.imageFavorites.size} 张", R.drawable.ic_bookmark) { selected = "images" } }
        item("subscriptions") { NavigationRow("标签订阅", R.drawable.ic_updates) { task("标签订阅") } }
        item("import") { NavigationRow("导入 CBZ / ZIP / 图片目录", R.drawable.ic_add, click = importLocal) }
        item("link") { NavigationRow("从链接打开作品", R.drawable.ic_explore, click = openLink) }
    }
}

@Composable internal fun LibraryNotice(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().artworkPanel()
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title(title)
        Copy(body)
        action?.invoke()
    }
}

@Composable private fun ShelfCover(title: String, subtitle: String, covers: List<Video>, modifier: Modifier = Modifier, note: String = "", pinned: Boolean = false, click: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().artworkPanel()
        .clickable(role = Role.Button, onClick = click).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val thumb = Modifier.size(94.dp, 70.dp).clip(RoundedCornerShape(9.dp))
        if (covers.isEmpty()) {
            Box(thumb.background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                EmptyTapeArt(Modifier.size(66.dp, 39.dp))
            }
        } else Row(thumb.background(colors.surfaceVariant), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            covers.take(3).forEach { video ->
                SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.weight(1f).fillMaxHeight())
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (pinned) Text("已置顶", color = colors.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            if (note.isNotBlank()) Text(note, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
            Glyph(R.drawable.ic_chevron_right, null, Modifier.padding(end = 8.dp).size(16.dp))
        }
    }
}

@Composable private fun EmptyTapeArt(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(5.dp)
    Canvas(modifier.clip(shape).background(colors.background).border(1.dp, colors.outline, shape)) {
        val w = size.width
        val h = size.height
        drawRect(colors.onSurfaceVariant, Offset(w * .10f, h * .14f), Size(w * .8f, h * .16f))
        drawRect(colors.primary, Offset(w * .10f, h * .26f), Size(w * .8f, h * .06f))
        drawRect(colors.surfaceVariant, Offset(w * .12f, h * .42f), Size(w * .76f, h * .38f))
        for (x in listOf(.25f, .75f)) {
            val center = Offset(w * x, h * .61f)
            drawCircle(colors.onSurfaceVariant, h * .145f, center)
            drawCircle(colors.background, h * .060f, center)
        }
        drawRect(colors.outline, Offset(w * .40f, h * .55f), Size(w * .20f, h * .12f))
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
    LaunchedEffect(video.entryKey) { try {
        app.localLibrary.remove(video.id)
        // 本地视频还要清掉播放登记（PlayerActivity 的 local 分支按 id+path 校验）。
        if (video.mediaKind == MediaKind.VIDEO) app.library.removeLocal(video.id, SiteRegistry.LOCAL.id)
        done()
    } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message.orEmpty() } }
    AlertDialog(done, title = { Text("移除本地副本") }, text = { if (error.isBlank()) Loading(title = "正在移除") else Text(error) }, confirmButton = { if (error.isNotBlank()) TextAction(done) { Text("关闭") } })
}
