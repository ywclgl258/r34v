package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.playback.LinkSpeed
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
        repeat(3) { ui.onNodeWithContentDescription("内容列表").performTouchInput { swipeUp() } }
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
        fun showControls() {
            if (ui.onAllNodesWithContentDescription("播放器菜单").fetchSemanticsNodes().isEmpty()) {
                ui.onNodeWithTag("player-surface").performTouchInput { click() }
                ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("播放器菜单").fetchSemanticsNodes().isNotEmpty() }
            }
        }
        ready(); showControls()
        // Startup quality adapts to the process-wide measured link speed.
        val initialQuality = listOf("720p", "480p").first { label ->
            ui.onAllNodesWithContentDescription("清晰度，当前 $label").fetchSemanticsNodes().isNotEmpty()
        }
        val changedQuality = if (initialQuality == "720p") "480p" else "720p"
        ui.onNodeWithContentDescription("清晰度，当前 $initialQuality").performClick()
        ui.onNode(hasText(initialQuality) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
            .assertIsSelected(); f.shot("05-player-quality")
        ui.onNodeWithText(changedQuality).performScrollTo().performClick(); ready()
        assertEquals(changedQuality, f.app.library.string("quality"))
        showControls(); ui.onNodeWithContentDescription("播放速度，当前 1.0 倍").performClick()
        ui.onNodeWithText("2.0×").performScrollTo().assertIsDisplayed(); f.shot("05-player-speed")
        ui.onNodeWithText("取消").assertIsDisplayed()
        ui.onNodeWithText("2.0×").performClick()
        ui.runOnIdle { assertEquals(2f, engine()!!.playbackParameters.speed, .001f); engine()!!.seekTo(2000) }
        f.shot("05-player")
        showControls()
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("全屏").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("全屏").performClick(); ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("退出全屏").fetchSemanticsNodes().isNotEmpty() }
        // 横屏视频进全屏必须真的旋转；SENSOR_* 在方向锁定或部分 ROM 上会留在竖屏。
        ui.waitUntil(10000) { var landscape = false; f.ins.runOnMainSync { landscape = activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp }; landscape }
        f.shot("06-player-fullscreen"); back(f)
        // Reattaching the surface can buffer long enough for the existing adaptive
        // policy to drop to 480p. The preference must still survive the transition.
        val qualityControl = hasContentDescription("清晰度，当前 $changedQuality") or hasContentDescription("清晰度，当前 480p")
        showControls(); ui.waitUntil(5000) { ui.onAllNodes(qualityControl).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(qualityControl).assertExists()
        ui.runOnIdle { activity.recreate() }; waitText("${f.video().title}")
        ui.waitUntil(15000) { f.app.library.watch(f.video().id)?.position?.let { it >= 1 } == true }
        assertEquals(changedQuality, f.app.library.string("quality")); assertEquals("2.0", f.app.library.string("native_speed"))
    }

    @Test fun aStoredAutoDowngradeDoesNotPinTheNextPlayback() = ComposeFixture().use { f ->
        val link = f.app.linkSpeed
        val sampled = LinkSpeed::class.java.getDeclaredField("sampled").apply { isAccessible = true }
        val seeded = LinkSpeed::class.java.getDeclaredField("seeded").apply { isAccessible = true }
        val sampledAt = LinkSpeed::class.java.getDeclaredField("sampledAt").apply { isAccessible = true }
        val seededAt = LinkSpeed::class.java.getDeclaredField("seededAt").apply { isAccessible = true }
        var previous: List<Long> = listOf(0L, 0L, 0L, 0L)
        f.ins.runOnMainSync { previous = listOf(sampled.getInt(link).toLong(), seeded.getInt(link).toLong(), sampledAt.getLong(link), seededAt.getLong(link)) }
        f.app.library.set("quality", "720p")
        val video = f.video()
        // 旧版会把自动降档的结果写回本片偏好，之后网络再好也永远从 480p 起播。
        f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 5, 300, quality = "480p", site = video.site))
        try {
            // 固定成健康链路，并让注入值被当作“刚测到”。
            f.ins.runOnMainSync { sampled.setInt(link, 0); seeded.setInt(link, 20_000); sampledAt.setLong(link, 0); seededAt.setLong(link, 0) }
            val activity = f.launchPlayer()
            val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            ui.waitUntil(20000) { var ready = false; f.ins.runOnMainSync { ready = (field.get(activity) as? androidx.media3.common.Player)?.playbackState == androidx.media3.common.Player.STATE_READY }; ready }
            ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("清晰度，当前 720p").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            f.ins.runOnMainSync {
                sampled.setInt(link, previous[0].toInt()); seeded.setInt(link, previous[1].toInt())
                sampledAt.setLong(link, previous[2]); seededAt.setLong(link, previous[3])
            }
        }
    }
}
