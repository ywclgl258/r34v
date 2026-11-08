package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfOrganizationUiTest : ComposeUiCase() {
    private fun seed(f: ComposeFixture, shelves: List<Shelf>) {
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary(shelves = shelves))).commit()
        f.app.library.switchSite("r34v")
    }
    @Test fun shelfSearchAndSortRestoreWithoutChangingManualOrder(): Unit = ComposeFixture(awaitUi = { ui.waitForIdle() }).use { f ->
        val first = f.video().copy(title = "Alpha")
        val second = f.video(index = 1).copy(title = "Zulu")
        val comic = f.video("ehentai").copy(title = "Space comic")
        seed(f, listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(first, at = 1), ShelfEntry(second, at = 3), ShelfEntry(comic, at = 2)))))
        val activity = f.launch(); waitHome(); clickText("片库"); openWatchLater()
        ui.onNodeWithContentDescription("搜索片单").performClick()
        ui.onNodeWithText("片单内标题或来源").performTextReplacement("space EHENTAI")
        ui.onNodeWithText("片单内标题或来源").performImeAction()
        closeKeyboard(f); ui.onNodeWithText(comic.title).assertIsDisplayed(); ui.onAllNodesWithText(first.title).assertCountEquals(0)
        ui.runOnIdle { activity.recreate() }; waitText("片单内标题或来源")
        ui.onNodeWithText("space EHENTAI").assertExists(); closeKeyboard(f); ui.onNodeWithText(comic.title).assertIsDisplayed()
        ui.onNodeWithContentDescription("清空片单搜索").performClick()
        closeKeyboard(f); clickText("筛选"); waitText("片单筛选"); f.shot("shelf-filters")
        ui.onNodeWithText("手动顺序").performScrollTo().performClick(); clickText("最近加入"); clickText("显示作品")
        ui.onNodeWithText(second.title).assertIsDisplayed()
        assertTrue(ui.onNodeWithText(second.title).fetchSemanticsNode().boundsInRoot.top < ui.onNodeWithText(comic.title).fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithContentDescription("管理 ${second.title}").performClick(); ui.onAllNodesWithText("上移").assertCountEquals(0); back(f)
        assertEquals(listOf(first.entryKey, second.entryKey, comic.entryKey), f.app.library.personal.shelves().first().entries.map { it.video.entryKey })
        ui.onNodeWithText("片单内标题或来源").performTextReplacement("not-found"); closeKeyboard(f)
        waitText("没有匹配的作品"); clickText("清空条件"); ui.onNodeWithText(second.title).assertIsDisplayed()
        f.shot("shelf-search-sort")
    }
    @Test fun metadataCoverAndPinPersistAndCanBeCancelled(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        val custom = Shelf("weekend", "周末片单", (0..2).map { ShelfEntry(f.video(index = it)) })
        seed(f, PersonalLibrary().shelves + custom)
        val activity = f.launch(); waitHome(); clickText("片库")
        ui.onNodeWithTag("library-hub").performScrollToNode(hasText(custom.name)); clickText(custom.name); clickText("合集设置")
        ui.onNodeWithText("合集备注").performTextReplacement("留给周末的作品"); closeKeyboard(f)
        ui.onNodeWithText("置顶到片库").performScrollTo().performClick()
        ui.onNodeWithText("选择封面").performScrollTo().performClick(); f.shot("shelf-cover-picker"); clickText(f.video(index = 1).title)
        clickText("保存合集")
        val saved = f.app.library.personal.shelves().last()
        assertEquals("留给周末的作品", saved.note); assertTrue(saved.pinned); assertEquals(f.video(index = 1).entryKey, saved.coverKey)
        ui.runOnIdle { activity.recreate() }; waitText("合集设置"); clickText("合集设置")
        ui.onNodeWithText("留给周末的作品").assertExists()
        f.shot("shelf-settings")
        ui.onNodeWithText("合集备注").performTextReplacement("不保存的草稿"); closeKeyboard(f); clickText("取消")
        assertEquals(saved, f.app.library.personal.shelves().last())
        ui.onNodeWithContentDescription("返回我的片库").performClick()
        val list = ui.onNodeWithTag("library-hub")
        list.performScrollToNode(hasText("留给周末的作品")); waitText("已置顶")
        ui.onNodeWithText("留给周末的作品").assertIsDisplayed()
        list.performScrollToNode(hasText(custom.name))
        val pinnedBounds = ui.onNodeWithText(custom.name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pinnedScroll = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        // LazyColumn need not compose the next shelf on a short screen. Compare scroll position
        // as well as visible bounds, so the same ordering assertion works across viewports.
        list.performScrollToNode(hasText("稍后看"))
        val laterBounds = ui.onNodeWithText("稍后看").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val laterScroll = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Pinned shelf must precede Watch Later", laterScroll > pinnedScroll ||
            laterScroll == pinnedScroll && (laterBounds.top > pinnedBounds.top ||
                laterBounds.top == pinnedBounds.top && laterBounds.left > pinnedBounds.left))
        list.performScrollToNode(hasText(custom.name))
        f.shot("shelf-pinned-cover-note")
        validateBackup(Gson().fromJson(Gson().toJson(LibraryBackup(personal = f.app.library.personal.snapshot())), LibraryBackup::class.java))
    }
    @Test fun removingChosenCoverFallsBackWithoutLosingOtherMetadata(): Unit = ComposeFixture(awaitUi = { ui.waitForIdle() }).use { f ->
        val first = f.video(); val second = f.video(index = 1)
        seed(f, listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(first), ShelfEntry(second)), "保留备注", true, second.entryKey, 1)))
        f.launch(); waitHome(); clickText("片库"); openWatchLater()
        ui.onNodeWithTag("catalog-list").performScrollToNode(hasText(second.title))
        ui.onNodeWithContentDescription("管理 ${second.title}").performClick(); clickText("移出当前片单")
        ui.waitUntil(10000) { f.app.library.personal.shelves().first().entries.size == 1 }
        val shelf = f.app.library.personal.shelves().first()
        assertEquals("", shelf.coverKey); assertEquals(listOf(first), shelf.coverVideos()); assertTrue(shelf.pinned); assertEquals("保留备注", shelf.note)
        clickText("合集设置"); waitText("当前封面：自动使用合集作品"); f.shot("shelf-cover-fallback"); clickText("取消")
    }
}
