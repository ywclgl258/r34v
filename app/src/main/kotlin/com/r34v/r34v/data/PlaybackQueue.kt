package com.r34v.r34v.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.offline.Download
import com.r34v.r34v.GalleryActivity
import com.r34v.r34v.PlayerActivity
import com.r34v.r34v.R34Application
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.*

data class PlaybackQueue(
    val id: String = "",
    val shelf: String = "",
    val title: String = "",
    val entries: List<Video> = emptyList(),
    val index: Int = 0,
    val offlineOnly: Boolean = false,
) {
    val current get() = entries.getOrNull(index)
    val next get() = entries.getOrNull(index + 1)
}

/** One persisted session, with small intent tokens instead of large Binder payloads. */
class PlaybackQueueStore(private val library: LibraryStore) {
    private val key = "playback_queue_v1"
    @Synchronized fun snapshot() = runCatching { library.gson.fromJson(library.prefs.getString(key, null), PlaybackQueue::class.java) }.getOrNull()
    @Synchronized fun start(title: String, shelf: String, entries: List<Video>, offlineOnly: Boolean = false): PlaybackQueue {
        require(entries.isNotEmpty() && entries.size <= 50000)
        return PlaybackQueue(UUID.randomUUID().toString(), shelf, title, entries.distinctBy { it.entryKey }, offlineOnly = offlineOnly)
            .also { write(it) }
    }
    fun session(token: String?, video: Video) = snapshot()?.takeIf { token != null && token == it.id && it.current?.entryKey == video.entryKey }
    @Synchronized fun advance(token: String, video: Video): PlaybackQueue? {
        val queue = session(token, video) ?: return null
        if (queue.next == null) return null
        return queue.copy(index = queue.index + 1).also { write(it) }
    }
    private fun write(value: PlaybackQueue) { check(library.prefs.edit().putString(key, library.gson.toJson(value)).commit()) { "无法保存片单观看进度" } }
    @Synchronized fun end(token: String) { if (snapshot()?.id == token) check(library.prefs.edit().remove(key).commit()) }
    @Synchronized internal fun endCurrent(token: String, video: Video) {
        if (session(token, video) != null) end(token)
    }
    @Synchronized internal fun restore(previous: PlaybackQueue, advanced: PlaybackQueue) {
        val current = snapshot()
        if (current?.id == advanced.id && current.index == advanced.index) write(previous)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaRouter {
    private val opening = WeakHashMap<ComponentActivity, Job>()

    private fun launchOpen(context: Context, failed: (Throwable) -> Unit, cancelled: () -> Unit, block: suspend () -> Unit): Boolean {
        val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { base -> base !== it } }
            .filterIsInstance<ComponentActivity>().firstOrNull() ?: error("打开作品需要有效页面")
        if (opening[owner]?.isActive == true) return false
        val job = owner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (error: CancellationException) { cancelled(); throw error }
            catch (error: Exception) { failed(error) }
        }
        opening[owner] = job
        return job.start()
    }

    fun open(context: Context, video: Video, queue: String? = null, read: Boolean = true,
        opened: () -> Unit = {}, failed: (Throwable) -> Unit = { showError(context, it, "无法打开作品") }, cancelled: () -> Unit = {}) {
        launchOpen(context, failed, cancelled) {
            val app = context.applicationContext as R34Application
            val session = queue?.let { app.queue.session(it, video) ?: error("片单已结束") }
            if (session?.offlineOnly == true) {
                val source = offlineSources(app, listOf(video))[video.entryKey] ?: stopOffline(app, session.id, video, video)
                check(app.queue.session(session.id, video) != null) { "片单已结束" }
                launchOffline(context, app, video, source.intent(context, app, video, read), session.id)
            } else {
                val intent = regularIntent(context, app, video, read)
                val token = queue ?: app.queue.snapshot()?.takeIf { !it.offlineOnly && it.current?.entryKey == video.entryKey }?.id
                context.startActivity(intent.putExtra("queue_session", token))
            }
            opened()
        }
    }

    fun startShelf(context: Context, title: String, shelf: String, entries: List<ShelfEntry>, offlineOnly: Boolean,
        opened: (skipped: Int) -> Unit, failed: (Throwable) -> Unit, cancelled: () -> Unit): Boolean =
        launchOpen(context, failed, cancelled) {
            val app = context.applicationContext as R34Application
            val candidates = entries.asSequence().filterNot { it.completed }.map { it.video }.distinctBy { it.entryKey }.toList()
            if (offlineOnly) {
                val available = offlineSources(app, candidates)
                val ready = candidates.filter { it.entryKey in available }
                val first = ready.firstOrNull() ?: error("当前筛选下没有完整可离线打开的未完成作品")
                val source = offlineSources(app, listOf(first))[first.entryKey] ?: error("「${first.title}」的离线文件已不可用")
                val intent = source.intent(context, app, first, read = true)
                val queue = app.queue.start(title, shelf, ready, offlineOnly = true)
                launchOffline(context, app, first, intent, queue.id)
                opened(candidates.size - ready.size)
            } else {
                val queue = app.queue.start(title, shelf, candidates)
                val intent = regularIntent(context, app, candidates.first(), read = true)
                context.startActivity(intent.putExtra("queue_session", queue.id))
                opened(0)
            }
        }

