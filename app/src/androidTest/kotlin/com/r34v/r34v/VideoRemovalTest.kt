@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoRemovalTest : ComposeUiCase() {
    @Test fun deletingWhileAFailedDownloadRefreshesDoesNotResurrectIt() = ComposeFixture().use { f ->
        f.launch(); waitHome()
        val video = f.video("r34v")
        val stream = runBlocking { AdapterRegistry.adapter(SiteRegistry.R34V, f.app.network).detail(video.url).streams.first() }
        val id = f.app.playback.key(video, stream.label)
        val refreshStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRefresh = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.detailHook = { refreshStarted.complete(Unit); releaseRefresh.await() }
        f.videoStatus = 503
        try {
            ui.runOnIdle { f.app.playback.add(video, stream) }
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(20000) { refreshStarted.await() } }
            ui.runOnIdle { f.app.playback.remove(id) }
            ui.waitUntil(15000) { f.app.playback.downloads().none { it.request.id == id } }
            releaseRefresh.complete(Unit)
            Thread.sleep(500)
            assertTrue(f.app.playback.downloads().none { it.request.id == id })
            assertFalse(f.app.playback.cached(id))
            f.videoStatus = 0
            ui.runOnIdle { f.app.playback.add(video, stream) }
            ui.waitUntil(20000) { f.app.playback.downloads().any { it.request.id == id && it.state == Download.STATE_COMPLETED } }
        } finally {
            releaseRefresh.complete(Unit); f.videoStatus = 0
            ui.runOnIdle { f.app.playback.remove(id) }
        }
    }

    @Test fun immediatelyReaddingADeletedVideoWaitsForItsOldFilesToBeRemoved() = ComposeFixture().use { f ->
        f.launch(); waitHome()
        val video = f.video("r34v")
        val stream = runBlocking { AdapterRegistry.adapter(SiteRegistry.R34V, f.app.network).detail(video.url).streams.first() }
        val id = f.app.playback.key(video, stream.label)
        val manager = f.app.playback.manager
        var failure: Exception? = null
        val listener = object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: androidx.media3.exoplayer.offline.DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) failure = finalException
            }
        }
        lateinit var requirements: Requirements
        var paused = false
        ui.runOnIdle { manager.addListener(listener); requirements = manager.requirements; paused = manager.downloadsPaused; manager.requirements = Requirements(0) }
        fun task() = manager.downloadIndex.getDownload(id)
        try {
            ui.runOnIdle { f.app.playback.remove(id) }
            ui.runOnIdle { f.app.playback.add(video, stream) }
            ui.waitUntil(20000) { task()?.state in listOf(Download.STATE_COMPLETED, Download.STATE_FAILED) }
            assertEquals("Initial download failure: ${failure?.stackTraceToString()}", Download.STATE_COMPLETED, task()?.state)
            val first = task()!!.startTimeMs
            ui.runOnIdle { f.app.playback.remove(id); f.app.playback.add(video, stream) }
            ui.waitUntil(20000) { task()?.let { it.startTimeMs > first && it.state == Download.STATE_COMPLETED } == true }
            assertTrue(f.app.playback.cached(id))
            val records = runBlocking { f.app.index.scan(f.app.playback::cached) }
            assertTrue(records.any { it.cacheKey == id && it.state == LibraryRecord.STATE_COMPLETED })
            ui.runOnIdle { f.app.playback.remove(id) }
            ui.waitUntil(15000) { task() == null && !f.app.playback.cached(id) }
            ui.runOnIdle { f.app.playback.remove(id); f.app.playback.add(video, stream) }
            ui.waitUntil(20000) { task()?.state == Download.STATE_COMPLETED }
            assertTrue(f.app.playback.cached(id))
        } finally {
            ui.runOnIdle { f.app.playback.remove(id) }
            ui.waitUntil(15000) { task() == null && !f.app.playback.cached(id) }
            ui.runOnIdle { manager.removeListener(listener); manager.requirements = requirements; if (paused) manager.pauseDownloads() else manager.resumeDownloads() }
        }
    }
}
