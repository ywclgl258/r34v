package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextResponsiveUiTest : ComposeUiCase() {
    @Test fun homeLibraryAndCollectionKeepPrimaryActionsReachable() = ComposeFixture(cinematic = true).use { f ->
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary())).commit()
        val video = f.video(); f.app.library.personal.add(video); f.app.library.personal.add(f.video("ehentai"))
        f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 42, 600, site = video.site))
        val activity = f.launch(); waitHome(); waitCovers()
        val width = activity.resources.configuration.screenWidthDp
        val scale = activity.resources.configuration.fontScale
        val tag = "${width}dp-${(scale * 100).toInt()}font"
        val settings = ui.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Settings must fit inside the screen", settings.right <= root.right && settings.left >= root.left)
        ui.onNodeWithText("继续观看", substring = false).performScrollTo().assertIsDisplayed()
        f.shot("next-responsive-home-$tag")
        clickText("片库"); waitText("我的片库"); f.shot("next-responsive-library-$tag")
        openWatchLater(); waitText("按顺序观看 / 阅读")
        val short = activity.resources.configuration.screenHeightDp < 500
        if (short) ui.onNodeWithText("按顺序观看 / 阅读").performScrollTo()
        ui.onNodeWithText("按顺序观看 / 阅读").assertIsDisplayed()
        if (width >= 840) {
            clickText(video.title); waitText("打开播放器")
            ui.onNodeWithText("打开播放器").assertIsDisplayed()
            ui.runOnIdle { activity.recreate() }; waitText("打开播放器")
        }
        f.shot("next-responsive-collection-$tag")
        if (short) ui.onNodeWithText("筛选").performScrollTo()
        clickText("筛选"); waitText("片单筛选"); ui.onNodeWithText("显示作品").assertIsDisplayed()
        f.shot("next-responsive-filter-$tag")
    }
}
