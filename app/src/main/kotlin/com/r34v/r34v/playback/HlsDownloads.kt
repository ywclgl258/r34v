@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.Executor

/** HLS keeps one isolated group of resources per download; legacy file keys stay unchanged. */
object HlsDownloads {
    internal const val METADATA = "r34v_hls_download"
    private val gson = Gson()
    private data class Part(@SerializedName("key") val key: String, @SerializedName("position") val position: Long,
                            @SerializedName("length") val length: Long)
    private data class Snapshot(@SerializedName("uri") val uri: String, @SerializedName("parts") val parts: List<Part>)

    fun mime(url: String): String? = when {
        url.substringBefore('?').endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
        Regex("^https?://[^/]+/api/hls/").containsMatchIn(url) -> MimeTypes.APPLICATION_M3U8
        else -> null
    }

    private fun prefix(id: String) = "$id|hls|"
    fun keys(id: String) = CacheKeyFactory { spec -> prefix(id) + spec.uri.toString() }
    private fun snapshot(cache: Cache, id: String): Snapshot? = runCatching {
        gson.fromJson(cache.getContentMetadata(id).get(METADATA, ""), Snapshot::class.java)
    }.getOrNull()
    fun uri(cache: Cache, id: String): String? = snapshot(cache, id)?.uri

    fun complete(cache: Cache, id: String): Boolean {
        val saved = snapshot(cache, id) ?: return false
        return cache.isCached(id, 0, 1) && cache.getCachedSpans(id).all { it.file?.isFile == true } &&
            saved.parts.isNotEmpty() && saved.parts.all { part ->
            part.key.startsWith(prefix(id)) && part.position >= 0 && part.length > 0 &&
                cache.isCached(part.key, part.position, part.length) &&
                cache.getCachedSpans(part.key).filter { it.position < part.position + part.length && it.position + it.length > part.position }
                    .all { it.file?.let { file -> file.isFile && file.length() == it.length } == true }
        }
    }

    private fun record(cache: Cache, request: DownloadRequest) {
        val parts = cache.keys.filter { it.startsWith(prefix(request.id)) }.flatMap { key ->
            cache.getCachedSpans(key).map { Part(key, it.position, it.length) }
        }
        check(parts.isNotEmpty()) { "HLS 下载没有缓存媒体资源" }
        // SimpleCache drops entries with no spans when reopening its portable index. A one-byte
        // marker keeps this metadata entry alive across reinstall and storage migration.
        CacheWriter(CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(byteArrayOf(1)) }.createDataSource(),
            DataSpec.Builder().setUri("https://r34v.local/hls-index").setKey(request.id).setLength(1).build(), null, null).cache()
        cache.applyContentMetadataMutations(request.id, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 1))
        cache.applyContentMetadataMutations(request.id, ContentMetadataMutations()
            .set(METADATA, gson.toJson(Snapshot(request.uri.toString(), parts))))
        check(complete(cache, request.id)) { "HLS 缓存不完整" }
    }

    fun remove(cache: Cache, id: String) {
        cache.keys.filter { it.startsWith(prefix(id)) }.forEach(cache::removeResource)
        cache.removeResource(id)
    }

    fun factory(cache: Cache, upstream: (DownloadRequest) -> DataSource.Factory,
                executor: Executor): DownloaderFactory = DownloaderFactory { request ->
        val hls = request.mimeType == MimeTypes.APPLICATION_M3U8 || mime(request.uri.toString()) != null
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream(request))
        if (hls) source.setCacheKeyFactory(keys(request.id))
        // Pending requests created before HLS support may still carry a progressive cache key.
        val normalized = if (hls) DownloadRequest.Builder(request.id, request.uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8).setStreamKeys(request.streamKeys)
            .setKeySetId(request.keySetId).setData(request.data).build() else request
        val delegate = DefaultDownloaderFactory(source, executor).createDownloader(normalized)
        if (!hls) delegate else object : Downloader {
            override fun download(listener: Downloader.ProgressListener?) {
                cache.removeResource(request.id)
                cache.applyContentMetadataMutations(request.id, ContentMetadataMutations()
                    .set(METADATA, gson.toJson(Snapshot(request.uri.toString(), emptyList()))))
                delegate.download(listener)
                record(cache, request)
            }
            override fun cancel() = delegate.cancel()
            override fun remove() = HlsDownloads.remove(cache, request.id)
        }
    }
}
