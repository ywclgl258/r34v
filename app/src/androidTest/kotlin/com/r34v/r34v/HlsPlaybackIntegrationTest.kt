@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class HlsPlaybackIntegrationTest {
    @Test fun productionDownloadServiceAndPlayerRestoreWrappedHlsWithoutNetwork() = ComposeFixture().use { f ->
        val id = "hls-audit-${System.nanoTime()}"
        val video = Video(id, "Neutral HLS", "https://rou.video/v/$id", site = "rouvideo")
        val stream = Stream("自动", "https://rou.video/api/hls/$id")
        val key = f.app.playback.key(video, stream.label)
        val offline = AtomicBoolean(false)
        val fixture = f.ins.context.assets.list("hls")!!.associateWith { f.ins.context.assets.open("hls/$it").use { input -> input.readBytes() } }
        val field = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        val before = f.app.network.client
        val client = before.newBuilder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.host != "rou.video") return@addInterceptor chain.proceed(chain.request())
            check(!offline.get()) { "Offline playback attempted a network request" }
            val name = url.pathSegments.last()
            val bytes = if (name == id) "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmedia.m3u8\n".toByteArray()
                else fixture[name] ?: error("Unexpected neutral fixture request")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Neutral fixture")
                .body(TestPng.wrap(bytes).toResponseBody("image/png".toMediaType())).build()
        }.build()
        field.set(f.app.network, client)
        var activity: PlayerActivity? = null
        var requirements: androidx.media3.exoplayer.scheduler.Requirements? = null
        try {
            f.ins.waitForIdleSync()
            f.ins.runOnMainSync {
                requirements = f.app.playback.manager.requirements
                // All responses are local neutral fixtures; emulator Internet validation is irrelevant.
                f.app.playback.manager.requirements = androidx.media3.exoplayer.scheduler.Requirements(0)
                f.app.playback.add(video, stream)
            }
            fun waitUntil(message: String, condition: () -> Boolean) {
                val until = android.os.SystemClock.elapsedRealtime() + 25000
                while (android.os.SystemClock.elapsedRealtime() < until) {
                    var done = false
                    f.ins.runOnMainSync { done = condition() }
                    if (done) return
                    Thread.sleep(100)
                }
                fail(message)
            }
            waitUntil("HLS download did not complete") {
                val download = f.app.playback.manager.downloadIndex.getDownload(key)
                assertNotEquals("HLS download failed", Download.STATE_FAILED, download?.state)
                download?.state == Download.STATE_COMPLETED
            }
            assertTrue(f.app.playback.cached(key))
            offline.set(true)
            for (extra in listOf("download", "cached")) {
                activity = f.ins.startActivitySync(Intent(f.ins.targetContext, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("video", f.app.library.gson.toJson(video)).putExtra(extra, key)) as PlayerActivity
                val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                waitUntil("Production player did not decode $extra HLS") {
                    val player = playerField.get(activity) as? ExoPlayer
                    assertNull(player?.playerError)
                    player?.playbackState == Player.STATE_READY && player.videoSize.width > 0
                }
                f.ins.runOnMainSync { activity?.finish() }; activity = null
                f.ins.waitForIdleSync()
            }
        } finally {
            f.ins.runOnMainSync {
                activity?.finish(); f.app.playback.remove(key)
                requirements?.let { f.app.playback.manager.requirements = it }
            }
            f.ins.waitForIdleSync()
            field.set(f.app.network, before)
        }
    }
}
