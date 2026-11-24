package com.r34v.r34v

import com.r34v.r34v.data.ReaderNavigation
import com.r34v.r34v.data.readerSheets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSupportTest {
    @Test fun sparseSpreadNavigationSkipsGapsLikeScreenButtonsAndVolumeKeys() {
        val separate = ReaderNavigation(listOf(3, 7, 9, 11), 2, combineCover = false)
        assertEquals(listOf(listOf(3, 7), listOf(9, 11)), separate.sheets)
        assertNull("A visible spread is not navigated by an individual leaf", separate.adjacent(7, forward = false))
        assertEquals(9, separate.adjacent(3, forward = true))
        assertEquals(9, separate.adjacent(7, forward = true))
        assertEquals(11, separate.boundary(forward = true))
        assertNull(separate.adjacent(11, forward = true))

        val combined = ReaderNavigation(listOf(3, 7, 9, 11), 2, combineCover = true)
        assertEquals(listOf(listOf(3, 7), listOf(9, 11)), combined.sheets)
        assertEquals(9, combined.adjacent(7, forward = true))
    }

    @Test fun coverRuleAppliesOnlyToBooksBeginningWithTheirCover() {
        val wholeBook = ReaderNavigation((1..7).toList(), 2, combineCover = false)
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5), listOf(6, 7)), wholeBook.sheets)
        assertEquals(1, wholeBook.adjacent(2, forward = false))
        assertEquals(1, wholeBook.sheetIndex(3))
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6), listOf(7)),
            ReaderNavigation((1..7).toList(), 2, combineCover = true).sheets)
        assertEquals((1..7).map(::listOf), readerSheets(7, 1, combineCover = false))
    }

    @Test fun bothReadingDirectionsUseTheSameAdjacentPage() {
        for (pages in listOf((1..8).toList(), listOf(3, 7, 9, 11))) {
            val navigation = ReaderNavigation(pages, 1, combineCover = false)
            for (page in pages) {
                val index = pages.indexOf(page)
                assertEquals(pages.getOrNull(index - 1), navigation.adjacent(page, forward = false))
                assertEquals(pages.getOrNull(index + 1), navigation.adjacent(page, forward = true))
            }
        }
    }
}