    fun next(activity: Activity, video: Video, token: String): Boolean {
        val app = activity.application as R34Application
        if (activity is ComponentActivity && opening[activity]?.isActive == true) return false
        val previous = app.queue.session(token, video) ?: return false
        val target = previous.next ?: return false
        if (previous.offlineOnly) return launchOpen(activity, { showError(activity, it, "无法打开下一项") }, {}) {
            val sources = offlineSources(app, listOf(video, target))
            if (video.entryKey !in sources) stopOffline(app, token, video, video)
            val source = sources[target.entryKey] ?: stopOffline(app, token, video, target)
            val intent = source.intent(activity, app, target, read = true)
            val queue = app.queue.advance(token, video) ?: return@launchOpen
            launchOffline(activity, app, target, intent, queue.id)
            app.library.personal.complete(video, queue.shelf, true)
            activity.finish()
        }
        return launchOpen(activity, { showError(activity, it, "无法打开下一项") }, {}) {
            val queue = app.queue.advance(token, video) ?: return@launchOpen
            try {
                val intent = regularIntent(activity, app, target, read = true)
                activity.startActivity(intent.putExtra("queue_session", queue.id))
                app.library.personal.complete(video, queue.shelf, true)
                activity.finish()
            } catch (error: Exception) {
                app.queue.restore(previous, queue)
                throw error
            }
        }
    }

    private fun stopOffline(app: R34Application, token: String, current: Video, missing: Video): Nothing {
        app.queue.endCurrent(token, current)
        error("「${missing.title}」的离线文件已不可用，顺序打开已停止")
    }

    private fun launchOffline(context: Context, app: R34Application, video: Video, intent: Intent, token: String) {
        try { context.startActivity(intent.putExtra("queue_session", token)) }
        catch (error: Exception) { app.queue.endCurrent(token, video); throw error }
    }

    private fun showError(context: Context, error: Throwable, fallback: String) {
        Toast.makeText(context, error.message ?: fallback, Toast.LENGTH_LONG).show()
    }

    // 本地站点按条目类型分流，因为该站点的历史默认类型为图集。
    private fun isGallery(video: Video) = video.mediaKind == MediaKind.GALLERY ||
        (video.site != SiteRegistry.LOCAL.id && SiteRegistry.byId(video.site)?.mediaKind == MediaKind.GALLERY)

    private fun galleryIntent(context: Context, app: R34Application, video: Video, read: Boolean, offline: Boolean, root: String) =
        Intent(context, GalleryActivity::class.java).putExtra("gallery", app.library.gson.toJson(video))
            .putExtra("read", read).putExtra("offline", offline).putExtra("root", root)

    private fun playerIntent(context: Context, app: R34Application, video: Video) =
        Intent(context, PlayerActivity::class.java).putExtra("video", app.library.gson.toJson(video))

    private suspend fun regularIntent(context: Context, app: R34Application, video: Video, read: Boolean): Intent {
        val gallery = isGallery(video)
        val scanned = if (video.site == SiteRegistry.LOCAL.id) null else withContext(Dispatchers.IO) {
            app.index.scan(app.playback::cached).firstOrNull { it.site == video.site && it.id == video.id && it.isComic == gallery && it.state == LibraryRecord.STATE_COMPLETED }
        }
        return if (gallery) {
            val offline = scanned != null || video.site == SiteRegistry.LOCAL.id || app.galleryDownloads.task(video)?.state == GalleryDownloadState.COMPLETED
            galleryIntent(context, app, video, read, offline, scanned?.sourceRoot.orEmpty())
        } else if (video.site == SiteRegistry.LOCAL.id) {
            val record = withContext(Dispatchers.IO) { app.library.localVideos(SiteRegistry.LOCAL.id).firstOrNull { it.id == video.id } }
                ?: error("本地视频文件不存在，请重新导入")
            playerIntent(context, app, video).putExtra("local", record.path)
        } else {
            val task = app.playback.downloads().firstOrNull { it.state == Download.STATE_COMPLETED && app.playback.metadata(it)?.video?.entryKey == video.entryKey }
            playerIntent(context, app, video).apply {
                if (scanned != null) { putExtra("cached", scanned.cacheKey); putExtra("root", scanned.sourceRoot) } else putExtra("download", task?.request?.id)
            }
        }
    }

