package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SavedSearchStorageTest {
    @Test fun renameRetainsUnreadChangedConditionResetsAndLateResponsesCannotResurrectDeletedSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context, "search-storage-${System.nanoTime()}", "no-legacy-${System.nanoTime()}")
        try {
            val search = SavedSearch(name = "原方案", query = "a", sources = listOf("r34v"))
            val video = Video("new", site = "r34v")
            val empty = FeedPage(emptyList(), 1, 1, "")
            store.personal.saveSearch(search); store.personal.refreshSearch(search, "r34v", empty)
            store.personal.refreshSearch(search, "r34v", empty.copy(videos = listOf(video)))
            store.personal.saveSearch(search.copy(name = "新名称"))
            assertFalse(PersonalStore(store).snapshot().searchUpdates.getValue(search.id).entries.single().read)
            store.personal.setReadingBehavior("r34v", ReadingBehavior(showClock = true, brightnessPercent = 35, keepAwake = true))
            val backup = store.backupSnapshot()
            val decoded = BackupCodec.decode(BackupCodec.encode(backup, "storage-test".toCharArray()), "storage-test".toCharArray())
            store.restoreBackup(decoded)
            assertEquals(35, store.personal.readingBehavior("r34v").brightnessPercent)
            store.personal.markRead("r34v", setOf(video.entryKey))
            assertTrue(PersonalStore(store).snapshot().searchUpdates.getValue(search.id).entries.single().read)
            store.personal.saveSearch(search.copy(query = "b"))
            assertFalse(store.personal.snapshot().searchUpdates.containsKey(search.id))
            store.personal.refreshSearch(search, "r34v", empty.copy(videos = listOf(video)))
            assertFalse(store.personal.snapshot().searchUpdates.containsKey(search.id))
            store.personal.removeSearch(search.id); store.personal.refreshSearch(search, "r34v", empty)
            assertTrue(store.personal.snapshot().savedSearches.isEmpty()); assertTrue(store.personal.snapshot().searchUpdates.isEmpty())
        } finally { store.prefs.edit().clear().commit() }
    }
    @Test fun refreshBoundsConcurrencyAndKeepsSuccessfulSourcesWhenCancelled(): Unit = ComposeFixture().use { f ->
        val search = SavedSearch(name = "并发测试", query = "q", sources = listOf("r34v", "hanime1", "nhentai", "ehentai"))
        f.app.library.personal.saveSearch(search)
        val active = AtomicInteger(); val maximum = AtomicInteger()
        f.feedOverride = { site, request ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try { delay(if (site == "ehentai") 60_000 else 100); FeedPage(listOf(f.video(site)), 1, 1, request.target) }
            finally { active.decrementAndGet() }
        }
        val model = SavedSearchUpdateModel(f.app)
        try {
            f.ins.runOnMainSync { model.check(listOf(search)) }
            runBlocking { withTimeout(15000) { while ((f.app.library.personal.snapshot().searchUpdates[search.id]?.checks?.size ?: 0) < 3) delay(20) } }
            f.ins.runOnMainSync { model.cancel() }
            runBlocking { withTimeout(5000) { while (model.busy) delay(20) } }
            assertTrue(maximum.get() <= 3)
            assertEquals(3, f.app.library.personal.snapshot().searchUpdates.getValue(search.id).known.size)
            assertFalse(f.app.library.personal.snapshot().searchUpdates.getValue(search.id).known.containsKey("ehentai"))
        } finally { f.ins.runOnMainSync { model.cancel() } }
    }
}
