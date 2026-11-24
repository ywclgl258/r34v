package com.r34v.r34v

import com.r34v.r34v.data.*
import com.google.gson.Gson
import com.r34v.r34v.data.adapter.GalleryEpicProfile
import com.r34v.r34v.data.adapter.SiteAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SavedSearchUpdatesTest {
    private fun page(vararg ids: String, site: String = "r34v", cached: Boolean = false) = FeedPage(ids.map { Video(it, site = site) }, 1, 1, "", cached)
    @Test fun firstLivePageIsBaselineAndLaterResultsAreUnread() {
        val first = SavedSearchInbox("key").refreshed("r34v", page("a", "b"), now = 10)
        assertTrue(first.entries.all { it.read })
        val next = first.refreshed("r34v", page("c", "a"), now = 20)
        assertEquals(listOf("c"), next.entries.filterNot { it.read }.map { it.video.id })
        assertEquals(3, next.known.getValue("r34v").size)
        assertEquals(next.entries, next.refreshed("r34v", page("c", "a"), now = 30).entries)
    }
    @Test fun cachedAndFailedResultsNeverAdvanceBaselineOrLoseUnread() {
        val first = SavedSearchInbox("key").refreshed("r34v", page("cached", cached = true), now = 10)
        assertTrue(first.known.isEmpty()); assertTrue(first.entries.isEmpty()); assertEquals(0, first.checks.getValue("r34v").successAt)
        val live = first.refreshed("r34v", page("a"), now = 20).refreshed("r34v", page("b"), now = 30)
        val failure = live.refreshed("r34v", null, "failure", now = 40).refreshed("r34v", page("c", cached = true), now = 50)
        assertEquals(live.entries, failure.entries); assertEquals(live.known, failure.known)
        assertEquals(30, failure.checks.getValue("r34v").successAt)
    }
    @Test fun emptyFirstResponseStillEstablishesBaselineAndSourcesStaySeparate() {
        val box = SavedSearchInbox("key").refreshed("r34v", page(), now = 1).refreshed("r34v", page("same"), now = 2)
            .refreshed("nhentai", page("same", site = "nhentai"), now = 3)
        assertEquals(2, box.entries.size)
        assertFalse(box.entries.first { it.video.site == "r34v" }.read)
        assertTrue(box.entries.first { it.video.site == "nhentai" }.read)
    }
    @Test fun renameAndSourceOrderingKeepDefinitionButChangedConditionsResetIt() {
        val search = SavedSearch(name = "one", query = "sample", sources = listOf("r34v", "nhentai"))
        assertEquals(search.conditionKey, search.copy(name = "two", sources = search.sources.reversed()).conditionKey)
        assertNotEquals(search.conditionKey, search.copy(query = "new").conditionKey)
        assertNotEquals(search.conditionKey, search.copy(languages = mapOf("nhentai" to "CHINESE")).conditionKey)
    }
    @Test fun backupMergePreservesReadFlagsAndRejectsWrongSearchBaseline() {
        val search = SavedSearch(name = "saved", query = "q", sources = listOf("r34v"))
        val state = SavedSearchInbox(search.conditionKey).refreshed("r34v", page(), now = 1).refreshed("r34v", page("new"), now = 2)
        val backup = LibraryBackup(personal = PersonalLibrary(savedSearches = listOf(search), searchUpdates = mapOf(search.id to state)))
        validateBackup(backup)
        val read = backup.copy(personal = backup.personal.copy(searchUpdates = mapOf(search.id to state.copy(entries = state.entries.map { it.copy(read = true) })) ))
        val merged = mergeBackups(backup, read)
        assertTrue(merged.personal.searchUpdates.getValue(search.id).entries.single().read)
        val encoded = BackupCodec.encode(merged, "search-backup".toCharArray())
        assertEquals(merged, BackupCodec.decode(encoded, "search-backup".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) { validateBackup(backup.copy(personal = backup.personal.copy(savedSearches = listOf(search.copy(query = "changed")))) ) }
    }
    @Test fun directorySourceDoesNotAdvanceABaselineOrRetainOldWorkResults() {
        val source = SiteRegistry.GALLERYEPIC.id
        val old = SavedSearchInbox("key").refreshed(source, page("coser", site = source), now = 10)
            .refreshed("r34v", page("work"), now = 11)
        val updated = old.refreshed(source, null, now = 20, directory = true)
        assertEquals(SearchCheck(checkedAt = 20, directory = true), updated.checks[source])
        assertEquals(listOf("r34v:work"), updated.entries.map { it.video.entryKey })
        assertFalse(source in updated.known)
        assertEquals(updated, updated.merge(old))
    }

    @Test fun savedScopeChangesDefinitionAndOldDataRetainsItsConditionKey() {
        val legacyDefinition = """{"id":"","name":"","query":"cat","sources":["r34v"],"category":"","languages":{},"includeTags":[],"excludeTags":[],"filter":{"year":0,"month":0,"minDurationSec":0,"maxDurationSec":0,"brand":"","category":"","minRating":0,"minPages":0,"maxPages":0}}"""
        val old = Gson().fromJson(legacyDefinition, SavedSearch::class.java).restored()
        val expected = MessageDigest.getInstance("SHA-256").digest(legacyDefinition.toByteArray()).joinToString("") { "%02x".format(it) }
        assertNull(old.singleSite)
        assertEquals(listOf("r34v"), old.matchingSources().map { it.id })
        assertEquals(expected, old.conditionKey)
        val tagged = old.copy(singleSiteScope = FeedKind.TAG)
        assertEquals("r34v", tagged.singleSite?.id)
        assertNotEquals(old.conditionKey, tagged.conditionKey)
        assertNotEquals(tagged.conditionKey, tagged.copy(singleSiteScope = FeedKind.MODEL).conditionKey)
    }

    @Test fun galleryEpicSavedSearchStaysInItsCoserDirectory() {
        val owner = SiteRegistry.GALLERYEPIC
        val profile = GalleryEpicProfile()
        val adapter = object : SiteAdapter {
            override val site = owner
            override val capabilities = emptySet<com.r34v.r34v.data.adapter.Capability>()
            override val sections = emptyList<com.r34v.r34v.data.adapter.Section>()
            override val sorts = emptyList<Sort>()
            override fun resolve(request: FeedRequest) = profile.resolve(owner, request)
            override fun homeUrl() = "${owner.base}/"
            override suspend fun feed(request: FeedRequest) = error("Not needed")
            override suspend fun detail(url: String) = error("Not needed")
            override suspend fun index(section: String) = emptyList<Link>()
            override suspend fun login(network: Network, username: String, password: String) = Unit
        }
        val search = SavedSearch(name = "coser", query = "sample", sources = listOf(owner.id))
        val request = search.feedRequest(adapter)
        assertEquals("cosers:1:sample", profile.indexSection(request))
        assertEquals("sample", request.target)
        val url = profile.indexUrl(owner, requireNotNull(profile.indexSection(request)))!!.toHttpUrl()
        assertEquals("sample", url.queryParameter("coserName"))
        assertEquals("/en/cosers/1", url.encodedPath)
        val legacy = Gson().fromJson("""{"name":"old","query":"sample","sources":["galleryepic"]}""", SavedSearch::class.java).restored()
        assertEquals("cosers:1:sample", profile.indexSection(legacy.feedRequest(adapter)))
    }

    @Test fun backupsFromBeforeUpdatesHaveAnEmptyInboxMap() {
        assertTrue(Gson().fromJson("{\"savedSearches\":[]}", PersonalLibrary::class.java).searchUpdates.isEmpty())
    }
}