    private sealed interface OfflineSource {
        data class Gallery(val root: String) : OfflineSource
        data class LocalFile(val path: String) : OfflineSource
        data class CachedVideo(val record: LibraryRecord) : OfflineSource

        fun intent(context: Context, app: R34Application, video: Video, read: Boolean): Intent = when (this) {
            is Gallery -> galleryIntent(context, app, video, read, offline = true, root = root)
            is LocalFile -> playerIntent(context, app, video).putExtra("local", path)
            is CachedVideo -> playerIntent(context, app, video.copy(url = video.url.ifBlank { record.url.ifBlank { "https://r34v.local/offline" } }))
                .putExtra("cached", record.cacheKey).putExtra("root", record.sourceRoot)
        }
    }

    private suspend fun offlineSources(app: R34Application, videos: List<Video>): Map<String, OfflineSource> = withContext(Dispatchers.IO) {
        val sources = mutableMapOf<String, OfflineSource>()
        val registered = videos.filterNot(::isGallery).map { it.site }.distinct().associateWith { site ->
            app.library.localVideos(site).groupBy { it.id }
        }
        for (video in videos) {
            if (isGallery(video)) {
                if (video.site == SiteRegistry.LOCAL.id && localComicAvailable(app, video)) sources[video.entryKey] = OfflineSource.Gallery("")
            } else {
                val file = registered[video.site]?.get(video.id)?.firstOrNull { localVideoAvailable(app, video, it.path) }
                if (file != null) sources[video.entryKey] = OfflineSource.LocalFile(file.path)
            }
        }
        val indexed = videos.filter { it.site != SiteRegistry.LOCAL.id && it.entryKey !in sources }
        if (indexed.isEmpty()) return@withContext sources
        val videoIds = indexed.filterNot(::isGallery).mapTo(mutableSetOf()) { it.site to it.id }
        val known = if (videoIds.isEmpty()) emptyList() else app.playback.downloads().mapNotNull { task ->
            val meta = app.playback.metadata(task) ?: return@mapNotNull null
            if ((meta.site to meta.video.id) !in videoIds) return@mapNotNull null
            LibraryRecord(site = meta.site, id = meta.video.id, quality = meta.quality, cacheKey = task.request.id,
                title = meta.video.title, url = meta.video.url, thumb = meta.video.thumb, duration = meta.video.duration,
                bytes = task.bytesDownloaded, at = task.startTimeMs,
                state = if (task.state == Download.STATE_COMPLETED) LibraryRecord.STATE_COMPLETED else LibraryRecord.STATE_PARTIAL)
        }
        val records = app.index.scan(app.playback::cached, known).filter { it.state == LibraryRecord.STATE_COMPLETED }
            .groupBy { Triple(it.site, it.id, it.isComic) }
        for (video in indexed) {
            val gallery = isGallery(video)
            val record = records[Triple(video.site, video.id, gallery)]?.firstOrNull() ?: continue
            if (gallery) {
                // The reader additionally requires usable page metadata. Its audit is disk-only.
                val complete = try { app.galleries.auditDownload(video, record.sourceRoot).complete }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { false }
                if (complete) sources[video.entryKey] = OfflineSource.Gallery(record.sourceRoot)
            } else if (record.sourceRoot.isNotBlank() && record.cacheKey.isNotBlank()) {
                sources[video.entryKey] = OfflineSource.CachedVideo(record)
            }
        }
        sources
    }

    private fun localVideoAvailable(app: R34Application, video: Video, path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() == 0L) return false
        if (video.site != SiteRegistry.LOCAL.id) return true
        val book = app.localLibrary.book(video.id) ?: return false
        val imported = File(File(app.localLibrary.rootDirectory(), book.id), book.files.singleOrNull() ?: return false)
        book.kind == "video" && imported.canonicalFile == file.canonicalFile && file.length() == book.bytes
    }.getOrDefault(false)

    private fun localComicAvailable(app: R34Application, video: Video): Boolean = runCatching {
        val book = app.localLibrary.book(video.id) ?: return false
        if (book.kind != "comic") return false
        val folder = File(app.localLibrary.rootDirectory(), book.id)
        var bytes = 0L
        for (name in book.files) {
            val file = File(folder, name)
            if (!file.isFile || !file.canRead() || file.length() == 0L) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            bytes += file.length()
        }
        book.bytes <= 0 || bytes == book.bytes
    }.getOrDefault(false)
}
