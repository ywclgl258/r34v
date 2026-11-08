package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r34v.r34v.data.*

@Composable fun ShelfSettingsDialog(store: PersonalStore, shelf: Shelf, dismiss: () -> Unit, delete: () -> Unit) {
    var name by remember(shelf.id) { mutableStateOf(shelf.name) }
    var note by remember(shelf.id) { mutableStateOf(shelf.note) }
    var pinned by remember(shelf.id) { mutableStateOf(shelf.pinned) }
    var cover by remember(shelf.id) { mutableStateOf(shelf.coverKey) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    if (picking) {
        AlertDialog({ picking = false }, title = { Text("选择合集封面") }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CheckRow("自动使用合集作品", cover.isEmpty()) { cover = ""; picking = false } }
                items(shelf.entries, key = { it.video.entryKey }) { entry ->
                    val video = entry.video
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(cover == video.entryKey, role = Role.RadioButton) {
                        cover = video.entryKey; picking = false
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SiteImage(video.thumb, SiteRegistry.byIdOrDefault(video.site), Modifier.width(44.dp).height(60.dp), true)
                        Column(Modifier.weight(1f)) {
                            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Copy(SiteRegistry.byIdOrDefault(video.site).name)
                        }
                        RadioButton(cover == video.entryKey, null)
                    }
                }
            }
        }, confirmButton = { TextAction({ picking = false }) { Text("返回设置") } })
    } else AlertDialog(dismiss, title = { Text("合集设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (shelf.id != PersonalLibrary.WATCH_LATER) TextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(),
                label = { Text("合集名称") }, singleLine = true, colors = filledFieldColors())
            else Title(shelf.name)
            TextField(note, { note = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("合集备注") },
                minLines = 2, maxLines = 5, supportingText = { Text("${note.length} / 1000") }, colors = filledFieldColors())
            CheckRow("置顶到片库", pinned) { pinned = it }
            Copy("当前封面：${shelf.entries.firstOrNull { it.video.entryKey == cover }?.video?.title ?: "自动使用合集作品"}")
            TextAction({ picking = true }) { Text("选择封面") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextAction({
            runCatching { store.updateShelf(shelf.id, name, note, pinned, cover) }
                .onSuccess { dismiss() }.onFailure { error = it.message ?: "保存失败，请重试" }
        }, enabled = name.isNotBlank()) { Text("保存合集") }
    }, dismissButton = {
        Row {
            if (shelf.id != PersonalLibrary.WATCH_LATER) TextAction(delete) { Text("删除合集") }
            TextAction(dismiss) { Text("取消") }
        }
    })
}
