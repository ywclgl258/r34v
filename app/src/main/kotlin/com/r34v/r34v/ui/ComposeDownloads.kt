@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import com.r34v.r34v.downloads.failedRemoval
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private data class DownloadSnapshot(
    val videos: List<Pair<Download, DownloadMeta>> = emptyList(),
    val books: List<GalleryDownload> = emptyList(),
    val legacy: List<LegacyDownload> = emptyList(),
    /** Rebuilt from the storage tree; the only list that survives an uninstall. */
    val onDisk: List<LibraryRecord> = emptyList(),
    val root: String = "",
    val failedBookIds: Set<String> = emptySet(),
    val damagedBookIds: Set<String> = emptySet(),
)

private const val DamagedDownload = "离线文件不完整"
private val LibraryRecord.offlineReady get() = state == LibraryRecord.STATE_COMPLETED || (isComic && intactSelection.isNotEmpty())
private data class VideoTransferProgress(val bytes: Long, val percent: Float)
private data class LegacyDownload(val video: LocalVideo, val modifiedAt: Long, val bytes: Long)
private data class DownloadRow(
    val key: String, val title: String, val addedAt: Long, val bytes: Long, val completion: Float,
    val render: @Composable () -> Unit,
)

private enum class DownloadStatus(val label: String) {
    ALL("全部状态"), ACTIVE("进行中"), COMPLETE("已完成"), ATTENTION("需处理"),
}

private data class FilteredDownloads(
    val videos: List<Pair<Download, DownloadMeta>>,
    val books: List<GalleryDownload>,
    val legacy: List<LegacyDownload>,
    val diskComics: List<LibraryRecord>,
    val diskVideos: List<LibraryRecord>,
    val counts: IntArray,
)

private fun downloadSiteName(id: String) = SiteRegistry.byId(id)?.name ?: SiteRegistry.retiredVideoHosts[id] ?: id

private fun galleryCategory(site: String) = when (SiteRegistry.byId(site)?.category) {
    SiteCategory.VIDEO, SiteCategory.PHOTO -> SiteCategory.PHOTO
    else -> SiteCategory.COMIC
}

