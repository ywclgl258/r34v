package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModernUiTest : ComposeUiCase() {
    private fun clear(f: ComposeFixture) {
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary())).commit()
        f.app.library.prefs.edit().apply {
            SiteRegistry.librarySources.forEach { remove("${it.id}:watch_history_v1"); remove("${it.id}:reading_history_v1") }
        }.commit()
    }

    @Test fun emptyHomeAndLibraryKeepDestinationsReadable(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f); f.launch(); waitHome(); waitText("发现下一部"); f.shot("modern-empty-home")
        clickText("片库"); waitText("我的片库")
        val config = f.ins.targetContext.resources.configuration
        if (config.screenWidthDp < 840 || config.fontScale > 1.3f) {
            ui.onNode(hasText("稍后看") and hasClickAction()).assertWidthIsAtLeast((config.screenWidthDp * .8f).dp)
        }
        f.shot("modern-empty-library")
        ui.onNodeWithTag("library-hub").performScrollToNode(hasText("导入 CBZ / ZIP / 图片目录"))
        ui.onNodeWithText("导入 CBZ / ZIP / 图片目录").assertIsDisplayed()
        ui.onNodeWithTag("library-hub").performScrollToNode(hasText("从链接打开作品"))
        ui.onNodeWithText("从链接打开作品").assertIsDisplayed().performClick()
        waitText("从链接打开作品"); f.shot("modern-open-link")
    }

    @Test fun favoriteKeepsTheChosenWorkAfterReopeningLibrary(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f)
        val video = f.video(index = 1)
        f.app.library.personal.add(video)
        f.launch(); waitHome(); clickText("开始观看"); waitText("简介")
        ui.onNodeWithText("收藏").performScrollTo().performClick()
        waitText("已收藏")
        org.junit.Assert.assertTrue(f.app.library.isFavorite("video", video.id, video.site))
        f.launch(); waitHome(); clickText("片库"); clickText("收藏"); waitText(video.title)
        ui.onNodeWithText(video.title).assertIsDisplayed()
        f.shot("modern-favorite-identity")
    }

    @Test fun finishedWatchIsNotPromotedAsContinueWatching(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f)
        val video = f.video()
        f.app.library.personal.add(video)
        f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 600, 600, site = video.site))
        f.launch(); waitHome(); waitText("发现下一部")
        ui.onAllNodes(hasText("继续观看") and hasClickAction()).assertCountEquals(0)
        ui.onNodeWithText("我的稍后看").assertExists()
        f.shot("modern-finished-watch")
    }

    /** Opt-in live fixture for independent manual UX review; always restores data when closed. */
    @Test fun liveReviewFixture(): Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modernUiReview") == "1")
        ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
            clear(f)
            val video = f.video()
            f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 42, 600, site = video.site))
            (0..3).forEach { f.app.library.personal.add(f.video(index = it)) }
            f.launch(); waitHome(); waitCovers()
            val done = File(f.ins.targetContext.cacheDir, "modern-review-done")
            done.delete()
            val end = android.os.SystemClock.elapsedRealtime() + 8 * 60_000
            while (!done.exists() && android.os.SystemClock.elapsedRealtime() < end) {
                ui.waitForIdle() // Drive Compose's test frame clock while an external reviewer taps.
                Thread.sleep(100)
            }
            done.delete()
        }
    }
}
