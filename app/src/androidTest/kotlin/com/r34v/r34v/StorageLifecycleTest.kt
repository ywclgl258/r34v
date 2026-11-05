package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
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
        context = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "private").apply { mkdirs() } }
        store = LibraryStore(context, root.name, "${root.name}-legacy")
        storage = StorageRoot(context, store, File(root, "shared"))
        storage.choose(StorageRoot.Mode.PRIVATE)
    }
    @After fun cleanUp() {
        store.prefs.edit().clear().commit()
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }
    private fun fresh() = StorageRoot(context, store, storage.sharedRoot)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun book(mode: StorageRoot.Mode, id: String): Video {
        val entry = Video(id, "Neutral recovery fixture", "https://nhentai.net/g/$id/", site = "nhentai", mediaKind = MediaKind.GALLERY)
        val folder = File(storage.ensure(mode).comics, "nhentai/${hash(id)}").apply { mkdirs() }
        val url = "https://nhentai.net/g/$id/1/"
        val detail = GalleryDetail(entry, 1, listOf(GalleryPageLink(1, url)), 1)
        File(folder, "book.json").writeText(store.gson.toJson(detail))
        File(folder, "links.json").writeText(store.gson.toJson(mapOf("links" to mapOf(1 to url))))
        File(folder, "${hash(url)}.page.json").writeText(store.gson.toJson(GalleryImage(1, url, "https://example.invalid/image.png", null, null)))
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        File(folder, "${hash(url)}.image").writeBytes(bytes)
        File(folder, "complete").writeText("1")
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
