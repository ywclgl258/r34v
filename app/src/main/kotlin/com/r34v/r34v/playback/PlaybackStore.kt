@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

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

class PlaybackStore(private val context: Context, private val network: Network, private val library: LibraryStore) {
    private val database = StandaloneDatabaseProvider(context)
    private val downloadCache by lazy { SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), database) }
    private val streamCache by lazy { SimpleCache(File(context.cacheDir, "streams"), LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024), database) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshed = mutableSetOf<String>()

    /**
     * Builds a data source for one URL.
     *
     * Media3's download manager only accepts a single DataSource.Factory, but a request's host is
     * only known when the source opens. Resolving User-Agent and Referer here keeps media working
     * after a site switch, including on CDNs that check the referer.
     */
    private fun httpFor(url: String, owner: SiteContext? = null): DataSource.Factory {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
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
        DownloadManager(context, database, downloadCache, httpFactory(), Executors.newFixedThreadPool(3)).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    if (download.state == Download.STATE_FAILED && refreshed.add(download.request.id)) {
                        scope.launch {
                            runCatching {
                                val meta = metadata(download) ?: return@runCatching
                                // Signed stream URLs expire; re-resolve against the owning site.
                                val site = SiteRegistry.byIdOrDefault(meta.site)
                                val detail = AdapterRegistry.adapter(site, network).detail(meta.video.url)
                                val fresh = detail.streams.firstOrNull { it.label == meta.quality } ?: return@runCatching
                                add(meta.video, fresh)
                            }
                        }
                    } else if (download.state == Download.STATE_COMPLETED) refreshed.remove(download.request.id)
                }
            })
            resumeDownloads()
        }
    }

    fun sourceFactory(offline: Boolean = false, siteId: String? = null): DataSource.Factory {
        val streaming = CacheDataSource.Factory().setCache(streamCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory(siteId?.let(SiteRegistry::byId))))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory().setCache(downloadCache).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(if (offline) null else streaming)
    }

    fun key(video: Video, quality: String) = key(video.site, video.id, quality)

    fun item(video: Video, stream: Stream) =
        MediaItem.Builder().setUri(stream.url).setMediaId("${video.site}:${video.id}")
            .setCustomCacheKey(key(video, stream.label)).build()

    fun add(video: Video, stream: Stream) {
        val site = SiteRegistry.byIdOrDefault(video.site)
        val normalized = video.copy(site = site.id)
        val request = DownloadRequest.Builder(key(normalized, stream.label), Uri.parse(stream.url))
            .setCustomCacheKey(key(normalized, stream.label))
            .setData(library.gson.toJson(DownloadMeta(normalized, stream.label, site.id)).toByteArray()).build()
        DownloadService.sendResumeDownloads(context, VideoDownloadService::class.java, false)
        DownloadService.sendAddDownload(context, VideoDownloadService::class.java, request, false)
    }
    fun pause(id: String, pause: Boolean) {
        if (!pause) DownloadService.sendResumeDownloads(context, VideoDownloadService::class.java, false)
        DownloadService.sendSetStopReason(context, VideoDownloadService::class.java, id, if (pause) 1 else 0, false)
    }
    fun remove(id: String) = DownloadService.sendRemoveDownload(context, VideoDownloadService::class.java, id, false)
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
