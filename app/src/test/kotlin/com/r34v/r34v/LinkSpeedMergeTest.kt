package com.r34v.r34v

import com.r34v.r34v.playback.StreamSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 链路估值的来源优先级。
 *
 * 这里锁住的是一个容易踩的坑：Media3 的 `bitrateEstimate` 在拿到样本前会返回 1 Mbps 的
 * 出厂默认值。如果把它当实测，每一次首播都会被误判成 1 Mbps 的烂网、按最低档起播。
 */
class LinkSpeedMergeTest {
    @Test fun aPlaybackSampleBeatsAnEarlierProbe() {
        assertEquals(5_000, StreamSelection.mergeEstimates(sampledKbps = 5_000, seededKbps = 2_000))
    }

    @Test fun aProbeAloneIsUsable() {
        assertEquals(2_000, StreamSelection.mergeEstimates(sampledKbps = 0, seededKbps = 2_000))
    }

    @Test fun noEvidenceAtAllStaysUnknown() {
        assertEquals(0, StreamSelection.mergeEstimates(sampledKbps = 0, seededKbps = 0))
        assertEquals(0, StreamSelection.mergeEstimates(sampledKbps = -1, seededKbps = -1))
    }
}
