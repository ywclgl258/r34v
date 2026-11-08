@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*

fun ComposeDialogs.addToShelf(store: PersonalStore, video: Video) {
    val shelves = store.shelves()
    choose("加入片单", shelves.map { it.name } + "新建合集") { index ->
        if (index < shelves.size) store.add(video, shelves[index].id)
        else edit("新建合集", validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { name -> store.add(video, store.createShelf(name)) }
    }
}

fun ComposeDialogs.selectVideos(videos: List<Video>, action: (List<Video>) -> Unit) {
    val entries = videos.distinctBy { it.entryKey }
    content = {
        var selected by remember { mutableStateOf(entries.map { it.entryKey }.toSet()) }
        AlertDialog(::dismiss, title = { Text("选择下载作品 · ${selected.size} / ${entries.size}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextAction({ selected = if (selected.size == entries.size) emptySet() else entries.map { it.entryKey }.toSet() }) { Text("全选 / 清空") }
                entries.forEach { video -> CheckRow("${SiteRegistry.byIdOrDefault(video.site).name} · ${video.title}", video.entryKey in selected) {
                    selected = if (it) selected + video.entryKey else selected - video.entryKey
                } }
            }
        }, confirmButton = { TextAction({ dismiss(); action(entries.filter { it.entryKey in selected }) }, enabled = selected.isNotEmpty()) { Text("下一步") } },
            dismissButton = { TextAction(::dismiss) { Text("取消") } })
    }
}

@Composable fun SaveForLater(video: Video, app: R34Application, dialogs: ComposeDialogs) {
    val revision by app.library.personal.changes.collectAsState()
    val saved = remember(revision, video.entryKey) { app.library.personal.contains(video) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action(if (saved) "已加入稍后看" else "稍后看", selected = saved) {
            if (saved) app.library.personal.remove(video) else app.library.personal.add(video)
        }
        Action("加入合集") { dialogs.addToShelf(app.library.personal, video) }
    }
}

@Composable fun ShelfScreen(app: R34Application, dialogs: ComposeDialogs, play: (Video) -> Unit, download: (List<Video>) -> Unit,
    initialShelf: String = PersonalLibrary.WATCH_LATER, catalog: List<CatalogEntry> = emptyList(), onBack: (() -> Unit)? = null, discover: () -> Unit) {
    val store = app.library.personal
    val revision by store.changes.collectAsState()
    val shelves = remember(revision) { store.shelves() }
    var selected by rememberSaveable(initialShelf) { mutableStateOf(initialShelf) }
    var status by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf(ShelfOrder.MANUAL) }
    var addedDays by rememberSaveable { mutableIntStateOf(0) }
    var filtering by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val queryEditing = searching && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val shortWindow = windowSizeDp().height < 500
    val shelf = shelves.firstOrNull { it.id == selected } ?: shelves.first()
    val view = ShelfQuery(query, status, if (category == 0) "" else SiteCategory.entries[category - 1].name, source, addedDays, order)
    val entries = view.select(shelf)
    val listState = rememberLazyListState()
    var revealMove by remember(shelf.id) { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(revision, revealMove) {
        val (afterRevision, key) = revealMove ?: return@LaunchedEffect
        if (revision < afterRevision) return@LaunchedEffect
        val index = entries.indexOfFirst { it.video.entryKey == key }
        if (index >= 0) listState.scrollToItem(index)
        revealMove = null
    }
    fun move(video: Video, delta: Int) {
        store.move(video, shelf.id, delta)
        revealMove = store.changes.value to video.entryKey
    }
    var previousView by remember { mutableStateOf(shelf.id to view) }
    LaunchedEffect(shelf.id, view) {
        if (previousView != (shelf.id to view)) { listState.scrollToItem(0); previousView = shelf.id to view }
    }
    Column(Modifier.fillMaxSize().then(if (shortWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            if (onBack != null) IconAction(com.r34v.r34v.R.drawable.ic_back, "返回我的片库", click = onBack)
            TextAction({ val choices = shelves.displayOrder(); dialogs.choose("切换片单", choices.map { it.name }, choices.indexOf(shelf)) { selected = choices[it].id } }, Modifier.weight(1f)) { Title(shelf.name, Modifier.weight(1f), true); Glyph(com.r34v.r34v.R.drawable.ic_chevron_down, null) }
            IconAction(com.r34v.r34v.R.drawable.ic_search, "搜索片单") { searching = !searching; if (!searching) query = "" }
            TextAction({ dialogs.edit("新建合集", validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { selected = store.createShelf(it) } }) { Text("新建") }
        }
        if (searching) TextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("片单内标题或来源") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
            trailingIcon = { if (query.isNotEmpty()) IconAction(com.r34v.r34v.R.drawable.ic_close, "清空片单搜索") { query = "" } }, colors = filledFieldColors())
        Copy("${entries.size} / ${shelf.entries.size} 部 · ${listOf("全部", "未完成", "已完成")[status]} · ${order.title}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!queryEditing) TextAction({ editing = true }) { Text("合集设置") }
                TextAction({ filtering = true }) { Text("筛选") }
                if (!queryEditing && entries.isNotEmpty()) TextAction({ preparing = true }) { Text("准备离线片单") }
            }
        val unfinished = entries.filterNot { it.completed }.map { it.video }
        if (!queryEditing && unfinished.isNotEmpty()) Action("按顺序观看 / 阅读", primary = true, modifier = Modifier.fillMaxWidth()) {
            runCatching { val queue = app.queue.start(shelf.name, shelf.id, unfinished); MediaRouter.open(context, unfinished.first(), queue.id) }
                .onFailure { dialogs.message("无法开始片单", it.message.orEmpty()) }
        }
        if (entries.isEmpty()) {
            if (view.filtered) EmptyState("没有匹配的作品", "搜索与筛选只作用于当前片单。", "清空条件") {
                query = ""; status = 0; category = 0; source = ""; addedDays = 0
            } else EmptyState("片单中暂无内容", "在作品菜单或详情页加入稍后看或合集。", "去搜索", discover)
        }
        AdaptiveCatalogList(app, entries.map { entry ->
            (catalog.firstOrNull { it.video.entryKey == entry.video.entryKey } ?: CatalogEntry(entry.video)).let { if (entry.completed) it.copy(progress = "已完成") else it }
        }, play, if (shortWindow) Modifier.height(300.dp) else Modifier.weight(1f), listState = listState, menu = { video ->
            val entry = entries.first { it.video.entryKey == video.entryKey }
            buildList {
                add((if (entry.completed) "标记未完成" else "标记完成") to { store.complete(entry.video, shelf.id, !entry.completed) })
                if (order == ShelfOrder.MANUAL && !view.filtered) {
                    add("上移" to { move(entry.video, -1) }); add("下移" to { move(entry.video, 1) })
                }
                add("移出当前片单" to { store.remove(entry.video, shelf.id) })
            }
        })
    }
    if (filtering) AlertDialog({ filtering = false }, title = { Text("片单筛选") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChoice("完成状态", listOf("全部", "未完成", "已完成"), status) { status = it }
            FilterChoice("媒体类型", listOf("全部类型") + SiteCategory.entries.map { it.title }, category) { category = it }
            val sources = SiteRegistry.librarySources
            FilterChoice("来源", listOf("全部来源") + sources.map { it.name }, sources.indexOfFirst { it.id == source } + 1) { source = if (it == 0) "" else sources[it - 1].id }
            val days = listOf(0, 7, 30, 90)
            FilterChoice("加入时间", listOf("不限时间", "最近 7 天", "最近 30 天", "最近 90 天"), days.indexOf(addedDays)) { addedDays = days[it] }
            FilterChoice("显示顺序", ShelfOrder.entries.map { it.title }, order.ordinal) { order = ShelfOrder.entries[it] }
            Copy("顺序播放使用当前列表；回到手动顺序并清空筛选后可上移／下移作品。")
        }
    }, confirmButton = { TextAction({ filtering = false }) { Text("显示作品") } })
    if (preparing) OfflinePlanDialog(app, entries.map { it.video }, catalog, { preparing = false }, download)
    if (editing) ShelfSettingsDialog(store, shelf, { editing = false }) {
        editing = false
        dialogs.confirm("删除「${shelf.name}」？", body = "作品的收藏、进度和下载会保留。") { store.removeShelf(shelf.id); selected = PersonalLibrary.WATCH_LATER }
    }
}

@Composable fun FilterChoice(label: String, values: List<String>, selected: Int, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column { Copy(label); Box { Selector(values.getOrElse(selected) { values.first() }, modifier = Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) { values.forEachIndexed { index, value -> DropdownMenuItem({ Text(value) }, { choose(index); expanded = false }) } }
    } }
}

@Composable fun OfflinePlanDialog(app: R34Application, videos: List<Video>, catalog: List<CatalogEntry>, dismiss: () -> Unit, download: (List<Video>) -> Unit) {
    val byKey = catalog.associateBy { it.video.entryKey }
    val candidates = videos.filter { it.site != SiteRegistry.LOCAL.id && byKey[it.entryKey]?.offline != true && byKey[it.entryKey]?.downloadState !in listOf("正在下载", "已排队", "正在删除") }
    var selected by remember(videos) { mutableStateOf(candidates.map { it.entryKey }.toSet()) }
    val chosen = candidates.filter { it.entryKey in selected }
    val known = chosen.mapNotNull { byKey[it.entryKey]?.remainingBytes }.sum()
    val unknown = chosen.count { byKey[it.entryKey]?.remainingBytes == null }
    val free = remember { app.storage.layout().root.usableSpace }
    AlertDialog(dismiss, title = { Text("准备离线片单") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("${videos.count { byKey[it.entryKey]?.offline == true }} / ${videos.size} 部已就绪")
            Copy("可用空间 ${free / 1024 / 1024} MiB；已知还需 ${known / 1024 / 1024} MiB${if (unknown > 0) "，另 $unknown 项大小待解析" else ""}")
            Copy("使用当前下载网络条件；已排队和已离线的作品不会重复添加。")
            videos.forEach { video ->
                if (video in candidates) CheckRow(video.title, video.entryKey in selected) { selected = if (it) selected + video.entryKey else selected - video.entryKey }
                else Copy("${video.title} · ${byKey[video.entryKey]?.downloadState ?: "未就绪"}")
            }
            if (known > free) Text("已知下载大小超过可用空间，请减少项目")
        }
    }, confirmButton = { TextAction({ dismiss(); download(chosen) }, enabled = chosen.isNotEmpty() && known <= free) { Text("加入下载 · ${chosen.size}") } }, dismissButton = { TextAction(dismiss) { Text("取消") } })
}
