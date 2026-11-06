package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseUiTest : ComposeUiCase() {
    @Test fun browseSearchLibraryAndSettings() = ComposeFixture().use { f ->
        f.app.library.clearHistory()
        val activity = f.launch(); openSourceHome(); ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("暂停轮播").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        assertTrue("The hero must be visible on the initial viewport", ui.onNodeWithTag("home-carousel").fetchSemanticsNode().boundsInRoot.height > f.ins.targetContext.resources.displayMetrics.density * 200)
        f.shot("01-home")
        ui.onNodeWithContentDescription("暂停轮播").performClick(); ui.onNodeWithContentDescription("继续轮播").assertExists()
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(5)
        ui.waitUntil(15000) { f.requests.any { it.first == "r34v" && it.second.page == 2 } }
        clickText("搜索"); ui.onNodeWithContentDescription("提交搜索").performClick(); waitText("请输入关键词")
        ui.onNode(hasSetTextAction()).performTextInput("sample query"); clickText("标签"); ui.onNodeWithContentDescription("提交搜索").performClick()
        ui.waitUntil(15000) { f.requests.any { it.second.kind == FeedKind.TAG && it.second.target == "sample-query" } }
        ui.waitUntil(5000) {
            ui.runOnIdle { androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("内容列表").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("返回").performClick()
        waitText("最近搜索"); ui.onNode(hasSetTextAction()).assertTextContains("sample query")
        ui.onNodeWithContentDescription("清除关键词").performClick(); ui.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        f.shot("02-search")
        val video = f.video(); f.app.library.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb))
        clickText("片库"); clickText("收藏"); waitText(video.title); f.shot("03-library")
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("默认清晰度").performScrollTo().performClick(); clickText("1080p")
        assertEquals("1080p", f.app.library.string("quality")); f.shot("04-settings")
    }
    @Test fun playerQualitySpeedFullScreenAndPositionSurviveRecreation() = ComposeFixture().use { f ->
        val activity = f.launchPlayer()
        val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
        fun engine() = field.get(activity) as? androidx.media3.exoplayer.ExoPlayer
        fun ready() { ui.waitUntil(20000) { var ready = false; f.ins.runOnMainSync { ready = engine()?.playbackState == androidx.media3.common.Player.STATE_READY }; ready } }
        ready(); ui.onNodeWithContentDescription("清晰度，当前 720p").performScrollTo().performClick()
        ui.onNode(hasText("720p") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
            .assertIsSelected(); f.shot("05-player-quality"); ui.onNodeWithText("480p").performScrollTo().performClick(); ready()
        ui.onNodeWithContentDescription("播放速度，当前 1.0 倍").performScrollTo().performClick()
        ui.onNodeWithText("2.0×").performScrollTo().assertIsDisplayed(); f.shot("05-player-speed")
        ui.onNodeWithText("取消").assertIsDisplayed()
        ui.onNodeWithText("2.0×").performClick()
        ui.runOnIdle { assertEquals(2f, engine()!!.playbackParameters.speed, .001f); engine()!!.seekTo(2000) }
        f.shot("05-player")
        if (ui.onAllNodesWithContentDescription("全屏").fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("player-surface").performTouchInput { click() }
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("全屏").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("全屏").performClick(); ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("退出全屏").fetchSemanticsNodes().isNotEmpty() }
        f.shot("06-player-fullscreen"); back(f)
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("清晰度，当前 480p").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("清晰度，当前 480p").assertExists()
        ui.runOnIdle { activity.recreate() }; waitText("${f.video().title}")
        ui.waitUntil(15000) { f.app.library.watch(f.video().id)?.position?.let { it >= 1 } == true }
        assertEquals("480p", f.app.library.string("quality")); assertEquals("2.0", f.app.library.string("native_speed"))
    }
}
