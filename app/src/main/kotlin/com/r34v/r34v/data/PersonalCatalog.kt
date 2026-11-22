@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.data

import androidx.media3.exoplayer.offline.Download
import com.r34v.r34v.R34Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CatalogEntry(val video: Video, val at: Long = 0, val addedAt: Long = 0, val progress: String = "尚未开始", val fraction: Float? = null,
    val started: Boolean = false, val completed: Boolean = false, val offline: Boolean = false, val downloadState: String = "未下载", val remainingBytes: Long? = null)

fun SmartShelf.select(entries: List<CatalogEntry>, now: Long = System.currentTimeMillis()): List<CatalogEntry> = entries.filter { item ->
    (source.isBlank() || item.video.site == source) && (category.isBlank() || SiteRegistry.byId(item.video.site)?.category?.name == category) && when (rule) {
        SmartRule.UNFINISHED -> item.started && !item.completed
        SmartRule.OFFLINE_UNSEEN -> item.offline && !item.started
        SmartRule.RECENT -> item.addedAt > 0 && now - item.addedAt <= 30L * 24 * 60 * 60 * 1000
    }
}.sortedByDescending { if (rule == SmartRule.RECENT) it.addedAt else it.at }.take(500)

suspend fun personalCatalog(app: R34Application, scanned: List<LibraryRecord> = emptyList()): List<CatalogEntry> = withContext(Dispatchers.IO) {
    val store = app.library
    val values = linkedMapOf<String, CatalogEntry>()
    fun add(video: Video, at: Long = 0) {
        val previous = values[video.entryKey]
        values[video.entryKey] = previous?.copy(addedAt = maxOf(previous.addedAt, at)) ?: CatalogEntry(video, at = at, addedAt = at)
    }
    store.personal.shelves().forEach { shelf -> shelf.entries.forEach { add(it.video, it.addedAt.takeIf { time -> time > 0 } ?: it.at) } }
    for (source in SiteRegistry.librarySources) {
        store.favorites(source.id).filter { it.kind in listOf("video", "gallery") }.forEach { add(Video(it.target, it.title, if (source.id == SiteRegistry.LOCAL.id) "r34local://${it.target}" else source.absolute(it.path), it.thumb, it.subtitle, site = source.id, mediaKind = if (it.kind == "gallery") MediaKind.GALLERY else MediaKind.VIDEO), it.at) }
        store.history(source.id).forEach { watch ->
            val video = watch.video(); add(video)
            val old = values.getValue(video.entryKey)
            val position = timeText(watch.position * 1000)
            val duration = watch.duration.takeIf { it > 0 }
            values[video.entryKey] = old.copy(at = watch.at,
                progress = if (duration != null) "进度 · $position / ${timeText(duration * 1000)}" else "进度 · $position",
                fraction = duration?.let { (watch.position.toFloat() / it).coerceIn(0f, 1f) },
                started = watch.position > 0, completed = watch.finished)
        }
        store.readings(source.id).forEach { reading ->
            add(reading.entry); val old = values.getValue(reading.entry.entryKey)
            val unit = if (source.category == SiteCategory.PHOTO) "张" else "页"
            val total = reading.pages.takeIf { it > 0 }
            val position = if (total != null) "${reading.page} / $total\u00a0$unit" else "第 ${reading.page}\u00a0$unit"
            values[reading.entry.entryKey] = old.copy(at = reading.at, progress = "进度 · $position",
                fraction = total?.let { (reading.page.toFloat() / it).coerceIn(0f, 1f) },
                started = true, completed = reading.page == total)
        }
    }
    scanned.filter { it.state == LibraryRecord.STATE_COMPLETED }.forEach { record -> add(record.video.copy(mediaKind = if (record.isComic) MediaKind.GALLERY else MediaKind.VIDEO), record.at); val old = values.getValue(record.video.entryKey); values[record.video.entryKey] = old.copy(offline = true, downloadState = "已离线") }
    app.playback.downloads().forEach { task -> app.playback.metadata(task)?.let { meta ->
        val video = meta.video; add(video, task.startTimeMs); val old = values.getValue(video.entryKey)
        val ready = task.state == Download.STATE_COMPLETED && app.playback.cached(task.request.id)
        val label = when (task.state) { Download.STATE_COMPLETED -> if (ready) "已离线" else "文件缺失"; Download.STATE_DOWNLOADING -> "正在下载"; Download.STATE_QUEUED, Download.STATE_RESTARTING -> "已排队"; Download.STATE_STOPPED -> "已暂停"; Download.STATE_REMOVING -> "正在删除"; else -> "下载失败" }
        values[video.entryKey] = old.copy(offline = old.offline || ready, downloadState = if (old.offline) "已离线" else label, remainingBytes = task.contentLength.takeIf { it > 0 }?.let { (it - task.bytesDownloaded).coerceAtLeast(0) })
    } }
    app.galleryDownloads.tasks().forEach { task ->
        add(task.entry, task.addedAt); val old = values.getValue(task.entry.entryKey)
        val ready = task.state == GalleryDownloadState.COMPLETED && app.galleries.isDownloaded(task.entry)
        val label = when(task.state) { GalleryDownloadState.COMPLETED -> if (ready) "已离线" else "文件缺失"; GalleryDownloadState.DOWNLOADING -> "正在下载"; GalleryDownloadState.QUEUED -> "已排队"; GalleryDownloadState.PAUSED -> "已暂停"; GalleryDownloadState.REMOVING -> "正在删除"; else -> "下载失败" }
        values[task.entry.entryKey] = old.copy(offline = old.offline || ready, downloadState = if (old.offline) "已离线" else label)
    }
    app.localLibrary.books().forEach { book ->
        val video = if (book.kind == "video") app.localLibrary.videoEntry(book) else app.localLibrary.video(book)
        add(video, book.at)
        values[video.entryKey] = values.getValue(video.entryKey).copy(video = video, offline = true, downloadState = "本地导入")
    }
    values.values.filter { store.allowed(it.video, it.video.site) }.sortedByDescending { it.at }
}
