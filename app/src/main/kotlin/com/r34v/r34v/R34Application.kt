package com.r34v.r34v

import android.app.Application
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.LibraryIndex
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.Repository
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.StorageRoot
import com.r34v.r34v.data.GalleryRepository
import com.r34v.r34v.playback.PlaybackStore
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.r34v.r34v.data.SiteContext
import okhttp3.Call
import java.io.File
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.*
import androidx.media3.exoplayer.offline.Download

class R34Application : Application() {
    val library by lazy { LibraryStore(this) }
    val network by lazy { Network(this, library) }
    val repository by lazy { Repository(network, library) }
    val storage by lazy { StorageRoot(this, library) }
    val index by lazy { LibraryIndex(this, storage, library.gson) }
    val galleries by lazy { GalleryRepository(this, network, storage) }
    val galleryDownloads by lazy { com.r34v.r34v.downloads.GalleryDownloadStore(this, galleries, network, index) }
    val playback by lazy { PlaybackStore(this, network, library, storage, index) }
    private val coverMemory by lazy { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
    private val coverDisk by lazy { DiskCache.Builder().directory(File(cacheDir, "coil-covers").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
    private val coverLoaders = mutableMapOf<String, ImageLoader>()

    /** Resolve the current network client for every call, including after system proxy changes. */
    @Synchronized fun coverLoader(site: SiteContext): ImageLoader = coverLoaders.getOrPut(site.id) {
        ImageLoader.Builder(this).memoryCache(coverMemory).diskCache(coverDisk).components {
            add(OkHttpNetworkFetcherFactory(callFactory = Call.Factory { request ->
                network.client.newCall(request.newBuilder().tag(SiteContext::class.java, site).build())
            }))
        }.build()
    }
    override fun onCreate() {
        super.onCreate()
        SiteRegistry.current = SiteRegistry.byIdOrDefault(library.siteId)
        // A pending storage move is applied on the next start: the cache index is bound to one
        // absolute path and cannot be relocated while the download manager holds it open.
        storage.applyPendingOnStart()
        // Media3's manager is owned by the application main looper, including after service restarts.
        playback.manager
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) { network.invalidate() }
        }, android.content.IntentFilter(android.net.Proxy.PROXY_CHANGE_ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    /** Stop writers before copying, and keep them stopped until the new process binds its cache. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun changeDownloadLocation(mode: StorageRoot.Mode, progress: (String) -> Unit): String {
        val manager = withContext(Dispatchers.Main) { playback.manager }
        val wasPaused = withContext(Dispatchers.Main) { manager.downloadsPaused }
        storage.beginChange()
        var committed = false
        val pausedBooks = mutableListOf<com.r34v.r34v.data.Video>()
        try {
            withContext(Dispatchers.Main) { manager.pauseDownloads() }
            val books = galleryDownloads.tasks().filter { it.state in listOf(com.r34v.r34v.data.GalleryDownloadState.QUEUED, com.r34v.r34v.data.GalleryDownloadState.DOWNLOADING) }
            for (book in books) { pausedBooks += book.entry; galleryDownloads.pause(book.id) }
            withTimeout(30_000) {
                while (withContext(Dispatchers.IO) { playback.downloads().any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_REMOVING } }) delay(50)
            }
            val result = if (mode == StorageRoot.Mode.SHARED) storage.migrate(
                copyVideos = { playback.copyDownloadsToShared() }, report = { _, _, name -> progress("正在复制 $name") })
            else { storage.requestOnRestart(mode); "已准备改回应用内；公共目录文件保留，重启后生效" }
            committed = true
            return result
        } finally {
            storage.endChange()
            if (!committed && !storage.pendingRestart) withContext(NonCancellable) {
                if (!wasPaused) withContext(Dispatchers.Main) { manager.resumeDownloads() }
                pausedBooks.forEach { runCatching { galleryDownloads.add(it) } }
            }
        }
    }
}
