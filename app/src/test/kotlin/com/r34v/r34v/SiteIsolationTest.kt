package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.HanimeAdapter
import com.r34v.r34v.playback.PlaybackStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the boundaries that keep two catalogues from mixing. These are read-only checks of
 * key derivation and record routing; the persistence side is covered on device.
 */
class SiteIsolationTest {

    @Test fun cacheKeyCarriesTheSiteSoIdenticalIdsCannotCollide() {
        val r34 = PlaybackStore.key("r34v", "408990", "720p")
        val han = PlaybackStore.key("hanime1", "408990", "720p")
        assertEquals("r34v:408990:720p", r34)
        assertEquals("hanime1:408990:720p", han)
        assertNotEquals(r34, han)
    }

    @Test fun switchingSitesResolvesUrlsAgainstTheOwningSite() {
        val watch = Watch("408990", "Example", "", "/watch?v=408990", 30, 600, site = "hanime1")
        val video = watch.video()
        // The record remembers its own site, not whatever is active right now.
        assertEquals("https://hanime1.me/watch?v=408990", video.url)
        assertEquals("hanime1", video.site)
    }

    @Test fun absoluteUrlsAreNeverRebasedOntoAnotherSite() {
        val foreign = Watch("42", "Example", "", "https://hanime1.me/watch?v=42", 30, 600, site = "hanime1")
        assertEquals("https://hanime1.me/watch?v=42", foreign.video().url)
    }

    @Test fun favoritesKeepSiteInTheirIdentity() {
        val a = Favorite("video", "408990", "A", "/watch?v=408990", site = "r34v")
        val b = Favorite("video", "408990", "A", "/watch?v=408990", site = "hanime1")
        assertNotEquals(a.key, b.key)
    }

    @Test fun siteLookupMatchesOnlyRegisteredHosts() {
        assertTrue(SiteRegistry.isRegistered("rule34video.com"))
        assertTrue(SiteRegistry.isRegistered("hanime1.me"))
        assertFalse(SiteRegistry.isRegistered("vdownload.hembed.com"))
        assertFalse(SiteRegistry.isRegistered("api.github.com"))
    }

    @Test fun hanimeRoutesUseQueryParametersNotKvsPaths() {
        val site = SiteRegistry.HANIME1
        val search = HanimeAdapter.resolve(site, FeedRequest(FeedKind.SEARCH, "ネル"))
        assertTrue(search, search.startsWith("https://hanime1.me/search?"))
        assertTrue(search, search.contains("query="))
        assertFalse(search, search.contains("/search/ネル/"))

        val category = HanimeAdapter.resolve(site, FeedRequest(FeedKind.CATEGORY, "裏番"))
        assertTrue(category, category.contains("genre="))

        val paged = HanimeAdapter.resolve(site, FeedRequest(FeedKind.SEARCH, "a", page = 3))
        assertTrue(paged, paged.contains("page=3"))
    }

    @Test fun kvsRoutesKeepTheirPathForm() {
        val request = FeedRequest(FeedKind.TAG, "example", page = 3)
        assertEquals("/tags/example/3/", request.path)
        assertEquals("https://rule34video.com/tags/example/3/", SiteRegistry.R34V.base + request.path)
    }

    @Test fun legacyRecordsWithoutSiteDefaultToTheFirstSite() {
        val parsed = com.google.gson.Gson().fromJson(
            """{"id":"42","title":"Old","thumb":"","path":"/video/42/example/","pos":300,"dur":600,"at":1234,"q":"720p"}""",
            Watch::class.java,
        )
        assertEquals(SiteRegistry.R34V.id, parsed.site)
    }
}
