package com.r34v.r34v

import com.r34v.r34v.data.*
import com.google.gson.Gson
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
    @Test fun backupsFromBeforeUpdatesHaveAnEmptyInboxMap() {
        assertTrue(Gson().fromJson("{\"savedSearches\":[]}", PersonalLibrary::class.java).searchUpdates.isEmpty())
    }
}
