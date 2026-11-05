package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The promise under test: downloads kept in shared storage come back on their own after the app
 * is uninstalled and installed again, using nothing but the files on disk.
 *
 * Every case runs against its own root and its own preference file, so the result does not depend
 * on what the device happens to hold — the real `/sdcard/R34V` and the app's own library are never
 * read or written here.
 */
@RunWith(AndroidJUnit4::class)
class LibraryIndexTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app = ins.targetContext.applicationContext as R34Application
    private val nonce = System.nanoTime()
    private lateinit var root: File
    private lateinit var store: LibraryStore
    private lateinit var storage: StorageRoot

    @Before fun setUp() {
        root = File(app.filesDir, "reinstall-root-$nonce").apply { mkdirs() }
        store = LibraryStore(ins.targetContext, "reinstall-prefs-$nonce")
        storage = StorageRoot(ins.targetContext, store, root)
    }

    @After fun cleanUp() {
        runCatching { root.deleteRecursively() }
        runCatching { store.prefs.edit().clear().commit() }
    }

    private fun index() = LibraryIndex(ins.targetContext, storage, app.library.gson)

    private fun book(site: String, entryId: String, pages: Int, complete: Boolean, written: Int = pages, mode: StorageRoot.Mode = StorageRoot.Mode.SHARED): File {
        val folder = File(File(storage.ensure(mode).comics, site), hash(entryId)).apply { mkdirs() }
        val entry = Video(entryId, "重装前的漫画 $entryId", "https://$site.example/g/$entryId/", "", site = site)
        val links = (1..pages).map { GalleryPageLink(it, "https://$site.example/s/$entryId-$it") }
        write(folder, "book.json", app.library.gson.toJson(GalleryDetail(entry, pages, links, pages)))
        write(folder, "links.json", app.library.gson.toJson(mapOf("links" to links.associate { it.index to it.url })))
        links.take(written).forEach {
            File(folder, "${hash(it.url)}.image").writeBytes(ByteArray(64) { b -> b.toByte() })
            write(folder, "${hash(it.url)}.page.json", app.library.gson.toJson(GalleryImage(it.index, it.url, "${it.url}.jpg", null, null)))
        }
        if (complete) write(folder, "complete", pages.toString())
        return folder
    }

    private fun hash(id: String) = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun write(folder: File, name: String, text: String) {
        check(folder.isDirectory || folder.mkdirs()) { "无法创建 ${folder.path}" }
        File(folder, name).writeText(text)
    }

    @Test fun privateLayoutKeepsThePathsOlderInstallsAlreadyUse() {
        val layout = storage.layoutOf(StorageRoot.Mode.PRIVATE)
        // An upgrade must find the existing library exactly where the previous version left it.
        assertEquals(File(app.filesDir, "downloads").absolutePath, layout.videoCache.absolutePath)
        assertEquals(File(app.filesDir, "download-meta").absolutePath, layout.videoMeta.absolutePath)
        assertEquals(File(app.filesDir, "gallery-downloads").absolutePath, layout.comics.absolutePath)
    }

    @Test fun sharedLayoutIsVisibleToFileManagersAndHiddenFromTheGallery() {
        val layout = storage.ensure(StorageRoot.Mode.SHARED)
        assertTrue(layout.root.isDirectory)
        assertTrue(layout.videoCache.isDirectory)
        assertTrue(layout.comics.isDirectory)
        assertEquals(root.absolutePath, layout.root.absolutePath)
        assertTrue(layout.comics.absolutePath.endsWith("/comics"))
        assertTrue("图页不能进系统相册", File(layout.root, ".nomedia").isFile)
    }

    @Test fun aFinishedBookReappearsAfterAReinstall() {
        val id = "reinstall-$nonce"
        book("ehentai", id, pages = 3, complete = true)
        // Nothing in memory describes this book; a cold index has to find it on its own.
        val found = runBlocking { index().scan({ _, _ -> false }, emptyList()) }.filter { it.id == id }
        assertEquals(1, found.size)
        assertEquals(LibraryRecord.STATE_COMPLETED, found.single().state)
        assertEquals("重装前的漫画 $id", found.single().title)
        assertEquals(192L, found.single().bytes)
        // The sidecar is written where the book was found, so a second scan need not re-walk it.
        assertTrue("侧车应被补写", index().comicSidecars("ehentai", id).any { it.isFile })
    }

    @Test fun aMarkerWithoutItsPagesIsNotOfferedAsReadable() {
        val id = "hollow-$nonce"
        // A folder copied by hand, or interrupted by a card removal: marker present, pages absent.
        book("nhentai", id, pages = 20, complete = true, written = 4)
        val found = runBlocking { index().scan({ _, _ -> false }, emptyList()) }.single { it.id == id }
        assertEquals("缺页不能冒充完整", LibraryRecord.STATE_PARTIAL, found.state)
        assertTrue(found.detail.contains("4 / 20"))
    }

    @Test fun aHalfDownloadedBookIsPartialAndNeverOfferedAsReadable() {
        val id = "partial-$nonce"
        book("nhentai", id, pages = 20, complete = false, written = 4)
        val found = runBlocking { index().scan({ _, _ -> false }, emptyList()) }.single { it.id == id }
        assertEquals(LibraryRecord.STATE_PARTIAL, found.state)
    }

    @Test fun unrelatedImagesCannotReplaceAMissingMappedPage() {
        val id = "mapped-$nonce"
        val folder = book("nhentai", id, pages = 3, complete = true)
        File(folder, "${hash("https://nhentai.example/s/$id-2")}.image").delete()
        File(folder, "unrelated.image").writeBytes(ByteArray(64))
        val found = runBlocking { index().scan({ _, _ -> false }) }.single { it.id == id }
        assertEquals(LibraryRecord.STATE_PARTIAL, found.state)
        assertEquals("已保存 2 / 3 页", found.detail)
    }

    @Test fun aPageWithoutItsMetadataIsNotReadable() {
        val id = "metadata-$nonce"
        val folder = book("nhentai", id, pages = 2, complete = true)
        File(folder, "${hash("https://nhentai.example/s/$id-1")}.page.json").delete()
        val found = runBlocking { index().scan({ _, _ -> false }) }.single { it.id == id }
        assertEquals(LibraryRecord.STATE_PARTIAL, found.state)
    }

    @Test fun recordsRememberTheirActualRootAndPreferACompleteCopy() {
        val sharedId = "shared-$nonce"
        val privateId = "private-$nonce"
        book("nhentai", sharedId, pages = 2, complete = true)
        book("nhentai", privateId, pages = 2, complete = true, written = 1)
        val privateBook = book("nhentai", privateId, pages = 2, complete = true, mode = StorageRoot.Mode.PRIVATE)
        try {
            val found = runBlocking { index().scan({ _, _ -> false }) }
            assertEquals(root.absolutePath, found.single { it.id == sharedId }.sourceRoot)
            val completePrivate = found.single { it.id == privateId }
            assertEquals(LibraryRecord.STATE_COMPLETED, completePrivate.state)
            assertEquals(app.filesDir.absolutePath, completePrivate.sourceRoot)
        } finally {
            privateBook.deleteRecursively()
            index().forgetComic("nhentai", privateId)
        }
    }

    @Test fun structurallyMalformedFilesDoNotHideValidRecords() {
        val id = "valid-$nonce"
        book("nhentai", id, pages = 1, complete = true)
        val brokenBook = File(storage.ensure(StorageRoot.Mode.SHARED).comics, "nhentai/${hash("broken-$nonce")}")
        write(brokenBook, "book.json", """{"entry":null,"pageCount":1}""")
        File(storage.ensure(StorageRoot.Mode.SHARED).videoMeta, "null-site.json")
            .writeText("""{"kind":"video","site":null,"id":"broken","cacheKey":"x"}""")
        val found = runBlocking { index().scan({ _, _ -> false }) }
        assertEquals(1, found.count { it.id == id })
        assertTrue(found.none { it.id == "broken" })
    }

    @Test fun knownCompletedTasksStillRequireTheirActualCache() {
        val id = "known-$nonce"
        val known = LibraryRecord(site = "r34v", id = id, quality = "720p", cacheKey = "r34v:$id:720p")
        val found = runBlocking { index().scan({ _, _ -> false }, listOf(known)) }.single { it.id == id }
        assertEquals(LibraryRecord.STATE_MISSING, found.state)
        assertEquals(app.filesDir.absolutePath, found.sourceRoot)
    }

    @Test fun aVideoSidecarWithoutCacheIsMissingNotPlayable() {
        val entry = Video("orphan-$nonce", "缓存已被清理", "https://rule34video.com/video/$nonce/", site = "r34v")
        index().putVideo(entry, "1080p", 4096)
        val sidecar = index().videoSidecar(LibraryRecord(site = "r34v", id = entry.id, quality = "1080p"))
        assertTrue(sidecar.isFile)

        val missing = runBlocking { index().scan({ _, _ -> false }, emptyList()) }.single { it.id == entry.id }
        assertEquals("索引在但缓存没了，只能算缺失", LibraryRecord.STATE_MISSING, missing.state)

        // The moment the cache answers for that key the same record becomes playable, no reimport.
        val ready = runBlocking { index().scan({ key, _ -> key == "r34v:${entry.id}:1080p" }, emptyList()) }.single { it.id == entry.id }
        assertEquals(LibraryRecord.STATE_COMPLETED, ready.state)
        assertEquals("r34v:${entry.id}:1080p", ready.cacheKey)

        index().removeVideo("r34v", entry.id, "1080p")
        assertFalse("删除后不能被重新扫描出来", sidecar.isFile)
    }

    @Test fun corruptSidecarsAreDroppedInsteadOfBreakingTheCentre() {
        val broken = File(storage.ensure(StorageRoot.Mode.SHARED).videoMeta, "broken-$nonce.json").apply { writeText("{ not json") }
        assertTrue(index().dropCorruptSidecars() >= 1)
        assertFalse(broken.isFile)
    }

    @Test fun aSharedLibraryIsAdoptedWithoutTheUserPickingTheLocationAgain() {
        val id = "adopt-$nonce"
        book("nhentai", id, pages = 2, complete = true)
        // A reinstall: no preference at all, but the folder is there with real content.
        val fresh = StorageRoot(ins.targetContext, store, root)
        assertEquals(StorageRoot.Mode.PRIVATE, fresh.applied)
        assertEquals("有真实内容时自动采纳公共目录", StorageRoot.Mode.SHARED, fresh.effective())
        assertEquals(StorageRoot.Mode.SHARED, fresh.applied)
        val found = runBlocking { LibraryIndex(ins.targetContext, fresh, app.library.gson).scan({ _, _ -> false }) }.filter { it.id == id }
        assertEquals(1, found.size)
        assertEquals(LibraryRecord.STATE_COMPLETED, found.single().state)
    }

    @Test fun anEmptySharedFolderDoesNotHijackLaterDownloads() {
        storage.ensure(StorageRoot.Mode.SHARED)
        val fresh = StorageRoot(ins.targetContext, store, root)
        assertEquals("空目录不能改写用户的默认位置", StorageRoot.Mode.PRIVATE, fresh.effective())
    }

    @Test fun anUpgradingUserKeepsTheirExistingPrivateLocation() {
        // A previous install already has a book in private storage…
        val privateBook = File(File(app.filesDir, "gallery-downloads/ehentai"), "upgrade-$nonce").apply { mkdirs() }
        val entry = Video("upgrade-$nonce", "老用户的漫画", "https://e-hentai.org/g/upgrade-$nonce/", site = "ehentai")
        write(privateBook, "book.json", app.library.gson.toJson(GalleryDetail(entry, 1, emptyList(), 1)))
        try {
            // …and a leftover shared folder exists from something else.
            book("nhentai", "other-$nonce", pages = 1, complete = true)
            val fresh = StorageRoot(ins.targetContext, store, root)
            assertEquals("已有下载的老用户不能被悄悄搬走", StorageRoot.Mode.PRIVATE, fresh.effective())
        } finally {
            privateBook.deleteRecursively()
        }
    }

    @Test fun migrationCopiesAndLeavesTheOriginalInPlace() {
        val probe = File(app.filesDir, "gallery-downloads/hitomi/migrate-probe-$nonce").apply { mkdirs() }
        val payload = File(probe, "page.image").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        try {
            val report = runBlocking { storage.migrate(copyVideos = {}) }
            assertTrue(report.contains("已复制"))
            assertTrue("原文件必须保留，迁移不能变成移动", payload.isFile)
            assertEquals(2048L, payload.length())
            val copied = File(File(storage.ensure(StorageRoot.Mode.SHARED).comics, "hitomi"), "migrate-probe-$nonce/page.image")
            assertTrue("复制品应出现在公共目录", copied.isFile)
            assertEquals(2048L, copied.length())
        } finally {
            probe.deleteRecursively()
        }
    }
}
