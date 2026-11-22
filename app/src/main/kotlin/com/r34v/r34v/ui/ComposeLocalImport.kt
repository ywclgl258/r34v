package com.r34v.r34v.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.Video
import kotlinx.coroutines.CancellationException

@Composable fun LocalImportDialog(app: R34Application, uri: Uri, directory: Boolean, video: Boolean = false, dismiss: () -> Unit, complete: (Video) -> Unit) {
    var progress by remember { mutableStateOf("正在读取所选内容…") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri, directory, video) { try {
        val video = when {
            video -> app.localLibrary.importVideo(uri) { progress = it }
            directory -> app.localLibrary.importDirectory(uri) { progress = it }
            else -> app.localLibrary.importArchive(uri) { progress = it }
        }
        complete(video)
    } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "导入失败，原文件未更改" } }
    AlertDialog(dismiss, title = { Text(if (video) "导入本地视频" else "导入本地漫画") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(error ?: progress, Modifier.semantics { if (error != null) liveRegion = LiveRegionMode.Polite })
        if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Copy(if (video) "复制应用内副本（上限 8 GiB），并生成首帧缩略图。原始文件保留，重复导入同一文件会自动去重。"
        else "导入应用内副本，单本最多 2000 页 / 1 GiB。原始文件保留。")
    } }, confirmButton = { TextAction(dismiss) { Text(if (error == null) "取消导入" else "关闭") } })
}
