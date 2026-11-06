@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.downloads.GalleryDownloadStore
import com.r34v.r34v.playback.PlaybackStore
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors

/** Opt-in live evidence, not a synthetic success test. Each row reports its own outcome. */
@RunWith(AndroidJUnit4::class)
class LiveDownloadAudit {
    @org.junit.Before fun explicitSelection() = requireLiveProbe(javaClass.name)
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as R34Application
    private val output get() = File(app.cacheDir, "live-download-audit").apply { mkdirs() }
    private fun cleanError(error: Throwable) = "${error.javaClass.simpleName}: ${error.message}".replace(Regex("https?://[^\\s]+")) { match ->
        match.value.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/…" } ?: "[url]"
    }.take(800)
    private class AuditContext(base: Context, val root: File, val prefix: String) : ContextWrapper(base) {
        val preferences = mutableSetOf<String>()
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
    }
    @Test fun auditSelectedSources() = runBlocking {
        val selected = InstrumentationRegistry.getArguments().getString("probeSites").orEmpty().split(',').filter(String::isNotBlank).toSet()
        val rows = mutableListOf<MutableMap<String, Any>>()
        val sources = SiteRegistry.all.filter { selected.isEmpty() || it.id in selected }
        check(sources.isNotEmpty())
        for (site in sources) {
            val row = linkedMapOf<String, Any>("site" to site.id, "kind" to site.category.name, "stage" to "feed", "outcome" to "pending")
            rows += row
            val started = System.currentTimeMillis()
            row["route"] = app.network.routeDescription(site.base)
            if (site.id == "nhentai") row["systemDns"] = listOf("nhentai.net", "i1.nhentai.net", "i2.nhentai.net", "i3.nhentai.net", "i4.nhentai.net").associateWith { host ->
                runCatching { java.net.InetAddress.getAllByName(host).map { it.hostAddress } }.getOrDefault(emptyList())
            }
            try { withTimeout(150_000) { probe(site, row) }; row["outcome"] = "passed" }
            catch (error: Exception) { row["outcome"] = if (error is GalleryLoginRequiredException) "login_required" else "failed"; row["error"] = cleanError(error) }
            row["elapsedMs"] = System.currentTimeMillis() - started
            File(output, "latest.json").writeText(app.library.gson.toJson(rows))
            android.util.Log.i("R34VDownloadAudit", app.library.gson.toJson(row))
        }
    }
    private suspend fun probe(site: SiteContext, row: MutableMap<String, Any>) {
        val adapter = AdapterRegistry.adapter(site, app.network)
        val request = when (site.id) {
            "nhentai" -> FeedRequest(FeedKind.SEARCH, "tag:non-h pages:<10 -tag:lolicon -tag:shotacon")
            "ehentai", "exhentai" -> FeedRequest(FeedKind.CATEGORY, "256")
            else -> adapter.homeRequest()
        }
        val feed = if (site.id == "iwara") IwaraProtocol.feed(app.network.json("${site.apiBase}/videos?sort=date&rating=general&limit=5&page=0"), site, FeedRequest()) else adapter.feed(request)
        row["feedItems"] = feed.videos.size
        val candidates = feed.videos.take(8).filterNot { Regex("lolicon|shotacon|underage", RegexOption.IGNORE_CASE).containsMatchIn(it.title) }
        check(candidates.isNotEmpty()) { "No eligible sample in the returned feed" }
        val video = if (site.mediaKind == MediaKind.GALLERY) candidates.minBy { Regex("\\d+").find(it.duration)?.value?.toIntOrNull() ?: Int.MAX_VALUE } else candidates.first { it.type.isBlank() || site.id != "iwara" }
        row["sampleId"] = video.id
        row["stage"] = "detail"
        if (site.mediaKind == MediaKind.VIDEO) {
            val detail = adapter.detail(video.url)
            row["streams"] = detail.streams.map { it.label }
            row["officialDownloadQualities"] = detail.downloads.map { it.label }
            val stream = detail.streams.lastOrNull() ?: error("No playable source")
            row["stage"] = "media_range"
            val method = PlaybackStore::class.java.getDeclaredMethod("httpFor", String::class.java, SiteContext::class.java).apply { isAccessible = true }
            val factory = method.invoke(app.playback, stream.url, site) as DataSource.Factory
            for (offset in listOf(0L, 65536L)) {
                val data = factory.createDataSource()
                try {
                    data.open(DataSpec.Builder().setUri(stream.url).setPosition(offset).setLength(4096).build())
                    val buffer = ByteArray(4096); var bytes = 0
                    while (bytes < buffer.size) { val n = data.read(buffer, bytes, buffer.size - bytes); if (n == -1) break; bytes += n }
                    check(bytes > 0) { "Empty media response" }
                    if (offset == 0L) check(!buffer.take(bytes.coerceAtMost(128)).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith("<")) { "Media endpoint returned HTML" }
                    row["bytesAt$offset"] = bytes
                    row["rangeAt$offset"] = data.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull().orEmpty()
                } finally { data.close() }
            }
            detail.downloads.lastOrNull()?.let { official ->
                runCatching {
                    val source = (method.invoke(app.playback, official.url, site) as DataSource.Factory).createDataSource()
                    try {
                        source.open(DataSpec.Builder().setUri(official.url).setLength(4096).build())
                        val bytes = source.read(ByteArray(4096), 0, 4096)
                        check(bytes > 0) { "Empty official media response" }
                        row["officialSampleBytes"] = bytes
                        row["officialSampleHost"] = official.url.toHttpUrlOrNull()?.host.orEmpty()
                    } finally { source.close() }
                }.onFailure { row["officialSampleError"] = cleanError(it) }
            }
            val expectedBytes = row["rangeAt0"].toString().substringAfterLast('/').toLongOrNull()
            if (expectedBytes != null && expectedBytes in 1..(64L * 1024 * 1024)) {
                row["stage"] = "full_video_download"
                fullVideo(site, stream, factory, row)
            } else row["fullVideo"] = "not_run_unknown_size_or_over_64MiB"
            row["stage"] = "finished"
            return
        }
        val nonce = System.nanoTime().toString()
        val context = AuditContext(ins.targetContext, File(app.cacheDir, "download-audit-$nonce"), "audit-$nonce")
        val prefs = LibraryStore(context)
        prefs.prefs.edit().putString("download_root_requested", "private").putString("download_root_applied", "private").commit()
        val storage = StorageRoot(context, prefs, File(context.root, "shared"))
        val repository = GalleryRepository(context, app.network, storage)
        val index = LibraryIndex(context, storage)
        val downloads = GalleryDownloadStore(context, repository, app.network, index)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gallery = repository.prepareDownload(video)
            row["pages"] = gallery.pageCount
            row["stage"] = "download"
            if (gallery.pageCount <= 120) {
                val record = GalleryDownload(downloads.key(video), video)
                context.getSharedPreferences("gallery_downloads_v1", Context.MODE_PRIVATE).edit().putString(record.id, app.library.gson.toJson(record)).commit()
                val worker = downloads.next(scope) ?: error("Download worker did not start")
                val budget = scope.launch { while (worker.isActive) { if ((downloads.task(record.id)?.bytes ?: 0) > 64L * 1024 * 1024) { worker.cancel(); break }; delay(100) } }
                try { worker.join() } finally { budget.cancelAndJoin() }
                val result = downloads.task(record.id) ?: error("Download record disappeared")
                row["downloadState"] = result.state.name; row["downloadedPages"] = result.pages; row["bytes"] = result.bytes
                check(result.state == GalleryDownloadState.COMPLETED) { result.error.ifBlank { "Stopped before completion (time/byte budget or cancellation)" } }
                check(repository.isDownloaded(video)) { "Missing complete-book integrity marker" }
                repository.detail(video, offlineOnly = true)
                row["fullBook"] = "completed_and_offline_verified"
            } else {
                val first = adapter.galleryPageLink(gallery, 1)
                val last = adapter.galleryPageLink(gallery, gallery.pageCount)
                val sizes = listOf(1 to first, gallery.pageCount to last).map { (page, url) -> repository.downloadPage(video, page, url).second }
                row["bytes"] = sizes.sum(); row["fullBook"] = "not_run_over_120_pages"
            }
            row["stage"] = "offline_image"
            val first = repository.cachedPageUrl(video, 1) ?: error("No saved first-page mapping")
            repository.page(site, first, entry = video, offlineOnly = true).bitmap.recycle()
            row["offlineImage"] = "decoded_without_network"
            row["stage"] = "finished"
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            downloads.task(downloads.key(video))?.let { row["downloadState"] = it.state.name; row["downloadedPages"] = it.pages; row["bytes"] = it.bytes }
            context.preferences.forEach { ins.targetContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            check(context.root.canonicalPath.startsWith(app.cacheDir.canonicalPath + File.separator))
            context.root.deleteRecursively()
        }
    }
    private suspend fun fullVideo(site: SiteContext, stream: Stream, factory: DataSource.Factory, row: MutableMap<String, Any>) {
        val nonce = System.nanoTime().toString()
        val directory = File(app.cacheDir, "video-download-audit-$nonce")
        val databaseName = "video-download-audit-$nonce.db"
        val helper = object : SQLiteOpenHelper(ins.targetContext, databaseName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) = Unit
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val provider = object : DatabaseProvider {
            override fun getReadableDatabase() = helper.readableDatabase
            override fun getWritableDatabase() = helper.writableDatabase
        }
        val cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        val executor = Executors.newFixedThreadPool(2)
        var manager: DownloadManager? = null
        val id = "audit:${site.id}:$nonce"
        try {
            val download = withContext(Dispatchers.Main) {
                DownloadManager(ins.targetContext, provider, cache, factory, executor).apply {
                    maxParallelDownloads = 1
                    resumeDownloads()
                    addDownload(DownloadRequest.Builder(id, Uri.parse(stream.url)).setMimeType(MimeTypes.VIDEO_MP4).setCustomCacheKey(id).build())
                }
            }.also { manager = it }
            var completed: Download? = null
            while (completed == null) {
                currentCoroutineContext().ensureActive()
                val state = download.downloadIndex.getDownload(id)
                if (state?.state == Download.STATE_FAILED) error("Media3 download failed: reason=${state.failureReason}, bytes=${state.bytesDownloaded}")
                if (state?.state == Download.STATE_COMPLETED) completed = state else delay(200)
            }
            check(completed.contentLength > 0 && completed.bytesDownloaded == completed.contentLength)
            check(cache.isCached(id, 0, completed.contentLength)) { "The completed video has cache holes" }
            for (position in listOf(0L, (completed.contentLength - 4096).coerceAtLeast(0))) {
                val offline = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).createDataSource()
                try {
                    offline.open(DataSpec.Builder().setUri(stream.url).setKey(id).setPosition(position).setLength(minOf(4096L, completed.contentLength - position)).build())
                    check(offline.read(ByteArray(4096), 0, 4096) > 0) { "Offline video read failed" }
                } finally { offline.close() }
            }
            row["fullVideo"] = "media3_completed_and_offline_head_tail_verified"
            row["videoBytes"] = completed.bytesDownloaded
            row["videoQuality"] = stream.label
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { manager?.release() }
            cache.release(); executor.shutdownNow(); helper.close()
            ins.targetContext.deleteDatabase(databaseName)
            check(directory.canonicalPath.startsWith(app.cacheDir.canonicalPath + File.separator)); directory.deleteRecursively()
        }
    }
}
