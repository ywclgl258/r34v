package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.SiteAdapter
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
            store.setGalleryLanguage(GalleryLanguage.ALL, "nhentai")
            val incoming = store.backupSnapshot().sites.first { it.site == "nhentai" }.copy(language = GalleryLanguage.CHINESE)
            store.restoreBackup(LibraryBackup(sites = listOf(incoming)))
            assertEquals(GalleryLanguage.CHINESE, store.galleryLanguage("nhentai"))
            val callback = store.backupSnapshot().sites.first { it.site == "nhentai" }
            assertEquals(GalleryLanguage.CHINESE, callback.language)
            store.personal.setReadingBehavior("r34v", ReadingBehavior(showClock = true, brightnessPercent = 35, keepAwake = true))
            val backup = store.backupSnapshot()
            val decoded = BackupCodec.decode(BackupCodec.encode(backup, "storage-test".toCharArray()), "storage-test".toCharArray())
            store.restoreBackup(decoded)
            assertEquals(GalleryLanguage.CHINESE, store.galleryLanguage("nhentai"))
            assertEquals(35, store.personal.readingBehavior("r34v").brightnessPercent)
            store.personal.markRead("r34v", setOf(video.entryKey))
            assertTrue(PersonalStore(store).snapshot().searchUpdates.getValue(search.id).entries.single().read)
            store.personal.saveSearch(search.copy(query = "b"))
            assertFalse(store.personal.snapshot().searchUpdates.containsKey(search.id))
            store.personal.refreshSearch(search, "r34v", empty.copy(videos = listOf(video)))
            assertFalse(store.personal.snapshot().searchUpdates.containsKey(search.id))
            store.personal.saveSearch(search.copy(singleSiteScope = FeedKind.MODEL))
            assertEquals(FeedKind.MODEL, store.personal.snapshot().savedSearches.single().singleSiteScope)
            assertNull(Gson().fromJson("""{"name":"old","query":"q","sources":["r34v"]}""", SavedSearch::class.java).singleSiteScope)
            store.personal.removeSearch(search.id); store.personal.refreshSearch(search, "r34v", empty)
            assertTrue(store.personal.snapshot().savedSearches.isEmpty()); assertTrue(store.personal.snapshot().searchUpdates.isEmpty())
        } finally { store.prefs.edit().clear().commit() }
    }
    @Test fun directoryCheckAndIwaraPinnedFeedsKeepTheirOwnContracts() = ComposeFixture().use { f ->
        val profile = com.r34v.r34v.data.adapter.GalleryEpicProfile()
        val site = SiteRegistry.GALLERYEPIC
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val neutral = adapters.getValue(site.id)
        adapters[site.id] = object : SiteAdapter by neutral {
            override fun indexSection(request: FeedRequest) = profile.indexSection(request)
        }
        val search = SavedSearch(name = "目录", query = "sample", sources = listOf(site.id))
        f.app.library.personal.saveSearch(search)
        val model = SavedSearchUpdateModel(f.app)
        f.ins.runOnMainSync { model.check(listOf(search)) }
        runBlocking { withTimeout(15_000) {
            while (f.app.library.personal.snapshot().searchUpdates[search.id]?.checks?.get(site.id)?.directory != true) delay(20)
        } }
        val state = f.app.library.personal.snapshot().searchUpdates.getValue(search.id)
        assertTrue(state.entries.isEmpty())
        assertTrue(state.known.isEmpty())
        assertTrue(f.requests.none { it.first == site.id && it.second.kind == FeedKind.SEARCH })
        f.ins.runOnMainSync { model.cancel() }

        val target = "author"
        val videos = FeedRequest(FeedKind.MODEL, target, contentKind = MediaKind.VIDEO)
        val images = FeedRequest(FeedKind.MODEL, target, contentKind = MediaKind.GALLERY)
        f.ins.runOnMainSync {
            f.app.library.personal.pinFeed(site = "iwara", request = videos, name = "视频频道")
            f.app.library.personal.pinFeed(site = "iwara", request = images, name = "图库频道")
            f.app.library.personal.pinFeed(site = "iwara", request = images, name = "图库重命名")
        }
        val channels = f.app.library.personal.snapshot().channels
        assertEquals(setOf(MediaKind.VIDEO, MediaKind.GALLERY), channels.map { it.feedRequest.contentKind }.toSet())
        assertEquals(channels.size, channels.distinctBy { it.feedRequest }.size)
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
