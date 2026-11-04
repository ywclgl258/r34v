package com.r34v.r34v

import android.app.Application
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.Repository
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.GalleryRepository
import com.r34v.r34v.playback.PlaybackStore

class R34Application : Application() {
    val library by lazy { LibraryStore(this) }
    val network by lazy { Network(this, library) }
    val repository by lazy { Repository(network) }
    val galleries by lazy { GalleryRepository(this, network) }
    val playback by lazy { PlaybackStore(this, network, library) }
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
