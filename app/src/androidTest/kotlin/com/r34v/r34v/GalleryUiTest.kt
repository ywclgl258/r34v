package com.r34v.r34v

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.SiteSwitcher
import com.r34v.r34v.ui.ZoomPageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GalleryUiTest {
    @get:org.junit.Rule val existingUser = ExistingUserRule()
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as R34Application
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun activity(): Activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
    private fun text(value: String) = nodes(activity().window.decorView).any { it is TextView && it.isShown && it.text.toString().replace("\u2060", "") == value }
    private fun waitUi(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false; ins.runOnMainSync {
                if (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty()) done = predicate()
            }
            if (done) { ins.waitForIdleSync(); return }; Thread.sleep(80)
        }
        var visible = ""
        ins.runOnMainSync { visible = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.let { current -> nodes(current.window.decorView).filterIsInstance<TextView>().filter { it.isShown && it !is android.widget.EditText }.map { it.text.toString() }.filter { it.isNotBlank() }.take(12).joinToString(" | ") } ?: "no resumed activity" }
        error("$message; visible=$visible")
    }
    private fun bounds(value: String): Rect {
        val result = Rect()
        val active = ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.firstOrNull {
            it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value)
        }
        if (active != null) { active.getBoundsInScreen(result); return result }
        var view: View? = null
        ins.runOnMainSync {
            view = nodes(activity().window.decorView).firstOrNull { it.isShown && (it.contentDescription?.toString() == value || it is TextView && it.text.toString() == value) }
                ?: error("Missing UI target: $value")
            view!!.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        ins.waitForIdleSync(); ins.runOnMainSync { assertTrue(view!!.getGlobalVisibleRect(result)) }
        return result
    }
    private fun tap(value: String) = tap(bounds(value))
    private fun tap(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ins.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        ins.waitForIdleSync()
    }
    private fun editableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        (if (node.isEditable) listOf(node) else emptyList()) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::editableNodes).orEmpty() }
    private fun type(value: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var edit: AccessibilityNodeInfo? = null
        while (edit == null && SystemClock.uptimeMillis() < deadline) {
            edit = ins.uiAutomation.rootInActiveWindow?.let(::editableNodes)?.singleOrNull()
            if (edit == null) Thread.sleep(50)
        }
        checkNotNull(edit) { "Editable input did not become accessible" }
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        ins.waitForIdleSync()
    }
    private fun back() { ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ins.waitForIdleSync() }
    private fun jumpReader(page: Int) { tap("跳转页码"); type(page.toString()); tap("阅读") }
    private fun swipeReader() {
        val rect = bounds("条漫阅读，上下滑动连续阅读")
        val down = SystemClock.uptimeMillis()
        for (step in 0..16) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val y = rect.top + rect.height() * (.9f - .8f * step / 16)
            val event = MotionEvent.obtain(down, down + step * 20L, action, rect.centerX().toFloat(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ins.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        ins.waitForIdleSync()
    }
    private fun shot(name: String) {
        Thread.sleep(350); ins.waitForIdleSync()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = if (output != null) File(output, "gallery-shots") else File(ins.targetContext.getExternalFilesDir(null), "gallery-shots")
        dir.mkdirs()
        val bitmap = ins.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun switchTo(from: SiteContext, to: SiteContext) {
        tap("切换站点，当前 ${from.name}")
        waitUi("Drawer did not finish opening") { nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single().isDrawerOpen(GravityCompat.START) }
        ins.runOnMainSync {
            val drawer = nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single()
            nodes(drawer).filterIsInstance<TextView>().first { it.text.toString() == to.name }.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        ins.waitForIdleSync()
        tap(to.name)
        waitUi("Selected site did not change") { SiteRegistry.current == to && !nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single().isShowing }
    }

    @Test fun officialExLoginGateValidatesPermissionBeforeSavingSession() {
        val store = app.library; val oldSite = store.siteId; val prefs = store.prefs.all.toMap()
        val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val savedAdapters = adapters.toMap(); val field = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        val original = app.network.client; val source = EhFixtures().apply { rejectEx = true }
        val network = Network(ins.targetContext, store)
        Network::class.java.getDeclaredField("session").apply { isAccessible = true }.set(network, app.network.session)
        val client = network.client.newBuilder().addInterceptor { source.respond(it.request()) }.build(); field.set(network, client)
        try {
            app.network.session.clear(SiteRegistry.EXHENTAI); app.network.session.clear(SiteRegistry.EHENTAI)
            adapters["exhentai"] = EhAdapter(SiteRegistry.EXHENTAI, network); field.set(app.network, client); store.switchSite("exhentai")
            ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitUi("EX login gate missing") { text("ExHentai 必须登录") && text("官方网页登录") }
            assertTrue(source.requests.isEmpty())
            shot("ex-login-gate")
            tap("搜索"); waitUi("EX search bypassed login gate") { text("ExHentai 必须登录") }
            tap("官方网页登录")
            waitUi("Official login did not open") { activity() is SiteAuthActivity && nodes(activity().window.decorView).filterIsInstance<android.webkit.WebView>().any { it.url?.startsWith("https://forums.e-hentai.org/") == true } }
            ins.runOnMainSync {
                assertTrue(activity().window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                val web = nodes(activity().window.decorView).filterIsInstance<android.webkit.WebView>().single()
                web.stopLoading()
                val officialClient = web.webViewClient
                web.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) =
                        android.webkit.WebResourceResponse("text/html", "UTF-8", "<html><body>Official login test page</body></html>".byteInputStream())
                    override fun onPageFinished(view: android.webkit.WebView, url: String) { officialClient.onPageFinished(view, url) }
                }
                web.loadDataWithBaseURL(EhLogin.officialUrl, "<html><body>Official login test page</body></html>", "text/html", "UTF-8", null)
            }
            val cookieReady = java.util.concurrent.CountDownLatch(2)
            ins.runOnMainSync {
                val manager = android.webkit.CookieManager.getInstance()
                manager.setCookie("https://e-hentai.org/", "ipb_member_id=12345; Path=/; Secure; HttpOnly") { cookieReady.countDown() }
                manager.setCookie("https://e-hentai.org/", "ipb_pass_hash=0123456789abcdef0123456789abcdef; Path=/; Secure; HttpOnly") { cookieReady.countDown() }
            }
            assertTrue(cookieReady.await(5, java.util.concurrent.TimeUnit.SECONDS))
            tap("验证登录")
            ins.runOnMainSync {
                val web = nodes(activity().window.decorView).filterIsInstance<android.webkit.WebView>().single()
                web.webViewClient.onPageFinished(web, "${SiteRegistry.EXHENTAI.base}/")
            }
            waitUi("Denied EX permission was not explained") { nodes(activity().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("尚无 ExHentai 访问权限") } }
            ins.runOnMainSync {
                val web = nodes(activity().window.decorView).filterIsInstance<android.webkit.WebView>().single()
                web.webViewClient.onPageFinished(web, EhLogin.officialUrl)
                assertTrue("Late forum callback erased the permission explanation", nodes(activity().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("尚无 ExHentai 访问权限") })
            }
            assertFalse(app.network.session.hasGallerySession(SiteRegistry.EXHENTAI))
            source.rejectEx = false; tap("验证登录")
            waitUi("Verified official session did not return to EX") { activity() is MainActivity && app.network.session.hasGallerySession(SiteRegistry.EXHENTAI) && text("最新画廊") }
            assertFalse(app.network.session.hasGallerySession(SiteRegistry.EHENTAI))
            waitUi("Temporary web login cookies were not cleared") { android.webkit.CookieManager.getInstance().getCookie("https://e-hentai.org/").isNullOrBlank() }
            shot("ex-login-verified")
        } finally {
            ins.runOnMainSync { listOf(Stage.RESUMED, Stage.STARTED, Stage.STOPPED, Stage.PAUSED, Stage.CREATED).flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct().forEach { it.finish() } }
            ins.waitForIdleSync(); app.network.session.clear(SiteRegistry.EXHENTAI); app.network.session.clear(SiteRegistry.EHENTAI)
            adapters.clear(); adapters.putAll(savedAdapters); field.set(app.network, original); store.switchSite(oldSite)
            store.prefs.edit().clear().apply { prefs.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
                is Int -> putInt(key, value); is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } } }.commit()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun addedComicSitesReadSearchAndKeepOfflineProgressSeparate() {
        val store = app.library; val oldSite = store.siteId; val prefs = store.prefs.all.toMap()
        val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val savedAdapters = adapters.toMap(); val originalClient = app.network.client
        val field = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        val fixture = ComicFixtures(); val network = Network(ins.targetContext, store)
        val client = network.client.newBuilder().addInterceptor { fixture.respond(it.request()) }.build()
        field.set(network, client)
        val feeds = app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        try {
            field.set(app.network, client)
            adapters["nhentai"] = NhentaiAdapter(network); adapters["hitomi"] = HitomiAdapter(network); adapters["r34v"] = KvsAdapter(SiteRegistry.R34V, network)
            for (site in listOf(SiteRegistry.NHENTAI, SiteRegistry.HITOMI)) {
                app.galleries.clear(site)
                for (key in listOf("reading_history_v1", "favorites_v1", "native_search_history", "gallery_language")) store.prefs.edit().remove("${site.id}:$key").commit()
            }
            store.switchSite("r34v")
            ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitUi("Initial home missing") { text("最新视频") }
            fixture.listGate = null
            switchTo(SiteRegistry.R34V, SiteRegistry.NHENTAI)
            field.set(app.network, client)
            waitUi("nhentai gallery list missing") { text("City sketchbook") }
            fixture.nhLanguages = mapOf("chinese" to listOf(8800), "japanese" to listOf(8801))
            tap("仅中文")
            waitUi("Chinese home filter did not reach the API") { fixture.requests.any { it.url.queryParameter("query") == "language:chinese" } && text("City sketchbook") }
            ins.runOnMainSync { activity().recreate() }
            waitUi("Language selection did not survive recreation") {
                nodes(activity().window.decorView).any { it is TextView && it.contentDescription?.toString() == "仅中文" && it.isSelected } && text("City sketchbook")
            }
            tap("发现"); tap("漫画")
            waitUi("Language filter was dropped from category") { fixture.requests.any { it.url.queryParameter("query") == "category:manga language:chinese" } && text("City sketchbook") }
            tap("搜索"); type("city"); tap("提交搜索")
            waitUi("Language filter was dropped from search") { fixture.requests.any { it.url.queryParameter("query") == "city language:chinese" } && text("City sketchbook") }
            tap("仅日文")
            waitUi("Changing language lost search terms") { fixture.requests.any { it.url.queryParameter("query") == "city language:japanese" } && text("City sketchbook") }
            tap("中文＋日文")
            waitUi("Combined languages did not query both streams") { fixture.requests.any { it.url.queryParameter("query") == "city language:japanese -language:chinese" } && text("City sketchbook") }
            shot("nh-language-filters")
            tap("切换站点，当前 nhentai")
            waitUi("Compact site drawer did not open") { nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single().isDrawerOpen(GravityCompat.START) }
            ins.runOnMainSync { assertFalse("Site names still repeat as domains", text("nhentai.net")); assertFalse(text("hitomi.la")) }
            shot("compact-site-switcher"); back()
            waitUi("Compact drawer did not close") { !nodes(activity().window.decorView).filterIsInstance<SiteSwitcher>().single().isShowing }
            tap("首页"); tap("全部显示")
            waitUi("All languages did not restore the original home") { store.galleryLanguage() == GalleryLanguage.ALL && text("City sketchbook") }
            shot("nh-01-home")
            tap("City sketchbook"); waitUi("nhentai detail missing") { activity() is GalleryActivity && text("开始阅读") }
            tap("收藏画廊"); tap("开始阅读")
            waitUi("nhentai page one did not render") { store.reading("8800", "nhentai")?.page == 1 }
            swipeReader(); swipeReader(); waitUi("Vertical scrolling did not reach nhentai page two") { store.reading("8800", "nhentai")?.page == 2 }
            shot("nh-02-reader")
            back(); back(); waitUi("nhentai main missing") { activity() is MainActivity }
            tap("片库"); waitUi("nhentai reading history missing") { text("City sketchbook") }
            val images = fixture.requests.count { it.url.host == "i1.nhentai.net" }
            fixture.offline = true
            tap("City sketchbook"); waitUi("nhentai cached reader missing") { activity() is GalleryActivity && text("2 / 2") }
            ins.runOnMainSync { activity().recreate() }
            waitUi("Cached reader did not survive recreation") { activity() is GalleryActivity && text("2 / 2") && nodes(activity().window.decorView).filterIsInstance<ZoomPageView>().any { it.drawable != null } }
            assertEquals(images, fixture.requests.count { it.url.host == "i1.nhentai.net" })
            back(); back(); fixture.offline = false
            waitUi("Main did not resume before switching") { activity() is MainActivity }
            fixture.listGate = null
            switchTo(SiteRegistry.NHENTAI, SiteRegistry.HITOMI)
            field.set(app.network, client)
            waitUi("Hitomi gallery list missing") { text("City sketchbook") }
            waitUi("Gallery must use a masonry layout") {
                nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().any {
                    it.layoutManager is androidx.recyclerview.widget.StaggeredGridLayoutManager && it.childCount >= 3
                }
            }
            ins.runOnMainSync {
                val list = nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first()
                val covers = nodes(list).filterIsInstance<com.r34v.r34v.ui.RatioImage>()
                assertTrue("Covers should retain different aspect ratios", covers.map { it.height }.distinct().size > 1)
            }
            assertTrue(store.readings().isEmpty()); assertFalse(store.isFavorite("gallery", "8800"))
            ins.runOnMainSync {
                val bar = nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.GalleryLanguageBar>().single()
                val buttons = nodes(bar).filterIsInstance<android.widget.Button>()
                assertEquals(4, buttons.size)
                assertEquals(1, buttons.map { it.top }.distinct().size)
                assertTrue("Language row is too tall", bar.height <= activity().resources.displayMetrics.density * 48 + 1)
                nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first().scrollBy(0, activity().resources.displayMetrics.heightPixels / 3)
            }
            waitUi("Language row did not hide while scrolling down") { nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.GalleryLanguageBar>().single().visibility == View.GONE }
            shot("languages-hidden-on-scroll")
            ins.runOnMainSync { nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first().scrollBy(0, -activity().resources.displayMetrics.heightPixels / 5) }
            waitUi("Language row did not return on upward scroll") {
                nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.GalleryLanguageBar>().single().let { it.visibility == View.VISIBLE && it.height >= activity().resources.displayMetrics.density * 47 }
            }
            ins.runOnMainSync { nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first().scrollToPosition(0) }
            shot("hitomi-01-home")
            ins.runOnMainSync { activity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUi("Masonry did not adapt to landscape") {
                nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().any { (it.layoutManager as? androidx.recyclerview.widget.StaggeredGridLayoutManager)?.spanCount == 3 }
            }
            shot("hitomi-waterfall-landscape")
            ins.runOnMainSync { activity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUi("Masonry did not restore portrait columns") {
                nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().any { (it.layoutManager as? androidx.recyclerview.widget.StaggeredGridLayoutManager)?.spanCount == 2 }
            }
            ins.runOnMainSync {
                val list = nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first()
                list.smoothScrollToPosition(list.adapter!!.itemCount - 1)
            }
            waitUi("Masonry scrolling did not load the next index range") { fixture.requests.any { it.url.encodedPath == "/index-all.nozomi" && it.header("Range") == "bytes=48-95" } }
            ins.runOnMainSync { nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first().scrollToPosition(0) }
            waitUi("Masonry did not return to the first card") {
                nodes(activity().window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().any { (it.layoutManager as? androidx.recyclerview.widget.StaggeredGridLayoutManager)?.findFirstVisibleItemPositions(null)?.contains(0) == true }
            }
            tap("City sketchbook"); waitUi("Hitomi detail missing") { activity() is GalleryActivity && text("开始阅读") }
            tap("收藏画廊"); tap("开始阅读")
            waitUi("Hitomi page one missing") { store.reading("8800", "hitomi")?.page == 1 }
            tap("跳转页码"); type("2"); tap("阅读")
            waitUi("Hitomi page two missing") { store.reading("8800", "hitomi")?.page == 2 }
            assertEquals(2, store.reading("8800", "nhentai")!!.page)
            assertEquals(2, app.galleries.cacheInfo(SiteRegistry.HITOMI).first)
            shot("hitomi-02-reader")
            back(); back(); waitUi("Hitomi main missing") { activity() is MainActivity }
            tap("搜索"); tap("画师"); type("City Artist"); tap("提交搜索")
            waitUi("Hitomi artist search did not use artist index") { fixture.requests.any { it.url.encodedPath == "/artist/city%20artist-all.nozomi" } && text("City sketchbook") }
            tap("片库"); tap("收藏")
            waitUi("Hitomi gallery favorite missing") { text("City sketchbook") }
            assertTrue(store.isFavorite("gallery", "8800", "nhentai")); assertTrue(store.isFavorite("gallery", "8800", "hitomi"))
            shot("hitomi-03-library")
            tap("设置")
            val networkPolicy = "自动跟随系统代理；未设置代理时使用系统 DNS 直连。代理连接失败时会显示错误。"
            waitUi("System network policy missing") { text(networkPolicy) }
            ins.runOnMainSync { assertFalse(text("使用 DoH 解析 DNS")); assertFalse(text("站点 IP 直连")); assertFalse(text("自定义站点 IP")); assertFalse(text("HTTP / SOCKS5 代理")) }
            ins.runOnMainSync { nodes(activity().window.decorView).filterIsInstance<TextView>().first { it.text.toString() == networkPolicy }.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) } }
            ins.waitForIdleSync()
            shot("system-network-settings")
        } finally {
            fixture.listGate?.countDown()
            runCatching { shot("added-final") }
            ins.runOnMainSync { listOf(Stage.RESUMED, Stage.STARTED, Stage.STOPPED, Stage.PAUSED, Stage.CREATED).flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct().forEach { it.finish() } }
            ins.waitForIdleSync()
            app.galleries.clear(SiteRegistry.NHENTAI); app.galleries.clear(SiteRegistry.HITOMI)
            adapters.clear(); adapters.putAll(savedAdapters); field.set(app.network, originalClient); store.switchSite(oldSite)
            store.prefs.edit().clear().apply { prefs.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
                is Int -> putInt(key, value); is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } } }.commit()
            app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            feeds.forEach { (name, bytes) -> File(app.network.feedCacheDirectory, name).writeBytes(bytes) }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun readingZoomJumpCacheAndExSessionFlow() {
        val store = app.library; val oldSite = store.siteId; val prefs = store.prefs.all.toMap()
        val oldFeeds = app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val savedAdapters = adapters.toMap()
        val fixture = EhFixtures()
        val network = Network(ins.targetContext, store)
        // Production adapters share the application's jar; the isolated HTTP fixture must too.
        Network::class.java.getDeclaredField("session").apply { isAccessible = true }.set(network, app.network.session)
        val client = network.client.newBuilder().addInterceptor { fixture.respond(it.request()) }.build()
        val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        clientField.set(network, client)
        adapters["ehentai"] = EhAdapter(SiteRegistry.EHENTAI, network); adapters["exhentai"] = EhAdapter(SiteRegistry.EXHENTAI, network)
        adapters["r34v"] = KvsAdapter(SiteRegistry.R34V, network)
        val originalClient = app.network.client
        try {
            for (site in SiteRegistry.all) for (key in listOf("watch_history_v1", "reading_history_v1", "favorites_v1", "native_search_history", "blocked_tags")) store.prefs.edit().remove("${site.id}:$key").commit()
            app.network.session.clear(SiteRegistry.EHENTAI); app.network.session.clear(SiteRegistry.EXHENTAI)
            store.switchSite("r34v"); store.saveWatch(Watch("900001", "R34V saved record", "", "/video/900001/sample/", 30, 100))
            ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitUi("Initial home missing") { text("最新视频") }
            switchTo(SiteRegistry.R34V, SiteRegistry.EHENTAI)
            waitUi("Gallery list missing") { text("最新画廊") && text("City sketchbook") }
            clientField.set(app.network, client)
            shot("01-gallery-home")
            tap("City sketchbook")
            waitUi("Gallery detail did not open") { activity() is GalleryActivity && text("开始阅读") }
            shot("02-gallery-detail")
            tap("收藏画廊"); assertTrue(store.isFavorite("gallery", "8888"))
            fixture.quotaPage = 3
            tap("开始阅读")
            waitUi("Page one did not render") { store.reading("8888")?.page == 1 && nodes(activity().window.decorView).filterIsInstance<ZoomPageView>().any { it.drawable != null } }
            waitUi("Future pages were not pre-read before scrolling") { fixture.requests.any { it.url.encodedPath == "/s/a4/8888-4" } }
            assertEquals("Pre-reading must not advance history", 1, store.reading("8888")!!.page)
            assertTrue("Pre-reading fetched beyond its window", fixture.requests.none { it.url.encodedPath == "/s/a5/8888-5" })
            assertTrue("The strip eagerly loaded the whole book", fixture.requests.count { it.url.host == "sample.hath.network" } < 5)
            var inlineHeight = 0
            ins.runOnMainSync { inlineHeight = nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.ComicStripView>().single().height }
            tap("全屏阅读")
            waitUi("Reading controls did not hide for fullscreen") {
                !text("1 / 5") && nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.ComicStripView>().single().height > inlineHeight &&
                    androidx.core.view.ViewCompat.getRootWindowInsets(activity().window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) == false
            }
            shot("reader-fullscreen")
            ins.runOnMainSync { activity().recreate() }
            waitUi("Fullscreen was lost on recreation") { activity() is GalleryActivity && !text("1 / 5") && nodes(activity().window.decorView).filterIsInstance<ZoomPageView>().any { it.drawable != null } }
            tap("漫画第 1 页，双击或双指缩放")
            waitUi("Single tap did not restore reading controls") { text("1 / 5") }
            tap("漫画第 1 页，双击或双指缩放")
            waitUi("Single tap did not hide reading controls") { !text("1 / 5") }
            back()
            waitUi("Back should restore controls before leaving the reader") { activity() is GalleryActivity && text("1 / 5") }
            val imageBounds = bounds("漫画第 1 页，双击或双指缩放")
            tap(imageBounds); Thread.sleep(80); tap(imageBounds)
            waitUi("Double tap zoom did not work") { nodes(activity().window.decorView).filterIsInstance<ZoomPageView>().any { it.isZoomed } }
            ins.runOnMainSync { assertTrue("Double tap unexpectedly hid menus", text("1 / 5")) }
            shot("03-reader-zoom")
            tap(imageBounds); Thread.sleep(80); tap(imageBounds)
            waitUi("Double tap did not restore strip scrolling") { nodes(activity().window.decorView).filterIsInstance<ZoomPageView>().none { it.isZoomed } }
            swipeReader(); swipeReader(); waitUi("Vertical scrolling did not advance the reading progress") { (store.reading("8888")?.page ?: 0) >= 2 }
            ins.runOnMainSync { nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.ComicStripView>().single().scrollToPage(2, -120) }
            waitUi("Strip did not save the within-page offset") { store.reading("8888")?.let { it.page == 2 && kotlin.math.abs(it.offset + 120) < 5 } == true }
            waitUi("Pre-reading window did not advance with the current page") { fixture.requests.any { it.url.encodedPath == "/s/a5/8888-5" } }
            assertEquals("Pre-read/visible page fetched the same image twice", 1, fixture.requests.count { it.url.encodedPath == "/s/a2/8888-2" })
            ins.runOnMainSync { activity().recreate() }
            waitUi("Strip position did not survive recreation") {
                text("2 / 5") && nodes(activity().window.decorView).filterIsInstance<com.r34v.r34v.ui.ComicStripView>().singleOrNull()?.let {
                    it.currentPage == 2 && kotlin.math.abs(it.readingOffset + 120) < 5
                } == true
            }
            tap("跳转页码"); type("5"); tap("阅读")
            waitUi("Jump did not load page five") { store.reading("8888")?.page == 5 }
            assertTrue(fixture.requests.any { it.url.encodedPath == "/s/a5/8888-5" })
            shot("04-reader-last-page")
            jumpReader(4); waitUi("Previous page did not render") { store.reading("8888")?.page == 4 }
            back(); waitUi("Overview did not restore progress") { text("继续阅读 · 第 4 页") }
            back(); waitUi("Main did not resume") { activity() is MainActivity }
            tap("片库"); waitUi("Reading history missing") { nodes(activity().window.decorView).any { it is TextView && it.isShown && it.text.toString().contains("第 4 / 5 页") } }
            assertTrue(store.history().isEmpty())
            shot("05-reading-library")
            val imageRequests = fixture.requests.count { it.url.host == "sample.hath.network" }
            fixture.offline = true
            tap("City sketchbook")
            waitUi("Offline cached page did not open") { activity() is GalleryActivity && text("4 / 5") && nodes(activity().window.decorView).filterIsInstance<ZoomPageView>().any { it.drawable != null } }
            assertEquals(imageRequests, fixture.requests.count { it.url.host == "sample.hath.network" })
            tap("跳转页码"); type("5"); tap("阅读")
            waitUi("Cached page outside the first preview chunk did not open offline") { store.reading("8888")?.page == 5 && text("5 / 5") }
            assertEquals(imageRequests, fixture.requests.count { it.url.host == "sample.hath.network" })
            jumpReader(4); waitUi("Cached fourth page did not reopen") { store.reading("8888")?.page == 4 }
            jumpReader(3); waitUi("Uncached page should offer retry") { text("重试此页") }
            assertEquals(4, store.reading("8888")!!.page)
            fixture.offline = false; fixture.quotaPage = 0
            tap("重试此页"); waitUi("Failed strip row did not recover") { store.reading("8888")?.page == 3 }
            back(); back(); waitUi("Main did not resume from offline reader") { activity() is MainActivity }
            switchTo(SiteRegistry.EHENTAI, SiteRegistry.EXHENTAI)
            waitUi("ExHentai auth gate missing") { text("站点设置") }
            assertTrue(store.readings().isEmpty()); assertFalse(store.isFavorite("gallery", "8888"))
            tap("站点设置"); tap("导入站点会话"); type(fixture.cookie); tap("验证并导入")
            waitUi("Ex session import did not load gallery list") { app.network.session.hasGallerySession(SiteRegistry.EXHENTAI) && text("最新画廊") }
            clientField.set(app.network, client)
            shot("06-exhentai-home")
            tap("City sketchbook"); waitUi("Ex gallery did not open") { activity() is GalleryActivity && text("开始阅读") }; tap("开始阅读")
            waitUi("Ex page did not render") { store.reading("8888", "exhentai")?.page == 1 }
            assertEquals(3, store.reading("8888", "ehentai")!!.page)
            assertTrue(store.isFavorite("gallery", "8888", "ehentai")); assertFalse(store.isFavorite("gallery", "8888", "exhentai"))
            assertEquals("https://exhentai.org/s/a1/8888-1", fixture.requests.first { it.url.host == "sample.hath.network" && it.url.encodedPath == "/page1.png" && it.header("Referer")?.startsWith("https://exhentai.org/") == true }.header("Referer"))
            shot("07-exhentai-reader")
        } finally {
            runCatching { shot("08-final") }
            ins.runOnMainSync {
                listOf(Stage.RESUMED, Stage.STARTED, Stage.STOPPED, Stage.PAUSED, Stage.CREATED).flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct().forEach { it.finish() }
            }
            ins.waitForIdleSync()
            app.network.session.clear(SiteRegistry.EHENTAI); app.network.session.clear(SiteRegistry.EXHENTAI)
            app.galleries.clear(SiteRegistry.EHENTAI); app.galleries.clear(SiteRegistry.EXHENTAI)
            adapters.clear(); adapters.putAll(savedAdapters); clientField.set(app.network, originalClient); store.switchSite(oldSite)
            store.prefs.edit().clear().apply { prefs.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
                is Int -> putInt(key, value); is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } } }.commit()
            app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            oldFeeds.forEach { (name, bytes) -> File(app.network.feedCacheDirectory, name).writeBytes(bytes) }
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
