package com.r34v.r34v

import android.app.Application
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.Repository
import com.r34v.r34v.playback.PlaybackStore

class R34Application : Application() {
    val library by lazy { LibraryStore(this) }
    val network by lazy { Network(this, library) }
    val repository by lazy { Repository(network) }
    val playback by lazy { PlaybackStore(this, network, library) }
    override fun onCreate() {
        super.onCreate()
        // Media3's manager is owned by the application main looper, including after service restarts.
        playback.manager
    }
}
