@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import androidx.core.net.toUri

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

class PlaybackStore(private val context: Context, private val network: Network, private val library: LibraryStore,
                    private val storage: StorageRoot, private val index: LibraryIndex) {
    private val database = StandaloneDatabaseProvider(context)
    private val downloadCaches = DownloadCaches(context, storage, database)
    private val downloadCache get() = downloadCaches.cache()
    private val streamCache by lazy { SimpleCache(File(context.cacheDir, "streams"), LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024), database) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val refreshJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val generations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val removals = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val submitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun cleanRemoved(id: String, removed: CompletableDeferred<Unit>) {
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { downloadCaches.remove(id) }
                val parts = id.split(':')
                if (parts.size >= 3) index.removeVideo(parts.first(), parts.subList(1, parts.size - 1).joinToString(":"), parts.last())
                index.invalidate()
            } finally { removals.remove(id, removed); removed.complete(Unit) }
        }
    }

    /**
     * Builds a data source for one URL.
     *
     * Media3's download manager only accepts a single DataSource.Factory, but a request's host is
     * only known when the source opens. Resolving User-Agent and Referer here keeps media working
     * after a site switch, including on CDNs that check the referer.
     */
    private fun httpFor(url: String, owner: SiteContext? = null): DataSource.Factory {
        val host = runCatching { url.toUri().host.orEmpty() }.getOrDefault("")
        val site = owner ?: SiteRegistry.forMediaHost(host)
        val transport = network.mediaClient.newBuilder().apply {
            if (site != null) interceptors().add(0, okhttp3.Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().tag(SiteContext::class.java, site).build())
            })
        }.build()
        return OkHttpDataSource.Factory(transport)
            .setUserAgent(site?.userAgent?.takeIf { it.isNotBlank() } ?: SiteContext.DEFAULT_UA)
            .setDefaultRequestProperties(mapOf("Referer" to (site?.mediaReferer ?: "https://$host/")))
    }

    /** Data source factory for the download manager (host resolved per open). */
    private fun httpFactory(owner: SiteContext? = null) = DataSource.Factory {
        object : DataSource {
            private var impl: DataSource? = null
            override fun addTransferListener(transferListener: TransferListener) {
                impl?.addTransferListener(transferListener)
            }
            override fun open(dataSpec: DataSpec): Long {
                val keyOwner = dataSpec.key?.substringBefore(':')?.let(SiteRegistry::byId)
                val created = httpFor(dataSpec.uri.toString(), owner ?: keyOwner).createDataSource()
                created.addTransferListener(NoOpTransferListener)
                impl = created
                return created.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                impl?.read(buffer, offset, length) ?: -1
            override fun getUri(): Uri? = impl?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = impl?.responseHeaders.orEmpty()
            override fun close() { runCatching { impl?.close() }; impl = null }
        }
    }

    val manager: DownloadManager by lazy {
        val downloader = HlsDownloads.factory(downloadCache, { request ->
            val owner = request.id.substringBefore(':').let(SiteRegistry::byId)
            mediaAware(httpFactory(owner), owner)
        }, Executors.newFixedThreadPool(3))
        DownloadManager(context, DefaultDownloadIndex(database), downloader).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    submitted.remove(download.request.id)
                    val removed = removals.getOrPut(download.request.id) { CompletableDeferred() }
                    cleanRemoved(download.request.id, removed)
                }
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    submitted.remove(download.request.id)
                    if (download.state == Download.STATE_FAILED && !removals.containsKey(download.request.id) && refreshed.add(download.request.id)) {
                        val id = download.request.id
                        val generation = generations[id] ?: 0L
                        val job = scope.launch {
                            try {
                                val meta = metadata(download) ?: return@launch
                                val site = SiteRegistry.byIdOrDefault(meta.site)
                                val detail = AdapterRegistry.adapter(site, network).detail(meta.video.url)
                                ensureActive()
                                val fresh = detail.streams.firstOrNull { it.label == meta.quality } ?: return@launch
                                add(meta.video, fresh, generation)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                            } finally { refreshJobs.remove(id, coroutineContext[Job]) }
                        }
                        refreshJobs[id] = job
                    } else if (download.state == Download.STATE_COMPLETED) {
                        refreshed.remove(download.request.id)
                        refreshJobs.remove(download.request.id)?.cancel()
                        // The sidecar is what survives an uninstall; Media3's database does not.
                        runCatching {
                            val meta = metadata(download) ?: return@runCatching
                            index.putVideo(meta.video, meta.quality, download.bytesDownloaded)
                        }
                    }
                }
            })
            if (!storage.pendingRestart && !storage.changing) resumeDownloads()
        }
    }

    /**
     * Wraps a factory so rou.video's PNG-wrapped manifests and segments are unwrapped before
     * Media3 parses them. Other hosts pass through untouched: the check is per request, and a
     * genuine cover image on the same CDN stays an image.
     */
    private fun mediaAware(factory: DataSource.Factory, owner: SiteContext?): DataSource.Factory = DataSource.Factory {
        val inner = factory.createDataSource()
        object : DataSource {
            private var created: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            override fun addTransferListener(transferListener: TransferListener) {
                listeners += transferListener; created?.addTransferListener(transferListener)
            }
            override fun open(dataSpec: DataSpec): Long {
                val source = if (owner?.id == SiteRegistry.ROUVIDEO.id) RouPng.UnwrappingDataSource(inner) else inner
                source.addTransferListener(NoOpTransferListener)
                created = source
                listeners.forEach(source::addTransferListener)
                return source.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = created?.read(buffer, offset, length) ?: -1
            override fun getUri(): Uri? = created?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = created?.responseHeaders.orEmpty()
            override fun close() { runCatching { created?.close() }; created = null }
        }
    }

    fun sourceFactory(offline: Boolean = false, siteId: String? = null, sourceRoot: String = "", hlsKey: String? = null): DataSource.Factory {
        val owner = siteId?.let(SiteRegistry::byId)
        val cacheKeys = hlsKey?.let(HlsDownloads::keys) ?: androidx.media3.datasource.cache.CacheKeyFactory.DEFAULT
        // Select a restored HLS library once, not once per segment (which would rescan every file).
        val hlsCache by lazy { downloadCaches.readCache(hlsKey.orEmpty(), sourceRoot) }
        val streaming = CacheDataSource.Factory().setCache(streamCache)
            .setCacheKeyFactory(cacheKeys)
            .setUpstreamDataSourceFactory(mediaAware(DefaultDataSource.Factory(context, httpFactory(owner)), owner))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory {
            object : DataSource {
                private var impl: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()
                override fun addTransferListener(listener: TransferListener) { listeners += listener; impl?.addTransferListener(listener) }
                override fun open(spec: DataSpec): Long {
                    val created = CacheDataSource.Factory().setCache(if (hlsKey != null) hlsCache else downloadCaches.readCache(spec.key.orEmpty(), sourceRoot))
                        .setCacheKeyFactory(cacheKeys)
                        .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(if (offline) null else streaming).createDataSource()
                    impl = created; listeners.forEach(created::addTransferListener)
                    return created.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = impl?.read(buffer, offset, length) ?: -1
                override fun getUri(): Uri? = impl?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = impl?.responseHeaders.orEmpty()
                override fun close() { try { impl?.close() } finally { impl = null } }
            }
        }
    }

    fun key(video: Video, quality: String) = key(video.site, video.id, quality)

    /**
     * Plays straight from the download cache by key, with no Media3 download record.
     *
     * This is the path that matters after a reinstall: the database that used to describe the
     * download is gone, but the cache and its index survived on shared storage, so a fully
     * cached key still opens offline. A cache miss raises instead of silently going online.
     */
    fun restoredStream(key: String, sourceRoot: String, fallback: String) =
        Stream(key.substringAfterLast(':'), downloadCaches.hlsUri(key, sourceRoot) ?: fallback)

    fun offlineItem(record: LibraryRecord): MediaItem = item(record.video,
        restoredStream(record.cacheKey, record.sourceRoot, record.url.takeIf { it.startsWith("http") } ?: "https://r34v.local/offline"))
    fun item(video: Video, stream: Stream) =
        MediaItem.Builder().setUri(stream.url).setMediaId("${video.site}:${video.id}")
            .apply { val mime = HlsDownloads.mime(stream.url)
                if (mime != null) setMimeType(mime) else setCustomCacheKey(key(video, stream.label))
            }.build()

    fun add(video: Video, stream: Stream) = add(video, stream, null)
    private fun add(video: Video, stream: Stream, expectedGeneration: Long?) {
        storage.checkDownloadsAllowed()
        val site = SiteRegistry.byIdOrDefault(video.site)
        val normalized = video.copy(site = site.id)
        val id = key(normalized, stream.label)
        fun stale() = expectedGeneration != null && (removals.containsKey(id) || expectedGeneration != (generations[id] ?: 0L))
        if (stale()) return
        removals[id]?.let { removing ->
            scope.launch { removing.await(); if (isActive && !stale()) runCatching { add(normalized, stream, expectedGeneration) } }
            return
        }
        if (stale()) return
        val request = DownloadRequest.Builder(id, stream.url.toUri())
            .apply { val mime = HlsDownloads.mime(stream.url)
                if (mime != null) setMimeType(mime) else setCustomCacheKey(id)
            }
            .setData(library.gson.toJson(DownloadMeta(normalized, stream.label, site.id)).toByteArray()).build()
        submitted.add(request.id)
        try {
            DownloadService.sendResumeDownloads(context, VideoDownloadService::class.java, false)
            DownloadService.sendAddDownload(context, VideoDownloadService::class.java, request, false)
        } catch (error: Exception) { submitted.remove(request.id); throw error }
    }
    fun pause(id: String, pause: Boolean) {
        if (!pause) storage.checkDownloadsAllowed()
        if (!pause) DownloadService.sendResumeDownloads(context, VideoDownloadService::class.java, false)
        DownloadService.sendSetStopReason(context, VideoDownloadService::class.java, id, if (pause) 1 else 0, false)
    }
    fun remove(id: String) {
        val removed = CompletableDeferred<Unit>()
        if (removals.putIfAbsent(id, removed) != null) return
        generations[id] = (generations[id] ?: 0L) + 1L
        refreshed.remove(id)
        refreshJobs.remove(id)?.cancel()
        try {
            // Missing records have no Media3 removal callback. A queued add still needs its
            // ordered remove command; otherwise the absent-record cleanup can finish directly.
            if (id !in submitted && manager.downloadIndex.getDownload(id) == null) cleanRemoved(id, removed)
            else DownloadService.sendRemoveDownload(context, VideoDownloadService::class.java, id, false)
        }
        catch (error: Exception) { removals.remove(id)?.complete(Unit); throw error }
        // Cleanup runs after the manager stops its writer, so completion cannot recreate a sidecar.
    }
    suspend fun removeRestored(record: LibraryRecord) = withContext(Dispatchers.IO) {
        downloadCaches.remove(record.cacheKey)
        index.removeVideo(record.site, record.id, record.quality)
    }

    /**
     * Whether the download cache still holds bytes for [cacheKey].
     *
     * Used when rebuilding the library from disk: a sidecar whose media is gone must not look
     * playable. Any position/length means at least one complete span is cached.
     */
    fun cached(cacheKey: String, sourceRoot: String = ""): Boolean = downloadCaches.cached(cacheKey, sourceRoot)
    suspend fun copyDownloadsToShared() = withContext(Dispatchers.IO) { downloadCaches.copyToShared() }
    fun downloads(): List<Download> = buildList {
        manager.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) add(cursor.download) }
    }
    fun metadata(download: Download): DownloadMeta? =
        runCatching { library.gson.fromJson(String(download.request.data), DownloadMeta::class.java) }.getOrNull()

    /** Downloads belonging to the site that is currently open. */
    fun downloadsFor(siteId: String = library.siteId): List<Download> =
        downloads().filter { metadata(it)?.site == siteId }

    fun completed(video: Video): Download? = downloads().firstOrNull {
        it.state == Download.STATE_COMPLETED && metadata(it)?.video?.id == video.id && metadata(it)?.site == video.site
    }
    fun clearStreaming() {
        // All access is serialized by Media3's cache. Completed downloads use a separate cache.
        streamCache.keys.toList().forEach(streamCache::removeResource)
    }
    val streamingBytes get() = streamCache.cacheSpace

    private object NoOpTransferListener : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    companion object {
        /**
         * Cache identity. The site prefix is not cosmetic: Media3 indexes both the cache and the
         * download database by this string, so two sites sharing a video id would silently reuse
         * each other's media file.
         */
        fun key(siteId: String, videoId: String, quality: String) = "$siteId:$videoId:$quality"
    }
}

class VideoDownloadService : DownloadService(40, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "video-downloads", com.r34v.r34v.R.string.download_channel, 0) {
    private val app get() = application as R34Application
    private val notifications by lazy { DownloadNotificationHelper(this, "video-downloads") }
    override fun getDownloadManager() = app.playback.manager
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 41)
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = android.app.PendingIntent.getActivity(this, 40,
            android.content.Intent(this, com.r34v.r34v.MainActivity::class.java).putExtra("downloads", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return notifications.buildProgressNotification(this, android.R.drawable.stat_sys_download, open, "视频下载", downloads, notMetRequirements)
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.playback.manager.pauseDownloads()
        stopSelf()
    }
}
