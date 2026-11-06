package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.downloads.GalleryDownloadStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureStorageTest {
    private fun store() = LibraryStore(InstrumentationRegistry.getInstrumentation().targetContext, "feature-test-${System.nanoTime()}", "no-legacy-${System.nanoTime()}")
    private fun video(id: String, site: String = "r34v") = Video(id, "测试 $id", "${SiteRegistry.byIdOrDefault(site).base}/videos/$id/", site = site)

    @Test fun collectionsCompletionOrderAndBookmarksSurviveReopening() {
        val data = store()
        try {
            val a = video("same"); val b = video("same", "hanime1")
            data.personal.add(a); data.personal.add(b); data.personal.add(a)
            data.personal.complete(a, PersonalLibrary.WATCH_LATER, true); data.personal.move(b, PersonalLibrary.WATCH_LATER, -1)
            val custom = data.personal.createShelf("测试合集"); data.personal.add(a, custom); data.personal.renameShelf(custom, "重命名")
            data.personal.toggleBookmark(a, 3, 25); data.personal.toggleBookmark(b, 3, 40)
            data.personal.setReaderMode("ehentai", ReaderMode.RIGHT_TO_LEFT)
            val reopened = PersonalStore(data)
            assertEquals(listOf(b.entryKey, a.entryKey), reopened.shelves().first().entries.map { it.video.entryKey })
            assertTrue(reopened.shelves().first().entries.last().completed)
            assertEquals("重命名", reopened.shelves().last().name)
            assertEquals(25, reopened.bookmarks(a).single().offset); assertEquals(40, reopened.bookmarks(b).single().offset)
            assertEquals(ReaderMode.RIGHT_TO_LEFT, reopened.readerMode("ehentai"))
            assertThrows(IllegalArgumentException::class.java) { reopened.removeShelf(PersonalLibrary.WATCH_LATER) }
        } finally { data.prefs.edit().clear().commit() }
    }
    @Test fun portableBackupRestoresIntoAnotherStoreWithoutReplacingCredentials() {
        val source = store(); val target = store()
        try {
            source.toggleFavorite(Favorite("video", "one", "收藏", "/videos/one/", site = "r34v"), "r34v")
            source.saveWatch(Watch("one", position = 20, at = 1, site = "r34v"))
            source.personal.add(video("one")); source.set("quality", "1080p")
            source.prefs.edit().putString("r34v:native_cookies", "fixture-private-cookie").putString("settings_v1_download_location", "fixture-private-path").commit()
            val snapshot = source.backupSnapshot()
            assertFalse(source.gson.toJson(snapshot).contains("fixture-private"))
            val bytes = BackupCodec.encode(snapshot, "fixture-password".toCharArray())
            target.saveWatch(Watch("one", position = 5, at = 100, site = "r34v"))
            target.personal.add(video("other", "hanime1"))
            target.prefs.edit().putString("r34v:native_cookies", "target-credential").commit()
            target.restoreBackup(BackupCodec.decode(bytes, "fixture-password".toCharArray()))
            assertEquals(5L, target.watch("one", "r34v")!!.position)
            assertTrue(target.isFavorite("video", "one", "r34v")); assertEquals(2, target.personal.shelves().first().entries.size)
            assertEquals("1080p", target.string("quality")); assertEquals("target-credential", target.prefs.getString("r34v:native_cookies", null))
            assertFalse(target.prefs.contains("settings_v1_download_location"))
        } finally { source.prefs.edit().clear().commit(); target.prefs.edit().clear().commit() }
    }
    @Test fun rejectedRestoreLeavesTheEntirePreferencesSnapshotUntouched() {
        val data = store()
        try {
            data.personal.add(video("keep")); data.set("quality", "480p")
            val before = data.prefs.all.toMap()
            assertThrows(IllegalArgumentException::class.java) { data.restoreBackup(LibraryBackup(schema = 999)) }
            assertEquals(before, data.prefs.all)
        } finally { data.prefs.edit().clear().commit() }
    }
    @Test fun galleryNetworkGateDoesNotDownloadAndPreservesManualPause() = ComposeFixture().use { f ->
        var allowed = false
        val downloads = GalleryDownloadStore(f.ins.targetContext, f.app.galleries, f.app.network, f.app.index) { allowed }
        val work = f.video("ehentai")
        runBlocking {
            val task = downloads.add(work)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                assertNull(downloads.next(scope)); assertEquals(GalleryDownloadState.QUEUED, downloads.task(task.id)!!.state)
                assertTrue(f.galleryCalls.isEmpty())
                downloads.pause(task.id); allowed = true
                assertNull(downloads.next(scope)); assertEquals(GalleryDownloadState.PAUSED, downloads.task(task.id)!!.state)
            } finally { scope.cancel(); downloads.remove(task.id) }
        }
    }
    @Test fun subscriptionRefreshLimitsConcurrentRequests() = ComposeFixture().use { f ->
        val active = java.util.concurrent.atomic.AtomicInteger(); val maximum = java.util.concurrent.atomic.AtomicInteger()
        f.feedOverride = { owner, request ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try { delay(50); FeedPage(listOf(f.video(owner)), 1, 1, request.target) } finally { active.decrementAndGet() }
        }
        val result = runBlocking { f.app.repository.subscriptions(SiteRegistry.R34V, SubscriptionOrder.SUBSCRIBED,
            (1..12).map { Subscription("tag-$it", site = "r34v") }) }
        assertTrue(result.failed.isEmpty()); assertTrue(maximum.get() in 2..3); assertEquals(0, active.get())
    }
}
