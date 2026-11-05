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
import kotlinx.coroutines.flow.*

private data class DownloadSnapshot(
    val videos: List<Pair<Download, DownloadMeta>> = emptyList(),
    val books: List<GalleryDownload> = emptyList(),
    val legacy: List<LocalVideo> = emptyList(),
    /** Rebuilt from the storage tree; the only list that survives an uninstall. */
    val onDisk: List<LibraryRecord> = emptyList(),
    val root: String = "",
)

@Composable fun DownloadCenter(app: R34Application, resumed: Boolean, dialogs: ComposeDialogs,
    video: (Video, String?, String?) -> Unit, offline: (LibraryRecord) -> Unit, comic: (Video) -> Unit,
    discover: () -> Unit, rescan: suspend () -> Int = { 0 }) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(DownloadSnapshot()) }
    var ready by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var siteFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumed, refresh) {
        if (!resumed) return@LaunchedEffect
        var scanned: List<Any>? = null
        var disk = emptyList<LibraryRecord>()
        val ticks = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(750) } }
        merge(ticks, app.galleryDownloads.changes.map { Unit }, app.index.revision.map { Unit }).conflate().collect {
            try {
                snapshot = withContext(Dispatchers.IO) {
                    val tasks = app.playback.downloads().mapNotNull { task -> app.playback.metadata(task)?.let { task to it } }
                    val books = app.galleryDownloads.tasks()
                    val root = app.storage.describe()
                    fun signature() = listOf(app.index.revision.value, root, app.storage.granted(),
                        tasks.map { it.first.request.id to it.first.state }, books.map { it.id to it.state })
                    val before = signature()
                    if (scanned != before) {
                        val known = tasks.map { (task, meta) ->
                            LibraryRecord(kind = LibraryRecord.KIND_VIDEO, site = meta.site, id = meta.video.id, quality = meta.quality,
                                cacheKey = task.request.id, title = meta.video.title, url = meta.video.url, thumb = meta.video.thumb,
                                duration = meta.video.duration, bytes = task.bytesDownloaded, at = task.startTimeMs,
                                state = if (task.state == Download.STATE_COMPLETED) LibraryRecord.STATE_COMPLETED else LibraryRecord.STATE_PARTIAL)
                        }
                        disk = app.index.scan(app.playback::cached, known)
                        // An invalidation during the scan must still be observed on the next event.
                        scanned = before
                    }
                    val checked = books.map { task ->
                        if (task.state == GalleryDownloadState.COMPLETED && disk.none { it.isComic && it.site == task.entry.site && it.id == task.entry.id && it.state == LibraryRecord.STATE_COMPLETED })
                            task.copy(state = GalleryDownloadState.FAILED, error = "下载图页不完整，请重新下载") else task
                    }
                    val legacy = SiteRegistry.all.flatMap { owner -> app.library.localVideos(owner.id).map { it.copy(site = owner.id) } }
                    DownloadSnapshot(tasks, checked, legacy, disk, root)
                }
                ready = true; message = null
            } catch (e: Exception) { if (e is CancellationException) throw e; ready = true; message = e.message ?: "下载任务读取失败" }
        }
    }
    fun act(id: String, block: suspend () -> Unit) {
        scope.launch {
            busy = busy + id
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; dialogs.message("下载操作失败", e.message ?: "请稍后重试") }
            finally { busy = busy - id; refresh++ }
        }
    }
    // A downloaded item appears once. The in-app task lists describe live work; the disk scan
    // describes what is still openable, including everything left behind by a previous install.
    val galleryIds = snapshot.books.filter { it.state == GalleryDownloadState.COMPLETED }.map { "${it.entry.site}:${it.entry.id}" }.toSet()
    val videoIds = snapshot.videos.filter { it.first.state == Download.STATE_COMPLETED }.map { it.first.request.id }.toSet()
    val allGalleryIds = snapshot.books.map { "${it.entry.site}:${it.entry.id}" }.toSet()
    val allVideoIds = snapshot.videos.map { it.first.request.id }.toSet()
    val restoredComics = snapshot.onDisk.filter { it.isComic &&
        ("${it.site}:${it.id}" !in allGalleryIds || (it.state == LibraryRecord.STATE_COMPLETED && "${it.site}:${it.id}" !in galleryIds)) }
    val restoredVideos = snapshot.onDisk.filter { !it.isComic &&
        (it.cacheKey !in allVideoIds || (it.state == LibraryRecord.STATE_COMPLETED && it.cacheKey !in videoIds)) }
    val videos = snapshot.videos.filter { (task, meta) -> task.state != Download.STATE_REMOVING && kind != 2 && (siteFilter == null || meta.site == siteFilter) }
    val books = snapshot.books.filter { kind != 1 && (siteFilter == null || it.entry.site == siteFilter) }
    val legacy = snapshot.legacy.filter { kind != 2 && (siteFilter == null || it.site == siteFilter) }
    val diskComics = restoredComics.filter { kind != 1 && (siteFilter == null || it.site == siteFilter) }
    val diskVideos = restoredVideos.filter { kind != 2 && (siteFilter == null || it.site == siteFilter) }
    val count = videos.size + books.size + legacy.size + diskComics.size + diskVideos.size
    LazyColumn(Modifier.fillMaxSize().testTag("download-center"), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("filters") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Title("下载中心"); Copy("视频与图集，统一管理 · $count 项") }
                Selector(siteFilter?.let { SiteRegistry.byIdOrDefault(it).name } ?: "全部站点") {
                    val owners = listOf<SiteContext?>(null) + SiteRegistry.all
                    dialogs.choose("下载来源", owners.map { it?.name ?: "全部站点" }, owners.indexOfFirst { it?.id == siteFilter }) { siteFilter = owners[it]?.id }
                }
            }
            Choices(listOf("全部内容", "视频", "图集"), kind) { kind = it }
            if (count == 0 || diskComics.isNotEmpty() || diskVideos.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Copy(snapshot.root, Modifier.weight(1f))
                TextAction({ act("rescan") { scanNote = "已重新扫描：${rescan()} 项" } }, enabled = "rescan" !in busy) { Text("重新扫描") }
            }
            scanNote?.let { Copy(it) }
        }
        message?.let { text -> item("error") { EmptyState("下载任务读取失败", text) { refresh++ } } }
        if (!ready) item("loading") { Loading("正在读取下载任务") }
        else if (count == 0 && message == null) item("empty") {
            EmptyState("还没有下载内容", "在视频详情选择清晰度下载，或在漫画/写真详情下载整本。原有视频下载会自动显示在这里。", "去发现", discover)
        }
        items(books, key = { "gallery:${it.id}" }) { book ->
            val downloading = book.state in listOf(GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED)
            val completed = book.state == GalleryDownloadState.COMPLETED
            val state = when (book.state) {
                GalleryDownloadState.QUEUED -> "等待下载"
                GalleryDownloadState.DOWNLOADING -> "下载中"
                GalleryDownloadState.PAUSED -> "已暂停"
                GalleryDownloadState.FAILED -> "下载失败"
                GalleryDownloadState.COMPLETED -> "已下载 · 可离线打开"
                GalleryDownloadState.REMOVING -> "正在删除"
            }
            val pages = if (book.total > 0) "${book.pages} / ${book.total} 页" else "等待获取页数"
            DownloadTile("gallery:${book.id}", book.entry, SiteRegistry.byIdOrDefault(book.entry.site).category.itemNoun, "$state · $pages · ${downloadSize(book.bytes)}",
                if (book.total > 0) book.pages.toFloat() / book.total else null, book.error) {
                Action(if (completed) "离线打开" else if (downloading) "暂停" else if (book.state == GalleryDownloadState.FAILED) "重试" else "继续",
                    primary = completed, enabled = book.id !in busy && book.state != GalleryDownloadState.REMOVING) {
                    if (completed) comic(book.entry) else act(book.id) { if (downloading) app.galleryDownloads.pause(book.id) else app.galleryDownloads.add(book.entry) }
                }
                TextAction({ dialogs.confirm("删除这份下载？", "删除", "将删除已下载图页，记录和收藏会保留。") { act(book.id) { app.galleryDownloads.remove(book.id) } } }, enabled = book.id !in busy) { Text("删除") }
            }
        }
        items(diskComics, key = { "disk-comic:${it.site}:${it.id}" }) { record ->
            val available = record.state == LibraryRecord.STATE_COMPLETED
            DownloadTile("gallery:${record.site}:${record.id}", record.video, "${SiteRegistry.byIdOrDefault(record.site).category.itemNoun} · ${if (record.sourceRoot == app.filesDir.absolutePath) "应用内" else "公共目录"}",
                (if (available) "可离线打开" else record.detail.ifBlank { "图页不完整，可重新下载" }) + " · ${downloadSize(record.bytes)}") {
                if (available) {
                    Action("离线打开", true) { offline(record) }
                    TextAction({ dialogs.confirm("删除这份下载？", "删除", "将删除已下载图页。") { act("disk:${record.site}:${record.id}") {
                        val book = app.galleryDownloads.task(record.video)
                        if (book != null) app.galleryDownloads.remove(book.id) else withContext(Dispatchers.IO) {
                            app.galleries.removeDownload(record.video); app.index.forgetComic(record.site, record.id)
                        }
                    } } }) { Text("删除") }
                } else Action("重新下载") { video(record.video.copy(mediaKind = MediaKind.GALLERY), null, null) }
            }
        }
        items(videos, key = { "video:${it.first.request.id}" }) { (task, meta) ->
            val intact = snapshot.onDisk.any { it.cacheKey == task.request.id && it.state == LibraryRecord.STATE_COMPLETED }
            val completed = task.state == Download.STATE_COMPLETED && intact
            val failed = task.state == Download.STATE_FAILED || (task.state == Download.STATE_COMPLETED && !intact)
            val paused = task.state == Download.STATE_STOPPED
            val state = when (task.state) {
                Download.STATE_COMPLETED -> if (intact) "已下载 · 可离线播放" else "缓存不完整，请重新下载"
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
        items(diskVideos, key = { "disk-video:${it.site}:${it.id}:${it.quality}" }) { record ->
            val available = record.state == LibraryRecord.STATE_COMPLETED
            DownloadTile("disk-video:${record.site}:${record.id}:${record.quality}", record.video,
                "视频 · ${record.quality} · ${if (record.sourceRoot == app.filesDir.absolutePath) "应用内" else "公共目录"}",
                (if (available) "可离线播放" else "缓存已缺失，需重新下载") + " · ${downloadSize(record.bytes)}") {
                if (available) Action("离线播放", true) { offline(record) }
                else Action("删除索引") { act("disk:${record.site}:${record.id}") { withContext(Dispatchers.IO) { app.index.removeVideo(record.site, record.id, record.quality) } } }
                if (available) TextAction({ dialogs.confirm("删除这份视频下载？", "删除", "将删除所有下载位置中的这份视频，观看记录和收藏会保留。") {
                    act("disk:${record.site}:${record.id}") { app.playback.removeRestored(record) }
                } }) { Text("删除") }
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
    Column(Modifier.fillMaxWidth().testTag("download-$id").cinemaGlass(16).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SiteImage(entry.thumb, SiteRegistry.byIdOrDefault(entry.site), Modifier.size(62.dp, 84.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title.ifBlank { "未命名内容" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
