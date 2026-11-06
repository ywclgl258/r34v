package com.r34v.r34v.ui

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*

@Composable fun QueueNextAction(activity: Activity, video: Video, token: String?, dialogs: ComposeDialogs) {
    val app = activity.application as R34Application
    val revision by app.library.personal.changes.collectAsState()
    var ended by remember { mutableStateOf(false) }
    if (ended) return
    val queue = remember(token, video.entryKey, revision) { app.queue.session(token, video) } ?: return
    val next = queue.next
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Copy("${queue.title} · ${queue.index + 1} / ${queue.entries.size}")
        if (next != null) Action("下一项 · ${next.title}", modifier = Modifier.fillMaxWidth()) {
            val type = if (next.mediaKind == MediaKind.GALLERY) "阅读器" else "播放器"
            dialogs.confirm("打开下一项？", "打开$type", "${next.title}\n从保存的进度继续，当前项将标为完成。") {
                runCatching { MediaRouter.next(activity, video, queue.id) }.onFailure { dialogs.message("无法打开下一项", it.message.orEmpty()) }
            }
        } else Action("片单已到最后 · 标记完成", modifier = Modifier.fillMaxWidth()) { app.library.personal.complete(video, queue.shelf, true) }
        TextAction({ app.queue.end(queue.id); ended = true }) { Text("结束顺序观看") }
    }
}
