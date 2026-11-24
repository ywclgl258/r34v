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
    private val maintenanceLock = Mutex()
    private val unsavedFailures = mutableMapOf<String, GalleryDownload>()
    private val singleRemovals = mutableSetOf<String>()
    val changes = MutableStateFlow(0L)

    fun key(entry: Video): String = galleryDownloadKey(entry)
    private fun decode(raw: Any?): GalleryDownload? = runCatching {
        gson.fromJson(raw as String, GalleryDownload::class.java).let {
            require(it.entry.mediaKind == MediaKind.GALLERY && it.entry.id.isNotBlank())
            require(it.id == key(it.entry) && it.state in GalleryDownloadState.entries)
            val selected = it.selectedPages?.filterNotNull()?.distinct()?.sorted()
            val total = if (selected.isNullOrEmpty()) it.total else {
                val stored = if (it.total == selected.size) 0 else it.total
                maxOf(stored, selected.last())
            }
            it.copy(error = it.error.orEmpty(), completedPages = it.completedPages?.filterNotNull()?.distinct()?.sorted(),
                selectedPages = selected, total = total)
        }
    }.getOrNull()
    @Synchronized fun tasks(): List<GalleryDownload> =
        (prefs.all.values.mapNotNull(::decode).associateBy { it.id } + unsavedFailures).values.sortedByDescending { it.addedAt }
    @Synchronized fun task(id: String): GalleryDownload? = unsavedFailures[id] ?: decode(prefs.all[id])?.takeIf { it.id == id }
    fun task(entry: Video) = task(key(entry))

    @Synchronized private fun persist(id: String, task: GalleryDownload?) {
        val previous = prefs.all[id] as? String
        val edit = prefs.edit()
        if (task == null) edit.remove(id) else edit.putString(id, gson.toJson(task))
        if (!runCatching { edit.commit() }.getOrDefault(false)) {
            runCatching { prefs.edit().putString(id, previous).commit() }
            throw GalleryStorageException("无法保存图集下载记录，请检查存储空间后重试")
        }
        unsavedFailures.remove(id)
        changes.update { it + 1 }
    }

    @Synchronized private fun update(id: String, change: (GalleryDownload) -> GalleryDownload) {
        task(id)?.let { current -> change(current).takeIf { it != current }?.let { persist(id, it) } }
    }

    @Synchronized private fun fail(id: String, expected: GalleryDownloadState, error: String,
        failure: GalleryFailureKind, stage: GalleryDownloadStage? = null) {
        val current = task(id)?.takeIf { it.state == expected } ?: return
        val failed = current.copy(state = GalleryDownloadState.FAILED, failure = failure, error = error,
            stage = stage ?: current.stage, retryAt = 0)
        try { persist(id, failed) }
        catch (storage: GalleryStorageException) {
            // Keep the queue usable even when it cannot persist the failure status to disk.
            unsavedFailures[id] = failed
            changes.update { it + 1 }
        }
    }
    /** 条件写：只有当前记录仍满足 [whenRecord] 时才应用变更，避免定时器把用户刚做的暂停覆盖掉。 */
    @Synchronized private fun updateIf(id: String, whenRecord: (GalleryDownload) -> Boolean, change: (GalleryDownload) -> GalleryDownload): Boolean {
        val current = task(id) ?: return false
        if (!whenRecord(current)) return false
        val updated = change(current)
        if (updated != current) persist(id, updated)
        return true
    }

    suspend fun add(entry: Video, pages: Collection<Int>? = null, group: String? = null,
                    recheckSavedPages: Boolean = false): GalleryDownload = withContext(Dispatchers.IO) {
        repository.checkDownloadsAllowed()
        require(entry.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        val selection = pages?.map { require(it > 0) { "页码必须大于零" }; it }?.distinct()?.sorted()
        require(selection == null || selection.isNotEmpty()) { "请至少选择一页" }
        val id = key(entry)
        val value = synchronized(this@GalleryDownloadStore) {
            repository.checkDownloadsAllowed()
            val previous = task(id)
            check(id !in singleRemovals && previous?.state != GalleryDownloadState.REMOVING && previous?.failedRemoval != true) {
                "请先完成此图集的删除，再重新下载"
            }
            if (previous != null && (previous.state in activeStates || active.containsKey(id))) {
                val previousPages = previous.selectedPages
                check(previous.state in activeStates && selection == previousPages) {
                    "当前图集已有不同的下载范围，请先暂停任务再更改"
                }
                previous
            } else if (!recheckSavedPages && selection == null && previous?.state == GalleryDownloadState.COMPLETED &&
                !previous.partial && repository.isDownloaded(entry)) {
                previous
            } else {
                val queued = (previous ?: GalleryDownload(id, entry.copy(mediaKind = MediaKind.GALLERY))).copy(
                    state = GalleryDownloadState.QUEUED, error = "", failure = null, retryAt = 0,
                    selectedPages = selection, group = group?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.group,
                )
                persist(id, queued)
                retryJobs.remove(id)?.cancel()
                queued
            }
        }
        if (value.state != GalleryDownloadState.COMPLETED && canRun) startService()
        value
    }

    suspend fun retryFailed(id: String): Boolean = withContext(Dispatchers.IO) {
        repository.checkDownloadsAllowed()
        val queued = updateIf(id, { it.state == GalleryDownloadState.FAILED && !it.failedRemoval }) {
            it.copy(state = GalleryDownloadState.QUEUED, error = "", failure = null, retryAt = 0)
        }
        if (queued && canRun) startService()
        queued
    }

    private fun startService(keepQueued: Boolean = false) {
        try { ContextCompat.startForegroundService(context, Intent(context, GalleryDownloadService::class.java)) }
        catch (e: Exception) {
            tasks().filter { it.state == GalleryDownloadState.QUEUED }.forEach { record ->
                try {
                    updateIf(record.id, { it.state == GalleryDownloadState.QUEUED }) { task ->
                        val state = if (keepQueued || !repository.downloadsAllowed) GalleryDownloadState.QUEUED else GalleryDownloadState.PAUSED
                        task.copy(state = state, error = "系统暂时限制后台下载，请回到下载中心点击继续")
                    }
                } catch (storage: GalleryStorageException) {
                    fail(record.id, GalleryDownloadState.QUEUED, storage.message.orEmpty(), GalleryFailureKind.STORAGE)
                }
            }
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
        val delayMs = (record.retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val job = maintenance.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            // 只有任务仍在等这一次重试时才改状态：用户期间暂停或被删除都不能被定时器覆盖。
            val resumed = try {
                updateIf(record.id, { it.state == GalleryDownloadState.RETRY_WAIT && it.retryAt == record.retryAt }) {
                    it.copy(state = GalleryDownloadState.QUEUED, retryAt = 0, error = "")
                }
            } catch (storage: GalleryStorageException) {
                fail(record.id, GalleryDownloadState.RETRY_WAIT, storage.message.orEmpty(), GalleryFailureKind.STORAGE)
                false
            }
            if (resumed && networkAllowed() && repository.downloadsAllowed) runCatching { startService(keepQueued = true) }
        }
        if (retryJobs.putIfAbsent(record.id, job) != null) {
            job.cancel()
            return
        }
        // 用完成回调回收，避免依赖 Job 实例身份（取消、未启动即取消都会触发）。
        job.invokeOnCompletion { retryJobs.remove(record.id, job) }
        job.start()
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
    /** The storage gate is already closed. Drain writers without changing user pause or selection. */
    internal suspend fun <T> withWritersStopped(block: suspend () -> T): T = maintenanceLock.withLock {
        val writers = synchronized(this) { active.toMap() }
        for ((id, job) in writers) {
            job.cancelAndJoin()
            updateIf(id, { it.state == GalleryDownloadState.DOWNLOADING }) { it.copy(state = GalleryDownloadState.QUEUED) }
        }
        block()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        maintenanceLock.withLock {
            val record = task(id) ?: return@withLock
            removeTask(record)
        }
    }

    internal suspend fun removeRestored(entry: Video, sourceRoot: String) = withContext(Dispatchers.IO + NonCancellable) {
        maintenanceLock.withLock {
            val record = task(entry)
            if (record != null) removeTask(record, sourceRoot)
            else {
                repository.removeDownload(entry, knownRoot = sourceRoot)
                index.forgetComic(entry.site, entry.id)
            }
        }
    }

    private suspend fun removeTask(record: GalleryDownload, knownRoot: String = record.sourceRoot.orEmpty()) {
        val id = record.id
        update(id) { it.copy(state = GalleryDownloadState.REMOVING, retryAt = 0, sourceRoot = knownRoot.takeIf(String::isNotBlank) ?: it.sourceRoot) }
        retryJobs.remove(id)?.cancel()
        active[id]?.cancelAndJoin()
        try {
            repository.removeDownload(record.entry, knownRoot = knownRoot)
            index.forgetComic(record.entry.site, record.entry.id)
            persist(id, null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(id, GalleryDownloadState.REMOVING, "$REMOVAL_FAILURE_PREFIX${e.message}", GalleryFailureKind.STORAGE)
            throw e
        }
    }

    internal suspend fun removeDuplicate(copy: DuplicateCopy) = withContext(Dispatchers.IO + NonCancellable) {
        require(copy.source == "download" && copy.sourceRoot.isNotBlank())
        val entry = checkNotNull(copy.entry)
        val id = key(entry)
        require(id == copy.id)
        maintenanceLock.withLock {
            synchronized(this@GalleryDownloadStore) {
                val record = task(id)
                check(!active.containsKey(id) && record?.state !in (activeStates + GalleryDownloadState.REMOVING) &&
                    record?.failedRemoval != true) { "请先暂停当前任务或完成删除，再移除此副本" }
                singleRemovals += id
            }
            try {
                repository.removeDownload(entry, sourceRoot = copy.sourceRoot)
                index.forgetComic(entry.site, entry.id, copy.sourceRoot)
                val remaining = repository.hasOtherCopy(entry, copy.sourceRoot)
                val record = task(id)
                if (record != null && (record.sourceRoot.isNullOrBlank() || record.sourceRoot == copy.sourceRoot)) {
                    if (remaining) update(id) { it.copy(sourceRoot = null) }
                    else persist(id, null)
                }
            } finally {
                synchronized(this@GalleryDownloadStore) { singleRemovals -= id }
            }
        }
    }

    @Synchronized fun pauseForTimeout() {
        tasks().filter { it.state in activeStates }.forEach { record ->
            try {
                if (updateIf(record.id, { it.state in activeStates }) {
                    it.copy(state = GalleryDownloadState.PAUSED, retryAt = 0, error = "系统后台下载时限已到，点击继续即可恢复")
                }) {
                    retryJobs.remove(record.id)?.cancel()
                    active[record.id]?.cancel()
                }
            } catch (storage: GalleryStorageException) {
                fail(record.id, record.state, storage.message.orEmpty(), GalleryFailureKind.STORAGE)
                retryJobs.remove(record.id)?.cancel()
                active[record.id]?.cancel()
            }
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
        maintenanceLock.withLock {
            repository.checkDownloadsAllowed()
            val record = task(id) ?: error("下载任务不存在")
            check(record.state != GalleryDownloadState.REMOVING && active[id]?.isActive != true) { "请先暂停任务再修复" }
            val layout = repository.downloadLayout()
            val audit = repository.auditWriteTarget(record.entry, layout) ?: return@withLock add(record.entry)
            val damaged = audit.missingPages + audit.corruptPages
            if (!audit.complete) return@withLock add(record.entry, damaged)

            repository.commitDownload(record.entry, layout, audit.validPages.toSet())
            index.putComic(record.entry, audit.bytes, layout = layout)
            update(id) { it.copy(state = GalleryDownloadState.COMPLETED, pages = audit.total, total = audit.total, completedPages = audit.validPages,
                selectedPages = null, bytes = audit.bytes, error = "", failure = null, sourceRoot = layout.root.absolutePath) }
            task(id)!!
        }
    }

    @Synchronized fun next(scope: CoroutineScope): Job? {
        if (!canRun || active.isNotEmpty()) return null
        val record = tasks().filter { it.state in runnableStates && it.id !in singleRemovals }
            .sortedWith(compareByDescending<GalleryDownload> { it.priority }.thenBy { it.addedAt }).firstOrNull() ?: return null
        val layout = repository.downloadLayout()
        try {
            persist(record.id, record.copy(state = GalleryDownloadState.DOWNLOADING, error = "", failure = null,
                stage = GalleryDownloadStage.PREPARING, retryAt = 0, sourceRoot = layout.root.absolutePath))
        } catch (storage: GalleryStorageException) {
            fail(record.id, record.state, storage.message.orEmpty(), GalleryFailureKind.STORAGE, GalleryDownloadStage.PREPARING)
            return null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { download(record, layout) }
        // 协程体从未执行就被取消时 finally 不会跑，active 会残留一个 Job 让整条队列永久停摆。
        // invokeOnCompletion 对“未启动即取消”同样触发，用它统一回收。
        active[record.id] = job
        job.invokeOnCompletion { active.remove(record.id, job) }
        job.start()
        return job
    }

    private class RetryWait(val failure: DownloadFailure) : Exception(failure.message)

    private suspend fun download(record: GalleryDownload, layout: StorageRoot.Layout) {
        var stage = GalleryDownloadStage.PREPARING
        try {
            val gallery = retryIo(record.id, stage) { repository.prepareDownload(record.entry, layout) }
            val requested = record.selectedPages?.toSet() ?: (1..gallery.pageCount).toSet()
            check(requested.isNotEmpty() && requested.all { it in 1..gallery.pageCount }) { "所选页码超出本图集范围" }
            val site = SiteRegistry.byIdOrDefault(record.entry.site)
            val adapter = AdapterRegistry.adapter(site, network)
            var previous: GalleryImage? = null
            var bytes = 0L
            val already = task(record.id)?.savedPages.orEmpty().filter { it in requested }.toSet()
            updateIf(record.id, { it.state == GalleryDownloadState.DOWNLOADING }) {
                it.copy(pages = already.size, completedPages = already.sorted(), total = gallery.pageCount, bytes = 0)
            }
            for (index in requested.sorted()) {
                currentCoroutineContext().ensureActive()
                stage = GalleryDownloadStage.RESOLVING_PAGE
                update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(stage = stage) else it }
                val url = repository.cachedPageUrl(record.entry, index, layout) ?: previous?.nextUrl?.takeIf { image -> previous?.index == index - 1 && image.isNotBlank() }
                    ?: retryIo(record.id, stage) { adapter.galleryPageLink(gallery, index) }
                stage = GalleryDownloadStage.DOWNLOADING_PAGE
                val (image, size) = retryIo(record.id, stage) { repository.downloadPage(record.entry, index, url, layout) }
                previous = image
                bytes += size
                val saved = (task(record.id)?.savedPages.orEmpty() + index).sorted()
                update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(pages = saved.size, completedPages = saved, bytes = bytes, error = "", failure = null, stage = stage) else it }
            }
            currentCoroutineContext().ensureActive()
            stage = GalleryDownloadStage.COMMITTING
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(stage = stage) else it }
            val audit = repository.commitDownload(record.entry, layout, requested)
            if (audit.complete) runCatching { index.putComic(record.entry, audit.bytes, layout = layout) }
            index.invalidate()
            val saved = audit.validPages
            update(record.id) { if (it.state == GalleryDownloadState.DOWNLOADING) it.copy(state = GalleryDownloadState.COMPLETED, pages = saved.size,
                completedPages = saved, bytes = audit.bytes, selectedPages = if (audit.complete) null else requested.sorted(), error = "", failure = null, retryAt = 0) else it }
        } catch (wait: RetryWait) {
            try {
                updateIf(record.id, { it.state == GalleryDownloadState.DOWNLOADING }) {
                    it.copy(state = GalleryDownloadState.RETRY_WAIT, error = wait.failure.message, failure = wait.failure.kind,
                        stage = stage, retryAt = wait.failure.retryAt)
                }
                task(record.id)?.takeIf { it.state == GalleryDownloadState.RETRY_WAIT }?.let(::scheduleRetry)
            } catch (storage: GalleryStorageException) {
                fail(record.id, GalleryDownloadState.DOWNLOADING, storage.message.orEmpty(), GalleryFailureKind.STORAGE, stage)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                try {
                    updateIf(record.id, { it.state == GalleryDownloadState.DOWNLOADING }) { it.copy(state = GalleryDownloadState.QUEUED) }
                } catch (storage: GalleryStorageException) {
                    fail(record.id, GalleryDownloadState.DOWNLOADING, storage.message.orEmpty(), GalleryFailureKind.STORAGE, stage)
                }
            }
            throw e
        } catch (e: Exception) {
            val failure = DownloadRecovery.classify(e, stage)
            fail(record.id, GalleryDownloadState.DOWNLOADING, failure.message, failure.kind, stage)
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

private val runnableStates = setOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING)
private val activeStates = runnableStates + GalleryDownloadState.RETRY_WAIT
private const val REMOVAL_FAILURE_PREFIX = "删除失败，请重试删除："

internal val GalleryDownload.failedRemoval: Boolean
    get() = state == GalleryDownloadState.FAILED && error.startsWith(REMOVAL_FAILURE_PREFIX)
