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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Persistent task records; the foreground service owns execution, one book at a time. */
class GalleryDownloadStore(private val context: Context, private val repository: GalleryRepository, private val network: Network,
                           private val index: LibraryIndex, private val networkAllowed: () -> Boolean = { true }) {
    constructor(context: Context, repository: GalleryRepository, network: Network) :
        this(context, repository, network, LibraryIndex(context, StorageRoot(context, LibraryStore(context))))

    private val prefs = context.getSharedPreferences("gallery_downloads_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val active = ConcurrentHashMap<String, Job>()
    private val retryJobs = ConcurrentHashMap<String, Job>()
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val removalLock = Mutex()
    val changes = MutableStateFlow(0L)

    fun key(entry: Video): String = MessageDigest.getInstance("SHA-256").digest("${entry.site}:${entry.id}".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun decode(raw: Any?): GalleryDownload? = runCatching {
        gson.fromJson(raw as String, GalleryDownload::class.java).also {
            require(it.entry.mediaKind == MediaKind.GALLERY && it.entry.id.isNotBlank())
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

    suspend fun add(entry: Video, pages: Collection<Int>? = null, group: String? = null): GalleryDownload = withContext(Dispatchers.IO) {
        repository.checkDownloadsAllowed()
        require(entry.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        val selection = pages?.map { require(it > 0) { "页码必须大于零" }; it }?.distinct()?.sorted()
        require(selection == null || selection.isNotEmpty()) { "请至少选择一页" }
        val id = key(entry)
        retryJobs.remove(id)?.cancel()
        val value = synchronized(this@GalleryDownloadStore) {
            val previous = task(id)
            check(previous?.state != GalleryDownloadState.REMOVING) { "图集正在删除，请稍后再下载" }
            if (previous?.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING)) previous!!
            else if (previous?.state == GalleryDownloadState.COMPLETED && !previous.partial && repository.isDownloaded(entry)) previous
            else (previous ?: GalleryDownload(id, entry.copy(mediaKind = MediaKind.GALLERY))).copy(
                state = GalleryDownloadState.QUEUED, error = "", failure = null, retryAt = 0,
                selectedPages = selection ?: previous?.selectedPages, group = group?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.group,
            ).also(::write)
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
        tasks().filter { it.state == GalleryDownloadState.RETRY_WAIT }.forEach(::scheduleRetry)
        if (repository.downloadsAllowed && networkAllowed() && tasks().any { it.state == GalleryDownloadState.QUEUED || it.state == GalleryDownloadState.DOWNLOADING }) runCatching { startService(keepQueued = true) }
    }

    private fun scheduleRetry(record: GalleryDownload) {
        if (retryJobs.contains(record.id)) return
        val delayMs = (record.retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
        retryJobs[record.id] = maintenance.launch {
            delay(delayMs)
            retryJobs.remove(record.id)
            val current = task(record.id) ?: return@launch
            if (current.state != GalleryDownloadState.RETRY_WAIT || current.retryAt != record.retryAt) return@launch
            update(record.id) { it.copy(state = GalleryDownloadState.QUEUED, retryAt = 0, error = "") }
            if (networkAllowed() && repository.downloadsAllowed) runCatching { startService(keepQueued = true) }
        }
    }

    val canRun get() = repository.downloadsAllowed && networkAllowed()
    suspend fun waitForNetwork() = withContext(Dispatchers.IO) {
        if (!networkAllowed()) active.values.toList().forEach { it.cancelAndJoin() }
    }
    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        retryJobs.remove(id)?.cancel()
        update(id) { if (it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING, GalleryDownloadState.RETRY_WAIT)) it.copy(state = GalleryDownloadState.PAUSED, error = "", failure = null, retryAt = 0) else it }
        active[id]?.cancelAndJoin()
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        removalLock.withLock {
            val record = task(id) ?: return@withLock
            retryJobs.remove(id)?.cancel()
            update(id) { it.copy(state = GalleryDownloadState.REMOVING, retryAt = 0) }
            active[id]?.cancelAndJoin()
            try { repository.removeDownload(record.entry) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                update(id) { it.copy(state = GalleryDownloadState.FAILED, failure = GalleryFailureKind.STORAGE, error = "删除失败，请重试删除：${e.message}") }
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
        tasks().filter { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING, GalleryDownloadState.RETRY_WAIT) }.forEach { record ->
            retryJobs.remove(record.id)?.cancel()
            update(record.id) { it.copy(state = GalleryDownloadState.PAUSED, retryAt = 0, error = "系统后台下载时限已到，点击继续即可恢复") }
            active[record.id]?.cancel()
        }
    }
    fun hasPending() = tasks().any { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) }

    fun move(id: String, delta: Int) {
        val queue = tasks().filter { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING, GalleryDownloadState.RETRY_WAIT, GalleryDownloadState.PAUSED, GalleryDownloadState.FAILED) }
            .sortedWith(compareByDescending<GalleryDownload> { it.priority }.thenBy { it.addedAt }).toMutableList()
        val from = queue.indexOfFirst { it.id == id }
        val to = from + delta
        if (from !in queue.indices || to !in queue.indices) return
        queue.add(to, queue.removeAt(from))
        queue.forEachIndexed { index, record -> update(record.id) { it.copy(priority = queue.size - index) } }
    }
    fun prioritize(id: String) {
        val highest = tasks().maxOfOrNull { it.priority } ?: 0
        update(id) { it.copy(priority = highest + 1) }
    }
    fun setGroup(id: String, group: String?) = update(id) { it.copy(group = group?.trim()?.takeIf(String::isNotEmpty)) }

    suspend fun inspect(id: String): GalleryRepository.GalleryAudit = withContext(Dispatchers.IO) {
        val record = task(id) ?: error("下载任务不存在")
        repository.auditDownload(record.entry)
    }
    suspend fun repair(id: String): GalleryDownload = withContext(Dispatchers.IO) {
        val record = task(id) ?: error("下载任务不存在")
        check(record.state != GalleryDownloadState.REMOVING && active[id]?.isActive != true) { "请先暂停任务再修复" }
        val audit = repository.auditDownload(record.entry)
        val damaged = audit.missingPages + audit.corruptPages
        if (audit.complete || damaged.isEmpty()) {
            require(audit.total > 0 && audit.validPages.size == audit.total) { "没有需要修复的图页，但下载仍不完整，请重新下载" }
            repository.finishDownload(record.entry, audit.total)
            index.putComic(record.entry, audit.bytes)
            update(id) { it.copy(state = GalleryDownloadState.COMPLETED, pages = audit.total, total = audit.total, completedPages = audit.validPages,
                selectedPages = null, bytes = audit.bytes, error = "", failure = null) }
            return@withContext task(id)!!
        }
        add(record.entry, damaged)
    }

    @Synchronized fun next(scope: CoroutineScope): Job? {
        if (!canRun || active.isNotEmpty()) return null
        val record = tasks().filter { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) && !active.containsKey(it.id) }
            .sortedWith(compareByDescending<GalleryDownload> { it.priority }.thenBy { it.addedAt }).firstOrNull() ?: return null
        write(record.copy(state = GalleryDownloadState.DOWNLOADING, error = "", failure = null, stage = GalleryDownloadStage.PREPARING, retryAt = 0))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { download(record) }
            finally { active.remove(record.id) }
        }
        active[record.id] = job
        job.start()
        return job
    }

    private class RetryWait(val failure: DownloadFailure) : Exception(failure.message)

    private suspend fun download(record: GalleryDownload) {
        var stage = GalleryDownloadStage.PREPARING
        try {
            val gallery = retryIo(record.id, stage) { repository.prepareDownload(record.entry) }
            val requested = record.selectedPages?.toSet() ?: (1..gallery.pageCount).toSet()
            check(requested.isNotEmpty() && requested.all { it in 1..gallery.pageCount }) { "所选页码超出本图集范围" }
            val site = SiteRegistry.byIdOrDefault(record.entry.site)
            val adapter = AdapterRegistry.adapter(site, network)
            var previous: GalleryImage? = null
            var bytes = 0L
            val already = task(record.id)?.savedPages.orEmpty().filter { it in requested }.toSet()
            update(record.id) { it.copy(pages = already.size, completedPages = already.sorted(), total = gallery.pageCount, bytes = 0, selectedPages = requested.sorted()) }
            for (index in requested.sorted()) {
                currentCoroutineContext().ensureActive()
                stage = GalleryDownloadStage.RESOLVING_PAGE
                update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(stage = stage) else it }
                val url = repository.cachedPageUrl(record.entry, index) ?: previous?.nextUrl?.takeIf { image -> previous?.index == index - 1 && image.isNotBlank() }
                    ?: retryIo(record.id, stage) { adapter.galleryPageLink(gallery, index) }
                stage = GalleryDownloadStage.DOWNLOADING_PAGE
                val (image, size) = retryIo(record.id, stage) { repository.downloadPage(record.entry, index, url) }
                previous = image
                bytes += size
                val saved = (task(record.id)?.savedPages.orEmpty() + index).sorted()
                update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(pages = saved.size, completedPages = saved, bytes = bytes, error = "", failure = null, stage = stage) else it }
            }
            currentCoroutineContext().ensureActive()
            stage = GalleryDownloadStage.COMMITTING
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(stage = stage) else it }
            val audit = repository.auditDownload(record.entry)
            if (audit.complete) {
                repository.finishDownload(record.entry, gallery.pageCount)
                runCatching { index.putComic(record.entry, audit.bytes) }
            } else repository.finishPartialDownload(record.entry, requested)
            val saved = audit.validPages
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.COMPLETED, pages = saved.size,
                completedPages = saved, bytes = audit.bytes, selectedPages = if (audit.complete) null else requested.sorted(), error = "", failure = null, retryAt = 0) else it }
        } catch (wait: RetryWait) {
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.RETRY_WAIT, error = wait.failure.message, failure = wait.failure.kind, stage = stage, retryAt = wait.failure.retryAt) else it }
            task(record.id)?.takeIf { it.state == GalleryDownloadState.RETRY_WAIT }?.let(::scheduleRetry)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.QUEUED) else it } }
            throw e
        } catch (e: Exception) {
            val failure = DownloadRecovery.classify(e, stage)
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.FAILED, error = failure.message, failure = failure.kind, stage = stage, retryAt = 0) else it }
        }
    }

    private suspend fun <T> retryIo(id: String, stage: GalleryDownloadStage, block: suspend () -> T): T {
        for (attempt in 1..DownloadRecovery.MAX_ATTEMPTS) {
            try { return block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = DownloadRecovery.classify(e, stage)
                if (!failure.retryable || attempt == DownloadRecovery.MAX_ATTEMPTS) throw e
                val delayMs = DownloadRecovery.automaticDelayMs(failure) ?: throw RetryWait(failure)
                update(id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(error = "${failure.message}；将自动重试 ${attempt + 1} / ${DownloadRecovery.MAX_ATTEMPTS}", failure = failure.kind, stage = stage) else it }
                delay(if (failure.retryAt > 0) delayMs else DownloadRecovery.backoffMs(attempt))
            }
        }
        error("不可达的重试状态")
    }
}
