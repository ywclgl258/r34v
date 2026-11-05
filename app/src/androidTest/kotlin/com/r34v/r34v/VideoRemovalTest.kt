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
    @Test fun immediatelyReaddingADeletedVideoWaitsForItsOldFilesToBeRemoved() = ComposeFixture().use { f ->
        f.launch(); waitHome()
        val video = f.video("r34v")
        val stream = runBlocking { AdapterRegistry.adapter(SiteRegistry.R34V, f.app.network).detail(video.url).streams.first() }
        val id = f.app.playback.key(video, stream.label)
        val manager = f.app.playback.manager
        lateinit var requirements: Requirements
        var paused = false
        ui.runOnIdle { requirements = manager.requirements; paused = manager.downloadsPaused; manager.requirements = Requirements(0) }
        fun task() = manager.downloadIndex.getDownload(id)
        try {
            ui.runOnIdle { f.app.playback.remove(id) }
            ui.runOnIdle { f.app.playback.add(video, stream) }
            ui.waitUntil(20000) { task()?.state == Download.STATE_COMPLETED }
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
            ui.runOnIdle { manager.requirements = requirements; if (paused) manager.pauseDownloads() else manager.resumeDownloads() }
        }
    }
}
