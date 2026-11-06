@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.downloads

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.exoplayer.scheduler.Requirements
import com.r34v.r34v.R34Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** One policy for both download engines; a manual pause remains a manual pause. */
class DownloadPolicy(private val app: R34Application) {
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val revision = MutableStateFlow(0)
    val unmeteredOnly get() = app.library.bool("download_unmetered")
    private fun requirements() = Requirements(if (unmeteredOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    val allowed get() = requirements().checkRequirements(app)
    private var refresh: Job? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "settings_v1_download_unmetered") apply()
    }
    fun start() {
        app.library.prefs.registerOnSharedPreferenceChangeListener(listener)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { apply() }
            override fun onLost(network: Network) { apply() }
        })
        apply()
    }
    private fun apply() {
        scope.launch {
            app.playback.manager.requirements = requirements()
            revision.value++
            refresh?.cancel()
            refresh = scope.launch {
                app.galleryDownloads.waitForNetwork()
                if (allowed) app.galleryDownloads.resumePending()
            }
        }
    }
    fun setUnmetered(value: Boolean) { app.library.set("download_unmetered", value) }
}
