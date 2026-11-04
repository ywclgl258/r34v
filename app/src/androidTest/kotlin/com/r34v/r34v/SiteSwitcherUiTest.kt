package com.r34v.r34v

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.HanimeAdapter
import com.r34v.r34v.data.adapter.KvsAdapter
import com.r34v.r34v.data.adapter.SiteAdapter
import com.r34v.r34v.ui.SiteSwitcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real touch dispatch and native navigation, with neutral fixtures isolated from live sites. */
@RunWith(AndroidJUnit4::class)
class SiteSwitcherUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as R34Application
    private lateinit var main: MainActivity

    private fun nodes(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()

    private fun waitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            ins.runOnMainSync { ready = condition() }
            if (ready) { ins.waitForIdleSync(); return }
            Thread.sleep(80)
        }
        error(message)
    }

    private fun visible(view: View) = view.isShown && view.getGlobalVisibleRect(Rect())
    private fun text(value: String) = nodes(main.window.decorView).any {
        it is TextView && it.text.toString().replace("\u2060", "") == value && visible(it)
    }
    private fun drawer() = nodes(main.window.decorView).filterIsInstance<SiteSwitcher>().single()

    /** Inject a physical tap, so an invisible overlay cannot be bypassed by performClick(). */
    private fun tap(description: String) {
        val bounds = Rect()
        var target: View? = null
        ins.runOnMainSync {
            target = nodes(main.window.decorView).firstOrNull {
                it.contentDescription?.toString() == description && it.isClickable && it.isShown
            } ?: nodes(main.window.decorView).firstOrNull {
                it is TextView && it.text.toString() == description && it.isShown
            } ?: error("Missing touch target $description: " + nodes(main.window.decorView)
                .filter { it.contentDescription != null || it is TextView }
                .joinToString { "${it.contentDescription ?: (it as TextView).text} ${it.width}x${it.height} shown=${it.isShown} clickable=${it.isClickable}" })
            target!!.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        ins.waitForIdleSync()
        ins.runOnMainSync { assertTrue("Target $description is off screen", target!!.getGlobalVisibleRect(bounds)) }
        gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun gesture(x: Float, y: Float, endX: Float = x, endY: Float = y, holdMs: Long = 0) {
        val down = SystemClock.uptimeMillis()
        val steps = if (x == endX && y == endY) 1 else 12
        for (step in 0..steps) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; steps -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x + (endX - x) * step / steps, y + (endY - y) * step / steps, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ins.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (step == 0 && holdMs > 0) Thread.sleep(holdMs)
            else if (step < steps) Thread.sleep(16)
        }
        ins.waitForIdleSync()
    }

    private fun openDrawer(site: SiteContext) {
        tap("切换站点，当前 ${site.name}")
        waitUi("Site drawer did not open") { drawer().isDrawerOpen(Gravity.START) && text("切换站点") }
    }

    private fun closeWithBack() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitUi("Back did not close the drawer") { !drawer().isShowing }
        assertFalse(main.isFinishing)
    }

    private fun shot(name: String) {
        Thread.sleep(350)
        ins.waitForIdleSync()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = if (output != null) File(output, "site-switcher-shots")
            else File(ins.targetContext.getExternalFilesDir(null), "site-switcher-shots")
        dir.mkdirs()
        val bitmap = ins.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun drawerTouchNavigationAndSiteSpecificDiscovery() {
        val store = app.library
        val oldSite = store.siteId
        val keys = listOf("active_site") + SiteRegistry.all.flatMap { site ->
            listOf("watch_history_v1", "favorites_v1", "native_search_history", "blocked_tags").map { "${site.id}:$it" }
        }
        val saved = keys.associateWith { store.prefs.getString(it, null) }
        val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val savedAdapters = adapters.toMap()
        val originalCache = app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        val requests = CopyOnWriteArrayList<okhttp3.HttpUrl>()
        val testNetwork = Network(ins.targetContext, store)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url)
            val body = when {
                request.url.encodedPath == "/top-characters/" ->
                    "<a class='positive' href='/top-characters/city/'>City work</a>"
                request.url.encodedPath == "/top-characters/city/" ->
                    "<ul><li class='cs-row'><a href='/tags/city-hero/'><span class='cs-row__t'>City hero</span></a></li></ul>"
                request.url.host == SiteRegistry.HANIME1.host ->
                    "<div class='video-item-container'><a class='video-link' href='${SiteRegistry.HANIME1.base}/watch?v=900001'></a><div class='title'>Hanime sample</div></div>"
                else -> "<div class='thumbs'><div data-video-card-id='900001'><a class='th' href='/video/900001/sample/'><span class='thumb_title'>R34V sample</span></a></div></div><button class='page-of'>Page 1 of 1</button>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Site fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        Network::class.java.getDeclaredField("current").apply { isAccessible = true }.set(testNetwork, client)
        adapters[SiteRegistry.R34V.id] = KvsAdapter(SiteRegistry.R34V, testNetwork)
        adapters[SiteRegistry.HANIME1.id] = HanimeAdapter(SiteRegistry.HANIME1, testNetwork)
        try {
            keys.filter { it != "active_site" }.forEach { store.prefs.edit().remove(it).commit() }
            store.switchSite(SiteRegistry.R34V.id)
            store.saveWatch(Watch("900001", "R34V saved record", "", "/video/900001/sample/", 10, 100))
            store.addSearch("R34V search")
            main = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitUi("R34V home did not load") { text("最新视频") }
            openDrawer(SiteRegistry.R34V)
            shot("01-drawer-r34v")
            closeWithBack()
            tap("发现")
            waitUi("Closed drawer blocked navigation") { text("找到下一部想看的") }
            openDrawer(SiteRegistry.R34V)
            // Tapping outside the panel must close it without activating the page beneath.
            val outside = Rect()
            ins.runOnMainSync { drawer().getGlobalVisibleRect(outside) }
            gesture(outside.right - 8f, outside.centerY().toFloat())
            waitUi("Scrim did not close the drawer") { !drawer().isShowing }
            assertTrue(textOnMain("找到下一部想看的"))
            tap("角色与作品")
            waitUi("KVS works did not load") { text("City work") }
            tap("City work")
            waitUi("KVS nested work did not use its own route") {
                text("1. City hero") && requests.any { it.encodedPath == "/top-characters/city/" }
            }
            openDrawer(SiteRegistry.R34V)
            tap(SiteRegistry.HANIME1.name)
            waitUi("Hanime switch did not finish") { SiteRegistry.current == SiteRegistry.HANIME1 && !drawer().isShowing && text("最新上传") }
            assertEquals("hanime1", store.prefs.getString("active_site", null))
            assertEquals("hanime1", LibraryStore(ins.targetContext).siteId)
            assertTrue(store.history().isEmpty())
            assertTrue(store.searches().isEmpty())
            tap("片库")
            waitUi("Hanime library did not open") { text("还没有观看记录") }
            shot("02-library-hanime")
            openDrawer(SiteRegistry.HANIME1)
            closeWithBack()
            tap("发现")
            waitUi("Hanime discovery did not open") { text("最新上市") && text("影片類型") }
            assertFalse(textOnMain("角色与作品"))
            assertFalse(textOnMain("标签索引"))
            shot("03-discover-hanime")
            tap("本週排行")
            waitUi("Hanime ranking did not reach its feed") {
                text("week") && requests.any { it.host == SiteRegistry.HANIME1.host && it.queryParameter("sort") == "本週排行" }
            }
            ins.runOnMainSync { main.onBackPressedDispatcher.onBackPressed() }
            waitUi("Back did not restore discovery") { text("影片類型") }
            tap("3DCG")
            waitUi("Hanime genre did not reach its feed") {
                text("3DCG") && requests.any { it.host == SiteRegistry.HANIME1.host && it.queryParameter("genre") == "3DCG" }
            }
            openDrawer(SiteRegistry.HANIME1)
            shot("04-drawer-hanime")
            tap(SiteRegistry.R34V.name)
            waitUi("R34V switch did not finish") { SiteRegistry.current == SiteRegistry.R34V && !drawer().isShowing && text("最新视频") }
            assertEquals("R34V saved record", store.history().single().title)
            tap("片库")
            waitUi("R34V history was lost") { text("R34V saved record") }
            shot("05-library-r34v")
            // Both the rebuilt toolbar and edge drag remain usable after several page changes.
            openDrawer(SiteRegistry.R34V)
            closeWithBack()
            val bounds = Rect()
            ins.runOnMainSync { drawer().getGlobalVisibleRect(bounds) }
            // In gesture navigation, holding the edge lets DrawerLayout peek before dragging.
            gesture(bounds.left + 2f, bounds.centerY().toFloat(), bounds.left + bounds.width() * .7f, holdMs = 350)
            waitUi("Edge drag did not open the drawer") { drawer().isDrawerOpen(Gravity.START) && text("切换站点") }
            closeWithBack()
        } finally {
            if (::main.isInitialized) {
                runCatching { shot("06-final") }
                ins.runOnMainSync { main.finish() }
            }
            ins.waitForIdleSync()
            adapters.clear(); adapters.putAll(savedAdapters)
            store.switchSite(oldSite)
            store.prefs.edit().apply {
                saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }.commit()
            app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            originalCache.forEach { (name, bytes) -> File(app.network.feedCacheDirectory, name).writeBytes(bytes) }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun textOnMain(value: String): Boolean {
        var result = false
        ins.runOnMainSync { result = text(value) }
        return result
    }
}
