@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import kotlinx.coroutines.*

private data class DownloadSnapshot(
    val videos: List<Pair<Download, DownloadMeta>> = emptyList(),
    val books: List<GalleryDownload> = emptyList(),
    val legacy: List<LocalVideo> = emptyList(),
)

@Composable fun DownloadCenter(app: R34Application, resumed: Boolean, dialogs: ComposeDialogs,
    video: (Video, String?, String?) -> Unit, comic: (Video) -> Unit, discover: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(DownloadSnapshot()) }
    var ready by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var siteFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val galleryRevision by app.galleryDownloads.changes.collectAsState()
    LaunchedEffect(resumed, refresh, galleryRevision) {
        if (resumed) while (isActive) {
            try {
                snapshot = withContext(Dispatchers.IO) { DownloadSnapshot(
                    app.playback.downloads().mapNotNull { task -> app.playback.metadata(task)?.let { task to it } },
                    app.galleryDownloads.tasks(), SiteRegistry.all.flatMap { owner -> app.library.localVideos(owner.id).map { it.copy(site = owner.id) } }) }
                ready = true; message = null
            } catch (e: Exception) { if (e is CancellationException) throw e; ready = true; message = e.message ?: "下载任务读取失败" }
            delay(750)
        }
    }
    fun act(id: String, block: suspend () -> Unit) {
        scope.launch {
            busy = busy + id
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; dialogs.message("下载操作失败", e.message ?: "请稍后重试") }
            finally { busy = busy - id; refresh++ }
        }
    }
    val videos = snapshot.videos.filter { (task, meta) -> task.state != Download.STATE_REMOVING && kind != 2 && (siteFilter == null || meta.site == siteFilter) }
    val books = snapshot.books.filter { kind != 1 && (siteFilter == null || it.entry.site == siteFilter) }
    val legacy = snapshot.legacy.filter { kind != 2 && (siteFilter == null || it.site == siteFilter) }
    val count = videos.size + books.size + legacy.size
    LazyColumn(Modifier.fillMaxSize().testTag("download-center"), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("filters") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Title("下载中心"); Copy("视频与漫画，统一管理 · $count 项") }
                Selector(siteFilter?.let { SiteRegistry.byIdOrDefault(it).name } ?: "全部站点") {
                    val owners = listOf<SiteContext?>(null) + SiteRegistry.all
                    dialogs.choose("下载来源", owners.map { it?.name ?: "全部站点" }, owners.indexOfFirst { it?.id == siteFilter }) { siteFilter = owners[it]?.id }
                }
            }
            Choices(listOf("全部内容", "视频", "漫画"), kind) { kind = it }
        }
        message?.let { text -> item("error") { EmptyState("下载任务读取失败", text) { refresh++ } } }
        if (!ready) item("loading") { Loading("正在读取下载任务") }
        else if (count == 0 && message == null) item("empty") {
            EmptyState("还没有下载内容", "在视频详情选择清晰度下载，或在漫画详情下载整本。原有视频下载会自动显示在这里。", "去发现", discover)
        }
        items(books, key = { "gallery:${it.id}" }) { book ->
            val downloading = book.state in listOf(GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED)
            val completed = book.state == GalleryDownloadState.COMPLETED
            val state = when (book.state) {
                GalleryDownloadState.QUEUED -> "等待下载"
                GalleryDownloadState.DOWNLOADING -> "下载中"
                GalleryDownloadState.PAUSED -> "已暂停"
                GalleryDownloadState.FAILED -> "下载失败"
                GalleryDownloadState.COMPLETED -> "已下载 · 可离线阅读"
                GalleryDownloadState.REMOVING -> "正在删除"
            }
            val pages = if (book.total > 0) "${book.pages} / ${book.total} 页" else "等待获取页数"
            DownloadTile("gallery:${book.id}", book.entry, "漫画", "$state · $pages · ${downloadSize(book.bytes)}",
                if (book.total > 0) book.pages.toFloat() / book.total else null, book.error) {
                Action(if (completed) "离线阅读" else if (downloading) "暂停" else if (book.state == GalleryDownloadState.FAILED) "重试" else "继续",
                    primary = completed, enabled = book.id !in busy && book.state != GalleryDownloadState.REMOVING) {
                    if (completed) comic(book.entry) else act(book.id) { if (downloading) app.galleryDownloads.pause(book.id) else app.galleryDownloads.add(book.entry) }
                }
                TextAction({ dialogs.confirm("删除这本漫画下载？", "删除", "将删除已下载图页，阅读记录和收藏会保留。") { act(book.id) { app.galleryDownloads.remove(book.id) } } }, enabled = book.id !in busy) { Text("删除") }
            }
        }
        items(videos, key = { "video:${it.first.request.id}" }) { (task, meta) ->
            val completed = task.state == Download.STATE_COMPLETED
            val failed = task.state == Download.STATE_FAILED
            val paused = task.state == Download.STATE_STOPPED
            val state = when (task.state) {
                Download.STATE_COMPLETED -> "已下载 · 可离线播放"
                Download.STATE_DOWNLOADING -> "下载中 ${task.percentDownloaded.coerceAtLeast(0f).toInt()}%"
                Download.STATE_STOPPED -> "已暂停"
                Download.STATE_FAILED -> "下载失败"
                else -> "等待下载 / 网络"
            }
            DownloadTile("video:${task.request.id}", meta.video, "视频 · ${meta.quality}", "$state · ${downloadSize(task.bytesDownloaded)}",
                task.percentDownloaded.takeIf { it >= 0 }?.div(100f)) {
                Action(if (completed) "离线播放" else if (paused || failed) "继续" else "暂停", primary = completed, enabled = task.request.id !in busy) {
                    if (completed) video(meta.video, task.request.id, null)
                    else act(task.request.id) {
                        if (failed) {
                            val detail = app.repository.detail(meta.video.url)
                            app.playback.add(meta.video, detail.streams.firstOrNull { it.label == meta.quality } ?: detail.streams.first())
                        } else app.playback.pause(task.request.id, !paused)
                    }
                }
                TextAction({ dialogs.confirm("删除这份视频下载？", "删除", "观看记录和收藏会保留。") { act(task.request.id) { app.playback.remove(task.request.id) } } }) { Text("删除") }
            }
        }
        items(legacy, key = { "legacy:${it.site}:${it.id}" }) { local ->
            val entry = Video(local.id, local.title, app.library.watch(local.id, local.site)?.path.orEmpty(), local.thumb, site = local.site)
            DownloadTile("legacy:${local.site}:${local.id}", entry, "视频 · ${local.quality}", "已下载 · 旧版本离线文件") {
                Action("离线播放", true) { video(entry, null, local.path) }
                TextAction({ dialogs.confirm("删除这份视频下载？", "删除") { act("legacy:${local.site}:${local.id}") { withContext(Dispatchers.IO) { app.library.removeLocal(local.id, local.site) } } } }) { Text("删除") }
            }
        }
    }
}

@Composable private fun DownloadTile(id: String, entry: Video, type: String, status: String, progress: Float? = null,
    error: String = "", actions: @Composable FlowRowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("download-$id").cinemaGlass(12).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SiteImage(entry.thumb, SiteRegistry.byIdOrDefault(entry.site), Modifier.size(62.dp, 84.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${SiteRegistry.byIdOrDefault(entry.site).name} · $type", fontSize = 11.sp, color = MutedColor)
                Text(status, fontSize = 12.sp, color = Color.White)
            }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = BrandColor, trackColor = Color.White.copy(alpha = .15f), gapSize = 0.dp, drawStopIndicator = {})
        if (error.isNotBlank()) Copy(error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

private fun downloadSize(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"
