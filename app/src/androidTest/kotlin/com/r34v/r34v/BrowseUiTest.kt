@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.playback.LinkSpeed
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable

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
    @Test fun playerQualitySpeedFullScreenAndPositionSurviveRecreation() = fullscreenFixture { f ->
        val activity = f.launchPlayer()
        val title = f.video().title
        val originalOrientation = ui.runOnIdle { activity.resources.configuration.orientation }
        val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
        waitForDecodedVideo(f); showPlayerControls()
        // Startup quality adapts to the process-wide measured link speed.
        val initialQuality = listOf("720p", "480p").first { label ->
            ui.onAllNodesWithContentDescription("清晰度，当前 $label").fetchSemanticsNodes().isNotEmpty()
        }
        val changedQuality = if (initialQuality == "720p") "480p" else "720p"
        ui.onNodeWithContentDescription("清晰度，当前 $initialQuality").performClick()
        ui.onNode(hasText(initialQuality) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
            .assertIsSelected(); f.shot("05-player-quality")
        ui.onNodeWithText(changedQuality).performScrollTo().performClick(); waitForDecodedVideo(f)
        assertEquals(changedQuality, f.app.library.string("quality"))
        showPlayerControls(); ui.onNodeWithContentDescription("播放速度，当前 1.0 倍").performClick()
        ui.onNodeWithText("2.0×").performScrollTo().assertIsDisplayed(); f.shot("05-player-speed")
        ui.onNodeWithText("取消").assertIsDisplayed()
        ui.onNodeWithText("2.0×").performClick()
        ui.runOnIdle {
            val engine = field.get(activity) as Player
            assertEquals(2f, engine.playbackParameters.speed, .001f)
            engine.pause()
            engine.seekTo(2000)
        }
        waitForDecodedVideo(f, 1900L..2100L)
        f.shot("05-player")
        showPlayerControls()
        ui.onNodeWithContentDescription("全屏").performClick()
        waitForPlayerWindow(activity, SCREEN_ORIENTATION_LANDSCAPE, ORIENTATION_LANDSCAPE, fullscreen = true)
        ui.onNodeWithText(title).assertDoesNotExist()
        f.shot("06-player-fullscreen")

        back(f)
        // The quality control exists in both modes. Confirm the details and the window have returned before recreating.
        waitForPlayerDetails(title)
        waitForPlayerWindow(activity, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)
        val recreated = recreatePlayer(f, activity)
        waitForPlayerDetails(title)
        waitForPlayerWindow(recreated, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)
        waitForDecodedVideo(f, 1900L..2100L)
        ui.runOnIdle {
            val engine = field.get(recreated) as Player
            assertFalse(engine.playWhenReady)
            assertEquals(2f, engine.playbackParameters.speed, .001f)
        }
        assertEquals(changedQuality, f.app.library.string("quality"))
    }

    @Test fun portraitFullscreenWaitsForDimensionsAndStaysExitedAfterRecreation() =
        fullscreenFixture(videoAsset = "test-pattern-portrait.mp4") { f ->
            f.app.library.set("auto_fullscreen", true)
            val details = CompletableDeferred<Unit>()
            f.detailHook = { details.await() }
            val activity = f.launchPlayer()
            val title = f.video().title
            val originalOrientation = ui.runOnIdle { activity.resources.configuration.orientation }
            waitForPlayerDetails(title)

            ui.onNodeWithContentDescription("全屏").performClick()
            waitForPlayerWindow(activity, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = true)
            ui.onNodeWithText(title).assertDoesNotExist()
            ui.onNodeWithContentDescription("退出全屏").performClick()
            waitForPlayerDetails(title)
            waitForPlayerWindow(activity, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)

            details.complete(Unit)
            waitForDecodedVideo(f)
            waitForPlayerDetails(title)
            waitForPlayerWindow(activity, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)
            showPlayerControls()
            ui.onNodeWithContentDescription("全屏").performClick()
            waitForPlayerWindow(activity, SCREEN_ORIENTATION_PORTRAIT, ORIENTATION_PORTRAIT, fullscreen = true)
            ui.onNodeWithText(title).assertDoesNotExist()

            showPlayerControls()
            ui.onNodeWithContentDescription("退出全屏").performClick()
            waitForPlayerDetails(title)
            waitForPlayerWindow(activity, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)
            val restoredDetails = CompletableDeferred<Unit>()
            f.detailHook = { restoredDetails.await() }
            val recreated = recreatePlayer(f, activity)
            waitForPlayerDetails(title)
            waitForPlayerWindow(recreated, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)
            restoredDetails.complete(Unit)
            waitForDecodedVideo(f)
            waitForPlayerDetails(title)
            waitForPlayerWindow(recreated, SCREEN_ORIENTATION_UNSPECIFIED, originalOrientation, fullscreen = false)
        }

    private fun fullscreenFixture(videoAsset: String = "test-pattern.mp4", test: (ComposeFixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val setting = "immersive_mode_confirmations"
        val previous = Settings.Secure.getString(resolver, setting)
        val confirmed = "confirmed"

        fun writeSetting(value: String?) {
            val command = when {
                value == null -> "settings --user current delete secure $setting"
                value == confirmed -> "settings --user current put secure $setting $confirmed"
                else -> {
                    val escaped = value.replace("'", "'\\''")
                    val restore = "settings --user current put secure $setting '$escaped'"
                    // UiAutomation splits on whitespace even inside quotes. Pass the restore command as one argument.
                    val encoded = Base64.encodeToString(restore.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    "sh -c echo\${IFS}$encoded|base64\${IFS}-d|sh"
                }
            }
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            check(Settings.Secure.getString(resolver, setting) == value) { "Could not update $setting. $output" }
        }

        // Android's first-run immersive tutorial takes focus and consumes system Back.
        Closeable { if (previous != confirmed) writeSetting(previous) }.use {
            if (previous != confirmed) writeSetting(confirmed)
            ComposeFixture(videoAsset = videoAsset).use(test)
        }
    }

    private fun showPlayerControls() {
        val menu = ui.onNodeWithContentDescription("播放器菜单")
        if (!menu.isDisplayed()) {
            ui.onNodeWithTag("player-surface").performTouchInput { click() }
            ui.waitUntil(5000) { menu.isDisplayed() }
        }
    }

    private fun waitForPlayerDetails(title: String) {
        val heading = ui.onNodeWithText(title)
        ui.waitUntil(10000) { heading.isDisplayed() }
        ui.onNodeWithContentDescription("退出全屏").assertDoesNotExist()
    }

    @Suppress("DEPRECATION")
    private fun waitForPlayerWindow(activity: PlayerActivity, requested: Int, orientation: Int, fullscreen: Boolean) {
        ui.waitUntil(10000) {
            ui.runOnIdle {
                val window = activity.window
                val decor = window.decorView
                val statusBarVisible = if (Build.VERSION.SDK_INT >= 30) {
                    ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.statusBars())
                } else {
                    decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 &&
                        window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0
                }
                activity.hasWindowFocus() && activity.requestedOrientation == requested &&
                    activity.resources.configuration.orientation == orientation && statusBarVisible == !fullscreen
            }
        }
    }

    private fun recreatePlayer(f: ComposeFixture, previous: PlayerActivity): PlayerActivity {
        ui.runOnIdle { previous.recreate() }
        var recreated: PlayerActivity? = null
        ui.waitUntil(10000) {
            f.ins.runOnMainSync {
                recreated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlayerActivity>().firstOrNull { it !== previous }
            }
            recreated != null
        }
        return requireNotNull(recreated)
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
