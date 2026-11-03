@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
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
    // Build each source against the current network settings, including proxy and DoH.
    private val http = DataSource.Factory { OkHttpDataSource.Factory(network.mediaClient).setUserAgent(Site.UA).setDefaultRequestProperties(mapOf("Referer" to Site.BASE + "/")).createDataSource() }
    val manager: DownloadManager by lazy {
        DownloadManager(context, database, downloadCache, http, Executors.newFixedThreadPool(3)).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    if (download.state == Download.STATE_FAILED && refreshed.add(download.request.id)) {
                        scope.launch {
                            runCatching {
                                val meta = metadata(download) ?: return@runCatching
                                val detail = Repository(network).detail(meta.video.url)
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
    fun sourceFactory(offline: Boolean = false): DataSource.Factory {
        val streaming = CacheDataSource.Factory().setCache(streamCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory().setCache(downloadCache).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(if (offline) null else streaming)
    }
    fun key(video: Video, quality: String) = "${video.id}:$quality"
    fun item(video: Video, stream: Stream) = MediaItem.Builder().setUri(stream.url).setMediaId(video.id).setCustomCacheKey(key(video, stream.label)).build()
    fun add(video: Video, stream: Stream) {
        val request = DownloadRequest.Builder(key(video, stream.label), Uri.parse(stream.url))
            .setCustomCacheKey(key(video, stream.label)).setData(library.gson.toJson(DownloadMeta(video, stream.label)).toByteArray()).build()
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
    fun metadata(download: Download): DownloadMeta? = runCatching { library.gson.fromJson(String(download.request.data), DownloadMeta::class.java) }.getOrNull()
    fun completed(video: Video): Download? = downloads().firstOrNull { it.state == Download.STATE_COMPLETED && metadata(it)?.video?.id == video.id }
    fun clearStreaming() {
        // All access is serialized by Media3's cache. Completed downloads use a separate cache.
        streamCache.keys.toList().forEach(streamCache::removeResource)
    }
    val streamingBytes get() = streamCache.cacheSpace
}

class VideoDownloadService : DownloadService(40, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "video-downloads", com.r34v.r34v.R.string.download_channel, 0) {
    private val app get() = application as R34Application
    private val notifications by lazy { DownloadNotificationHelper(this, "video-downloads") }
    override fun getDownloadManager() = app.playback.manager
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 41)
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notifications.buildProgressNotification(this, android.R.drawable.stat_sys_download, null, "视频缓存", downloads, notMetRequirements)
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.playback.manager.pauseDownloads()
        stopSelf()
    }
}
