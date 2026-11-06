package com.r34v.r34v.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Persistent task records; the foreground service owns execution, one book at a time. */
class GalleryDownloadStore(private val context: Context, private val repository: GalleryRepository, private val network: Network,
                           private val index: LibraryIndex, private val networkAllowed: () -> Boolean = { true }) {
    /** Test and tooling constructor: records are then purely local to this store. */
    constructor(context: Context, repository: GalleryRepository, network: Network) :
        this(context, repository, network, LibraryIndex(context, StorageRoot(context, LibraryStore(context))))

    private val prefs = context.getSharedPreferences("gallery_downloads_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val active = ConcurrentHashMap<String, Job>()
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val removalLock = Mutex()
    val changes = MutableStateFlow(0L)

    fun key(entry: Video): String = MessageDigest.getInstance("SHA-256").digest("${entry.site}:${entry.id}".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun decode(raw: Any?): GalleryDownload? = runCatching {
        gson.fromJson(raw as String, GalleryDownload::class.java).also {
            require(SiteRegistry.byId(it.entry.site)?.mediaKind == MediaKind.GALLERY && it.entry.id.isNotBlank())
            require(it.id == key(it.entry) && it.state in GalleryDownloadState.entries)
        }
    }.getOrNull()
    @Synchronized fun tasks(): List<GalleryDownload> = prefs.all.values.mapNotNull(::decode).sortedByDescending { it.addedAt }
    @Synchronized fun task(id: String): GalleryDownload? = decode(prefs.getString(id, null))?.takeIf { it.id == id }
    fun task(entry: Video) = task(key(entry))
    @Synchronized private fun write(task: GalleryDownload) {
        check(prefs.edit().putString(task.id, gson.toJson(task)).commit()) { "无法保存图集下载记录" }
        changes.update { it + 1 }
    }
    @Synchronized private fun update(id: String, change: (GalleryDownload) -> GalleryDownload) { task(id)?.let { write(change(it)) } }

    suspend fun add(entry: Video): GalleryDownload = withContext(Dispatchers.IO) {
        repository.checkDownloadsAllowed()
        require(SiteRegistry.byId(entry.site)?.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        val id = key(entry)
        val value = synchronized(this@GalleryDownloadStore) {
            val previous = task(id)
            check(previous?.state != GalleryDownloadState.REMOVING) { "图集正在删除，请稍后再下载" }
            if (previous?.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING)) previous!!
            else if (previous?.state == GalleryDownloadState.COMPLETED && repository.isDownloaded(entry)) previous
            else (previous ?: GalleryDownload(id, entry.copy(mediaKind = MediaKind.GALLERY))).copy(state = GalleryDownloadState.QUEUED, error = "").also(::write)
        }
        if (value.state != GalleryDownloadState.COMPLETED && networkAllowed()) startService()
        value
    }
    private fun startService(keepQueued: Boolean = false) {
        try { ContextCompat.startForegroundService(context, Intent(context, GalleryDownloadService::class.java)) }
        catch (e: Exception) {
            tasks().filter { it.state == GalleryDownloadState.QUEUED }.forEach { update(it.id) { task -> task.copy(state = if (keepQueued) GalleryDownloadState.QUEUED else GalleryDownloadState.PAUSED, error = "系统暂时限制后台下载，请回到下载中心点击继续") } }
            throw e
        }
    }
    fun resumePending() {
        val removing = tasks().filter { it.state == GalleryDownloadState.REMOVING }
        if (removing.isNotEmpty()) maintenance.launch { removing.forEach { runCatching { remove(it.id) } } }
        if (repository.downloadsAllowed && networkAllowed() && tasks().any { it.state == GalleryDownloadState.QUEUED || it.state == GalleryDownloadState.DOWNLOADING }) runCatching { startService(keepQueued = true) }
    }
    val canRun get() = repository.downloadsAllowed && networkAllowed()
    suspend fun waitForNetwork() = withContext(Dispatchers.IO) {
        if (!networkAllowed()) active.values.toList().forEach { it.cancelAndJoin() }
    }
    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        update(id) { if (it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING)) it.copy(state = GalleryDownloadState.PAUSED, error = "") else it }
        active[id]?.cancelAndJoin()
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        removalLock.withLock {
            val record = task(id) ?: return@withLock
            update(id) { it.copy(state = GalleryDownloadState.REMOVING) }
            active[id]?.cancelAndJoin()
            try { repository.removeDownload(record.entry) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                update(id) { it.copy(state = GalleryDownloadState.FAILED, error = "删除失败，请重试删除：${e.message}") }
                throw e
            }
            runCatching { index.forgetComic(record.entry.site, record.entry.id) }
            synchronized(this@GalleryDownloadStore) {
                check(prefs.edit().remove(id).commit()) { "无法移除下载记录" }
                changes.update { it + 1 }
            }
        }
    }
    fun pauseForTimeout() {
        tasks().filter { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) }.forEach { record ->
            update(record.id) { it.copy(state = GalleryDownloadState.PAUSED, error = "系统后台下载时限已到，点击继续即可恢复") }
            active[record.id]?.cancel()
        }
    }
    fun hasPending() = tasks().any { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) }

    @Synchronized fun next(scope: CoroutineScope): Job? {
        if (!canRun || active.isNotEmpty()) return null
        val record = tasks().lastOrNull { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) && !active.containsKey(it.id) } ?: return null
        write(record.copy(state = GalleryDownloadState.DOWNLOADING, error = ""))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { download(record) }
            finally { active.remove(record.id) }
        }
        active[record.id] = job
        job.start()
        return job
    }
    private suspend fun download(record: GalleryDownload) {
        try {
            val gallery = retryIo { repository.prepareDownload(record.entry) }
            val site = SiteRegistry.byIdOrDefault(record.entry.site)
            val adapter = AdapterRegistry.adapter(site, network)
            var previous: GalleryImage? = null
            var bytes = 0L
            update(record.id) { it.copy(pages = 0, total = gallery.pageCount, bytes = 0) }
            for (index in 1..gallery.pageCount) {
                currentCoroutineContext().ensureActive()
                val url = repository.cachedPageUrl(record.entry, index) ?: previous?.nextUrl
                    ?: retryIo { adapter.galleryPageLink(gallery, index) }
                val (image, size) = retryIo { repository.downloadPage(record.entry, index, url) }
                previous = image; bytes += size
                update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(pages = index, bytes = bytes, error = "") else it }
            }
            currentCoroutineContext().ensureActive()
            repository.finishDownload(record.entry, gallery.pageCount)
            // The book folder is self-describing; the sidecar gives the centre a title and size
            // that survive an uninstall of this app.
            runCatching { index.putComic(record.entry, bytes) }
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.COMPLETED, pages = gallery.pageCount, error = "") else it }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.QUEUED) else it } }
            throw e
        } catch (e: Exception) {
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.FAILED, error = e.message ?: "图集下载失败") else it }
        }
    }
    private suspend fun <T> retryIo(block: suspend () -> T): T {
        repeat(2) { attempt -> try { return block() } catch (e: IOException) { delay(1000L * (attempt + 1)) } }
        return block()
    }
}
