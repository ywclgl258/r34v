package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class SiteSwitcherUiTest : ComposeUiCase() {
    @Test fun allSitesFitOnOpeningAndTheLastOneSwitchesWithOneTap() = ComposeFixture().use { f ->
        val config = f.launch().resources.configuration
        org.junit.Assume.assumeTrue(config.screenWidthDp >= 360 && config.screenHeightDp >= 600 &&
            config.screenWidthDp < config.screenHeightDp && config.fontScale == 1f)
        waitHome()
        val list = hasContentDescription("站点列表")
        fun entry(site: SiteContext) = ui.onNode(hasText(site.name) and hasAnyAncestor(list))
        ui.onNode(hasContentDescription("切换站点，当前", substring = true)).performClick()
        ui.onAllNodes(hasTestTag("pinned-site-r34v")).assertCountEquals(0)
        val viewport = ui.onNode(list).getUnclippedBoundsInRoot()
        val minTarget = (48 * f.ins.targetContext.resources.displayMetrics.density).roundToInt()
        SiteRegistry.all.forEach { site ->
            val tile = entry(site).assertIsDisplayed()
            val bounds = tile.getUnclippedBoundsInRoot()
            assertTrue("${site.name} must be fully visible on opening", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            val touch = tile.fetchSemanticsNode().boundsInRoot
            assertTrue("${site.name} must retain a 48dp touch target", touch.height >= minTarget && touch.width >= minTarget)
        }
        entry(SiteRegistry.R34V).assertIsSelected()
        f.shot("site-switcher-all-sites")
        val last = SiteRegistry.all.last()
        entry(last).performClick()
        ui.onNodeWithContentDescription("切换站点，当前 ${last.name}").assertIsDisplayed().performClick()
        entry(last).assertIsSelected()
        ui.onNodeWithContentDescription("关闭站点列表").performClick()
        assertEquals(last.id, f.app.library.siteId)
    }

    @Test fun pinsCanBeAddedOpenedAndRemovedAcrossSites() = ComposeFixture().use { f ->
        f.app.library.prefs.edit().remove(LibraryStore.PINNED_SITE_IDS).commit()
        f.launch(); waitHome(); openSites(); clickText("常用站点")
        waitText("还没有常用站点")
        pinHanime()
        assertEquals(listOf("hanime1"), f.app.library.pinnedSiteIds())
        ui.onNodeWithTag("pinned-site-hanime1").performClick()
        ui.onNodeWithContentDescription("切换站点，当前 ${SiteRegistry.HANIME1.name}").assertIsDisplayed().performClick()
        assertEquals("hanime1", f.app.library.siteId)
        ui.onNodeWithTag("pinned-site-hanime1").assertIsSelected()
        clickText("管理")
        ui.onNodeWithTag("site-entry-hanime1").assertIsOn().performClick().assertIsOff()
        clickText("完成")
        ui.onNodeWithTag("pinned-site-hanime1").assertDoesNotExist()
        assertTrue(f.app.library.pinnedSiteIds().isEmpty())
        assertEquals("hanime1", f.app.library.siteId)
    }

    /** Run the two phases in separate instrumentation processes on a dedicated device. */
    @Test fun pinsPersistAcrossProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("sitePinProcessPhase")
        org.junit.Assume.assumeTrue(phase in setOf("write", "restore"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as R34Application
        val key = LibraryStore.PINNED_SITE_IDS
        val marker = File(app.cacheDir, "site-pins-process-test.json")
        if (phase == "write") {
            val before = app.library.prefs.getString(key, null)
            try {
                ComposeFixture(preservePreferences = setOf(key)).use { f ->
                    check(f.app.library.prefs.edit().remove(key).commit())
                    f.launch(); waitHome(); openSites(); clickText("常用站点"); pinHanime()
                    assertEquals(listOf("hanime1"), f.app.library.pinnedSiteIds())
                }
                marker.writeText(JSONObject().put("pid", android.os.Process.myPid()).put("previous", before ?: JSONObject.NULL).toString())
            } catch (failure: Throwable) {
                restorePins(app, key, before)
                throw failure
            }
        } else {
            val state = JSONObject(marker.readText())
            try {
                assertNotEquals(state.getInt("pid"), android.os.Process.myPid())
                ComposeFixture().use { f ->
                    f.launch(); waitHome(); openSites(); clickText("常用站点")
                    ui.onNodeWithTag("pinned-site-hanime1").assertIsDisplayed()
                }
            } finally {
                restorePins(app, key, if (state.isNull("previous")) null else state.getString("previous"))
                marker.delete()
            }
        }
    }

    private fun restorePins(app: R34Application, key: String, value: String?) {
        val edit = app.library.prefs.edit()
        check((if (value == null) edit.remove(key) else edit.putString(key, value)).commit())
    }

    private fun openSites() = ui.onNode(hasContentDescription("切换站点，当前", substring = true)).performClick()

    private fun pinHanime() {
        clickText("管理")
        ui.onNodeWithTag("site-entry-hanime1").assertIsOff().performClick().assertIsOn()
        clickText("完成")
        ui.onNodeWithTag("pinned-site-hanime1").assertIsDisplayed()
    }

    @Test fun drawerTouchNavigationAndSiteSpecificLibrary() = ComposeFixture().use { f ->
        val video = f.video(); f.app.library.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb))
        f.launch(); waitHome(); switchSite(SiteRegistry.HANIME1.name); waitHome(); assertEquals("hanime1", f.app.library.siteId)
        clickText("片库"); clickText("收藏"); waitText("还没有收藏内容")
        switchSite(SiteRegistry.IWARA.name); waitHome(); assertEquals("iwara", f.app.library.siteId)
        clickText("发现")
        val iwara = f.video(SiteRegistry.IWARA.id)
        waitText(iwara.title)
        ui.onNodeWithTag("media-${iwara.site}-${iwara.id}").assertIsDisplayed()
        f.shot("07-iwara-discover")
        switchSite(SiteRegistry.R34V.name); waitHome(); clickText("片库"); clickText("收藏"); waitText(video.title)
        f.launch(); waitHome(); assertEquals("r34v", f.app.library.siteId)
    }
}