@Composable fun DownloadCenter(app: R34Application, resumed: Boolean, dialogs: ComposeDialogs,
    video: (Video, String?, String?) -> Unit, offline: (LibraryRecord) -> Unit, comic: (Video) -> Unit,
    discover: () -> Unit, account: (String) -> Unit = {}, rescan: suspend () -> Int = { 0 },
    lockedSite: String? = null) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(DownloadSnapshot()) }
    var videoProgress by remember { mutableStateOf<Map<String, VideoTransferProgress>>(emptyMap()) }
    var ready by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var kind by rememberSaveable(lockedSite) { mutableIntStateOf(0) }
    var siteFilter by rememberSaveable(lockedSite) { mutableStateOf<String?>(null) }
    var statusFilter by rememberSaveable(lockedSite) { mutableIntStateOf(0) }
    var groupFilter by rememberSaveable(lockedSite) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(lockedSite) { mutableStateOf("") }
    var sort by rememberSaveable(lockedSite) { mutableIntStateOf(0) }
    var showFilters by rememberSaveable(lockedSite) { mutableStateOf(false) }
    var scanNote by remember(lockedSite) { mutableStateOf<String?>(null) }
    val policyRevision by app.downloadPolicy.revision.collectAsState()
    val onlyUnmetered = remember(policyRevision) { app.downloadPolicy.unmeteredOnly }
    val networkAllowed = remember(policyRevision) { app.downloadPolicy.allowed }
    LaunchedEffect(resumed, refresh) {
        if (!resumed) return@LaunchedEffect
        var scanned: List<Any>? = null
        var disk = emptyList<LibraryRecord>()
        val changes = combine(app.playback.downloadChanges, app.galleryDownloads.changes, app.index.revision) { _, _, _ -> Unit }
        changes.conflate().collect {
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
                    val savedBooks = disk.filter { it.isComic }.associateBy { it.site to it.id }
                    val damagedBooks = books.filter { book ->
                        val record = savedBooks[book.entry.site to book.entry.id]
                        book.state == GalleryDownloadState.COMPLETED && record?.state != LibraryRecord.STATE_COMPLETED &&
                            (!book.partial || record?.intactSelection != book.selectedPages)
                    }.mapTo(mutableSetOf()) { it.id }
                    val legacy = (SiteRegistry.all.map { it.id } + SiteRegistry.retiredVideoHosts.keys).flatMap { owner ->
                        app.library.localVideos(owner).map { item ->
                            val file = java.io.File(item.path)
                            LegacyDownload(item.copy(site = owner), file.lastModified(), file.length())
                        }
                    }
                    DownloadSnapshot(tasks, books, legacy, disk, root,
                        books.filter { it.state == GalleryDownloadState.FAILED && !it.failedRemoval }.mapTo(mutableSetOf()) { it.id },
                        damagedBookIds = damagedBooks)
                }
                ready = true; message = null
            } catch (e: Exception) { if (e is CancellationException) throw e; ready = true; message = e.message ?: "下载任务读取失败" }
        }
    }
    val transferringIds = remember(snapshot.videos, lockedSite) {
        snapshot.videos.asSequence().filter { (task, meta) ->
            task.state == Download.STATE_DOWNLOADING && (lockedSite == null || meta.site == lockedSite)
        }.map { (task, _) -> task.request.id }.toSet()
    }
    LaunchedEffect(resumed, transferringIds) {
        if (!resumed || transferringIds.isEmpty()) {
            videoProgress = emptyMap()
            return@LaunchedEffect
        }
        // Media3 updates live progress in memory without sending a state-change callback.
        while (isActive) {
            videoProgress = app.playback.manager.currentDownloads.asSequence()
                .filter { it.state == Download.STATE_DOWNLOADING && it.request.id in transferringIds }
                .associate { it.request.id to VideoTransferProgress(it.bytesDownloaded, it.percentDownloaded) }
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
    // Keep the existing distinction between a live task and a separately openable disk copy.
    val (restoredComics, restoredVideos) = remember(snapshot) {
        val galleryStates = snapshot.books.associate { (it.entry.site to it.entry.id) to it.state }
        val videoStates = snapshot.videos.associate { (task, _) -> task.request.id to task.state }
        snapshot.onDisk.filter { record ->
            if (record.isComic) {
                val state = galleryStates[record.site to record.id]
                state == null || (record.state == LibraryRecord.STATE_COMPLETED && state != GalleryDownloadState.COMPLETED)
            } else {
                val state = videoStates[record.cacheKey]
                state == null || (record.state == LibraryRecord.STATE_COMPLETED && state != Download.STATE_COMPLETED)
            }
        }.partition { it.isComic }
    }
    val videoTasks = remember(snapshot.videos) { snapshot.videos.filter { it.first.state != Download.STATE_REMOVING } }
    val siteChoices = remember(snapshot, lockedSite) {
        if (lockedSite != null) return@remember emptyList<String?>()
        val seen = buildSet {
            videoTasks.forEach { add(it.second.site) }
            snapshot.books.forEach { add(it.entry.site) }
            snapshot.legacy.forEach { add(it.video.site) }
            restoredComics.forEach { add(it.site) }
            restoredVideos.forEach { add(it.site) }
        }
        val known = SiteRegistry.librarySources.map { it.id }.filter { it in seen }
        listOf<String?>(null) + known + (seen - known.toSet())
    }
    val intactVideoKeys = remember(snapshot.onDisk) {
        snapshot.onDisk.filter { !it.isComic && it.state == LibraryRecord.STATE_COMPLETED }.mapTo(mutableSetOf()) { it.cacheKey }
    }
    fun damagedVideo(task: Download) = task.state == Download.STATE_COMPLETED && task.request.id !in intactVideoKeys
    fun transferProgress(task: Download): VideoTransferProgress? =
        if (task.state == Download.STATE_DOWNLOADING) videoProgress[task.request.id] else null
    val kinds = listOf("全部内容") + SiteCategory.entries.map { it.title }
    val category = SiteCategory.entries.getOrNull(kind - 1)
    val selectedSite = lockedSite ?: siteFilter
    val groups = remember(snapshot.books, lockedSite) {
        snapshot.books.filter { lockedSite == null || it.entry.site == lockedSite }.mapNotNull { it.group }.distinct()
    }
    val filtered = remember(snapshot, category, selectedSite, groupFilter, query, statusFilter) {
        val term = query.trim()
        val counts = IntArray(DownloadStatus.entries.size)
        fun matches(title: String, site: String, type: SiteCategory, group: String = "") =
            (selectedSite == null || selectedSite == site) && (category == null || category == type) &&
                (term.isEmpty() || title.contains(term, true) || site.contains(term, true) ||
                    downloadSiteName(site).contains(term, true) || group.contains(term, true))
        // Count over the same matches before applying the selected status tab.
        fun visible(status: DownloadStatus): Boolean {
            counts[DownloadStatus.ALL.ordinal]++
            if (status != DownloadStatus.ALL) counts[status.ordinal]++
            return statusFilter == DownloadStatus.ALL.ordinal || statusFilter == status.ordinal
        }
        fun bookStatus(task: GalleryDownload) = when (task.state) {
            GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING, GalleryDownloadState.RETRY_WAIT -> DownloadStatus.ACTIVE
            GalleryDownloadState.PAUSED, GalleryDownloadState.FAILED -> DownloadStatus.ATTENTION
            GalleryDownloadState.COMPLETED -> if (task.id in snapshot.damagedBookIds) DownloadStatus.ATTENTION else DownloadStatus.COMPLETE
            GalleryDownloadState.REMOVING -> DownloadStatus.ALL
        }
        fun videoStatus(task: Download) = when (task.state) {
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> DownloadStatus.ACTIVE
            Download.STATE_STOPPED, Download.STATE_FAILED -> DownloadStatus.ATTENTION
            Download.STATE_COMPLETED -> if (damagedVideo(task)) DownloadStatus.ATTENTION else DownloadStatus.COMPLETE
            else -> DownloadStatus.ALL
        }
        fun diskStatus(record: LibraryRecord) = if (record.offlineReady) DownloadStatus.COMPLETE else DownloadStatus.ATTENTION
        FilteredDownloads(
            videos = videoTasks.filter { (task, meta) ->
                matches(meta.video.title, meta.site, SiteCategory.VIDEO) && visible(videoStatus(task))
            },
            books = snapshot.books.filter {
                (groupFilter == null || it.group == groupFilter) &&
                    matches(it.entry.title, it.entry.site, galleryCategory(it.entry.site), it.group.orEmpty()) && visible(bookStatus(it))
            },
            legacy = snapshot.legacy.filter {
                matches(it.video.title, it.video.site, SiteCategory.VIDEO) && visible(DownloadStatus.COMPLETE)
            },
            diskComics = restoredComics.filter {
                matches(it.title, it.site, galleryCategory(it.site)) && visible(diskStatus(it))
            },
            diskVideos = restoredVideos.filter {
                matches(it.title, it.site, SiteCategory.VIDEO) && visible(diskStatus(it))
            },
            counts = counts,
        )
    }
    val videos = filtered.videos.sortedByDescending { it.first.startTimeMs }
    val books = filtered.books.sortedWith(compareByDescending<GalleryDownload> { it.priority }.thenBy { it.addedAt })
    val count = filtered.counts[statusFilter]
    val sortLabels = listOf("队列优先", "最近添加", "体积", "完成度", "标题")
    val conditions = listOfNotNull(
        kinds[kind].takeIf { kind != 0 },
        siteFilter?.takeIf { lockedSite == null }?.let(::downloadSiteName),
        groupFilter?.takeIf { category != SiteCategory.VIDEO }?.let { "图集分组 · $it" },
        sortLabels[sort].takeIf { sort != 0 },
    )
    val filtersActive = conditions.isNotEmpty() || statusFilter != 0 || query.isNotBlank()
    val showClearFilters = conditions.isNotEmpty() || (lockedSite != null && filtersActive)
    fun clearFilters() {
        kind = 0
        statusFilter = 0
        siteFilter = null
        groupFilter = null
        query = ""
        sort = 0
    }
    val failedVideos = videos.filter { it.first.state == Download.STATE_FAILED }
    val failedBooks = books.filter { it.id in snapshot.failedBookIds }
    val failedCount = failedVideos.size + failedBooks.size
    val retrying = "retry-failed" in busy
    val batchChoices = videos.filter { it.first.state != Download.STATE_COMPLETED }.map { "v:${it.first.request.id}" to it.second.video.title } +
        books.filter { !it.failedRemoval && it.state !in listOf(GalleryDownloadState.COMPLETED, GalleryDownloadState.REMOVING) }
            .map { "g:${it.id}" to it.entry.title }
    LazyColumn(Modifier.fillMaxSize().testTag("download-center"), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("filters") {
            lockedSite?.let { Copy(downloadSiteName(it), Modifier.padding(bottom = 6.dp)) }
            DownloadStatusTabs(if (ready) filtered.counts else null, statusFilter) { statusFilter = it }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    query, { query = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("搜索标题、来源或分组") },
                    shape = ArtworkShape, colors = rentalSearchFieldColors(),
                    leadingIcon = { Glyph(R.drawable.ic_search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconAction(R.drawable.ic_close, "清空下载搜索") { query = "" } },
                )
                TextAction({ showFilters = !showFilters }) {
                    Text(if (conditions.isEmpty()) "筛选" else "筛选 · ${conditions.size}")
                    Spacer(Modifier.width(4.dp))
                    Glyph(R.drawable.ic_chevron_down, null, Modifier.size(14.dp).rotate(if (showFilters) 180f else 0f))
                }
            }
            AnimatedVisibility(showFilters) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Selector(kinds[kind]) {
                        dialogs.choose("下载类型", kinds, kind) { kind = it }
                    }
                    if (lockedSite == null) {
                        Selector(siteFilter?.let(::downloadSiteName) ?: "全部站点") {
                            dialogs.choose("下载来源", siteChoices.map { it?.let(::downloadSiteName) ?: "全部站点" }, siteChoices.indexOf(siteFilter)) {
                                siteFilter = siteChoices[it]
                            }
                        }
                    }
                    Selector("排序 · ${sortLabels[sort]}") { dialogs.choose("下载排序", sortLabels, sort) { sort = it } }
                    if (category != SiteCategory.VIDEO && (groups.isNotEmpty() || groupFilter != null)) Selector(groupFilter ?: "全部图集分组") {
                        dialogs.choose("图集分组", listOf("全部图集分组") + groups, (groups.indexOf(groupFilter) + 1).coerceAtLeast(0)) {
                            groupFilter = if (it == 0) null else groups[it - 1]
                        }
                    }
                }
            }
            if (showClearFilters) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conditions.isEmpty()) Spacer(Modifier.weight(1f))
                    else Copy(conditions.joinToString(" · "), Modifier.weight(1f))
                    TextAction(::clearFilters) { Text("清空筛选") }
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (count > 0) Copy("$count 项下载", Modifier.weight(1f))
                if (lockedSite == null || batchChoices.isNotEmpty()) DownloadMenu("下载管理", R.drawable.ic_chevron_down) {
                    if (lockedSite == null) DownloadMenuItem("网络条件 · ${if (onlyUnmetered) "仅非计费网络" else "任意网络"}") {
                        dialogs.choose("下载网络条件", listOf("任意网络", "仅非计费网络"), if (onlyUnmetered) 1 else 0) {
                            app.downloadPolicy.setUnmetered(it == 1)
                        }
                    }
                    if (batchChoices.isNotEmpty()) DownloadMenuItem("批量管理", enabled = !retrying) {
                        val all = batchChoices.map { it.first }.toSet()
                        dialogs.content = {
                            var selected by remember { mutableStateOf(all) }
                            AlertDialog(dialogs::dismiss, title = { Text("批量管理 · ${selected.size} 项") }, text = {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    TextAction({ selected = if (selected == all) emptySet() else all }) { Text("全选 / 清空") }
                                    batchChoices.forEach { (id, title) ->
                                        CheckRow(title, id in selected) { selected = if (it) selected + id else selected - id }
                                    }
                                }
                            }, confirmButton = {
                                FlowRow {
                                    listOf(true to "暂停所选", false to "继续所选").forEach { (pause, label) ->
                                        TextAction({
                                            dialogs.dismiss()
                                            act("batch") {
                                                for (key in selected) {
                                                    if (key.startsWith("v:")) app.playback.pause(key.removePrefix("v:"), pause)
                                                    else if (pause) app.galleryDownloads.pause(key.removePrefix("g:"))
                                                    else books.firstOrNull { it.id == key.removePrefix("g:") }
                                                        ?.let { app.galleryDownloads.add(it.entry, it.selectedPages) }
                                                }
                                            }
                                        }, enabled = selected.isNotEmpty()) { Text(label) }
                                    }
                                }
                            }, dismissButton = { TextAction(dialogs::dismiss) { Text("取消") } })
                        }
                    }
                }
                if (failedCount > 0 || retrying) TextAction({
                    dialogs.confirm("重试当前列表中的 $failedCount 项失败下载？", "重试失败项",
                        "视频沿用原清晰度，图集保留原选页和已保存的图页。") {
                        act("retry-failed") {
                            val errors = mutableListOf<String>()
                            var accepted = 0
                            suspend fun retry(title: String, enqueue: suspend () -> Boolean) {
                                try {
                                    if (enqueue()) accepted++
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errors += "$title · ${e.message ?: "无法重试"}"
                                }
                            }
                            for ((task, meta) in failedVideos) {
                                retry("${meta.video.title} · ${meta.quality}") { app.playback.retryFailed(task.request.id) }
                            }
                            for (book in failedBooks) retry(book.entry.title) { app.galleryDownloads.retryFailed(book.id) }
                            val summary = "已提交 $accepted 项重试"
                            val skipped = failedCount - accepted - errors.size
                            val results = listOfNotNull(summary, "$skipped 项状态已变化".takeIf { skipped > 0 },
                                errors.joinToString("\n").takeIf { errors.isNotEmpty() })
                            dialogs.message("重试结果", results.joinToString("\n"))
                        }
                    }
                }, enabled = busy.isEmpty()) { Text(if (retrying) "正在重试" else "重试失败项 · $failedCount") }
            }
            if (!networkAllowed && (lockedSite == null || filtered.counts[DownloadStatus.ACTIVE.ordinal] > 0)) {
                Copy("正在等待${if (onlyUnmetered) "非计费网络" else "网络连接"}，手动暂停的任务会保持暂停。")
            }
            if (lockedSite == null) {
                val batch by app.batchDownloads.progress.collectAsState()
                if (batch.total > 0) {
                    Copy("批量添加${if (batch.running) "中" else "已结束"}：${batch.processed} / ${batch.total}，已加入 ${batch.added} 项")
                    if (batch.running) TextAction(app.batchDownloads::cancel) { Text("停止添加剩余作品") }
                    if (batch.errors.isNotEmpty()) TextAction({ dialogs.message("${batch.errors.size} 项添加失败", batch.errors.joinToString("\n")) }) { Text("查看失败项") }
                }
            }
        }
        message?.let { text -> item("error") { EmptyState("下载任务读取失败", text) { refresh++ } } }
        if (!ready) item("loading") { Loading(title = "正在读取下载任务") }
        else if (count == 0 && message == null) item("empty") {
            Column(Modifier.fillMaxWidth().artworkPanel().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (filtersActive) {
                    Title("没有符合条件的下载", large = true)
                    Copy("试试其他关键词，或清空筛选条件。")
                    if (!showClearFilters) Action("清空筛选", primary = true, click = ::clearFilters)
                } else {
                    Glyph(R.drawable.ic_download, null, Modifier.size(24.dp))
                    Title("还没有下载内容", large = true)
                    Copy("在视频详情选择清晰度，或在漫画和写真详情下载整本。")
                    Action("去发现", primary = true, icon = R.drawable.ic_explore, click = discover)
                }
            }
        }
        val rows = mutableListOf<DownloadRow>()
        books.forEach { book ->
            val total = book.selectedPages?.size ?: book.total
            val progress = if (total > 0) book.pages.toFloat() / total else 0f
            rows += DownloadRow("gallery:${book.id}", book.entry.title, book.addedAt, book.bytes, progress) {
                val downloading = book.state in listOf(GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED)
                val damaged = book.id in snapshot.damagedBookIds
                val completed = book.state == GalleryDownloadState.COMPLETED && !damaged
                var expanded by remember(book.id) { mutableStateOf(false) }
                val state = when (book.state) {
                    GalleryDownloadState.QUEUED -> "等待下载"
                    GalleryDownloadState.DOWNLOADING -> "下载中"
                    GalleryDownloadState.PAUSED -> "已暂停"
                    GalleryDownloadState.RETRY_WAIT -> "等待限流"
                    GalleryDownloadState.FAILED -> if (book.failedRemoval) "删除失败" else "下载失败"
                    GalleryDownloadState.COMPLETED -> when {
                        damaged -> DamagedDownload
                        book.partial -> "部分已保存"
                        else -> "已完成"
                    }
                    GalleryDownloadState.REMOVING -> "正在删除"
                }
                val targetPages = book.selectedPages?.size ?: book.total
                val pages = when {
                    completed -> "${book.pages} 页"
                    targetPages > 0 -> "${book.pages} / $targetPages 页"
                    else -> null
                }
                val status = if (damaged) state else listOfNotNull(state, pages, downloadSize(book.bytes)).joinToString(" · ")
                fun openSavedBook() {
                    val saved = snapshot.onDisk.firstOrNull { it.isComic && it.site == book.entry.site && it.id == book.entry.id }
                    if (saved != null) offline(saved) else comic(book.entry)
                }
                fun confirmRemoval() {
                    dialogs.confirm("删除这份下载？", "删除", "将删除已下载图页，记录和收藏会保留。") {
                        act(book.id) { app.galleryDownloads.remove(book.id) }
                    }
                }
                DownloadTile("gallery:${book.id}", book.entry, galleryCategory(book.entry.site).itemNoun, status,
                    if (!completed && !damaged && targetPages > 0) book.pages.toFloat() / targetPages else null, book.error,
                    onClick = ::openSavedBook.takeIf { completed && !book.partial },
                    menu = {
                        DownloadMenuItem(if (expanded) "收起详情" else "任务详情") { expanded = !expanded }
                        if (!book.failedRemoval && book.state != GalleryDownloadState.REMOVING) {
                            DownloadMenuItem("删除", enabled = book.id !in busy, selected = ::confirmRemoval)
                        }
                    },
                    details = {
                        if (expanded) {
                            Copy("当前阶段：${downloadStage(book.stage)} · ${downloadFailure(book.failure)}")
                            if (book.savedPages.isNotEmpty()) Copy("已提交页：${book.savedPages.sorted().joinToString("、")}")
                            if (book.retryAt > 0) Copy("下次可重试：${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", java.util.Date(book.retryAt))}")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (book.failure == GalleryFailureKind.AUTH) TextAction({ account(book.entry.site) }) { Text("重新登录 / 检查权限") }
                                if (book.failure == GalleryFailureKind.NETWORK || book.failure == GalleryFailureKind.RATE_LIMIT) TextAction({
                                    act("diagnose:${book.id}") { dialogs.message("${book.entry.site} 网络诊断", app.network.diagnose(SiteRegistry.byIdOrDefault(book.entry.site))) }
                                }, enabled = "diagnose:${book.id}" !in busy) { Text("检查网络") }
                                if (book.failure == GalleryFailureKind.STORAGE) TextAction({
                                    act("storage:${book.id}") {
                                        val check = runCatching { app.galleries.checkDownloadsAllowed(); "当前目录可写" }.getOrElse { it.message.orEmpty() }
                                        dialogs.message("存储检查", "${app.storage.describe()}\n$check")
                                    }
                                }) { Text("检查存储") }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (lockedSite == null) {
                                    TextAction({ app.galleryDownloads.move(book.id, -1); refresh++ }) { Text("上移") }
                                    TextAction({ app.galleryDownloads.move(book.id, 1); refresh++ }) { Text("下移") }
                                    TextAction({ app.galleryDownloads.prioritize(book.id); refresh++ }) { Text("置顶") }
                                }
                                TextAction({ dialogs.edit("下载分组", book.group.orEmpty(), "留空表示未分组") { value -> app.galleryDownloads.setGroup(book.id, value); refresh++ } }) { Text("分组") }
                                TextAction({
                                    act("inspect:${book.id}") {
                                        val audit = app.galleryDownloads.inspect(book.id)
                                        val damaged = audit.missingPages + audit.corruptPages
                                        val report = "有效 ${audit.validPages.size} / ${audit.total} 页 · ${downloadSize(audit.bytes)}\n缺失：${audit.missingPages.joinToString("、").ifBlank { "无" }}\n损坏：${audit.corruptPages.joinToString("、").ifBlank { "无" }}"
                                        if (damaged.isEmpty()) dialogs.message("完整性检查", report) else dialogs.confirm("需要修复 ${damaged.size} 页", "只修复这些页", report) {
                                            act("repair:${book.id}") { app.galleryDownloads.repair(book.id) }
                                        }
                                    }
                                }, enabled = book.id !in busy && !downloading) { Text("检查完整性") }
                            }
                            if (downloading) Copy("优先级调整已保存，当前任务完成后按新顺序执行。")
                        }
                    },
                    actions = when {
                        book.failedRemoval -> ({
                            Action("重试删除", primary = true, enabled = book.id !in busy, click = ::confirmRemoval)
                        })
                        damaged -> ({
                            Action("重新下载", primary = true, enabled = !retrying && book.id !in busy) {
                                act(book.id) { app.galleryDownloads.add(book.entry, book.selectedPages, recheckSavedPages = true) }
                            }
                        })
                        completed && book.partial -> ({
                            Action("查看已存页", primary = true, click = ::openSavedBook)
                            TextAction({ act(book.id) { app.galleryDownloads.add(book.entry) } }) { Text("继续保存") }
                        })
                        completed || book.state == GalleryDownloadState.REMOVING -> null
                        else -> ({
                            Action(if (downloading) "暂停" else if (book.state == GalleryDownloadState.FAILED) "重试" else "继续",
                                primary = !downloading, enabled = !retrying && book.id !in busy) {
                                act(book.id) {
                                    if (downloading) app.galleryDownloads.pause(book.id)
                                    else if (book.id in snapshot.failedBookIds) app.galleryDownloads.retryFailed(book.id)
                                    else app.galleryDownloads.add(book.entry, book.selectedPages)
                                }
                            }
                        })
                    })
            }
        }
        filtered.diskComics.forEach { record ->
            rows += DownloadRow("disk-comic:${record.site}:${record.id}", record.title, record.at, record.bytes,
                if (record.state == LibraryRecord.STATE_COMPLETED) 1f else 0f) {
                val available = record.offlineReady
                val state = when {
                    record.state == LibraryRecord.STATE_COMPLETED -> "已完成"
                    available -> "部分已保存"
                    else -> DamagedDownload
                }
                val partial = available && record.intactSelection.isNotEmpty()
                DownloadTile("gallery:${record.site}:${record.id}", record.video, "${galleryCategory(record.site).itemNoun} · ${if (record.sourceRoot == app.filesDir.absolutePath) "应用内" else "公共目录"}",
                    listOf(state, record.detail, downloadSize(record.bytes)).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = { offline(record) }.takeIf { available && !partial },
                    menu = if (available) {
                        {
                            DownloadMenuItem("删除") {
                                dialogs.confirm("删除这份下载？", "删除", "将删除已下载图页。") {
                                    act("disk:${record.site}:${record.id}") {
                                        val book = app.galleryDownloads.task(record.video)
                                        if (book != null) app.galleryDownloads.remove(book.id) else withContext(Dispatchers.IO) {
                                            app.galleries.removeDownload(record.video)
                                            app.index.forgetComic(record.site, record.id)
                                        }
                                    }
                                }
                            }
                        }
                    } else null,
                    actions = when {
                        partial -> ({ Action("查看已存页", primary = true) { offline(record) } })
                        !available -> ({ Action("重新下载", primary = true) { video(record.video.copy(mediaKind = MediaKind.GALLERY), null, null) } })
                        else -> null
                    })
            }
        }
        videos.forEach { (task, meta) ->
            val progress = transferProgress(task)
            val bytes = progress?.bytes ?: task.bytesDownloaded
            val percent = progress?.percent ?: task.percentDownloaded
            rows += DownloadRow("video:${task.request.id}", meta.video.title, task.startTimeMs, bytes, percent / 100f) {
                val damaged = damagedVideo(task)
                val completed = task.state == Download.STATE_COMPLETED && !damaged
                val failed = task.state == Download.STATE_FAILED
                val paused = task.state == Download.STATE_STOPPED
                val state = when (task.state) {
                    Download.STATE_COMPLETED -> if (damaged) DamagedDownload else "已完成"
                    Download.STATE_DOWNLOADING -> "下载中" + if (percent >= 0f) " · ${percent.toInt()}%" else ""
                    Download.STATE_STOPPED -> "已暂停"
                    Download.STATE_FAILED -> "下载失败"
                    else -> "等待下载"
                }
                DownloadTile("video:${task.request.id}", meta.video, "视频 · ${meta.quality}",
                    if (damaged) state else "$state · ${downloadSize(bytes)}",
                    percent.takeIf { !completed && !damaged && it >= 0 }?.div(100f),
                    onClick = { video(meta.video, task.request.id, null) }.takeIf { completed },
                    menu = {
                        DownloadMenuItem("删除", enabled = task.request.id !in busy) {
                            dialogs.confirm("删除这份视频下载？", "删除", "观看记录和收藏会保留。") {
                                act(task.request.id) { app.playback.remove(task.request.id) }
                            }
                        }
                    },
                    actions = if (completed) null else ({
                        Action(if (damaged) "重新下载" else if (failed) "重试" else if (paused) "继续" else "暂停",
                            primary = damaged || failed || paused, enabled = !retrying && task.request.id !in busy) {
                            act(task.request.id) {
                                if (task.state == Download.STATE_FAILED) app.playback.retryFailed(task.request.id)
                                else if (damaged) app.playback.redownloadDamaged(meta.video, meta.quality)
                                else app.playback.pause(task.request.id, !paused)
                            }
                        }
                    }))
            }
        }
        filtered.diskVideos.forEach { record ->
            rows += DownloadRow("disk-video:${record.site}:${record.id}:${record.quality}", record.title, record.at, record.bytes,
                if (record.state == LibraryRecord.STATE_COMPLETED) 1f else 0f) {
                val available = record.offlineReady
                val id = "disk:${record.cacheKey}"
                DownloadTile("disk-video:${record.site}:${record.id}:${record.quality}", record.video,
                    "视频 · ${record.quality} · ${if (record.sourceRoot == app.filesDir.absolutePath) "应用内" else "公共目录"}",
                    (if (available) "已完成" else DamagedDownload) + " · ${downloadSize(record.bytes)}",
                    onClick = { offline(record) }.takeIf { available },
                    menu = {
                        if (available) DownloadMenuItem("删除") {
                            dialogs.confirm("删除这份视频下载？", "删除", "将删除所有下载位置中的这份视频，观看记录和收藏会保留。") {
                                act(id) { app.playback.removeRestored(record) }
                            }
                        } else DownloadMenuItem("删除索引", enabled = id !in busy) {
                            act(id) { withContext(Dispatchers.IO) { app.index.removeVideo(record.site, record.id, record.quality) } }
                        }
                    },
                    actions = if (!available && record.url.isNotBlank()) ({
                        Action("重新下载", primary = true, enabled = id !in busy) {
                            act(id) { app.playback.redownloadDamaged(record.video, record.quality) }
                        }
                    }) else null)
            }
        }
        filtered.legacy.forEach { legacy ->
            val local = legacy.video
            rows += DownloadRow("legacy:${local.site}:${local.id}", local.title, legacy.modifiedAt, legacy.bytes, 1f) {
                val entry = Video(local.id, local.title, app.library.watch(local.id, local.site)?.path.orEmpty(), local.thumb, site = local.site)
                DownloadTile("legacy:${local.site}:${local.id}", entry, listOf("视频", local.quality).filter(String::isNotBlank).joinToString(" · "),
                    "已完成 · 旧版本离线文件 · ${downloadSize(legacy.bytes)}",
                    onClick = { video(entry, null, local.path) },
                    menu = {
                        DownloadMenuItem("删除") {
                            dialogs.confirm("删除这份视频下载？", "删除") {
                                act("legacy:${local.site}:${local.id}") { withContext(Dispatchers.IO) { app.library.removeLocal(local.id, local.site) } }
                            }
                        }
                    })
            }
        }
        val ordered = when (sort) {
            1 -> rows.sortedByDescending(DownloadRow::addedAt)
            2 -> rows.sortedByDescending(DownloadRow::bytes)
            3 -> rows.sortedByDescending(DownloadRow::completion)
            4 -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DownloadRow::title))
            else -> rows
        }
        items(ordered, key = DownloadRow::key) { row -> row.render() }
        if (ready) item("storage") {
            if (count == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("存储位置", style = MaterialTheme.typography.titleSmall)
                    Copy(snapshot.root)
                    scanNote?.let { Copy(it) }
                }
                TextAction({
                    act("rescan") {
                        val total = rescan()
                        scanNote = if (lockedSite == null) "已重新扫描：$total 项" else "已重新扫描"
                    }
                }, enabled = "rescan" !in busy) { Text("重新扫描") }
            }
        }
    }
}

