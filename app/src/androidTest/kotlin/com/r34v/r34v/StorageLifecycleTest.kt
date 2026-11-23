package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.downloads.GalleryDownloadStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class StorageLifecycleTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: LibraryStore
    private lateinit var storage: StorageRoot
    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "storage-lifecycle-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(if (name == "gallery_downloads_v1") "${root.name}-queue" else name, mode)
        }
        store = LibraryStore(context, root.name, "${root.name}-legacy")
        storage = StorageRoot(context, store, File(root, "shared"))
        storage.choose(StorageRoot.Mode.PRIVATE)
    }
    @After fun cleanUp() {
        store.prefs.edit().clear().commit()
        queuePrefs().edit().clear().commit()
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }
    private fun fresh() = StorageRoot(context, store, storage.sharedRoot)
    private fun queuePrefs() = context.getSharedPreferences("gallery_downloads_v1", Context.MODE_PRIVATE)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun pageUrl(id: String, page: Int) = "https://nhentai.net/g/$id/$page/"
    private fun bookFolder(mode: StorageRoot.Mode, id: String) = File(storage.layoutOf(mode).comics, "nhentai/${hash(id)}")

    private fun book(mode: StorageRoot.Mode, id: String, count: Int = 1, saved: List<Int> = (1..count).toList()): Video {
        val entry = Video(id, "Neutral recovery fixture", "https://nhentai.net/g/$id/", site = "nhentai", mediaKind = MediaKind.GALLERY)
        val folder = bookFolder(mode, id).apply { mkdirs() }
        val detail = GalleryDetail(entry, count, (1..count).map { GalleryPageLink(it, pageUrl(id, it)) }, count)
        File(folder, "book.json").writeText(store.gson.toJson(detail))
        val links = saved.associateWith { pageUrl(id, it) }
        File(folder, "links.json").writeText(store.gson.toJson(mapOf("links" to links)))
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        for ((page, url) in links) {
            File(folder, "${hash(url)}.page.json").writeText(store.gson.toJson(GalleryImage(page, url, "https://example.invalid/image.png", null, null)))
            File(folder, "${hash(url)}.image").writeBytes(bytes)
        }
        if (saved.size == count) File(folder, "complete").writeText(count.toString())
        return entry
    }
    @Test fun bothSwitchDirectionsApplyBeforeTheNextProcessBindsItsDirectory() = runBlocking {
        val before = storage.layout().root.absolutePath
        storage.migrate(copyVideos = {})
        assertTrue(storage.pendingRestart)
        assertEquals(StorageRoot.Mode.PRIVATE, storage.applied)
        assertEquals(before, storage.layout().root.absolutePath)
        assertTrue(runCatching { storage.checkDownloadsAllowed() }.isFailure)
        val sharedProcess = fresh().apply { applyPendingOnStart() }
        assertEquals(StorageRoot.Mode.SHARED, sharedProcess.effective())
        sharedProcess.requestOnRestart(StorageRoot.Mode.PRIVATE)
        assertEquals(StorageRoot.Mode.SHARED, sharedProcess.effective())
        val privateProcess = fresh().apply { applyPendingOnStart() }
        assertEquals(StorageRoot.Mode.PRIVATE, privateProcess.effective())
        privateProcess.checkDownloadsAllowed()
    }
    @Test fun anUnavailableSharedDirectoryAtStartupRestoresThePrivateQueueAndKeepsCopies() = runBlocking {
        val entry = book(StorageRoot.Mode.PRIVATE, "startup-rollback")
        storage.migrate(copyVideos = {})
        val shared = storage.layoutOf(StorageRoot.Mode.SHARED)
        assertTrue(shared.videoMeta.delete())
        shared.videoMeta.writeText("blocked subdirectory")

        val started = fresh()
        val notice = started.applyPendingOnStart()
        assertFalse(notice.isNullOrBlank())
        assertEquals(notice, started.status().note)
        assertFalse(started.status().writable)
        assertFalse(started.pendingRestart)
        assertEquals(StorageRoot.Mode.PRIVATE, started.applied)
        assertEquals(StorageRoot.Mode.PRIVATE, started.requested)
        assertEquals(StorageRoot.Mode.PRIVATE, started.effective())
        val network = Network(context, store)
        val repository = GalleryRepository(context, network, started)
        val queue = GalleryDownloadStore(context, repository, network, LibraryIndex(context, started)) { false }
        val queued = queue.add(entry)
        assertEquals(GalleryDownloadState.QUEUED, queued.state)
        assertEquals("blocked subdirectory", shared.videoMeta.readText())
        assertTrue(GalleryRepository.isCompleteDownload(bookFolder(StorageRoot.Mode.PRIVATE, entry.id)))
        assertTrue(GalleryRepository.isCompleteDownload(bookFolder(StorageRoot.Mode.SHARED, entry.id)))

        assertTrue(shared.videoMeta.delete())
        val next = fresh()
        assertNull(next.applyPendingOnStart())
        assertEquals(notice, next.status().note)
        assertEquals(StorageRoot.Mode.PRIVATE, next.effective())
        next.beginChange()
        try { next.migrate(copyVideos = {}) } finally { next.endChange() }
        val retried = fresh()
        assertNull(retried.applyPendingOnStart())
        assertEquals(StorageRoot.Mode.SHARED, retried.effective())
        assertTrue(retried.status().note.isBlank())
    }

    @Test fun videoCopyFailureCannotCommitTheLocation() = runBlocking {
        assertTrue(runCatching { storage.migrate(copyVideos = { throw IOException("disk full") }) }.isFailure)
        assertFalse(storage.pendingRestart)
        assertEquals(StorageRoot.Mode.PRIVATE, storage.applied)
    }
    @Test fun aFileCopyFailureKeepsTheOriginalAndDoesNotCommitTheLocation() = runBlocking {
        val source = File(storage.ensure(StorageRoot.Mode.PRIVATE).comics, "nhentai/book/page.image").apply { parentFile!!.mkdirs(); writeText("original") }
        File(storage.ensure(StorageRoot.Mode.SHARED).comics, "nhentai").writeText("blocked parent")
        assertTrue(runCatching { storage.migrate(copyVideos = {}) }.isFailure)
        assertEquals("original", source.readText())
        assertFalse(storage.pendingRestart)
        assertEquals(StorageRoot.Mode.PRIVATE, storage.effective())
    }
    @Test fun scannedSharedBookOpensOfflineWhilePrivateIsSelectedAndCanBeDeleted() = runBlocking {
        val entry = book(StorageRoot.Mode.SHARED, "shared-only")
        val repository = GalleryRepository(context, Network(context, store), storage)
        val index = LibraryIndex(context, storage)
        val record = index.scan({ _, _ -> false }).single { it.id == entry.id }
        assertEquals(StorageRoot.Mode.PRIVATE, storage.effective())
        assertEquals(storage.sharedRoot!!.absolutePath, record.sourceRoot)
        assertEquals(1, repository.detail(entry, true, record.sourceRoot).pageCount)
        val pageUrl = repository.cachedPageUrl(entry, 1, record.sourceRoot)!!
        val page = repository.page(SiteRegistry.NHENTAI, pageUrl, entry = entry, offlineOnly = true, sourceRoot = record.sourceRoot)
        assertEquals(8, page.bitmap.width); page.bitmap.recycle()
        assertTrue(repository.isDownloaded(entry))
        repository.removeDownload(entry); index.forgetComic(entry.site, entry.id)
        assertFalse(repository.isDownloaded(entry))
        assertTrue(index.scan({ _, _ -> false }).none { it.id == entry.id })
    }
    @Test fun aCorruptSharedCopyCannotShadowThePrivateDownloadBeingCompleted() = runBlocking {
        val entry = book(StorageRoot.Mode.PRIVATE, "corrupt-duplicate", count = 3)
        book(StorageRoot.Mode.SHARED, entry.id, count = 3)
        val privateBook = bookFolder(StorageRoot.Mode.PRIVATE, entry.id)
        val sharedBook = bookFolder(StorageRoot.Mode.SHARED, entry.id)
        assertTrue(File(privateBook, "complete").delete())
        val damaged = byteArrayOf(1, 2, 3)
        val sharedPage = File(sharedBook, "${hash(pageUrl(entry.id, 2))}.image").apply { writeBytes(damaged) }
        val network = Network(context, store)
        val repository = GalleryRepository(context, network, storage)
        val index = LibraryIndex(context, storage)
        var allowed = false
        val queue = GalleryDownloadStore(context, repository, network, index) { allowed }
        assertFalse(repository.isDownloaded(entry))
        val job = queue.add(entry)
        allowed = true
        checkNotNull(queue.next(this)).join()

        val finished = checkNotNull(queue.task(job.id))
        assertEquals(GalleryDownloadState.COMPLETED, finished.state)
        assertNull(finished.selectedPages)
        assertEquals(listOf(1, 2, 3), finished.completedPages)
        assertEquals("3", File(privateBook, "complete").readText())
        assertFalse(File(privateBook, "selection.json").exists())
        assertTrue(index.comicSidecar(entry.site, entry.id, storage.layoutOf(StorageRoot.Mode.PRIVATE)).isFile)
        assertEquals("3", File(sharedBook, "complete").readText())
        assertArrayEquals(damaged, sharedPage.readBytes())
        val indexed = index.scan({ _, _ -> false }).single { it.id == entry.id }
        assertEquals(LibraryRecord.STATE_COMPLETED, indexed.state)
        assertEquals(context.filesDir.absolutePath, indexed.sourceRoot)
        assertEquals(indexed.sourceRoot, repository.readingRoot(entry))
        val detail = repository.detail(entry, offlineOnly = true, sourceRoot = indexed.sourceRoot)
        assertEquals(listOf(1, 2, 3), detail.downloadedPages)
        val page = repository.page(SiteRegistry.NHENTAI, pageUrl(entry.id, 2), entry = entry, offlineOnly = true, sourceRoot = indexed.sourceRoot)
        assertEquals(8, page.bitmap.width); page.bitmap.recycle()
    }

    @Test fun migrationRetainsSelectedQueueAndUserPauseAfterFailureAndAfterRestart() = runBlocking {
        val selection = listOf(1, 3)
        val entry = book(StorageRoot.Mode.PRIVATE, "selected-queue", count = 4, saved = selection)
        val pausedEntry = book(StorageRoot.Mode.PRIVATE, "manual-pause", count = 4, saved = listOf(2))
        val network = Network(context, store)
        val repository = GalleryRepository(context, network, storage)
        var allowed = false
        val queue = GalleryDownloadStore(context, repository, network, LibraryIndex(context, storage)) { allowed }
        val selected = queue.add(entry, selection, group = "fixture")
        val paused = queue.add(pausedEntry, listOf(2))
        queue.pause(paused.id)
        val userPaused = queue.task(paused.id)
        queue.prioritize(selected.id)
        val original = checkNotNull(queue.task(selected.id))
        allowed = true
        val active = checkNotNull(queue.next(this))
        assertEquals(GalleryDownloadState.DOWNLOADING, queue.task(selected.id)?.state)

        storage.beginChange()
        try {
            val error = runCatching {
                queue.withWritersStopped {
                    assertTrue(active.isCancelled)
                    assertNull(queue.next(this@runBlocking))
                    storage.migrate(copyVideos = { throw IOException("fixture copy failure") })
                }
            }.exceptionOrNull()
            assertEquals("fixture copy failure", error?.message)
        } finally { storage.endChange() }
        assertFalse(storage.pendingRestart)
        val restored = checkNotNull(queue.task(selected.id))
        assertEquals(GalleryDownloadState.QUEUED, restored.state)
        assertEquals(original.selectedPages, restored.selectedPages)
        assertEquals(original.group, restored.group)
        assertEquals(original.priority, restored.priority)
        assertEquals(userPaused, queue.task(paused.id))
        checkNotNull(queue.next(this)).join()
        assertEquals(selection, queue.task(selected.id)?.completedPages)
        assertEquals(GalleryDownloadState.COMPLETED, queue.task(selected.id)?.state)

        allowed = false
        queue.add(entry, selection)
        storage.beginChange()
        try { queue.withWritersStopped { storage.migrate(copyVideos = {}) } } finally { storage.endChange() }
        allowed = true
        assertTrue(storage.pendingRestart)
        assertNull(queue.next(this))
        val restarted = fresh().apply { assertNull(applyPendingOnStart()) }
        assertEquals(StorageRoot.Mode.SHARED, restarted.effective())
        val newRepository = GalleryRepository(context, network, restarted)
        val newQueue = GalleryDownloadStore(context, newRepository, network, LibraryIndex(context, restarted))
        assertEquals(GalleryDownloadState.QUEUED, newQueue.task(selected.id)?.state)
        assertEquals(selection, newQueue.task(selected.id)?.selectedPages)
        assertEquals(userPaused, newQueue.task(paused.id))
        checkNotNull(newQueue.next(this)).join()
        assertEquals(GalleryDownloadState.COMPLETED, newQueue.task(selected.id)?.state)
        assertEquals(selection, newQueue.task(selected.id)?.completedPages)
        assertTrue(File(bookFolder(StorageRoot.Mode.SHARED, entry.id), "selection.json").isFile)
        assertEquals(userPaused, newQueue.task(paused.id))
    }

    @Test fun removingDuplicatesCannotResurrectTheBookOrRemoveAnotherBook() = runBlocking {
        val entry = book(StorageRoot.Mode.PRIVATE, "duplicate")
        book(StorageRoot.Mode.SHARED, entry.id)
        val other = book(StorageRoot.Mode.SHARED, "keep")
        val repository = GalleryRepository(context, Network(context, store), storage)
        repository.removeDownload(entry)
        assertFalse(repository.isDownloaded(entry))
        assertTrue(repository.isDownloaded(other))
    }
    @Test fun aCompleteSharedBookIsPreservedWhenThePrivateCopyIsPartial() = runBlocking {
        val entry = book(StorageRoot.Mode.PRIVATE, "preserve-complete")
        book(StorageRoot.Mode.SHARED, entry.id)
        val privateBook = File(storage.layoutOf(StorageRoot.Mode.PRIVATE).comics, "nhentai/${hash(entry.id)}")
        val sharedBook = File(storage.layoutOf(StorageRoot.Mode.SHARED).comics, "nhentai/${hash(entry.id)}")
        File(privateBook, "links.json").writeText("{\"links\":{}}")
        File(privateBook, "complete").delete()
        val before = sharedBook.listFiles()!!.associate { it.name to it.readBytes().toList() }
        storage.migrate(copyVideos = {})
        assertEquals(before, sharedBook.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertTrue(GalleryRepository.isCompleteDownload(sharedBook, store.gson))
        assertFalse(GalleryRepository.isCompleteDownload(privateBook, store.gson))
        assertTrue(storage.pendingRestart)
    }
    @Test fun conflictingPartialBooksStopBeforeCopyingOrCommittingTheLocation() = runBlocking {
        val entry = book(StorageRoot.Mode.PRIVATE, "partial-conflict")
        book(StorageRoot.Mode.SHARED, entry.id)
        val privateBook = File(storage.layoutOf(StorageRoot.Mode.PRIVATE).comics, "nhentai/${hash(entry.id)}")
        val sharedBook = File(storage.layoutOf(StorageRoot.Mode.SHARED).comics, "nhentai/${hash(entry.id)}")
        File(privateBook, "complete").delete(); File(sharedBook, "complete").delete()
        val privateLinks = File(privateBook, "links.json")
        val sharedLinks = File(sharedBook, "links.json")
        val before = sharedLinks.readText()
        privateLinks.writeText(before.replace("/1/", "/2/"))
        assertEquals(privateLinks.length(), sharedLinks.length())
        var copiedVideos = false
        assertTrue(runCatching { storage.migrate(copyVideos = { copiedVideos = true }) }.isFailure)
        assertFalse(copiedVideos)
        assertFalse(storage.pendingRestart)
        assertEquals(before, sharedLinks.readText())
        assertTrue(privateLinks.isFile)
    }
    @Test fun aCompletePrivateBookRepairsThePartialSharedSnapshotAndCommitsItsMarkerLast() = runBlocking {
        val entry = book(StorageRoot.Mode.PRIVATE, "repair-partial")
        book(StorageRoot.Mode.SHARED, entry.id)
        val privateBook = File(storage.layoutOf(StorageRoot.Mode.PRIVATE).comics, "nhentai/${hash(entry.id)}")
        val sharedBook = File(storage.layoutOf(StorageRoot.Mode.SHARED).comics, "nhentai/${hash(entry.id)}")
        val expected = File(privateBook, "links.json").readText()
        val sharedLinks = File(sharedBook, "links.json")
        sharedLinks.writeText(expected.replace("/1/", "/2/"))
        assertEquals(File(privateBook, "links.json").length(), sharedLinks.length())
        assertFalse(GalleryRepository.isCompleteDownload(sharedBook, store.gson))
        storage.migrate(copyVideos = {}, report = { _, _, name ->
            if (name != "complete") assertFalse(File(sharedBook, "complete").exists())
        })
        assertEquals(expected, sharedLinks.readText())
        assertTrue(GalleryRepository.isCompleteDownload(sharedBook, store.gson))
        assertTrue(GalleryRepository.isCompleteDownload(privateBook, store.gson))
        assertTrue(storage.pendingRestart)
    }
}
