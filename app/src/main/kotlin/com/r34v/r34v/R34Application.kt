package com.r34v.r34v

import android.app.Application
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.Repository
import com.r34v.r34v.data.SiteRegistry
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

class R34Application : Application() {
    val library by lazy { LibraryStore(this) }
    val network by lazy { Network(this, library) }
    val repository by lazy { Repository(network) }
    val galleries by lazy { GalleryRepository(this, network) }
    val galleryDownloads by lazy { com.r34v.r34v.downloads.GalleryDownloadStore(this, galleries, network) }
    val playback by lazy { PlaybackStore(this, network, library) }
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
        // Media3's manager is owned by the application main looper, including after service restarts.
        playback.manager
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) { network.invalidate() }
        }, android.content.IntentFilter(android.net.Proxy.PROXY_CHANGE_ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
}
