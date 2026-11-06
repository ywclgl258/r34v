package com.r34v.r34v

import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

class NewFeatureDataTest {
    private fun video(id: String, site: String = "r34v") = Video(id, "标题 $id", "${SiteRegistry.byIdOrDefault(site).base}/videos/$id/", site = site)
    private fun feed(tag: String, vararg ids: String) = TagFeed(Subscription(tag, site = "r34v"), ids.map { video(it) })

    @Test fun firstSuccessfulRefreshIsABaseline() {
        val box = SubscriptionInbox().refreshed(listOf(feed("a", "1", "2")))
        assertEquals(listOf("1", "2"), box.known["a"])
        assertTrue(box.entries.all { it.read })
    }
    @Test fun onlyNewlyObservedIdsBecomeUnread() {
        val first = SubscriptionInbox().refreshed(listOf(feed("a", "old")))
        val next = first.refreshed(listOf(feed("a", "new", "old")))
        assertEquals(listOf("new"), next.entries.filter { !it.read }.map { it.video.id })
        assertEquals(1, next.refreshed(listOf(feed("a", "new", "old"))).entries.count { !it.read })
    }
    @Test fun failedAndCachedTagsDoNotAdvanceTheBaseline() {
        val first = SubscriptionInbox().refreshed(listOf(feed("a", "old")))
        assertEquals(first, first.refreshed(listOf(feed("a", "new").copy(cached = true), feed("b", "x").copy(error = "offline"))))
    }
    @Test fun addingATagDoesNotFloodTheInbox() {
        val first = SubscriptionInbox().refreshed(listOf(feed("a", "1")))
        assertEquals(0, first.refreshed(listOf(feed("a", "1"), feed("b", "2", "3"))).entries.count { !it.read })
    }
    @Test fun newWorkUnderAnExistingTagIsUnreadEvenWhenANewTagAlsoContainsIt() {
        val first = SubscriptionInbox().refreshed(listOf(feed("a", "old")))
        val next = first.refreshed(listOf(feed("b", "new"), feed("a", "new", "old")))
        assertFalse(next.entries.first { it.video.id == "new" }.read)
        assertEquals(1, next.entries.count { it.video.id == "new" })
    }
    @Test fun refreshingDoesNotMakeReadWorksUnreadAgain() {
        val first = SubscriptionInbox().refreshed(listOf(feed("a", "old"))).refreshed(listOf(feed("a", "new")))
        val read = first.copy(entries = first.entries.map { it.copy(read = true) })
        assertTrue(read.refreshed(listOf(feed("a", "new", "old"))).entries.all { it.read })
    }
    @Test fun inboxHistoryAndSeenIdsHaveABoundedSize() {
        val first = SubscriptionInbox().refreshed(listOf(feed("a", *(1..1100).map { "$it" }.toTypedArray())))
        assertEquals(1000, first.entries.size); assertEquals(1000, first.known.getValue("a").size)
    }
    @Test fun sameWorkIdFromTwoSitesSurvivesShelfMerge() {
        val a = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(video("1"))))))
        val b = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(video("1", "hanime1"))))))
        val merged = mergeBackups(LibraryBackup(personal = a), LibraryBackup(personal = b))
        assertEquals(2, merged.personal.shelves.single().entries.size)
    }
    @Test fun newerCompletionStateWinsWithoutChangingExistingShelfOrder() {
        val a = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(video("1"), at = 1), ShelfEntry(video("2"), at = 2)))))
        val b = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(video("1"), true, 10), ShelfEntry(video("3"), at = 11)))))
        val entries = mergeBackups(LibraryBackup(personal = a), LibraryBackup(personal = b)).personal.shelves.single().entries
        assertEquals(listOf("1", "2", "3"), entries.map { it.video.id }); assertTrue(entries.first().completed)
    }
    @Test fun latestTimestampWinsForProgressInsteadOfFurthestPosition() {
        val local = LibraryBackup(sites = listOf(SiteBackup("r34v", watches = listOf(Watch("1", position = 100, at = 1, site = "r34v")))))
        val remote = LibraryBackup(sites = listOf(SiteBackup("r34v", watches = listOf(Watch("1", position = 10, at = 2, site = "r34v")))))
        assertEquals(10L, mergeBackups(local, remote).sites.single().watches.single().position)
    }
    @Test fun mergePreservesOtherSitesAndLocalSubscriptionOrder() {
        val local = LibraryBackup(sites = listOf(SiteBackup("r34v", subscriptions = listOf(Subscription("a", site = "r34v"))), SiteBackup("hanime1")))
        val remote = LibraryBackup(sites = listOf(SiteBackup("r34v", subscriptions = listOf(Subscription("b", site = "r34v"), Subscription("a", site = "r34v")))))
        val merged = mergeBackups(local, remote)
        assertEquals(2, merged.sites.size); assertEquals(listOf("a", "b"), merged.sites.first().subscriptions.map { it.slug })
    }
    @Test fun restoreCannotMakeAnAlreadyReadInboxEntryUnread() {
        val v = video("1")
        val a = LibraryBackup(personal = PersonalLibrary(inboxes = mapOf("r34v" to SubscriptionInbox(entries = listOf(InboxEntry(v, true, 1))))))
        val b = LibraryBackup(personal = PersonalLibrary(inboxes = mapOf("r34v" to SubscriptionInbox(entries = listOf(InboxEntry(v, false, 2))))))
        assertTrue(mergeBackups(a, b).personal.inboxes.getValue("r34v").entries.single().read)
    }
}
