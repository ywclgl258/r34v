package com.r34v.r34v

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.ui.R34Theme
import com.r34v.r34v.ui.TimestampText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimestampTextUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun onlyTimestampsWithinTheVideoSeekWhenTapped() {
        val sample = "简介 00:05；超出时长 00:20"
        val positions = mutableListOf<Long>()
        ui.setContent { R34Theme { TimestampText(sample, 10_000, positions::add) } }
        val text = ui.onNodeWithText(sample)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        fun tap(fragment: String) {
            val bounds = layouts.single().getBoundingBox(sample.indexOf(fragment) + 1)
            text.performTouchInput { click(bounds.center) }
            ui.waitForIdle()
        }
        tap("00:05")
        ui.runOnIdle { assertEquals(listOf(5_000L), positions) }
        tap("00:20")
        tap("简介")
        ui.runOnIdle { assertEquals(listOf(5_000L), positions) }
    }
}
