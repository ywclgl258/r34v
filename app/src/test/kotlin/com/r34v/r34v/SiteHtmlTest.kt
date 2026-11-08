package com.r34v.r34v

import com.r34v.r34v.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SiteHtmlTest {
    @Test fun failedPrimaryUsesTheMirrorAndPreservesPathAndQuery() = runBlocking {
        for (status in listOf(403, 429, 503)) {
            val calls = mutableListOf<String>()
            val result = SiteHtml.load("https://missav.ws/search/sample?page=2", SiteRegistry.MISSAV) {
                calls += it
                if (calls.size == 1) throw GalleryResponseException(status, message = "blocked")
                "mirror"
            }
            assertEquals("mirror", result)
            assertEquals(listOf("https://missav.ws/search/sample?page=2", "https://missav.media/search/sample?page=2"), calls)
        }
    }

    @Test fun challengeAndConnectionFailureAlsoRecover() = runBlocking {
        for (connectionError in listOf(false, true)) {
            var calls = 0
            assertEquals("content", SiteHtml.load(SiteRegistry.MISSAV.base, SiteRegistry.MISSAV) {
                if (++calls == 1) {
                    if (connectionError) throw IOException("connection closed")
                    "<title>Just a moment</title>"
                } else "content"
            })
            assertEquals(2, calls)
        }
    }

    @Test fun cancellationAndMissingPagesNeverRetry() = runBlocking {
        for (error in listOf(CancellationException("cancelled"), GalleryResponseException(404, message = "missing"))) {
            var calls = 0
            val thrown = runCatching { SiteHtml.load(SiteRegistry.MISSAV.base, SiteRegistry.MISSAV) { calls++; throw error } }.exceptionOrNull()
            assertSame(error, thrown)
            assertEquals(1, calls)
        }
    }

    @Test fun foreignHostsCannotBeRewrittenAsASiteMirror() = runBlocking {
        var calls = 0
        runCatching { SiteHtml.load("https://example.invalid/path", SiteRegistry.MISSAV) { calls++; throw IOException("offline") } }
        assertEquals(1, calls)
    }
}
