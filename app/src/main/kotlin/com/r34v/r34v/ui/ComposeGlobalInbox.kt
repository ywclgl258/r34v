package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import kotlinx.coroutines.*

@Composable fun GlobalInboxScreen(app: R34Application, play: (Video) -> Unit, dialogs: ComposeDialogs) {
    val personal by app.library.personal.changes.collectAsState()
    var unread by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val entries = remember(personal, unread) { app.library.personal.snapshot().inboxes.values.flatMap { it.entries }.filter { !unread || !it.read }.distinctBy { it.video.entryKey }.sortedByDescending { it.at } }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("订阅更新", large = true)
        Choices(listOf("未读", "全部"), if (unread) 0 else 1) { unread = it == 0 }
        Action(if (busy) "正在检查订阅…" else "检查全部来源", enabled = !busy) {
            busy = true; message = ""
            scope.launch { try {
                var failed = 0; var checked = 0
                // Each site's existing repository bounds requests to three; sites run sequentially.
                SiteRegistry.all.forEach { site ->
                    val tags = app.library.subscriptions(site.id)
                    if (tags.isNotEmpty()) { val result = app.repository.subscriptions(site, app.library.subscriptionOrder(site.id), tags); app.library.personal.refreshInbox(site.id, result.tags); failed += result.failed.size; checked += tags.size }
                }
                message = if (checked == 0) "还没有订阅标签，可在来源内容中订阅感兴趣的标签。" else "已检查 $checked 个订阅${if (failed > 0) "，$failed 项暂不可用，已保留原有记录" else ""}"
            } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message ?: "检查失败，原有更新保留" } finally { busy = false } }
        }
        if (message.isNotBlank()) Copy(message)
        if (entries.isEmpty()) Copy(if (unread) "暂无未读更新" else "暂无订阅更新")
        AdaptiveCatalogList(app, entries.map { CatalogEntry(it.video, progress = if (it.read) "已读" else "新作品") }, play, Modifier.weight(1f), menu = { video -> listOf("标记已读" to { app.library.personal.markRead(video.site, setOf(video.entryKey)) }) })
    }
}
