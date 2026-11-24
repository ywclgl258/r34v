@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LocalBook
import com.r34v.r34v.data.LocalVideo
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.Watch
import com.r34v.r34v.data.entryKey
import com.r34v.r34v.data.personalCatalog
import com.r34v.r34v.playback.LocalSeekPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalSeekPreviewTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as R34Application
    private val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }

    @Test fun shortLocalClipResumesAndSavesAnActualEnd() = withLocalPlayer(autoplay = false, watchedSeconds = 3) { video, engine ->
        ui.runOnIdle { assertEquals(3_000L, engine.currentPosition) }
        val partial = runBlocking { personalCatalog(app) }.single { it.video.entryKey == video.entryKey }
        assertTrue(partial.started)
        assertFalse(partial.completed)

        ui.runOnIdle {
            engine.seekTo(7_500)
            engine.play()
        }
        ui.waitUntil(10_000) { app.library.watch(video.id, video.site)?.ended == true }
        val saved = requireNotNull(app.library.watch(video.id, video.site))
        assertEquals(8L, saved.position)
        assertTrue(saved.finished)
        assertFalse(saved.resumable)
        val finished = runBlocking { personalCatalog(app) }.single { it.video.entryKey == video.entryKey }
        assertTrue(finished.completed)
        assertEquals(1f, finished.fraction ?: 0f, 0f)
    }

    @Test fun finishedClipPreviewDoesNotOverwriteHistoryWithoutPlaying() = withLocalPlayer(
        autoplay = false,
        watchedSeconds = 8,
        ended = true,
        afterClose = { video ->
            val saved = requireNotNull(app.library.watch(video.id, video.site))
            assertEquals(8L, saved.position)
            assertEquals(true, saved.ended)
            assertTrue(saved.finished)
        },
    ) { _, engine ->
        ui.runOnIdle {
            assertFalse(engine.isPlaying)
            assertEquals(0L, engine.currentPosition)
        }
    }

    @Test fun sliderShowsLatestLocalPreviewAndSeeksOnceOnRelease() = withLocalPlayer(autoplay = true) { _, engine ->
        var seeks = 0
        ui.runOnIdle {
            engine.pause()
            engine.seekTo(1_000)
            engine.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks++
                }
            })
        }
        val slider = ui.onNodeWithContentDescription("播放进度")
        val previewTime = hasTestTag("local-seek-preview-time")
        val previewFrame = hasTestTag("local-seek-preview-frame")
        ui.waitUntil(5_000) { ui.onAllNodesWithText("0:08").fetchSemanticsNodes().isNotEmpty() }
        slider.performTouchInput {
            down(percentOffset(.15f, .5f))
            moveTo(percentOffset(.35f, .5f))
            moveTo(percentOffset(.8f, .5f))
        }
        ui.waitUntil(10_000) { ui.onAllNodes(previewFrame, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(previewTime).assertTextEquals("0:06")
        ui.runOnIdle {
            assertEquals(1_000L, engine.currentPosition)
            assertEquals(0, seeks)
        }
        slider.performTouchInput { up() }
        ui.runOnIdle {
            assertTrue(engine.currentPosition in 6_000L..7_000L)
            assertEquals(1, seeks)
        }
        ui.onNode(previewTime).assertDoesNotExist()

        slider.performTouchInput {
            down(percentOffset(.8f, .5f))
            moveTo(percentOffset(.25f, .5f))
        }
        ui.onNode(previewTime).assertExists()
        slider.performTouchInput { cancel() }
        ui.onNode(previewTime).assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, seeks) }
    }

    @Test fun unreadableFramesReturnNoBitmap() {
        val file = File.createTempFile("invalid-preview-", ".mp4", app.cacheDir)
        try {
            file.writeText("invalid video data")
            runBlocking {
                val preview = LocalSeekPreview(file)
                try {
                    assertNull(preview.frameAt(500))
                    assertNull(preview.frameAt(1_000))
                } finally {
                    preview.close()
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun withLocalPlayer(
        autoplay: Boolean,
        watchedSeconds: Long? = null,
        ended: Boolean? = null,
        afterClose: (Video) -> Unit = {},
        test: (Video, ExoPlayer) -> Unit,
    ) {
        val id = java.security.MessageDigest.getInstance("SHA-256")
            .digest("seek-preview-${System.nanoTime()}".toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(app.localLibrary.rootDirectory(), id).apply { check(mkdirs()) }
        val file = File(directory, "clip.mp4")
        val site = SiteRegistry.LOCAL.id
        val store = app.library
        val keys = listOf("$site:video_cache_v1", "$site:watch_history_v1", "settings_v1_auto_fullscreen")
        val previous = store.prefs.all.filterKeys { it in keys }
        try {
            instrumentation.context.assets.open("test-pattern.mp4").use { input -> file.outputStream().use(input::copyTo) }
            File(directory, "book.json").writeText(store.gson.toJson(LocalBook(id, "Local seek test", listOf(file.name), file.length(), kind = "video")))
            val video = app.localLibrary.videoEntry(requireNotNull(app.localLibrary.book(id)))
            store.addLocalVideo(LocalVideo(video.id, video.title, file.absolutePath, site = site), site)
            watchedSeconds?.let { store.saveWatch(Watch(video.id, video.title, position = it, duration = 8, site = site, ended = ended)) }
            store.set("auto_fullscreen", false)
            val intent = Intent(app, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video))
                .putExtra("local", file.absolutePath).putExtra("autoplay", autoplay)
            ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                var ready: ExoPlayer? = null
                ui.waitUntil(15_000) {
                    scenario.onActivity { activity ->
                        val engine = playerField.get(activity) as? ExoPlayer
                        ready = engine?.takeIf { it.playbackState == Player.STATE_READY && it.duration == 8_000L }
                    }
                    ready != null
                }
                test(video, requireNotNull(ready))
            }
            afterClose(video)
        } finally {
            store.prefs.edit().apply {
                for (key in keys) when (val value = previous[key]) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                }
            }.commit()
            directory.deleteRecursively()
        }
    }
}
