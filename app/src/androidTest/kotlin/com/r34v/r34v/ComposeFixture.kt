package com.r34v.r34v

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Rule
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap

/** Neutral media and real repository/cache/player paths; no production test switches. */
class ComposeFixture(private val cinematic: Boolean = false) : Closeable {
    val ins = InstrumentationRegistry.getInstrumentation()
    val app = ins.targetContext.applicationContext as R34Application
    private val prefs = app.library.prefs.all.toMap()
    private val previousSite = app.library.siteId
    private val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
    private val originals = adapters.toMap()
    private val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
    private val originalClient = app.network.client
    private val originalFeeds = app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
    private val galleryFiles = File(app.filesDir, "gallery-cache").walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
    private val originalBookTasks = app.galleryDownloads.tasks().map { it.id }.toSet()
    val requests = ConcurrentLinkedQueue<Pair<String, FeedRequest>>()
    val imageCalls = ConcurrentHashMap.newKeySet<Int>()
    val coverRequests = ConcurrentLinkedQueue<Request>()
    val galleryCalls = ConcurrentLinkedQueue<String>()
    val imageRequests = ConcurrentLinkedQueue<Pair<String, Int>>()
    @Volatile var offline = false
    @Volatile var brokenPage = 0
    /** A tag slug that must fail, so a partially broken subscription can be exercised. */
    @Volatile var failTag = ""
    @Volatile var imageDelayMillis = 0L
    val nonce = System.nanoTime()
    private val png = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).let { image ->
        Canvas(image).apply { drawColor(Color.rgb(30, 60, 85)); drawRect(60f, 100f, 540f, 350f, Paint().apply { color = Color.rgb(110, 170, 180) }); drawCircle(300f, 600f, 130f, Paint().apply { color = Color.rgb(220, 190, 130) }) }
        java.io.ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray().also { image.recycle() }
    }
    private val clip = ins.context.assets.open("test-pattern.mp4").use { it.readBytes() }
    private val server = MockWebServer().apply {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull()?.coerceIn(0, clip.size) ?: 0
            return MockResponse().setResponseCode(if (request.getHeader("Range") != null) 206 else 200).setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                .apply { if (request.getHeader("Range") != null) setHeader("Content-Range", "bytes $start-${clip.size - 1}/${clip.size}") }.setBody(Buffer().write(clip.copyOfRange(start, clip.size)))
        } }; start()
    }
    private val fixtureClient = originalClient.newBuilder().dispatcher(okhttp3.Dispatcher()).connectionPool(ConnectionPool()).addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        if (path.contains("compose-image") || path.contains("compose-cover")) {
            val number = Regex("compose-image-(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull()
            if (number != null) imageRequests.add(chain.request().tag(SiteContext::class.java)?.id.orEmpty() to number)
            if (offline) throw IOException("Fixture offline")
            if (number != null) { imageCalls.add(number); if (imageDelayMillis > 0) Thread.sleep(imageDelayMillis); if (brokenPage == number) throw IOException("Fixture page error") }
            if (path.contains("compose-cover")) coverRequests.add(chain.request())
            val cover = if (cinematic && path.contains("compose-cover")) {
                val i = Regex("compose-cover-\\d+-(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                ins.context.assets.open("cinema/${cinemaArt[i % cinemaArt.size]}").use { it.readBytes() }
            } else png
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Neutral fixture").body(cover.toResponseBody("image/png".toMediaType())).build()
        } else chain.proceed(chain.request())
    }.build()
    init {
        clientField.set(app.network, fixtureClient)
        SiteRegistry.all.forEach { site -> adapters[site.id] = FixtureAdapter(site) }
        app.library.switchSite("r34v")
        app.library.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, true).putBoolean("settings_v1_auto_update", false).putBoolean("settings_v1_auto_fullscreen", false).commit()
    }
    fun video(siteId: String = app.library.siteId, page: Int = 1, index: Int = 0): Video {
        val site = SiteRegistry.byIdOrDefault(siteId)
        if (cinematic && site.mediaKind == MediaKind.VIDEO) return Video("compose-$nonce-$page-$index", cinemaTitles[index % cinemaTitles.size],
            "${site.base}/compose-$nonce/content-$page-$index/", "${site.base}/compose-cover-$nonce-$index.jpg", "${42 + index}:00",
            rating = "${98 - index % 9}% 匹配", type = if (index % 3 == 0) "悬疑 • 科幻 • 剧情" else "剧情 • 惊悚 • 口碑佳作", site = site.id)
        return Video("compose-$nonce-$page-$index", "${site.name} 示例 $page-$index", "${site.base}/compose-$nonce/content-$page-$index/", "${site.base}/compose-cover-$nonce-$index.png", "0:10", site = site.id, mediaKind = site.mediaKind, thumbWidth = if (site.mediaKind == MediaKind.GALLERY) 600 else 1600, thumbHeight = if (site.mediaKind == MediaKind.GALLERY) if (index % 2 == 0) 900 else 600 else 900)
    }
    private val cinemaTitles = listOf("怪奇物语", "黑暗荣耀", "爱，死亡和机器人", "暗黑", "星期三", "后翼弃兵")
    private val cinemaArt = listOf("stranger.jpg", "glory.jpg", "robots.jpg", "dark.jpg", "wednesday.jpg", "queen.jpg")
    private inner class FixtureAdapter(override val site: SiteContext) : SiteAdapter {
        override val capabilities = setOf(Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL, Capability.CATEGORY, Capability.RANKING) + if (site.id in listOf("ehentai", "exhentai")) setOf(Capability.SESSION_IMPORT) else emptySet()
        override val sections = listOf(Section("latest", "最新更新", feed = FeedRequest()), Section("popular", "热门榜", feed = FeedRequest(FeedKind.POPULAR)), Section("categories", "分类", feed = FeedRequest(FeedKind.CATEGORY, "sample")))
        override val sorts = listOf(Sort.NEWEST, Sort.VIEWS, Sort.RELEVANCE)
        override fun resolve(request: FeedRequest) = "${site.base}/compose-$nonce/${request.id}"
        override fun homeUrl() = "${site.base}/compose-$nonce/"
        override suspend fun feed(request: FeedRequest): FeedPage {
            requests.add(site.id to request); if (offline) throw IOException("Fixture offline")
            // Every tag gets its own ids so a merged subscription list can be told apart from a
            // plain feed and per-tag failures stay visible.
            val tag = request.target.takeIf { request.kind == FeedKind.TAG }.orEmpty()
            if (tag.isNotBlank() && tag == failTag) throw IOException("Fixture tag unavailable")
            return FeedPage((0..15).map { video(site.id, request.page, it).let { v ->
                if (tag.isBlank()) v else v.copy(id = "$tag-${v.id}", title = "$tag 作品 $it")
            } }, request.page, 3, "Neutral fixture")
        }
        override suspend fun detail(url: String): Detail {
            if (offline) throw IOException("Fixture offline")
            return Detail(video(site.id), listOf(Stream("720p", server.url("/clip.mp4?quality=720").toString()), Stream("480p", server.url("/clip.mp4?quality=480").toString())), mapOf("标签" to listOf(Link("sample", "示例标签", "${site.base}/tags/sample/"))), "用于验证原生播放的中性测试片段。", listOf(video(site.id, 1, 1)), listOf(Comment("测试作者", "中性测试评论", "2026")), emptyList())
        }
        override suspend fun index(section: String) = listOf(Link("sample", "示例索引", "${site.base}/tags/sample/"))
        override fun sectionUrl(key: String): String? = if (key == "catalog") "${site.base}/catalog/" else null
        override suspend fun login(network: Network, username: String, password: String) = Unit
        override suspend fun gallery(url: String): GalleryDetail {
            galleryCalls.add("${site.id}:detail")
            if (offline) throw IOException("Fixture offline")
            return GalleryDetail(video(site.id), 8, (1..8).map { GalleryPageLink(it, "${site.base}/compose-page-$nonce-$it") }, 8, description = "原创中性图页，用于测试连续阅读、预读与恢复。", language = "中文", groups = mapOf("标签" to listOf(Link("sample", "示例标签", "${site.base}/tags/sample/"))))
        }
        override suspend fun galleryImage(url: String): GalleryImage {
            galleryCalls.add("${site.id}:page")
            if (offline) throw IOException("Fixture offline")
            val number = url.substringBefore('?').substringAfterLast('-').toInt()
            return GalleryImage(number, url, "${site.base}/compose-image-$number-$nonce.png", if (number > 1) "${site.base}/compose-page-$nonce-${number - 1}" else null, if (number < 8) "${site.base}/compose-page-$nonce-${number + 1}" else null)
        }
    }
    fun launch(): MainActivity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    fun launchGallery(read: Boolean = false, site: String = "ehentai", offline: Boolean = false): GalleryActivity = ins.startActivitySync(Intent(ins.targetContext, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("gallery", app.library.gson.toJson(video(site))).putExtra("read", read).putExtra("offline", offline)) as GalleryActivity
    fun launchDownloads(): MainActivity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("downloads", true)) as MainActivity
    fun launchPlayer(): PlayerActivity = ins.startActivitySync(Intent(ins.targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("video", app.library.gson.toJson(video()))) as PlayerActivity
    fun shot(name: String) {
        val dir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: ins.targetContext.getExternalFilesDir(null)!!.absolutePath, "compose-shots").apply { mkdirs() }
        ins.waitForIdleSync(); ins.uiAutomation.waitForIdle(250, 3000)
        val image = ins.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    override fun close() {
        kotlinx.coroutines.runBlocking { app.galleryDownloads.tasks().filter { it.id !in originalBookTasks }.forEach { app.galleryDownloads.remove(it.id) } }
        ins.runOnMainSync { listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED).flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct().forEach(Activity::finish) }
        ins.waitForIdleSync(); adapters.clear(); adapters.putAll(originals); clientField.set(app.network, originalClient)
        app.library.switchSite(previousSite)
        app.library.prefs.edit().clear().apply { prefs.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value); is Int -> putInt(key, value); is Float -> putFloat(key, value); is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet()) } } }.commit()
        app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }; originalFeeds.forEach { (name, bytes) -> File(app.network.feedCacheDirectory, name).writeBytes(bytes) }
        File(app.filesDir, "gallery-cache").walkTopDown().filter { it.isFile && it.absolutePath !in galleryFiles }.forEach { it.delete() }
        fixtureClient.connectionPool.evictAll(); fixtureClient.dispatcher.executorService.shutdown(); server.shutdown()
    }
}
abstract class ComposeUiCase {
    @get:Rule val ui = createEmptyComposeRule()
    fun waitText(text: String) { ui.waitUntil(15000) { runCatching { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
    fun waitCovers() { ui.waitUntil(15000) { ui.onAllNodesWithTag("cover-ready", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() }
    fun waitHome() { ui.waitUntil(15000) { ui.onAllNodesWithTag("home-carousel").fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() }
    fun clickText(text: String) { waitText(text); ui.onAllNodesWithText(text).onLast().performClick() }
    fun back(fixture: ComposeFixture) { ui.waitForIdle(); fixture.ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); ui.waitForIdle() }
    fun switchSite(name: String) { ui.onNode(hasContentDescription("切换站点，当前", substring = true)).performClick(); clickText(name) }
}
