@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.HanimeAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Media3 rendering through the user proxy, including a third-party media host. */
@RunWith(AndroidJUnit4::class)
class LivePlaybackProxyProbe {
    @org.junit.Before fun requireExplicitSelection() = requireLiveProbe(javaClass.name)
    @Test fun hanimeNativePlaybackUsesSystemProxyAndOwnerHeaders() = runBlocking<Unit> {
        val ins = InstrumentationRegistry.getInstrumentation(); val app = ins.targetContext.applicationContext as R34Application
        val originalSite = app.library.siteId; val quality = app.library.string("quality", "720p")
        val auto = app.library.bool("auto_download"); val fullscreen = app.library.bool("auto_fullscreen")
        var activity: PlayerActivity? = null; val report = mutableListOf<String>()
        try {
            app.library.switchSite("hanime1"); app.library.set("auto_download", false); app.library.set("auto_fullscreen", false); app.library.set("quality", "480p")
            val route = app.network.routeDescription(); report.add(route); assertTrue(route.contains("127.0.0.1:7897"))
            val adapter = HanimeAdapter(SiteRegistry.HANIME1, app.network)
            val entry = adapter.feed(adapter.homeRequest()).videos.first()
            activity = ins.startActivitySync(Intent(ins.targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("video", app.library.gson.toJson(entry))) as PlayerActivity
            val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val deadline = System.currentTimeMillis()+45000; var frames = 0; var ready = false
            while (System.currentTimeMillis() < deadline && frames < 5) {
                ins.runOnMainSync { val player = field.get(activity) as? ExoPlayer; frames = player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0; ready = player?.playbackState == Player.STATE_READY }
                Thread.sleep(120)
            }
            report.add("Hanime Media3: ready=$ready, renderedFrames=$frames, quality=480p"); report.add(app.network.lastPeer)
            assertTrue("Real proxy playback did not render frames", frames >= 5)
            assertTrue(app.network.lastPeer.contains("系统代理"))
        } finally {
            ins.runOnMainSync { activity?.finish() }; ins.waitForIdleSync()
            app.library.set("quality", quality); app.library.set("auto_download", auto); app.library.set("auto_fullscreen", fullscreen); app.library.switchSite(originalSite)
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } } ?: ins.targetContext.filesDir
            File(dir,"native-playback-proxy.txt").writeText(report.joinToString("\n"))
        }
    }
}
