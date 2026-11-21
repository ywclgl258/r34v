package com.r34v.r34v

import com.r34v.r34v.data.GalleryResponseException
import com.r34v.r34v.data.RateLimitLog
import com.r34v.r34v.data.SiteHtml
import com.r34v.r34v.data.withRateLimitRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RateLimitObservabilityTest {
    @Before fun resetLog() = RateLimitLog.clear()

    @Test fun reportIsEmptyWithoutEvents() = assertEquals("", RateLimitLog.report())

    @Test fun eventsKeepNewestLastAndBoundTheSize() {
        repeat(15) { RateLimitLog.record("site$it", "HTTP 429") }
        val report = RateLimitLog.report()
        assertTrue(report.startsWith("近期限流事件"))
        val lines = report.split("\n").drop(1)
        assertEquals(12, lines.size)
        assertTrue(lines.first().contains("site3"))
        assertTrue(lines.last().contains("site14"))
    }

    @Test fun retryPassesThroughWhenNoRateLimit() = runBlocking {
        var calls = 0
        assertEquals("ok", withRateLimitRetry { calls++; "ok" })
        assertEquals(1, calls)
    }

    @Test fun retryRecoversOnceAfterBackoffOn429() = runBlocking {
        SiteHtml.rateLimitBackoffMs = 0
        try {
            var calls = 0
            assertEquals("ok", withRateLimitRetry { calls++; if (calls == 1) throw GalleryResponseException(429, message = "limited") else "ok" })
            assertEquals(2, calls)
        } finally { SiteHtml.rateLimitBackoffMs = 1_000L }
    }

    @Test fun retryAfterHeaderWinsOnTheApiPath() = runBlocking {
        var calls = 0
        // Retry-After: 0 表示站点允许立即重试：退避 0ms，第二次成功。
        assertEquals("ok", withRateLimitRetry { calls++; if (calls == 1) throw GalleryResponseException(429, retryAfter = "0", message = "limited") else "ok" })
        assertEquals(2, calls)
    }

    @Test fun secondRateLimitResponsePropagatesAndLogs() = runBlocking {
        SiteHtml.rateLimitBackoffMs = 0
        try {
            var calls = 0
            val thrown = runCatching {
                withRateLimitRetry { calls++; throw GalleryResponseException(429, message = "limited") }
            }.exceptionOrNull()
            assertTrue(thrown is GalleryResponseException)
            assertEquals(2, calls)
            assertTrue(RateLimitLog.report().contains("重试后仍被拒"))
        } finally { SiteHtml.rateLimitBackoffMs = 1_000L }
    }

    @Test fun otherStatusesAreNotRetried() = runBlocking {
        var calls = 0
        val thrown = runCatching {
            withRateLimitRetry { calls++; throw GalleryResponseException(404, message = "missing") }
        }.exceptionOrNull()
        assertTrue(thrown is GalleryResponseException)
        assertEquals(1, calls)
    }

    @Test fun cancellationIsNeverSwallowedByTheRetry() = runBlocking {
        val cancelled = CancellationException("cancelled")
        val thrown = runCatching { withRateLimitRetry { throw cancelled } }.exceptionOrNull()
        assertEquals(cancelled, thrown)
    }
}
