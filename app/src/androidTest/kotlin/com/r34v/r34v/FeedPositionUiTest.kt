package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedPage
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.GalleryLanguage
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Sort
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.entryKey
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
