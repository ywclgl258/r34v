package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IwaraUiTest : ComposeUiCase() {
    @Test fun thirdSiteSearchScopeAndStateRestoration() = ComposeFixture().use { f ->
        f.launch(); waitHome(); switchSite(SiteRegistry.IWARA.name); clickText("搜索")
        ui.onNode(hasSetTextAction()).performTextInput("creator sample")
        closeKeyboard(f)
        ui.onNodeWithText("作者").assertIsDisplayed()
        clickText("作者")
        try { ui.waitUntil(5000) { ui.onNodeWithText("作者").fetchSemanticsNode().config[SemanticsProperties.Selected] } }
        catch (e: Throwable) { f.shot("api24-iwara-choice"); throw e }
        ui.onNodeWithContentDescription("提交搜索").performClick()
        try { ui.waitUntil(15000) { f.requests.any { it.first == "iwara" && it.second.kind == FeedKind.MODEL && it.second.target == "creator-sample" } } }
        catch (e: Throwable) { println("Iwara search requests: ${f.requests.toList()}"); f.shot("api24-iwara-search-timeout"); throw e }
        back(f); waitText("最近搜索"); ui.onNode(hasSetTextAction()).assertTextContains("creator sample")
        assertEquals("creator sample", f.app.library.searches().first())
        switchSite(SiteRegistry.R34V.name); clickText("搜索"); waitText("搜索过的关键词会保存在这里")
        assertTrue(f.app.library.searches().isEmpty())
    }
}
