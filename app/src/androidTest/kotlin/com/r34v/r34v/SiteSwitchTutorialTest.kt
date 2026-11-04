package com.r34v.r34v

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.SiteSwitcher
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SiteSwitchTutorialTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as R34Application
    private val title = "这里可以切换站点"
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun main() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
    private fun waitUi(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false; ins.runOnMainSync { ready = condition() }
            if (ready) { ins.waitForIdleSync(); return }; Thread.sleep(60)
        }
        error(message)
    }
    private fun popupText(value: String) = ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.any { it.isVisibleToUser && it.text?.toString() == value } == true
    private fun waitPopup(value: String) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) { if (popupText(value)) return; Thread.sleep(60) }
        error("Missing popup text: $value")
    }
    private fun waitPopupDismissed() {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) { if (!popupText(title)) return; Thread.sleep(60) }
        error("First-use tutorial did not dismiss")
    }
    private fun tap(value: String) {
        val node = ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.firstOrNull {
            it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value)
        } ?: error("Missing touch target: $value")
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ins.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        ins.waitForIdleSync()
    }
    private fun launch() = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    private fun homeReady() = waitUi("Main screen did not become ready") {
        main()?.let { a -> nodes(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "最新视频" || it.text.toString() == "最新上传" } } == true
    }
    private fun shot(name: String) {
        ins.waitForIdleSync()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = File(output ?: ins.targetContext.getExternalFilesDir(null)!!.absolutePath, "tutorial-shots").apply { mkdirs() }
        val bitmap = ins.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun fixture(offline: Boolean = false, action: () -> Unit) {
        val store = app.library; val savedPrefs = store.prefs.all.toMap(); val originalSite = store.siteId
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val originalAdapters = adapters.toMap(); val network = Network(ins.targetContext, store)
        val client = network.client.newBuilder().addInterceptor { chain ->
            if (offline) throw IOException("Offline tutorial fixture")
            val html = if (chain.request().url.host == SiteRegistry.HANIME1.host) "<div class='video-item-container'></div>"
                else "<div class='thumbs'></div><button class='page-of'>Page 1 of 1</button>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Tutorial fixture")
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val networkField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        networkField.set(network, client)
        val originalClient = app.network.client
        val cacheFiles = app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        val nonce = System.nanoTime()
        adapters["r34v"] = object : SiteAdapter by KvsAdapter(SiteRegistry.R34V, network) {
            override fun resolve(request: FeedRequest) = "${SiteRegistry.R34V.base}/tutorial-$nonce/${request.id}"
        }
        adapters["hanime1"] = HanimeAdapter(SiteRegistry.HANIME1, network)
        networkField.set(app.network, client)
        store.switchSite("r34v"); store.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, false).putBoolean("settings_v1_auto_update", false).commit()
        try { action() } finally {
            ins.runOnMainSync { listOf(Stage.RESUMED, Stage.STARTED, Stage.STOPPED, Stage.PAUSED, Stage.CREATED).flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct().forEach(Activity::finish) }
            ins.waitForIdleSync()
            adapters.clear(); adapters.putAll(originalAdapters); networkField.set(app.network, originalClient); store.switchSite(originalSite)
            store.prefs.edit().clear().apply { savedPrefs.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
                is Int -> putInt(key, value); is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } } }.commit()
            app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            cacheFiles.forEach { (name, bytes) -> File(app.network.feedCacheDirectory, name).writeBytes(bytes) }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun eligibilityDistinguishesNewInstallUpgradeAndLegacyMigration() {
        val context = ins.targetContext; val prefix = "tutorial-store-${System.nanoTime()}"; val previous = SiteRegistry.current
        val names = listOf("new", "upgrade", "legacy").map { "$prefix-$it" }
        try {
            val fresh = LibraryStore(context, names[0], "${names[0]}-old")
            assertTrue(fresh.needsSiteSwitchTutorial)
            assertTrue(LibraryStore(context, names[0], "${names[0]}-old").needsSiteSwitchTutorial)
            assertTrue(fresh.consumeSiteSwitchTutorial()); assertFalse(fresh.consumeSiteSwitchTutorial())
            fresh.switchSite("hanime1"); assertFalse(fresh.needsSiteSwitchTutorial)
            assertFalse(LibraryStore(context, names[0], "${names[0]}-old").needsSiteSwitchTutorial)
            context.getSharedPreferences(names[1], 0).edit().putBoolean("kotlin_migration_v1", true).commit()
            assertFalse(LibraryStore(context, names[1], "${names[1]}-old").needsSiteSwitchTutorial)
            context.getSharedPreferences("${names[2]}-old", 0).edit().putString("flutter.settings_v1_quality", "720p").commit()
            assertFalse(LibraryStore(context, names[2], "${names[2]}-old").needsSiteSwitchTutorial)
        } finally {
            SiteRegistry.current = previous
            names.forEach { context.deleteSharedPreferences(it); context.deleteSharedPreferences("$it-old") }
        }
    }

    @Test fun firstUseOpensRealSiteListAndNeverReplaysAfterSwitchOrRestart() = fixture {
        launch(); waitPopup(title); shot("01-first-use")
        assertFalse(app.library.needsSiteSwitchTutorial)
        tap("打开站点列表")
        waitUi("Tutorial did not open the real site drawer") { main()?.let { nodes(it.window.decorView).filterIsInstance<SiteSwitcher>().single().isDrawerOpen(Gravity.START) } == true }
        tap("Hanime1"); homeReady()
        assertEquals("hanime1", app.library.siteId)
        launch(); homeReady(); assertFalse(popupText(title))
        assertFalse(LibraryStore(ins.targetContext).needsSiteSwitchTutorial)
        shot("02-restarted-no-tutorial")
    }

    @Test fun dismissAndBackAreBothFinal() = fixture {
        for (back in listOf(false, true)) {
            app.library.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, false).commit()
            launch(); waitPopup(title)
            if (back) ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) else tap("知道了")
            waitPopupDismissed(); homeReady(); assertFalse(app.library.needsSiteSwitchTutorial)
            launch(); homeReady(); assertFalse(popupText(title))
        }
    }

    @Test fun recreationDoesNotRepeatAnAlreadyVisibleTip() = fixture {
        val original = launch(); waitPopup(title)
        ins.runOnMainSync { original.recreate() }
        waitUi("Main activity did not recreate") { main() != null && main() !== original }
        homeReady(); assertFalse(popupText(title)); assertFalse(app.library.needsSiteSwitchTutorial)
    }

    @Test fun offlineFirstUseStillOffersSiteSwitching() = fixture(offline = true) {
        launch(); waitPopup(title); shot("03-offline-tutorial")
        tap("打开站点列表")
        waitUi("Offline tutorial could not open site drawer") { main()?.let { nodes(it.window.decorView).filterIsInstance<SiteSwitcher>().single().isDrawerOpen(Gravity.START) } == true }
    }
}
