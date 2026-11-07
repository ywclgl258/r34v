package com.r34v.r34v.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import java.text.DateFormat
import java.util.Date

@Composable fun SavedSearchUpdatesScreen(app: R34Application, play: (Video) -> Unit, dialogs: ComposeDialogs, modifier: Modifier = Modifier) {
    val owner = LocalContext.current as ComponentActivity
    val model = remember(owner) { ViewModelProvider(owner)[SavedSearchUpdateModel::class.java] }
    val revision by app.library.personal.changes.collectAsState()
    val data = remember(revision) { app.library.personal.snapshot() }
    var selected by rememberSaveable { mutableStateOf("") }
    var unread by rememberSaveable { mutableStateOf(true) }
    val searches = data.savedSearches.filter { selected.isBlank() || it.id == selected }
    LaunchedEffect(data.savedSearches) { if (selected.isNotBlank() && searches.isEmpty()) selected = "" }
    val rows = searches.flatMap { data.searchUpdates[it.id]?.entries.orEmpty() }.filter { !unread || !it.read }
        .sortedByDescending { it.at }.distinctBy { it.video.entryKey }
    if (data.savedSearches.isEmpty()) { Column(modifier) { Copy("还没有保存的搜索方案。可在搜索页设置关键词和来源后保存。") }; return }
    AdaptiveCatalogList(app, rows.map { CatalogEntry(it.video, progress = if (it.read) "已读" else "搜索新结果") }, play, modifier,
        header = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Selector(if (selected.isBlank()) "全部搜索方案 · ${data.savedSearches.size}" else searches.firstOrNull()?.name.orEmpty()) {
            dialogs.choose("选择搜索方案", listOf("全部搜索方案") + data.savedSearches.map { search ->
                "${search.name} · ${data.searchUpdates[search.id]?.entries?.count { !it.read } ?: 0} 未读"
            }) { selected = if (it == 0) "" else data.savedSearches[it - 1].id }
        }
        Choices(listOf("未读", "全部"), if (unread) 0 else 1) { unread = it == 0 }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (model.busy) "取消检查" else "检查搜索更新", modifier = Modifier.weight(1f)) { if (model.busy) model.cancel() else model.check(searches) }
            Action("检查详情", modifier = Modifier.weight(1f)) {
                dialogs.content = {
                    val changed by app.library.personal.changes.collectAsState()
                    val latest = remember(changed) { app.library.personal.snapshot() }
                    AlertDialog(dialogs::dismiss, title = { Text("搜索检查详情") }, text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            searches.forEach { search ->
                                Title(search.name)
                                search.sources.filter { search.category.isBlank() || SiteRegistry.byId(it)?.category?.name == search.category }.forEach { site ->
                                    val box = latest.searchUpdates[search.id]; val check = box?.checks?.get(site)
                                    val status = when {
                                        check == null -> "尚未检查"
                                        check.error.isNotBlank() -> check.error
                                        check.cached -> "仅取得缓存，未更新基线"
                                        else -> "检查成功 · ${box.entries.count { it.video.site == site && !it.read }} 未读"
                                    }
                                    Copy("${SiteRegistry.byIdOrDefault(site).name}：$status")
                                    if (check != null && check.successAt > 0) Copy("上次成功：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(check.successAt))}")
                                }
                            }
                        }
                    }, confirmButton = { TextAction(dialogs::dismiss) { Text("关闭") } })
                }
            }
        }
        if (model.progress.isNotBlank()) Copy(model.progress)
        Copy("检查各来源当前结果的第一页；支持时按最新排序。首次检查建立基线，后续新结果计为未读。")
        if (rows.any { !it.read }) TextAction({ searches.forEach { search -> app.library.personal.markSearchRead(search.id, rows.map { it.video.entryKey }.toSet()) } }) { Text("当前结果全部已读") }
        if (rows.isEmpty()) Copy(if (unread) "暂无未读搜索结果" else "尚无结果，请先检查更新")
        } }, menu = { video -> listOf("标记已读" to { searches.forEach { app.library.personal.markSearchRead(it.id, setOf(video.entryKey)) } }) })
}