@Composable private fun DownloadStatusTabs(counts: IntArray?, selected: Int, choose: (Int) -> Unit) {
    val palette = LocalArtworkPalette.current
    val shape = if (palette == null) RentalShape else ArtworkPill
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DownloadStatus.entries.forEach { status ->
            val index = status.ordinal
            val active = index == selected
            val fill = if (active) palette?.accent ?: RentalPaper else palette?.surface ?: RentalSurface
            val ink = if (active) palette?.onAccent ?: RentalInk else palette?.ink ?: RentalMuted
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape).background(fill)
                .border(1.dp, if (active) fill else palette?.edge ?: RentalEdge, shape)
                .selectable(active, role = Role.Tab) { choose(index) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(status.label, color = ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                counts?.let {
                    Text(it[index].toString(), color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun DownloadMenu(
    label: String,
    icon: Int,
    iconOnly: Boolean = false,
    items: @Composable DownloadMenuScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (iconOnly) IconAction(icon, label) { open = true }
        else TextAction({ open = true }) {
            Text(label)
            Spacer(Modifier.width(4.dp))
            Glyph(icon, null, Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, shape = ArtworkShape, containerColor = MaterialTheme.colorScheme.surface) {
            DownloadMenuScope { open = false }.items()
        }
    }
}

private class DownloadMenuScope(private val close: () -> Unit) {
    @Composable fun DownloadMenuItem(label: String, enabled: Boolean = true, selected: () -> Unit) {
        DropdownMenuItem(text = { Text(label) }, onClick = { close(); selected() }, enabled = enabled)
    }
}

@Composable private fun DownloadTile(id: String, entry: Video, type: String, status: String, progress: Float? = null,
    error: String = "", onClick: (() -> Unit)? = null, menu: (@Composable DownloadMenuScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit = {}, actions: (@Composable FlowRowScope.() -> Unit)? = null) {
    val site = SiteRegistry.byIdOrDefault(entry.site)
    val colors = MaterialTheme.colorScheme
    val coverWidth = 66.dp
    val coverGap = 12.dp
    Box(Modifier.fillMaxWidth().testTag("download-$id")) {
        Column(Modifier.fillMaxWidth().artworkPanel()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "离线打开", onClick = onClick) else Modifier)
            .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(coverGap), verticalAlignment = Alignment.CenterVertically) {
                SiteImage(entry.thumb, site, Modifier.size(coverWidth, 76.dp).clip(ArtworkShape),
                    fit = entry.mediaKind == MediaKind.GALLERY)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title.ifBlank { "未命名内容" }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Copy("${downloadSiteName(entry.site)} · $type")
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (onClick != null) Text("离线打开", color = colors.primary, style = MaterialTheme.typography.labelLarge)
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp), color = colors.primary, trackColor = colors.outline,
                            gapSize = 0.dp, drawStopIndicator = {})
                    }
                }
                if (actions == null && menu != null) {
                    DownloadMenu("${entry.title} 的任务操作", R.drawable.ic_more, iconOnly = true, items = menu)
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            details()
            if (actions != null) {
                Row(Modifier.fillMaxWidth().padding(start = coverWidth + coverGap), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                    if (menu != null) DownloadMenu("${entry.title} 的任务操作", R.drawable.ic_more, iconOnly = true, items = menu)
                }
            }
        }
    }
}

private fun downloadStage(stage: GalleryDownloadStage?) = when (stage) {
    GalleryDownloadStage.PREPARING -> "解析图集信息"
    GalleryDownloadStage.RESOLVING_PAGE -> "解析图页地址"
    GalleryDownloadStage.DOWNLOADING_PAGE -> "读取图片 CDN / 写入图页"
    GalleryDownloadStage.COMMITTING -> "校验并提交下载"
    null -> "等待任务信息"
}

private fun downloadFailure(failure: GalleryFailureKind?) = when (failure) {
    GalleryFailureKind.NETWORK -> "临时网络或服务错误"
    GalleryFailureKind.RATE_LIMIT -> "原站限流"
    GalleryFailureKind.AUTH -> "登录 / 权限不足"
    GalleryFailureKind.NOT_FOUND -> "内容或文件失效"
    GalleryFailureKind.QUOTA -> "原站配额不足"
    GalleryFailureKind.STORAGE -> "存储写入失败"
    GalleryFailureKind.TLS -> "安全连接校验失败"
    GalleryFailureKind.SOURCE -> "来源响应异常"
    GalleryFailureKind.UNKNOWN, null -> "无分类错误"
}

private fun downloadSize(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024}\u00A0KB" else "${bytes / (1024 * 1024)}\u00A0MB"
