@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.core.view.GravityCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.IwaraAdapter
import com.r34v.r34v.data.adapter.KvsAdapter
import com.r34v.r34v.data.adapter.SiteAdapter
import com.r34v.r34v.ui.SiteSwitcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IwaraUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as R34Application
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun activity(): Activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
    private fun hasText(value: String) = nodes(activity().window.decorView).any { it is TextView && it.text.toString().replace("\u2060", "") == value && it.isShown }
    private fun waitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false; ins.runOnMainSync { ready = condition() }
            if (ready) { ins.waitForIdleSync(); return }; Thread.sleep(80)
        }
        error(message)
    }
    private fun tap(value: String) {
        val bounds = Rect(); var target: View? = null
        val activeNode = ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.firstOrNull {
            it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value)
        }
        if (activeNode != null) activeNode.getBoundsInScreen(bounds)
        else {
        ins.runOnMainSync {
            target = nodes(activity().window.decorView).firstOrNull { it.isShown && (it.contentDescription?.toString() == value || it is TextView && it.text.toString() == value) }
            target?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        ins.waitForIdleSync()
        if (target != null) ins.runOnMainSync { assertTrue(target!!.getGlobalVisibleRect(bounds)) }
        else {
            val node = ins.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(value).firstOrNull { it.text?.toString() == value }
                ?: error("Missing touch target: $value")
            node.getBoundsInScreen(bounds)
        }
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ins.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        ins.waitForIdleSync()
    }
    private fun shot(name: String) {
        Thread.sleep(400); ins.waitForIdleSync()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = if (output != null) File(output, "iwara-shots") else File(ins.targetContext.getExternalFilesDir(null), "iwara-shots")
        dir.mkdirs()
        val bitmap = ins.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun thirdSiteDiscoverySearchPlaybackAndLibraryIsolation() {
        val store = app.library; val oldSite = store.siteId; val prefs = store.prefs.all.toMap()
        val oldCache = app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val oldAdapters = adapters.toMap()
        val network = Network(ins.targetContext, store)
        val fixture = IwaraFixtures(ins.context.assets.open("test-pattern.mp4").use { it.readBytes() })
        val client = network.client.newBuilder().addInterceptor { fixture.respond(it.request()) }.build()
        val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        clientField.set(network, client)
        adapters[SiteRegistry.IWARA.id] = IwaraAdapter(SiteRegistry.IWARA, network)
        adapters[SiteRegistry.R34V.id] = KvsAdapter(SiteRegistry.R34V, network)
        val oldClient = app.network.client
        val activities = mutableListOf<Activity>()
        val downloadId = "iwara:IwaraSample01:720p 原画"
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(ins.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            for (site in SiteRegistry.all) for (key in listOf("watch_history_v1", "favorites_v1", "native_search_history", "blocked_tags")) store.prefs.edit().remove("${site.id}:$key").commit()
            store.switchSite("r34v"); store.saveWatch(Watch("900001", "R34V saved record", "", "/video/900001/sample/", 30, 100))
            store.set("auto_fullscreen", false); store.set("auto_download", false); store.set("native_speed", "1.0"); store.set("translate_tags", false)
            val main = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activities.add(main)
            waitUi("Home did not load") { hasText("最新视频") }
            tap("切换站点，当前 Rule34Video")
            waitUi("Third site was not offered") { hasText("Iwara") && nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single().isDrawerOpen(GravityCompat.START) }
            shot("01-three-sites")
            tap("Iwara")
            waitUi("Iwara home did not load") { SiteRegistry.current == SiteRegistry.IWARA && hasText("最新上传") }
            assertEquals("iwara", store.prefs.getString("active_site", null))
            assertTrue(store.history().isEmpty())
            tap("发现"); waitUi("Iwara discovery missing") { hasText("趋势榜") && hasText("点赞榜") }
            shot("02-iwara-discovery")
            tap("趋势榜"); waitUi("Trending did not load") { hasText("趋势视频") }
            tap("趋势")
            assertTrue(ins.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("随机").isEmpty())
            assertTrue(ins.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("时长").isEmpty())
            tap("播放量")
            waitUi("Sort not applied") { fixture.requests.any { it.url.encodedPath == "/videos" && it.url.queryParameter("sort") == "views" } }
            tap("搜索")
            ins.runOnMainSync { nodes(activity().window.decorView).filterIsInstance<EditText>().single().setText("City walk & MMD") }
            tap("提交搜索")
            waitUi("Search did not reach Iwara") { fixture.requests.any { it.url.encodedPath == "/search" && it.url.queryParameter("query") == "City walk & MMD" } }
            tap("首页"); waitUi("Iwara home did not return") { hasText("最新上传") }
            clientField.set(app.network, client)
            tap("播放")
            val engineField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            var playerActivity: PlayerActivity? = null
            waitUi("Iwara did not play through Media3") {
                playerActivity = activity() as? PlayerActivity
                playerActivity?.let { (engineField.get(it) as? ExoPlayer)?.playbackState == Player.STATE_READY } == true
            }
            val player = playerActivity!!; activities.add(player)
            ins.runOnMainSync { (engineField.get(player) as ExoPlayer).pause() }
            shot("03-iwara-player")
            tap("收藏"); assertTrue(store.isFavorite("video", "IwaraSample01"))
            tap("下载"); tap("720p 原画")
            waitUi("Iwara download did not complete") { app.playback.manager.downloadIndex.getDownload(downloadId)?.state == Download.STATE_COMPLETED }
            tap("标签 · 1"); tap("city_walk")
            waitUi("Detail tag did not open an Iwara feed") { activity() is MainActivity && fixture.requests.any { it.url.queryParameter("tags") == "city_walk" } }
            ins.runOnMainSync { activities.add(activity()) }
            tap("片库"); waitUi("Iwara watch record missing") { hasText("Iwara city walk") }
            shot("04-iwara-library")
            tap("下载")
            waitUi("Iwara download missing from library") { hasText("离线播放") }
            val mediaRequests = fixture.requests.count { it.url.encodedPath.endsWith(".mp4") }
            tap("离线播放")
            waitUi("Iwara offline playback failed") { (activity() as? PlayerActivity)?.let { (engineField.get(it) as? ExoPlayer)?.playbackState == Player.STATE_READY } == true }
            var offlineActivity: Activity? = null
            ins.runOnMainSync { offlineActivity = activity(); activities.add(activity()); (engineField.get(activity()) as ExoPlayer).pause() }
            assertEquals("Offline playback requested network media", mediaRequests, fixture.requests.count { it.url.encodedPath.endsWith(".mp4") })
            shot("05-iwara-offline")
            ins.runOnMainSync { offlineActivity!!.finish() }; ins.waitForIdleSync()
            tap("切换站点，当前 Iwara")
            waitUi("Drawer not open") { nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single().isDrawerOpen(GravityCompat.START) }
            tap("Rule34Video")
            waitUi("R34V did not restore") { SiteRegistry.current == SiteRegistry.R34V && hasText("最新视频") }
            assertFalse(store.isFavorite("video", "IwaraSample01"))
            assertEquals("R34V saved record", store.history().single().title)
            assertTrue(app.playback.downloadsFor().none { it.request.id == downloadId })
            val media = fixture.requests.first { it.url.host == "himeko.iwara.tv" }
            assertEquals("https://www.iwara.tv/", media.header("Referer"))
            assertNull(media.header("Cookie")); assertNull(media.header("Authorization"))
        } finally {
            runCatching { shot("06-final") }
            ins.runOnMainSync { activities.reversed().forEach { it.finish() } }; ins.waitForIdleSync()
            ins.runOnMainSync { app.playback.remove(downloadId) }
            adapters.clear(); adapters.putAll(oldAdapters); clientField.set(app.network, oldClient)
            store.switchSite(oldSite)
            store.prefs.edit().clear().apply {
                prefs.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
                    is Int -> putInt(key, value); is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
            app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            oldCache.forEach { (name, bytes) -> File(app.network.feedCacheDirectory, name).writeBytes(bytes) }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
