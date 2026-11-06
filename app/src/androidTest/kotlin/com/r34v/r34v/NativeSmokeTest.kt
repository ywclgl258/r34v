@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.*
import java.io.File
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @get:org.junit.Rule val ui = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @get:org.junit.Rule val existingUser = ExistingUserRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun backgroundDownloadPausesResumesAndPlaysOffline() {
        val app = context.applicationContext as R34Application
        val previousSite = app.library.siteId
        val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        val originalClient = app.network.client
        clientField.set(app.network, originalClient.newBuilder().proxy(java.net.Proxy.NO_PROXY).build())
        val clip = instrumentation.context.assets.open("test-pattern.mp4").use { it.readBytes() }
        val ranges = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")
                    val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (start > 0) ranges.incrementAndGet()
                    val body = clip.copyOfRange(start.coerceAtMost(clip.size), clip.size)
                    return MockResponse().setResponseCode(if (range != null) 206 else 200)
                        .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        .apply { if (range != null) setHeader("Content-Range", "bytes $start-${clip.size - 1}/${clip.size}") }
                        .setBody(Buffer().write(body)).throttleBody(8192, 100, TimeUnit.MILLISECONDS)
                }
            }
            start()
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val video = Video("download-smoke-test", "Download test", "https://example.invalid/video/test/", site = "hanime1")
        app.library.switchSite("iwara")
        val stream = Stream("720p", server.url("/sample.mp4").toString())
        val id = app.playback.key(video, stream.label)
        val manager = app.playback.manager
        lateinit var previousRequirements: Requirements
        var previouslyPaused = false
        instrumentation.runOnMainSync {
            previousRequirements = manager.requirements
            previouslyPaused = manager.downloadsPaused
        }
        fun waitFor(timeout: Long = 15000, predicate: (Download?) -> Boolean): Download? {
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline) {
                val download = app.playback.manager.downloadIndex.getDownload(id)
                if (predicate(download)) return download
                Thread.sleep(100)
            }
            error("Download state did not match: ${app.playback.manager.downloadIndex.getDownload(id)?.state}")
        }
        try {
            // The fixture is loopback-only and must not depend on public-network validation.
            instrumentation.runOnMainSync { manager.requirements = Requirements(0); app.playback.add(video, stream) }
            waitFor { it?.state == Download.STATE_DOWNLOADING && it.bytesDownloaded > 10000 }
            instrumentation.runOnMainSync { app.playback.pause(id, true) }
            waitFor { it?.state == Download.STATE_STOPPED }
            // Also exercise the global pause used when Android's foreground-service time limit expires.
            instrumentation.runOnMainSync { app.playback.manager.pauseDownloads() }
            instrumentation.runOnMainSync { app.playback.pause(id, false) }
            val complete = waitFor(30000) { it?.state == Download.STATE_COMPLETED }!!
            assertTrue(complete.bytesDownloaded >= clip.size)
            val mediaRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("https://hanime1.me/", mediaRequest.getHeader("Referer"))
            assertEquals(SiteRegistry.HANIME1.userAgent, mediaRequest.getHeader("User-Agent")); assertNull(mediaRequest.getHeader("Cookie"))
            assertTrue("Resume did not use HTTP Range", ranges.get() > 0)
            server.shutdown()
            val source = app.playback.sourceFactory(offline = true).createDataSource()
            val output = java.io.ByteArrayOutputStream()
            try {
                source.open(DataSpec.Builder().setUri(stream.url).setKey(id).build())
                val buffer = ByteArray(8192)
                while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
            } finally { source.close() }
            assertArrayEquals(clip, output.toByteArray())
        } finally {
            instrumentation.runOnMainSync {
                app.playback.remove(id)
                manager.requirements = previousRequirements
                if (previouslyPaused) manager.pauseDownloads() else manager.resumeDownloads()
                activity.finish()
            }
            app.library.switchSite(previousSite)
            clientField.set(app.network, originalClient)
            runCatching { server.shutdown() }
        }
    }

    @Test fun flutterDataMigrationIsLosslessAndIdempotent() {
        val previousSite = SiteRegistry.current
        val old = context.getSharedPreferences("migration_fixture_old", Context.MODE_PRIVATE)
        val native = context.getSharedPreferences("migration_fixture_native", Context.MODE_PRIVATE)
        old.edit().clear().putString("flutter.watch_history_v1", """[{"id":"42","title":"Migration example","thumb":"","path":"/video/42/example/","pos":300,"dur":600,"at":1234,"q":"720p"}]""")
            .putString("flutter.favorites_v1", """[{"id":"42","title":"Old favorite","path":"/video/42/example/","at":1234}]""")
            .putString("flutter.auth_session_v1", """{"u":"test-user","c":{"PHPSESSID":"test-session"},"at":1234}""")
            .putString("flutter.search_history_v1", "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![\"example query\"]")
            .putString("flutter.settings_v1_speed", "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu1.5")
            .putString("flutter.settings_v1_quality", "1080p").putBoolean("flutter.settings_v1_doh", false).commit()
        native.edit().clear().commit()
        try {
            SiteRegistry.current = SiteRegistry.IWARA
            val store = LibraryStore(context, "migration_fixture_native", "migration_fixture_old")
            assertEquals(300L, store.history().single().position)
            assertEquals("r34v", store.history().single().site)
            assertEquals("42", store.favorites().single().target)
            assertEquals("r34v", store.favorites().single().site)
            assertEquals("1080p", store.string("quality")); assertFalse(store.prefs.contains("settings_v1_doh"))
            assertEquals("test-user", store.username); assertEquals("test-session", store.legacyCookies()["PHPSESSID"])
            assertEquals(listOf("example query"), store.searches()); assertEquals("1.5", store.string("native_speed"))
            store.saveWatch(store.history().single().copy(position = 400))
            assertEquals(400L, LibraryStore(context, "migration_fixture_native", "migration_fixture_old").history().single().position)
            assertTrue(old.contains("flutter.watch_history_v1"))
        } finally { old.edit().clear().commit(); native.edit().clear().commit(); SiteRegistry.current = previousSite }
    }

    @Test fun namespacedLegacyRowsKeepOwnersAndPreserveExplicitUnknownSites() {
        val previousSite = SiteRegistry.current
        val name = "legacy-site-owner-${System.nanoTime()}"
        val file = File(context.filesDir, "$name.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val store = LibraryStore(context, name, "$name-old")
            store.switchSite("iwara")
            val path = store.gson.toJson(file.absolutePath)
            store.prefs.edit()
                .putString("r34v:watch_history_v1", """[{"id":"legacy"},{"id":"explicit","site":"retired-site"}]""")
                .putString("r34v:favorites_v1", """[{"t":"legacy"},{"t":"explicit","site":"retired-site"}]""")
                .putString("r34v:video_cache_v1", """[{"id":"legacy","path":$path},{"id":"explicit","path":$path,"site":"retired-site"}]""").commit()
            assertEquals(mapOf("legacy" to "r34v", "explicit" to "retired-site"), store.history("r34v").associate { it.id to it.site })
            assertEquals(mapOf("legacy" to "r34v", "explicit" to "retired-site"), store.favorites("r34v").associate { it.target to it.site })
            assertEquals(mapOf("legacy" to "r34v", "explicit" to "retired-site"), store.localVideos("r34v").associate { it.id to it.site })
        } finally {
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-old"); file.delete()
            SiteRegistry.current = previousSite
        }
    }

    @Test fun nativePlayerDecodesSeeksAndReleases() {
        val app = context.applicationContext as R34Application
        val clip = File(context.filesDir, "native-test-pattern.mp4")
        instrumentation.context.assets.open("test-pattern.mp4").use { input -> clip.outputStream().use(input::copyTo) }
        val cacheKey = "${app.library.siteId}:video_cache_v1"
        val original = app.library.prefs.getString(cacheKey, null)
        val meta = LocalVideo("native-test-pattern", "Native playback test", clip.absolutePath)
        app.library.prefs.edit().putString(cacheKey, app.library.gson.toJson(listOf(meta))).commit()
        val intent = Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("video", app.library.gson.toJson(Video(meta.id, meta.title)))
            .putExtra("local", clip.absolutePath)
        var activity: PlayerActivity? = null
        try {
            activity = instrumentation.startActivitySync(intent) as PlayerActivity
            val engineField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val deadline = System.currentTimeMillis() + 20000
            var ready = false
            while (System.currentTimeMillis() < deadline && !ready) {
                instrumentation.runOnMainSync { ready = (engineField.get(activity) as? ExoPlayer)?.playbackState == Player.STATE_READY }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("Native decoder did not become ready", ready)
            instrumentation.runOnMainSync {
                val player = engineField.get(activity) as ExoPlayer
                assertTrue(player.videoSize.width > 0); player.seekTo(2000); player.setPlaybackSpeed(1.5f)
                assertEquals(1.5f, player.playbackParameters.speed, .001f)
            }
            Thread.sleep(1000)
            instrumentation.runOnMainSync {
                assertTrue((engineField.get(activity) as ExoPlayer).currentPosition >= 2000)
            }
            if (ui.onAllNodesWithContentDescription("全屏").fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("player-surface").performTouchInput { click() }
            ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("全屏").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("全屏").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("退出全屏").fetchSemanticsNodes().isNotEmpty() }
            instrumentation.runOnMainSync {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
            ui.onNodeWithContentDescription("清晰度，当前 本地").assertExists()
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            val releaseDeadline = System.currentTimeMillis() + 5000
            var released = false
            while (!released && System.currentTimeMillis() < releaseDeadline) {
                instrumentation.runOnMainSync { released = engineField.get(activity) == null }
                if (!released) Thread.sleep(100)
            }
            assertTrue("Player was not released on stop", released)
            assertNotNull(app.library.watch(meta.id))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            app.library.prefs.edit().apply { if (original == null) remove(cacheKey) else putString(cacheKey, original) }.commit()
            app.library.removeWatch(meta.id); clip.delete()
        }
    }

    @Test fun legacySerializedSearchListMigratesSafely() {
        val old = context.getSharedPreferences("migration_list_old", Context.MODE_PRIVATE)
        val native = context.getSharedPreferences("migration_list_native", Context.MODE_PRIVATE)
        val bytes = java.io.ByteArrayOutputStream().apply { java.io.ObjectOutputStream(this).use { it.writeObject(arrayListOf("first", "second")) } }.toByteArray()
        old.edit().clear().putString("flutter.search_history_v1", "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()
        native.edit().clear().commit()
        try { assertEquals(listOf("first", "second"), LibraryStore(context, "migration_list_native", "migration_list_old").searches()) }
        finally { old.edit().clear().commit(); native.edit().clear().commit() }
    }

    @Test fun playbackHistoryKeepsItsOwnerWhenActiveSiteChanges() {
        val previous = SiteRegistry.current
        val name = "compose-watch-owner-${System.nanoTime()}"
        try {
            val store = LibraryStore(context, name, "$name-old")
            store.switchSite("hanime1")
            store.saveWatch(Watch("owner-test", "Owner test", position = 20, duration = 60, site = "r34v"))
            assertTrue(store.history().isEmpty())
            assertEquals(20L, store.watch("owner-test", "r34v")!!.position)
            store.switchSite("r34v"); assertEquals("owner-test", store.history().single().id)
        } finally { context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-old"); SiteRegistry.current = previous }
    }
}
