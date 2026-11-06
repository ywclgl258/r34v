package com.r34v.r34v.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.offline.Download
import com.r34v.r34v.GalleryActivity
import com.r34v.r34v.PlayerActivity
import com.r34v.r34v.R34Application
import java.util.UUID
import kotlinx.coroutines.*

data class PlaybackQueue(val id: String = "", val shelf: String = "", val title: String = "", val entries: List<Video> = emptyList(), val index: Int = 0) {
    val current get() = entries.getOrNull(index)
    val next get() = entries.getOrNull(index + 1)
}

/** One persisted session, with small intent tokens instead of large Binder payloads. */
class PlaybackQueueStore(private val library: LibraryStore) {
    private val key = "playback_queue_v1"
    @Synchronized fun snapshot() = runCatching { library.gson.fromJson(library.prefs.getString(key, null), PlaybackQueue::class.java) }.getOrNull()
    @Synchronized fun start(title: String, shelf: String, entries: List<Video>): PlaybackQueue {
        require(entries.isNotEmpty() && entries.size <= 50000)
        return PlaybackQueue(UUID.randomUUID().toString(), shelf, title, entries.distinctBy { it.entryKey }).also { write(it) }
    }
    fun session(token: String?, video: Video) = snapshot()?.takeIf { token != null && token == it.id && it.current?.entryKey == video.entryKey }
    @Synchronized fun advance(token: String, video: Video): PlaybackQueue? {
        val queue = session(token, video) ?: return null
        if (queue.next == null) return null
        return queue.copy(index = queue.index + 1).also { write(it) }
    }
    private fun write(value: PlaybackQueue) { check(library.prefs.edit().putString(key, library.gson.toJson(value)).commit()) { "无法保存片单观看进度" } }
    @Synchronized fun end(token: String) { if (snapshot()?.id == token) check(library.prefs.edit().remove(key).commit()) }
    @Synchronized internal fun restore(value: PlaybackQueue) = write(value)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaRouter {
    private val opening = java.util.WeakHashMap<ComponentActivity, Job>()
    fun open(context: Context, video: Video, queue: String? = null, read: Boolean = true,
        opened: () -> Unit = {}, failed: (Throwable) -> Unit = { android.widget.Toast.makeText(context, it.message ?: "无法打开作品", android.widget.Toast.LENGTH_LONG).show() }, cancelled: () -> Unit = {}) {
        val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { base -> base !== it } }.filterIsInstance<ComponentActivity>().firstOrNull() ?: error("打开作品需要有效页面")
        if (opening[owner]?.isActive == true) return
        opening[owner] = owner.lifecycleScope.launch {
            try { val intent = intent(context, video, queue, read); context.startActivity(intent); opened() }
            catch (e: Exception) { if (e is CancellationException) { cancelled(); throw e }; failed(e) }
        }
    }
    private suspend fun intent(context: Context, video: Video, queue: String?, read: Boolean): Intent {
        val app = context.applicationContext as R34Application
        val gallery = video.mediaKind == MediaKind.GALLERY || SiteRegistry.byId(video.site)?.mediaKind == MediaKind.GALLERY
        val scanned = if (video.site == SiteRegistry.LOCAL.id) null else withContext(Dispatchers.IO) {
            app.index.scan(app.playback::cached).firstOrNull { it.site == video.site && it.id == video.id && it.isComic == gallery && it.state == LibraryRecord.STATE_COMPLETED }
        }
        val intent = if (gallery) {
            val offline = scanned != null || video.site == SiteRegistry.LOCAL.id || app.galleryDownloads.task(video)?.state == GalleryDownloadState.COMPLETED
            Intent(context, GalleryActivity::class.java).putExtra("gallery", app.library.gson.toJson(video)).putExtra("read", read).putExtra("offline", offline).putExtra("root", scanned?.sourceRoot.orEmpty())
        } else {
            val task = app.playback.downloads().firstOrNull { it.state == Download.STATE_COMPLETED && app.playback.metadata(it)?.video?.entryKey == video.entryKey }
            Intent(context, PlayerActivity::class.java).putExtra("video", app.library.gson.toJson(video)).apply {
                if (scanned != null) { putExtra("cached", scanned.cacheKey); putExtra("root", scanned.sourceRoot) } else putExtra("download", task?.request?.id)
            }
        }
        intent.putExtra("queue_session", queue ?: app.queue.snapshot()?.takeIf { it.current?.entryKey == video.entryKey }?.id)
        return intent
    }
    fun next(activity: Activity, video: Video, token: String): Boolean {
        val app = activity.application as R34Application
        if (activity is ComponentActivity && opening[activity]?.isActive == true) return false
        val previous = app.queue.session(token, video) ?: return false
        val queue = app.queue.advance(token, video) ?: return false
        open(activity, queue.current!!, queue.id, opened = { app.library.personal.complete(video, queue.shelf, true); activity.finish() }, failed = { app.queue.restore(previous); android.widget.Toast.makeText(activity, it.message ?: "无法打开下一项", android.widget.Toast.LENGTH_LONG).show() }, cancelled = { app.queue.restore(previous) })
        return true
    }
}
