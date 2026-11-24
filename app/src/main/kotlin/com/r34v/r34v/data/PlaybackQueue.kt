package com.r34v.r34v.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.GalleryActivity
import com.r34v.r34v.PlayerActivity
import com.r34v.r34v.R34Application
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()

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
    private fun write(value: PlaybackQueue) {
        check(library.prefs.edit().putString(key, library.gson.toJson(value)).commit()) { "无法保存片单观看进度" }
        revisions.update { it + 1 }
    }
    @Synchronized fun end(token: String) {
        if (snapshot()?.id != token) return
        check(library.prefs.edit().remove(key).commit()) { "无法结束片单" }
        revisions.update { it + 1 }
    }
    @Synchronized internal fun endCurrent(token: String, video: Video) {
        if (session(token, video) != null) end(token)
    }
    @Synchronized internal fun restore(previous: PlaybackQueue?, replacement: PlaybackQueue) {
        val current = snapshot()
        if (current?.id != replacement.id || current.index != replacement.index) return
        if (previous == null) end(replacement.id) else write(previous)
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
                if (queue != null) check(app.queue.session(queue, video) != null) { "片单已结束" }
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
            val available = if (offlineOnly) offlineSources(app, candidates) else null
            val ready = if (available == null) candidates else candidates.filter { it.entryKey in available }
            val first = ready.firstOrNull() ?: error(if (offlineOnly) "当前筛选下没有完整可离线打开的未完成作品" else "片单中没有未完成的作品")
            val intent = if (available == null) regularIntent(context, app, first, read = true) else {
                val source = available.getValue(first.entryKey)
                check(withContext(Dispatchers.IO) { source.available(app, first) }) { "「${first.title}」的离线文件已不可用" }
                source.intent(context, app, first, read = true)
            }
            val previous = app.queue.snapshot()
            val queue = app.queue.start(title, shelf, ready, offlineOnly)
            launchQueue(context, app, intent, previous, queue)
            opened(candidates.size - ready.size)
        }

    fun next(activity: Activity, video: Video, token: String,
        failed: (Throwable) -> Unit = { showError(activity, it, "无法打开下一项") }, finished: () -> Unit = {}): Boolean =
        launchOpen(activity, { error -> finished(); failed(error) }, finished) {
            val app = activity.application as R34Application
            val previous = app.queue.session(token, video) ?: error("片单已结束")
            val target = previous.next ?: error("片单没有下一项")
            val intent = if (previous.offlineOnly) {
                val sources = offlineSources(app, listOf(video, target))
                if (video.entryKey !in sources) stopOffline(app, token, video, video)
                val source = sources[target.entryKey] ?: stopOffline(app, token, video, target)
                source.intent(activity, app, target, read = true)
            } else regularIntent(activity, app, target, read = true)
            val queue = app.queue.advance(token, video) ?: error("片单已结束")
            launchQueue(activity, app, intent, previous, queue)
            app.library.personal.complete(video, queue.shelf, true)
            activity.finish()
            finished()
        }

    private fun launchQueue(context: Context, app: R34Application, intent: Intent, previous: PlaybackQueue?, queue: PlaybackQueue) {
        try { context.startActivity(intent.putExtra("queue_session", queue.id)) }
        catch (error: Exception) { app.queue.restore(previous, queue); throw error }
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

    private fun galleryIntent(context: Context, app: R34Application, video: Video, read: Boolean, offline: Boolean, root: String) =
        Intent(context, GalleryActivity::class.java).putExtra("gallery", app.library.gson.toJson(video.copy(mediaKind = MediaKind.GALLERY)))
            .putExtra("read", read).putExtra("offline", offline).putExtra("root", root)

    private fun playerIntent(context: Context, app: R34Application, video: Video) =
        Intent(context, PlayerActivity::class.java).putExtra("video", app.library.gson.toJson(video.copy(mediaKind = MediaKind.VIDEO)))

    private suspend fun regularIntent(context: Context, app: R34Application, video: Video, read: Boolean): Intent {
        offlineSources(app, listOf(video))[video.entryKey]?.let { return it.intent(context, app, video, read) }
        check(video.site != SiteRegistry.LOCAL.id) { "本地文件不可用，请重新导入" }
        val site = SiteRegistry.byId(video.site) ?: error("作品来源已不可用")
        val url = site.absolute(video.url)
        check(url.toHttpUrlOrNull() != null) { "作品地址无效" }
        val entry = video.copy(url = url)
        return if (entry.isGalleryWork) {
            val offline = app.galleryDownloads.task(entry)?.state == GalleryDownloadState.COMPLETED
            galleryIntent(context, app, entry, read, offline, "")
        } else playerIntent(context, app, entry)
    }

    private sealed interface OfflineSource {
        data class Gallery(val root: String) : OfflineSource
        data class LocalFile(val path: String) : OfflineSource
        data class CachedVideo(val record: LibraryRecord) : OfflineSource

        suspend fun available(app: R34Application, video: Video): Boolean = when (this) {
            is Gallery -> if (video.site == SiteRegistry.LOCAL.id) localComicAvailable(app, video) else completeGallery(app, video, root)
            is LocalFile -> localVideoAvailable(app, video, path)
            is CachedVideo -> app.playback.cached(record.cacheKey, record.sourceRoot)
        }

        fun intent(context: Context, app: R34Application, video: Video, read: Boolean): Intent = when (this) {
            is Gallery -> galleryIntent(context, app, video, read, offline = true, root = root)
            is LocalFile -> playerIntent(context, app, video).putExtra("local", path)
            is CachedVideo -> playerIntent(context, app, video.copy(url = video.url.ifBlank { record.url.ifBlank { "https://r34v.local/offline" } }))
                .putExtra("cached", record.cacheKey).putExtra("root", record.sourceRoot)
        }
    }

    private suspend fun offlineSources(app: R34Application, videos: List<Video>): Map<String, OfflineSource> = withContext(Dispatchers.IO) {
        val sources = mutableMapOf<String, OfflineSource>()
        val roots = app.storage.readableLayouts().map { it.root.absolutePath }
        // A local gallery id is independent from any legacy local-video registration.
        for (video in videos.filter { it.isGalleryWork }.distinctBy { it.entryKey }) {
            ensureActive()
            val source = if (video.site == SiteRegistry.LOCAL.id) {
                OfflineSource.Gallery("").takeIf { localComicAvailable(app, video) }
            } else roots.firstOrNull { completeGallery(app, video, it) }?.let(OfflineSource::Gallery)
            if (source != null) sources[video.entryKey] = source
        }
        val unresolved = videos.filterNot { it.isGalleryWork }
            .filterNot { it.entryKey in sources }.distinctBy { it.entryKey }.associateBy { it.entryKey }
        if (unresolved.isEmpty()) return@withContext sources
        // An imported video must be validated as a local book even if the same id also has a
        // legacy local-video registration. Do not let the latter bypass the imported file check.
        for (video in unresolved.values) {
            ensureActive()
            app.library.localVideos(video.site).firstOrNull { it.id == video.id && localVideoAvailable(app, video, it.path) }
                ?.let { sources[video.entryKey] = OfflineSource.LocalFile(it.path) }
        }
        val remoteUnresolved = unresolved.filterValues { it.site != SiteRegistry.LOCAL.id }
        if (remoteUnresolved.isEmpty()) return@withContext sources
        val known = app.playback.downloads().mapNotNull { app.playback.libraryRecord(it) }
            .filter { it.video.entryKey in remoteUnresolved }
        for (record in known.sortedByDescending { it.at }) {
            ensureActive()
            if (record.state != LibraryRecord.STATE_COMPLETED || record.video.entryKey in sources) continue
            val root = roots.firstOrNull { app.playback.cached(record.cacheKey, it) } ?: continue
            sources[record.video.entryKey] = OfflineSource.CachedVideo(record.copy(sourceRoot = root))
        }
        // Restored videos can have an arbitrary quality and no DownloadManager row. The sidecars
        // are the only list of those variants, so scan once if some requested videos remain.
        if (remoteUnresolved.keys.any { it !in sources }) {
            val unavailableTaskKeys = known.filter { it.state != LibraryRecord.STATE_COMPLETED }.mapTo(mutableSetOf()) { it.cacheKey }
            for (record in app.index.scan(app.playback::cached)) {
                ensureActive()
                val key = record.video.entryKey
                if (record.isComic || record.state != LibraryRecord.STATE_COMPLETED || record.cacheKey in unavailableTaskKeys ||
                    record.sourceRoot.isBlank() || key !in remoteUnresolved || key in sources) continue
                sources[key] = OfflineSource.CachedVideo(record)
            }
        }
        sources
    }

    private suspend fun completeGallery(app: R34Application, video: Video, root: String): Boolean = try {
        val entry = video.copy(mediaKind = MediaKind.GALLERY)
        val folder = app.galleries.downloadDirectory(entry, root)
        val pages = File(folder, "complete").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (pages == null || pages <= 0) false else {
            val audit = app.galleries.auditDownload(entry, root)
            audit.complete && audit.total == pages
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun localVideoAvailable(app: R34Application, video: Video, path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() == 0L) return false
        if (video.site != SiteRegistry.LOCAL.id) return true
        return app.localLibrary.isLocalVideoComplete(video.id, path)
    }.getOrDefault(false)

    internal fun localComicAvailable(app: R34Application, video: Video): Boolean = runCatching {
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
