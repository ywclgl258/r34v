package com.r34v.r34v

import com.google.gson.Gson
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

class NextFeatureDataTest {
    private fun video(id: String = "1", site: String = "r34v") = Video(id, "测试 $id", "${SiteRegistry.byIdOrDefault(site).base}/videos/$id/", site = site)
    @Test fun sharesFindARegisteredUrlInsideBrowserTextAndUpgradeHttp() {
        val link = incomingLink("标题\nhttp://rule34video.com/videos/123/demo/#position")
        assertEquals("r34v", link.site.id); assertEquals("https://rule34video.com/videos/123/demo/", link.url)
    }
    @Test fun unsupportedAndCredentialLinksAreRejected() {
        listOf("https://rule34video.com.attacker.invalid/videos/1/", "file:///private/file", "https://user:pass@rule34video.com/videos/1/").forEach { raw -> assertThrows(Exception::class.java) { incomingLink(raw) } }
    }
    @Test fun localIdentityIsNotANetworkSource() {
        assertEquals(SiteRegistry.LOCAL, SiteRegistry.byId("local")); assertFalse(SiteRegistry.all.any { it.id == "local" }); assertNull(SiteRegistry.forHost("local"))
    }
    @Test fun naturalPageOrderingDoesNotUseLexicographicNumbersOrOverflow() {
        val input = listOf("10.jpg", "2.jpg", "1.jpg", "100000000000000000000000.jpg")
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "100000000000000000000000.jpg"), input.sortedWith(LocalLibrary.naturalOrder))
    }
    @Test fun oldPersonalJsonReceivesEmptySearchAndSmartDefaults() {
        val data = Gson().fromJson("{\"shelves\":[{\"id\":\"watch-later\",\"name\":\"稍后看\",\"entries\":[]}]}", PersonalLibrary::class.java)
        assertTrue(data.savedSearches.isEmpty()); assertTrue(data.smartShelves.isEmpty())
    }
    @Test fun savedSearchAndSmartRuleSurviveBackupMerge() {
        val search = SavedSearch(name = "中文漫画", query = "space", sources = listOf("nhentai"), category = "COMIC", languages = mapOf("nhentai" to "CHINESE"))
        val smart = SmartShelf(name = "离线未读", rule = SmartRule.OFFLINE_UNSEEN, source = "local")
        val incoming = LibraryBackup(personal = PersonalLibrary(savedSearches = listOf(search), smartShelves = listOf(smart)))
        val merged = mergeBackups(LibraryBackup(), incoming)
        assertEquals(search, merged.personal.savedSearches.single()); assertEquals(smart, merged.personal.smartShelves.single())
        assertEquals(merged, Gson().fromJson(Gson().toJson(merged), LibraryBackup::class.java))
    }
    @Test fun unknownSearchSourceCannotBeRestored() {
        assertThrows(IllegalArgumentException::class.java) { validateBackup(LibraryBackup(personal = PersonalLibrary(savedSearches = listOf(SavedSearch(name = "bad", query = "x", sources = listOf("unknown")))))) }
    }
    @Test fun unknownSmartRuleCannotPassBackupValidation() {
        val json = Gson().toJson(LibraryBackup(personal = PersonalLibrary(smartShelves = listOf(SmartShelf(name = "future"))))).replace("UNFINISHED", "FUTURE_RULE")
        val decoded = Gson().fromJson(json, LibraryBackup::class.java)
        assertThrows(IllegalArgumentException::class.java) { validateBackup(decoded) }
    }
    @Test fun smartRulesSeparateFinishedStartedAndOfflineUnseen() {
        val started = CatalogEntry(video("started"), started = true)
        val finished = CatalogEntry(video("finished"), started = true, completed = true, offline = true)
        val unseen = CatalogEntry(video("unseen"), offline = true)
        val all = listOf(started, finished, unseen)
        assertEquals(listOf("started"), SmartShelf(rule = SmartRule.UNFINISHED).select(all).map { it.video.id })
        assertEquals(listOf("unseen"), SmartShelf(rule = SmartRule.OFFLINE_UNSEEN).select(all).map { it.video.id })
    }
    @Test fun recentShelfUsesAddedDateAndHonorsSourceFilter() {
        val all = listOf(CatalogEntry(video("new"), addedAt = 100), CatalogEntry(video("old"), addedAt = 1), CatalogEntry(video("other", "hanime1"), addedAt = 100))
        assertEquals(listOf("new"), SmartShelf(rule = SmartRule.RECENT, source = "r34v").select(all, 30L * 86400000 + 50).map { it.video.id })
    }
    @Test fun localBackupUsesLogicalIdentityAndRejectsPrivateFilesystemUrls() {
        val id = "a".repeat(64); val local = Video(id, "本地书", "r34local://$id", site = "local", mediaKind = MediaKind.GALLERY)
        validateBackup(LibraryBackup(personal = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(local)))))))
        val invalid = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(local.copy(url = "file:///private/book"))))))
        assertThrows(IllegalArgumentException::class.java) { validateBackup(LibraryBackup(personal = invalid)) }
    }
}
