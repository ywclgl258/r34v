package com.r34v.r34v

import com.google.gson.Gson
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun updateAssetSelectionSeparatesProductionAndCompatibilityPackages() {
        val names = listOf("app-debug.apk", "R34V-v2.0.1-compat-2.0.0.apk", "R34V-v2.0.1.apk")
        assertEquals("R34V-v2.0.1.apk", UpgradePackages.select(names, false))
        assertEquals("R34V-v2.0.1-compat-2.0.0.apk", UpgradePackages.select(names, true))
        assertNull(UpgradePackages.select(listOf("app-debug.apk"), false))
    }
    @Test fun routesAndSorts() {
        assertEquals("/tags/example/3/", FeedRequest(FeedKind.CHARACTER, "example", page = 3).path)
        assertEquals("/search/a%20b%2Fc/", FeedRequest(FeedKind.SEARCH, "a b/c").path)
        assertEquals("/for-you/", FeedRequest(FeedKind.FOR_YOU, page = 5).path)
        // URL building moved into the adapter, so the KVS path is asserted against its site base.
        assertEquals(SiteRegistry.R34V.base + "/latest-updates/", SiteRegistry.R34V.base + FeedRequest().path)
        assertTrue(FeedRequest(FeedKind.SEARCH, "example", Sort.NEWEST).id.contains("NEWEST"))
        assertNotEquals(
            FeedRequest(FeedKind.SEARCH, "example", Sort.NEWEST).id,
            FeedRequest(FeedKind.SEARCH, "example", Sort.RELEVANCE).id,
        )
    }
    @Test fun watchJsonRemainsCompatibleWithFlutter() {
        val old = """{"id":"42","title":"Example","thumb":"","path":"/video/42/example/","pos":300,"dur":600,"at":1234,"q":"720p"}"""
        val watch = Gson().fromJson(old, Watch::class.java)
        assertEquals(300L, watch.position); assertEquals(600L, watch.duration); assertEquals("720p", watch.quality); assertTrue(watch.resumable)
        assertEquals(watch, Gson().fromJson(Gson().toJson(watch), Watch::class.java))
    }
    @Test fun favoriteKindsKeepSeparateKeys() {
        val tag = Gson().fromJson("""{"k":"tag","t":"example","title":"Example","path":"/list/tag/example","at":42,"thumb":"","sub":""}""", Favorite::class.java)
        // The key is site-qualified so the same slug on two sites stays two distinct favourites.
        assertEquals("${SiteRegistry.R34V.id}:tag:example", tag.key)
        assertNotEquals(tag.key, tag.copy(kind = "model").key)
        assertNotEquals(tag.key, tag.copy(site = SiteRegistry.HANIME1.id).key)
    }
    @Test fun completionAndResumeBoundaries() {
        val watch = Watch("42", "Example", "", "/video/42/example/", 15, 600)
        assertTrue(watch.resumable); assertFalse(watch.copy(position = 14).resumable)
        assertTrue(watch.copy(position = 580).finished); assertFalse(watch.copy(position = 580).resumable)
        assertFalse(watch.copy(duration = 0).finished)
    }
    @Test fun durationFormatting() { assertEquals(3723L, durationSeconds("1:02:03")); assertEquals(0L, durationSeconds("bad")); assertEquals("1:02:03", timeText(3723000)) }
    @Test fun updateVersionsCompareNumerically() {
        assertTrue(UpdateController.newer("v2.10.0", "2.9.0")); assertFalse(UpdateController.newer("v1.18.0", "2.0.0")); assertFalse(UpdateController.newer("v2.0.0", "2.0.0"))
    }
    @Test fun cookiesAreNeverSentToCdnBySiteMatcher() { assertTrue(Site.isSite(Site.HOST)); assertFalse(Site.isSite("cdn.example")); assertFalse(Site.isSite("rule34video.com.example")) }
}
