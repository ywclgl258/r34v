package com.r34v.r34v

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.GalleryImage
import com.r34v.r34v.data.GalleryRepository
import com.r34v.r34v.data.ReaderFit
import com.r34v.r34v.data.ReaderMode
import com.r34v.r34v.data.ReaderNavigation
import com.r34v.r34v.ui.ComicReader
import com.r34v.r34v.ui.PagedComicReader
import com.r34v.r34v.ui.ReaderJump
import com.r34v.r34v.ui.R34Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused shared-gesture checks without a network or gallery details. */
@RunWith(AndroidJUnit4::class)
class ComposeReaderGestureTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun setReader(content: @androidx.compose.runtime.Composable () -> Unit) {
        ui.setContent { R34Theme(content = content) }
        ui.onRoot().assert(androidx.compose.ui.test.isRoot())
    }
    @Test fun stripGesturesSharePauseAndPreserveLastShortPagePosition() {
        var paused = 0
        var toggled = 0
        var position = 0 to 0
        var save: (() -> Unit)? = null
        val load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage = { page, _ ->
            kotlinx.coroutines.delay(if (page == 1) 150 else 0)
            rendered(page)
        }
        setReader {
            ComicReader(listOf(1, 2), ReaderJump(0, 1), load,
                toggle = { toggled++ }, pause = { paused++ },
                position = { page, _, offset -> position = page to offset },
                readyPages = {}, attachSave = { save = it })
        }
        ui.onNodeWithTag("reader-page-1").performClick()
        ui.waitUntil(3000) { ui.runOnIdle { toggled == 1 } }
        assertEquals(1, toggled)
        ui.onNodeWithTag("reader-page-1").performTouchInput {
            doubleClick()
            down(0, Offset(center.x - 60f, center.y))
            down(1, Offset(center.x + 60f, center.y))
            moveTo(0, Offset(center.x - 140f, center.y))
            moveTo(1, Offset(center.x + 140f, center.y))
            up(0)
            up(1)
        }
        ui.waitForIdle()
        assertTrue(toggled >= 1)
        assertTrue(paused >= 1)
        ui.onNodeWithTag("comic-strip").performTouchInput { swipeUp(bottom, top) }
        ui.waitUntil(10000) { ui.runOnIdle { position.first == 2 } }
        ui.runOnIdle { save!!.invoke() }
        assertEquals(2, position.first)
        assertTrue(position.second <= 0)
        ui.onNodeWithTag("reader-page-2").assertIsDisplayed()
    }

    @Test fun pagedGesturesAndProgrammaticJumpsDoNotOverwriteTheLatestPage() {
        var paused = 0
        var toggled = 0
        var jump by mutableStateOf(ReaderJump(0, 1))
        var position = 0
        var ready = emptyList<Int>()
        var save: (() -> Unit)? = null
        setReader {
            PagedComicReader(ReaderNavigation(listOf(1, 2, 3), 1, combineCover = true), jump, ReaderMode.LEFT_TO_RIGHT,
                load = { page, _ -> kotlinx.coroutines.delay(120); rendered(page) },
                toggle = { toggled++ }, pause = { paused++ },
                position = { page, _, _ -> position = page }, readyPages = { ready = it }, attachSave = { save = it },
                fit = ReaderFit.WIDTH, cropPercent = 0, currentPage = position.takeIf { it > 0 } ?: jump.page)
        }
        ui.onNodeWithTag("reader-page-1").performClick()
        ui.waitUntil(3000) { ui.runOnIdle { toggled == 1 } }
        assertEquals(1, toggled)
        ui.onNodeWithTag("reader-page-1").performTouchInput { doubleClick() }
        ui.waitForIdle()
        ui.onNodeWithTag("reader-page-1").performTouchInput { doubleClick() }
        ui.waitForIdle()
        ui.onNodeWithTag("paged-reader").performTouchInput { swipeLeft() }
        ui.waitUntil(10000) { ui.runOnIdle { position == 2 && ready == listOf(2) } }
        assertTrue(toggled >= 1)
        assertTrue(paused >= 1)
        ui.runOnIdle { jump = ReaderJump(1, 3) }
        ui.waitUntil(10000) { ui.runOnIdle { position == 3 && ready == listOf(3) } }
        ui.runOnIdle { save!!.invoke() }
        assertEquals(3, position)
        assertFalse(position == 1)
    }

    @Test fun stripReaderReusesSharedRatiosWhileARecreatedBufferIsLoading() {
        var generation by mutableStateOf(0)
        val ratios = mutableStateMapOf<Int, Float>()
        setReader {
            val current = generation
            key(current) {
                ComicReader(listOf(3), ReaderJump(0, 3),
                    load = { _, _ -> if (current == 0) rendered(3) else kotlinx.coroutines.awaitCancellation() },
                    toggle = {}, pause = {}, position = { _, _, _ -> }, readyPages = {}, attachSave = {}, ratios = ratios)
            }
        }
        ui.waitUntil(3000) { ui.runOnIdle { ratios[3] == 4f } }
        ui.runOnIdle { generation = 1 }
        ui.waitForIdle()
        val bounds = ui.onNodeWithTag("reader-page-3").fetchSemanticsNode().boundsInRoot
        assertEquals(4f, bounds.width / bounds.height, .1f)
    }

    private fun rendered(page: Int): GalleryRepository.RenderedPage {
        val bitmap = Bitmap.createBitmap(120, if (page == 3) 30 else 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(20, 50, 80))
        return GalleryRepository.RenderedPage(GalleryImage(page, "page-$page", "image-$page", null, null), bitmap, true)
    }
}
