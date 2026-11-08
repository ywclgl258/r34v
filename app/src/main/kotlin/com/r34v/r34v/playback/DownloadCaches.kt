@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.r34v.r34v.data.StorageRoot
import java.io.Closeable

/** One owner per allowed cache directory; the key index travels with its media files. */
class DownloadCaches(@Suppress("UNUSED_PARAMETER") context: Context, private val storage: StorageRoot,
                     private val database: DatabaseProvider) : Closeable {
    private val caches = mutableMapOf<String, SimpleCache>()

    @Synchronized fun cache(sourceRoot: String = ""): SimpleCache {
        val layout = storage.sourceLayout(sourceRoot)
        return caches.getOrPut(layout.root.canonicalPath) {
            storage.ensure(layout.mode)
            // Also converts an existing SQLite-backed index while that database is available.
            SimpleCache(layout.videoCache, NoOpCacheEvictor(), database, null, false, true).also {
                try { it.checkInitialization() } catch (error: Exception) { it.release(); throw error }
            }
        }
    }

    private fun layouts(sourceRoot: String) = if (sourceRoot.isNotBlank()) listOf(storage.sourceLayout(sourceRoot))
        else (listOf(storage.layout()) + storage.readableLayouts()).distinctBy { it.root.canonicalPath }

    @Synchronized private fun existing(layout: StorageRoot.Layout): SimpleCache? =
        caches[layout.root.canonicalPath] ?: layout.videoCache.takeIf {
            it.isDirectory && it.listFiles().orEmpty().isNotEmpty()
        }?.let { cache(layout.root.absolutePath) }

    private fun complete(cache: SimpleCache, key: String): Boolean {
        if (HlsDownloads.uri(cache, key) != null) return HlsDownloads.complete(cache, key)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length) && cache.getCachedSpans(key)
            .filter { it.position < length }.all { span -> span.file?.let { it.isFile && it.length() == span.length } == true }
    }

    fun cached(key: String, sourceRoot: String = ""): Boolean = runCatching {
        layouts(sourceRoot).any { layout -> existing(layout)?.let { complete(it, key) } == true }
    }.getOrDefault(false)

    fun readCache(key: String, sourceRoot: String = ""): SimpleCache = layouts(sourceRoot)
        .mapNotNull(::existing).firstOrNull { complete(it, key) } ?: cache(sourceRoot)

    fun hlsUri(key: String, sourceRoot: String = ""): String? = HlsDownloads.uri(readCache(key, sourceRoot), key)

    fun remove(key: String) {
        layouts("").mapNotNull(::existing).forEach { HlsDownloads.remove(it, key) }
    }

    /** Merge media by cache key, preserving a pre-existing shared library and its index/UID. */
    fun copyToShared() {
        val source = existing(storage.layoutOf(StorageRoot.Mode.PRIVATE)) ?: return
        val target = cache(storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath)
        if (source === target) return
        val offline = CacheDataSource.Factory().setCache(source).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(null)
        val destination = CacheDataSource.Factory().setCache(target).setUpstreamDataSourceFactory(offline)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        for (key in source.keys.toList()) {
            val length = ContentMetadata.getContentLength(source.getContentMetadata(key))
            val previousLength = ContentMetadata.getContentLength(target.getContentMetadata(key))
            check(length <= 0 || previousLength <= 0 || length == previousLength) { "同一视频的缓存长度不一致，已停止迁移" }
            for (span in source.getCachedSpans(key).toList()) {
                val spec = DataSpec.Builder().setUri("https://r34v.local/cache-copy").setKey(key)
                    .setPosition(span.position).setLength(span.length).build()
                CacheWriter(destination.createDataSource(), spec, null, null).cache()
            }
            if (length > 0) target.applyContentMetadataMutations(key,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), length))
            val metadata = source.getContentMetadata(key)
            val saved = metadata.get(HlsDownloads.METADATA, "").orEmpty()
            val mutations = ContentMetadataMutations()
            if (saved.isNotBlank()) mutations.set(HlsDownloads.METADATA, saved)
            ContentMetadata.getRedirectedUri(metadata)?.let { ContentMetadataMutations.setRedirectedUri(mutations, it) }
            target.applyContentMetadataMutations(key, mutations)
        }
    }

    /** Production keeps these open for the process lifetime; fixtures release their directory locks. */
    @Synchronized override fun close() {
        caches.values.forEach(SimpleCache::release)
        caches.clear()
    }
}
