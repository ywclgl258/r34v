package com.r34v.r34v

import com.r34v.r34v.data.Stream
import com.r34v.r34v.playback.StallPolicy
import com.r34v.r34v.playback.StreamSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起播选档、缓冲窗口与自动降档的判定。
 *
 * 这些规则以前根本不存在：档位只按标签精确匹配，缓冲窗口是全局常数，卡顿没有任何出口——所以
 * 一条 250 KB/s 的链路遇上 720p 就只会永远转圈。测试锁住的是“链路喂不动时会发生什么”。
 */
class StreamSelectionTest {
    private fun streams(vararg labels: String) = labels.map { Stream(it, "https://cdn.invalid/$it.mp4") }

    private val standard = streams("360p", "480p", "720p")

    @Test fun typicalBitratesMatchTheObservedDeliveryRates() {
        // 实测：720p 成片 13.19 MB / 42.99 s ≈ 2456 kbps；360p 约 4.5 MB，量级一致。
        assertEquals(2500, StreamSelection.requiredKbps(Stream("720p", "u")))
        assertEquals(800, StreamSelection.requiredKbps(Stream("360p", "u")))
        assertEquals(4500, StreamSelection.requiredKbps(Stream("1080p", "u")))
    }

    @Test fun labelsWithoutAHeightAreNotTreatedAsFree() {
        assertEquals(2500, StreamSelection.requiredKbps(Stream("原画", "u")))
        assertEquals(2500, StreamSelection.requiredKbps(Stream("自动", "u")))
    }

    @Test fun anUnknownLinkKeepsTheUserSetting() {
        // 带宽未知时必须和以前完全一样，避免诊断失败就悄悄改画质。
        assertEquals("720p", StreamSelection.pick(standard, "720p", 0).label)
        assertEquals("480p", StreamSelection.pick(standard, "480p", 0).label)
    }

    @Test fun aStrainedLinkDropsToTheHighestAffordableStepBelowThePreference() {
        // 3000 kbps 实测 → 预算 2100 kbps，720p 需要 2500，喂不动；480p 需要 1200，可用。
        assertEquals("480p", StreamSelection.pick(standard, "720p", 3000).label)
    }

    @Test fun aHealthyLinkKeepsThePreferredQuality() {
        assertEquals("720p", StreamSelection.pick(standard, "720p", 8000).label)
    }

    @Test fun aVerySlowLinkStillGetsTheLowestStepInsteadOfAnError() {
        assertEquals("360p", StreamSelection.pick(standard, "720p", 300).label)
    }

    @Test fun neverUpgradesAboveTheUserPreference() {
        // 用户设 480p 但链路很快：不许自动升到 720p，那是用户没要求的改动。
        assertEquals("480p", StreamSelection.pick(standard, "480p", 20000).label)
    }

    @Test fun anUnmatchedPreferenceDegradesLikeTheOldCodeDid() {
        assertEquals("480p", StreamSelection.preferred(standard, "540p")?.label)
        assertEquals("720p", StreamSelection.preferred(standard, "1080p")?.label)
        assertEquals("720p", StreamSelection.preferred(standard, "原始文件")?.label)
    }

    @Test fun lowerWalksDownOneStepAndStopsAtTheBottom() {
        val current = standard.first { it.label == "720p" }
        assertEquals("480p", StreamSelection.lower(standard, current)?.label)
        val bottom = standard.first { it.label == "360p" }
        assertNull(StreamSelection.lower(standard, bottom))
    }

    @Test fun aTightLinkShortensTheStartupBuffer() {
        val strained = StreamSelection.bufferPlan(2500, 3000)
        assertTrue("起播缓冲必须收紧", strained.minMs <= 2500)
        assertTrue("缓冲上限不能仍是 50 秒", strained.maxMs <= 12000)
        val healthy = StreamSelection.bufferPlan(2500, 20000)
        assertTrue("链路宽裕时不必压到最紧", healthy.minMs > strained.minMs)
    }

    @Test fun aLowBitrateStreamUsesTheSmallWindowRegardlessOfLink() {
        assertEquals(2500, StreamSelection.bufferPlan(800, 50000).minMs)
        assertEquals(8000, StreamSelection.bufferPlan(4500, 50000).minMs)
    }

    @Test fun recommendationsNameTheHighestStepTheLinkCanCarry() {
        assertEquals("480p", StreamSelection.recommendedLabel(3000))
        assertEquals("1080p", StreamSelection.recommendedLabel(9000))
        assertEquals("无法判定", StreamSelection.recommendedLabel(0))
    }

    @Test fun aStalledStartupDowngrades() {
        assertTrue(StallPolicy.shouldDowngrade(bufferingMs = 6_000, positionMs = 0, stallsInWindow = 1))
        assertFalse("刚起播就判卡会误伤正常首缓冲", StallPolicy.shouldDowngrade(3_000, 0, 1))
    }

    @Test fun aLongStallDuringPlaybackDowngrades() {
        assertTrue(StallPolicy.shouldDowngrade(8_000, 120_000, 1))
        assertFalse(StallPolicy.shouldDowngrade(4_000, 120_000, 1))
    }

    @Test fun repeatedShortStallsDowngradeEvenWhenEachIsBrief() {
        assertTrue(StallPolicy.shouldDowngrade(1_000, 120_000, StallPolicy.MAX_STALLS))
        assertFalse(StallPolicy.shouldDowngrade(1_000, 120_000, StallPolicy.MAX_STALLS - 1))
    }
}
