package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedPage
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.GalleryLanguage
import com.r34v.r34v.data.Link
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Sort
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.adapter.IwaraAdapter
import com.r34v.r34v.data.adapter.PornhubAdapter
import com.r34v.r34v.data.adapter.SiteAdapter
import com.r34v.r34v.data.entryKey
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class FeedPositionUiTest : ComposeUiCase() {
    private fun savedWorks(f: ComposeFixture): Set<String> {
        val saved = f.app.library.prefs.getString("browse_positions_v1", null) ?: return emptySet()
        return JsonParser.parseString(saved).asJsonObject.entrySet().mapTo(mutableSetOf()) { it.value.asJsonObject["work"].asString }
    }
    private fun savedWork(f: ComposeFixture, work: Video): Boolean = work.entryKey in savedWorks(f)

    private fun waitForWork(work: Video, prefix: String = "media") {
        val tag = "$prefix-${work.site}-${work.id}"
        ui.waitUntil(15000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun homeRestoresTheWorkEvenAfterItMovesToAnotherShelf() = ComposeFixture().use { f ->
        val all = (0 until 64).map { f.video("r34v", index = it) }
        val current = AtomicReference(all)
        f.feedOverride = { _, request -> FeedPage(
            if (request.kind == FeedKind.LATEST) current.get() else emptyList(), 1, 1, "首页样本") }
        f.launch(); waitHome()
        val first = all[8]
        val anchor = all[11]
        val home = ui.onNodeWithContentDescription("内容列表")
        home.performScrollToIndex(2)
        val shelf = ui.onNode(hasScrollToIndexAction() and hasAnyChild(hasTestTag("poster-${first.site}-${first.id}")))
        shelf.performScrollToIndex(3)
        ui.waitUntil(15000) { savedWork(f, anchor) }

        current.set(all.toMutableList().apply { remove(anchor); add(27, anchor) })
        f.requests.clear()
        f.launch()
        waitForWork(anchor, "poster")
        assertEquals(1, f.requests.count { it.second.kind == FeedKind.LATEST })
    }

    @Test fun discoverRestoresPagedWorkAndKeepsSitesAndFiltersSeparate() = ComposeFixture().use { f ->
        val owner = "r34v"
        val all = (0 until 48).map { f.video(owner, index = it) }
        val current = AtomicReference(all)
        f.feedOverride = { site, request ->
            if (site != owner) null else {
                val entries = if (request.kind == FeedKind.LATEST) current.get().drop((request.page - 1) * 16).take(16) else emptyList()
                FeedPage(entries, request.page, 3, "发现样本", nextCursor = "page-${request.page + 1}".takeIf { request.page < 3 })
            }
        }
        f.launch(); waitHome(); clickText("发现"); waitForWork(all[0])
        val grid = ui.onNodeWithTag("discover-feed")
        val anchor = all[20]
        grid.performScrollToIndex(14)
        ui.waitUntil(15000) { f.requests.any { it.first == owner && it.second.page == 2 } }
        ui.waitUntil(15000) {
            runCatching { grid.performScrollToIndex(21) }.isSuccess &&
                ui.onAllNodesWithTag("media-${anchor.site}-${anchor.id}").fetchSemanticsNodes().isNotEmpty()
        }
        ui.waitUntil(15000) { savedWork(f, anchor) }

        current.set(all.toMutableList().apply { remove(anchor); add(28, anchor) })
        f.requests.clear()
        f.launch(); waitHome(); clickText("发现"); waitForWork(anchor)
        assertTrue("The restored work must remain the saved anchor", savedWork(f, anchor))
        assertEquals("page-2", f.requests.first { it.first == owner && it.second.page == 2 }.second.cursor)

        switchSite(SiteRegistry.HANIME1.name); waitHome(); clickText("发现")
        waitForWork(f.video(SiteRegistry.HANIME1.id))
        ui.onNodeWithTag("media-${anchor.site}-${anchor.id}").assertDoesNotExist()
        switchSite(SiteRegistry.R34V.name); waitHome()
        val key = "$owner:explore_filter_v1"
        f.app.library.prefs.edit().putString(key, f.app.library.gson.toJson(FeedRequest(sort = Sort.VIEWS))).commit()
        f.launch(); waitHome(); clickText("发现"); waitForWork(all[0])
        ui.onNodeWithContentDescription("排序方式，当前 播放量").assertExists().performClick()
        clickText("最新"); waitForWork(anchor)
        assertTrue("The restored work must remain the saved anchor", savedWork(f, anchor))

        current.set(current.get().filterNot { it.entryKey == anchor.entryKey })
        f.launch(); waitHome(); clickText("发现"); waitForWork(all[0])
        ui.waitUntil(15000) { !savedWork(f, anchor) }
        f.requests.clear()
        clickText("刷新"); waitForWork(all[0])
        assertEquals(listOf(1), f.requests.filter { it.first == owner }.map { it.second.page })
    }

    @Test fun galleryHomeRestoresTheWorkOnlyForTheSameLanguageFilter() = ComposeFixture().use { f ->
        val site = SiteRegistry.EHENTAI.id
        val all = (0 until 40).map { f.video(site, index = it).copy(thumbWidth = 600, thumbHeight = 900) }
        f.app.library.switchSite(site)
        f.app.library.setGalleryLanguage(GalleryLanguage.ALL, site)
        f.feedOverride = { _, request -> FeedPage(all, request.page, 1, "图库首页样本") }
        f.launch(); waitHome()
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(12)
        ui.waitUntil(15000) { all.drop(6).any { savedWork(f, it) } }
        val anchor = all.drop(6).first { savedWork(f, it) }
        f.launch(); waitForWork(anchor)

        f.app.library.setGalleryLanguage(GalleryLanguage.JAPANESE, site)
        f.launch(); waitForWork(all.first())
        ui.onNodeWithTag("media-${anchor.site}-${anchor.id}").assertDoesNotExist()
    }

    @Test fun discoverFiltersReportTheSelectedLanguageAndResetToTheSiteDefault() = ComposeFixture().use { f ->
        val site = SiteRegistry.EHENTAI
        val all = f.video(site.id).copy(title = "全部语言样本")
        val chinese = all.copy(id = "${all.id}-chinese", title = "中文筛选样本")
        f.app.library.switchSite(site.id)
        f.app.library.setGalleryLanguage(GalleryLanguage.ALL, site.id)
        f.feedOverride = { owner, request ->
            if (owner != site.id) null
            else FeedPage(listOf(if (request.language == GalleryLanguage.CHINESE) chinese else all), 1, 1, "发现语言样本")
        }
        f.launch(); waitHome(); clickText("发现"); waitForWork(all)
        ui.onNodeWithContentDescription("漫画语言，当前 全部显示").performClick()
        clickText("仅中文")
        waitForWork(chinese)
        ui.onNodeWithText("仅中文").assertIsDisplayed()
        assertEquals(GalleryLanguage.CHINESE, f.app.library.galleryLanguage(site.id))

        f.launch(); waitHome(); clickText("发现"); waitForWork(chinese)
        ui.onNodeWithContentDescription("内容分类，当前 最新更新").performClick()
        clickText("热门榜")
        ui.waitUntil(15000) { f.requests.any { it.first == site.id && it.second.kind == FeedKind.POPULAR && it.second.language == GalleryLanguage.CHINESE } }
        ui.onNodeWithContentDescription("内容分类，当前 热门榜").assertIsDisplayed()
        clickText("清除筛选")
        waitForWork(all)
        ui.onNodeWithText("清除筛选").assertDoesNotExist()
        assertEquals(GalleryLanguage.ALL, f.app.library.galleryLanguage(site.id))
    }

    @Test fun discoverOpensAndSearchesTheCurrentSiteCategoryDirectory() = ComposeFixture().use { f ->
        val site = SiteRegistry.PORNHUB
        val native = PornhubAdapter(site, f.app.network)
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val neutral = adapters.getValue(site.id)
        val directory = (1..48).map { index ->
            Link("sample-$index", if (index == 48) "远山样本" else "分类样本 $index", "${site.base}/categories/sample-$index/")
        }
        val loaded = AtomicReference(directory)
        adapters[site.id] = object : SiteAdapter by neutral {
            override val sections = native.sections
            override val catalogueTitle = native.catalogueTitle
            override fun homeRequest(page: Int) = native.homeRequest(page)
            override fun sectionUrl(key: String) = native.sectionUrl(key)
            override fun linkRequest(url: String) = native.linkRequest(url)
            override suspend fun index(section: String): List<Link> {
                check(section == "categories")
                return loaded.get()
            }
        }
        val iwara = SiteRegistry.IWARA
        val iwaraFixture = adapters.getValue(iwara.id)
        adapters[iwara.id] = object : SiteAdapter by iwaraFixture {
            override val sections = IwaraAdapter(iwara, f.app.network).sections
        }
        f.app.library.switchSite(site.id)
        check(f.app.library.prefs.edit().remove("${site.id}:explore_filter_v1")
            .remove("${SiteRegistry.R34V.id}:explore_filter_v1").remove("${iwara.id}:explore_filter_v1").commit())
        f.launch(); waitHome(); clickText("发现")
        val openCategories = ui.onNode(hasText("分类") and hasClickAction())
        openCategories.assertIsDisplayed().performClick()
        waitText("分类样本 1")
        ui.onAllNodesWithText("分类").assertCountEquals(1)

        val search = ui.onNode(hasSetTextAction() and hasText("搜索分类"))
        search.performTextInput("远山")
        ui.onNodeWithText("远山样本").assertIsDisplayed()
        search.performTextReplacement("SAMPLE-48")
        ui.onNodeWithText("远山样本").assertIsDisplayed()
        closeKeyboard(f)
        f.shot("library-management-categories")
        search.performTextReplacement("no-match")
        ui.onNodeWithText("没有匹配的分类").assertIsDisplayed()
        ui.onNodeWithText("当前站点的分类目录为空").assertDoesNotExist()
        ui.onNodeWithContentDescription("清除分类搜索").performClick()
        ui.onNodeWithText("分类样本 1").assertIsDisplayed()

        search.performTextInput("sample-48")
        ui.onNodeWithText("远山样本").performClick()
        ui.waitUntil(15000) { f.requests.any { it.first == site.id && it.second.kind == FeedKind.CATEGORY && it.second.target == "sample-48" } }
        ui.onNodeWithContentDescription("固定当前频道").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        waitText("远山样本")
        ui.onNodeWithContentDescription("返回").performClick()

        loaded.set(emptyList())
        openCategories.performClick()
        waitText("当前站点的分类目录为空")
        ui.onNodeWithText("没有匹配的分类").assertDoesNotExist()

        switchSite(SiteRegistry.R34V.name); waitHome(); clickText("发现")
        openCategories.assertDoesNotExist()
        ui.onNodeWithContentDescription("内容分类，当前 最新更新").performClick()
        clickText("分类")
        ui.waitUntil(15000) { f.requests.any { it.first == SiteRegistry.R34V.id && it.second.kind == FeedKind.CATEGORY && it.second.target == "sample" } }

        switchSite(iwara.name); waitHome(); clickText("发现")
        openCategories.assertDoesNotExist()
        ui.onNodeWithContentDescription("内容分类，当前 最新上传").performClick()
        ui.onNode(hasText("最新上传") and hasAnyAncestor(isDialog())).assertExists()
        ui.onNode(hasText("分类") and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    @Test fun homeAndDiscoverRestoreWorksAcrossProcesses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("feedPositionProcessPhase")
        assumeTrue(phase == "write" || phase == "restore")
        val app = instrumentation.targetContext.applicationContext as R34Application
        val key = "browse_positions_v1"
        val marker = File(app.cacheDir, "feed-position-process-test.json")
        val writing = phase == "write"
        val prior = if (writing) {
            JSONObject().put("pid", android.os.Process.myPid())
                .put("previous", app.library.prefs.getString(key, null) ?: JSONObject.NULL)
        } else JSONObject(marker.readText())
        var saved = false
        try {
            if (!writing) assertNotEquals(prior.getInt("pid"), android.os.Process.myPid())
            ComposeFixture(preservePreferences = if (writing) setOf(key) else emptySet()).use { f ->
                if (writing) check(f.app.library.prefs.edit().remove(key).commit())
                val site = SiteRegistry.R34V.id
                check(f.app.library.prefs.edit().remove("$site:explore_filter_v1").commit())
                val works = (0 until 64).map { f.video(site, index = it).copy(id = "feed-process-test-$it") }
                val homeAnchor = works[11]
                val discoverAnchor = works[20]
                val discovering = AtomicBoolean(false)
                f.feedOverride = { _, request ->
                    val entries = when {
                        request.kind != FeedKind.LATEST -> emptyList()
                        discovering.get() -> works.drop((request.page - 1) * 16).take(16)
                        else -> works
                    }
                    FeedPage(entries, request.page, if (entries.isNotEmpty() && discovering.get()) 4 else 1, "进程恢复样本")
                }
                f.launch()
                if (writing) {
                    waitHome()
                    ui.onNodeWithContentDescription("内容列表").performScrollToIndex(2)
                    val first = works[8]
                    ui.onNode(hasScrollToIndexAction() and hasAnyChild(hasTestTag("poster-${first.site}-${first.id}")))
                        .performScrollToIndex(3)
                    ui.waitUntil(15000) { savedWork(f, homeAnchor) }
                    discovering.set(true)
                    clickText("发现"); waitForWork(works.first())
                    val grid = ui.onNodeWithTag("discover-feed")
                    grid.performScrollToIndex(14)
                    ui.waitUntil(15000) { runCatching { grid.performScrollToIndex(21) }.isSuccess }
                    ui.waitUntil(15000) { savedWork(f, discoverAnchor) }
                } else {
                    waitForWork(homeAnchor, "poster")
                    discovering.set(true)
                    clickText("发现"); waitForWork(discoverAnchor)
                    assertTrue(f.requests.any { it.first == site && it.second.page == 2 })
                }
            }
            if (writing) {
                marker.writeText(prior.toString())
                saved = true
            }
        } finally {
            if (!saved) {
                val edit = app.library.prefs.edit()
                if (prior.isNull("previous")) edit.remove(key) else edit.putString(key, prior.getString("previous"))
                check(edit.commit())
                marker.delete()
            }
        }
    }
}
