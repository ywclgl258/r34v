package com.r34v.r34v

import com.r34v.r34v.data.VideoLoopRange
import com.r34v.r34v.data.ReadingBehavior
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VideoLoopRangeTest {
    @Test fun rangeRepeatsOnlyWhilePlayingAtBoundary() {
        val range = VideoLoopRange().start(2000).end(5000)
        assertFalse(range.shouldRepeat(4999, true))
        assertTrue(range.shouldRepeat(5000, true))
        assertFalse(range.shouldRepeat(5000, false))
        assertTrue(range.contains(2000)); assertFalse(range.contains(5000))
        assertTrue(range.validFor(5000)); assertFalse(range.validFor(4999))
        assertFalse(range.start(3000).active)
    }
    @Test fun endpointsRequireOneSecondAndAnEstablishedStart() {
        assertTrue(runCatching { VideoLoopRange().end(5000) }.isFailure)
        assertTrue(runCatching { VideoLoopRange().start(2000).end(2999) }.isFailure)
        assertTrue(VideoLoopRange().start(2000).end(3000).active)
    }
    @Test fun oldReadingPreferencesKeepSystemBrightness() {
        val old = Gson().fromJson("{\"volumeKeys\":false,\"autoPageSeconds\":5}", ReadingBehavior::class.java)
        assertEquals(-1, old.brightnessPercent)
        assertTrue(old.showPage); assertFalse(old.keepAwake)
        assertEquals(5, old.autoPageSeconds)
    }
}
