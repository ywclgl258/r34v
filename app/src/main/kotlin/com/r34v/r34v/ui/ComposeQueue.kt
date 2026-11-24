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
    if (token == null) return
    val app = activity.application as R34Application
    val revision by app.queue.changes.collectAsState()
    val queue = remember(token, video.entryKey, revision) { app.queue.session(token, video) }
    if (queue == null) {
        Copy("顺序观看已结束")
        return
    }
    var advancing by remember(token, video.entryKey) { mutableStateOf(false) }
    val next = queue.next
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Copy("${queue.title} · ${queue.index + 1} / ${queue.entries.size}")
        if (next != null) Action("下一项 · ${next.title}", modifier = Modifier.fillMaxWidth(), enabled = !advancing) {
            val type = if (next.isGalleryWork) "阅读器" else "播放器"
            dialogs.confirm("打开下一项？", "打开$type", "${next.title}\n从保存的进度继续，当前项将标为完成。") {
                advancing = true
                fun failed(error: Throwable) { advancing = false; dialogs.message("无法打开下一项", error.message.orEmpty()) }
                runCatching { MediaRouter.next(activity, video, queue.id, failed = ::failed, finished = { advancing = false }) }
                    .onSuccess { if (!it) advancing = false }.onFailure(::failed)
            }
        } else Action("片单已到最后 · 标记完成", modifier = Modifier.fillMaxWidth()) {
            if (app.queue.session(token, video) == null) dialogs.message("无法标记完成", "片单已结束")
            else app.library.personal.complete(video, queue.shelf, true)
        }
        TextAction({
            runCatching { app.queue.endCurrent(token, video) }
                .onFailure { dialogs.message("无法结束片单", it.message.orEmpty()) }
        }) { Text("结束顺序观看") }
    }
}
