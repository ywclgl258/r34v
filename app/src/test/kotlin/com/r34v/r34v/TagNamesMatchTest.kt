package com.r34v.r34v

import com.r34v.r34v.data.TagNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNamesMatchTest {
    private val dictionary = mapOf(
        "touhou" to "东方Project",
        "touhou project" to "东方Project 系列",
        "hairband" to "发带",
        "anal" to "肛交",
        "sole-female" to "仅女性",
        "long hair" to "长发",
    )

    @Test fun blankOrNamespaceOnlyQueriesMatchNothing() {
        assertTrue(TagNames.match("", dictionary).isEmpty())
        assertTrue(TagNames.match("   ", dictionary).isEmpty())
        assertTrue(TagNames.match("female:", dictionary).isEmpty())
    }

    @Test fun prefixHitsRankBeforeSubstringHits() {
        val result = TagNames.match("tou", dictionary)
        assertEquals(listOf("touhou", "touhou project"), result.map { it.first })
    }

    @Test fun shorterSlugsRankFirstAmongSubstringHits() {
        val result = TagNames.match("hair", dictionary)
        // hairband 前缀命中在前；long hair 是子串命中，按长度排其后。
        assertEquals(listOf("hairband", "long hair"), result.map { it.first })
    }

    @Test fun translationAlsoMatchesAndKeepsTheSlug() {
        val result = TagNames.match("肛", dictionary)
        assertEquals(listOf("anal"), result.map { it.first })
        assertEquals("肛交", result[0].second)
    }

    @Test fun excludePrefixAndNamespaceAreStrippedFromTheNeedle() {
        // "-female:hair" → needle "hair"，命中 hairband / long hair。
        assertEquals(listOf("hairband", "long hair"), TagNames.match("-female:hair", dictionary).map { it.first })
    }

    @Test fun limitCapsTheResult() = assertEquals(2, TagNames.match("o", dictionary, limit = 2).size)
}
