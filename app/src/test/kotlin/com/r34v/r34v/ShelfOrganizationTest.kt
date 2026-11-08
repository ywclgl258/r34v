package com.r34v.r34v

import com.google.gson.Gson
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

class ShelfOrganizationTest {
    private fun video(id: String, title: String = id, site: String = "r34v") =
        Video(id, title, "${SiteRegistry.byIdOrDefault(site).base}/videos/$id/", site = site)
    private val day = 86_400_000L

    @Test fun oldShelfJsonReceivesSafeMetadataDefaults() {
        val old = Gson().fromJson("""{"id":"watch-later","name":"稍后看","entries":[]}""", Shelf::class.java)
        assertEquals("", old.note); assertEquals("", old.coverKey); assertFalse(old.pinned); assertEquals(0L, old.modifiedAt)
        validateBackup(LibraryBackup(personal = PersonalLibrary(shelves = listOf(old))))
    }
    @Test fun searchCombinesTitleSourceTypeAndCompletionWithoutInventedMetadata() {
        val shelf = Shelf(name = "混合", entries = listOf(ShelfEntry(video("1", "Space One")),
            ShelfEntry(video("2", "space Two", "ehentai"), completed = true), ShelfEntry(video("3", "Ocean"))))
        assertEquals(listOf("1"), ShelfQuery("SPACE r34v").select(shelf).map { it.video.id })
        assertEquals(listOf("2"), ShelfQuery("space", status = 2, category = "COMIC", source = "ehentai").select(shelf).map { it.video.id })
        assertTrue(ShelfQuery("Space unknown").select(shelf).isEmpty())
    }
    @Test fun dateUsesAddedTimeAndIncludesTheBoundary() {
        val entries = listOf(ShelfEntry(video("old"), at = 100 * day, addedAt = 90 * day),
            ShelfEntry(video("boundary"), at = 93 * day), ShelfEntry(video("new"), at = 99 * day))
        assertEquals(listOf("boundary", "new"), ShelfQuery(addedDays = 7).select(Shelf(name = "test", entries = entries), 100 * day).map { it.video.id })
    }
    @Test fun displayOrdersAreStableAndNeverRewriteManualOrder() {
        val shelf = Shelf(name = "test", entries = listOf(ShelfEntry(video("z", "zebra"), at = 2),
            ShelfEntry(video("a", "Alpha"), at = 3), ShelfEntry(video("b", "beta"), at = 3)))
        assertEquals(listOf("a", "b", "z"), ShelfQuery(order = ShelfOrder.NEWEST).select(shelf).map { it.video.id })
        assertEquals(listOf("a", "b", "z"), ShelfQuery(order = ShelfOrder.TITLE).select(shelf).map { it.video.id })
        assertEquals(listOf("z", "a", "b"), ShelfQuery().select(shelf).map { it.video.id })
        assertEquals(listOf("z", "a", "b"), shelf.entries.map { it.video.id })
    }
    @Test fun pinnedShelvesKeepTheirRelativeOrder() {
        val shelves = listOf(Shelf("a", "A"), Shelf("b", "B", pinned = true), Shelf("c", "C", pinned = true))
        assertEquals(listOf("b", "c", "a"), shelves.displayOrder().map { it.id })
        assertEquals(listOf("a", "b", "c"), shelves.map { it.id })
    }
    @Test fun selectedCoverUsesFullSiteIdentityAndHasAutomaticFallback() {
        val first = video("1"); val second = video("1", site = "hanime1")
        val shelf = Shelf(name = "test", entries = listOf(ShelfEntry(first), ShelfEntry(second)), coverKey = second.entryKey)
        assertEquals(listOf(second), shelf.coverVideos())
        assertEquals(listOf(first), shelf.copy(entries = listOf(ShelfEntry(first))).coverVideos())
    }
    @Test fun invalidMetadataAndForeignCoverAreRejected() {
        val shelf = Shelf(name = "test", entries = listOf(ShelfEntry(video("1"))))
        assertThrows(IllegalArgumentException::class.java) { shelf.edited(note = "x".repeat(1001)) }
        assertThrows(IllegalArgumentException::class.java) { shelf.edited(coverKey = "r34v:other") }
        assertThrows(IllegalArgumentException::class.java) { Shelf(PersonalLibrary.WATCH_LATER, "稍后看").edited(name = "other") }
        assertThrows(IllegalArgumentException::class.java) {
            validateBackup(LibraryBackup(personal = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", coverKey = "unknown")))))
        }
    }
    @Test fun backupMergeUsesLatestMetadataWhileKeepingBothSetsOfWorksAndManualOrder() {
        val first = video("1"); val second = video("2")
        val old = Shelf("shared", "旧名称", listOf(ShelfEntry(first)), note = "旧备注", modifiedAt = 10)
        val newer = Shelf("shared", "新名称", listOf(ShelfEntry(second)), "周末看", true, second.entryKey, 20)
        val local = LibraryBackup(personal = PersonalLibrary(shelves = PersonalLibrary().shelves + old))
        val remote = LibraryBackup(personal = PersonalLibrary(shelves = PersonalLibrary().shelves + newer))
        val merged = mergeBackups(local, remote)
        val result = merged.personal.shelves.last()
        assertEquals("新名称", result.name); assertEquals("周末看", result.note); assertTrue(result.pinned)
        assertEquals(second.entryKey, result.coverKey); assertEquals(listOf("1", "2"), result.entries.map { it.video.id })
        validateBackup(Gson().fromJson(Gson().toJson(merged), LibraryBackup::class.java))
        assertEquals(newer.note, mergeBackups(remote, local).personal.shelves.last().note)
    }
    @Test fun clearingMetadataHasANewerRevisionAndSurvivesMerge() {
        val original = Shelf("custom", "test", listOf(ShelfEntry(video("1"))), "note", true, "r34v:1", 50)
        val cleared = original.edited(note = "", pinned = false, coverKey = "", now = 50)
        assertTrue(cleared.modifiedAt > original.modifiedAt)
        fun backup(shelf: Shelf) = LibraryBackup(personal = PersonalLibrary(shelves = PersonalLibrary().shelves + shelf))
        val restored = mergeBackups(backup(original), backup(cleared)).personal.shelves.last()
        assertEquals("", restored.note); assertFalse(restored.pinned); assertEquals("", restored.coverKey)
    }
}
