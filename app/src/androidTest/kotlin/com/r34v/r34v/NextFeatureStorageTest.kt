package com.r34v.r34v

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NextFeatureStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    internal fun archive(name: String, corrupt: Boolean = false, dangerous: Boolean = false, shadeOffset: Int = 0): Pair<File, Uri> {
        val file = File(context.cacheDir, "updates/$name-${System.nanoTime()}.cbz").apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf(10, 2, 1).forEach { n ->
                zip.putNextEntry(ZipEntry(if (dangerous) "../../pages/$n.png" else "nested/pages/$n.png"))
                if (corrupt) zip.write("broken".toByteArray()) else {
                    val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(n + shadeOffset, n + shadeOffset, n + shadeOffset)) }
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle()
                }
                zip.closeEntry()
            }
        }
        return file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    @Test fun importedArchiveIsOrderedDeduplicatedAndReadableAfterReopening() = runBlocking {
        val (file, uri) = archive("local-test")
        val local = LocalLibrary(context)
        var id: String? = null
        try {
            val video = local.importArchive(uri); id = video.id
            assertEquals("local", video.site); assertEquals(MediaKind.GALLERY, video.mediaKind)
            assertEquals(video.id, local.importArchive(uri).id)
            val reopened = LocalLibrary(context)
            assertEquals(3, reopened.detail(video).pageCount)
            for ((page, expected) in listOf(1 to 1, 2 to 2, 3 to 10)) { val rendered = reopened.page(video.id, page); assertEquals(expected, Color.red(rendered.bitmap.getPixel(0,0))); rendered.bitmap.recycle() }
            assertFalse(File(context.filesDir, "pages").exists())
            assertFalse(File(context.filesDir, "local-library").listFiles().orEmpty().any { it.name.startsWith("incoming-") })
        } finally { id?.let { local.remove(it) }; file.delete() }
    }
    @Test fun corruptImportDoesNotPublishAPartialBook() = runBlocking {
        val (file, uri) = archive("local-corrupt", true); val local = LocalLibrary(context); val before = local.books().map { it.id }.toSet()
        try { assertTrue(runCatching { local.importArchive(uri) }.isFailure); assertEquals(before, local.books().map { it.id }.toSet()); assertFalse(File(context.filesDir, "local-library").listFiles().orEmpty().any { it.name.startsWith("incoming-") }) }
        finally { file.delete() }
    }
    @Test fun archivePathsCannotWriteOutsideThePrivateLibrary() = runBlocking {
        val (file, uri) = archive("local-paths", dangerous = true); val local = LocalLibrary(context)
        var imported: Video? = null
        try {
            val result = runCatching { local.importArchive(uri) }; imported = result.getOrNull()
            if (android.os.Build.VERSION.SDK_INT >= 34) assertTrue(result.isFailure)
            assertFalse(File(context.filesDir, "pages").exists())
        } finally { imported?.let { local.remove(it.id) }; file.delete() }
    }
    @Test fun savedSearchAndQueueSurviveReopeningAndDoNotMixSources() {
        val store = LibraryStore(context, "next-storage-${System.nanoTime()}", "no-legacy-${System.nanoTime()}")
        try {
            val search = SavedSearch(name = "测试条件", query = "test", sources = listOf("r34v", "nhentai"), category = "COMIC", languages = mapOf("nhentai" to "CHINESE"))
            store.personal.saveSearch(search); store.personal.saveSmart(SmartShelf(name = "离线未看", rule = SmartRule.OFFLINE_UNSEEN))
            store.set("reduce_transparency", true)
            val backup = store.backupSnapshot()
            assertEquals("true", backup.settings["reduce_transparency"])
            store.set("reduce_transparency", false); store.restoreBackup(backup)
            assertTrue(store.bool("reduce_transparency"))
            assertEquals(ReaderMode.LEFT_TO_RIGHT, store.personal.readerMode("4khd"))
            store.personal.setReaderMode("4khd", ReaderMode.STRIP)
            assertEquals(ReaderMode.STRIP, PersonalStore(store).readerMode("4khd"))
            val a = Video("same", site = "r34v"); val b = Video("same", site = "nhentai", mediaKind = MediaKind.GALLERY)
            val queue = PlaybackQueueStore(store).start("测试片单", PersonalLibrary.WATCH_LATER, listOf(a,b))
            val reopened = PlaybackQueueStore(store)
            assertEquals(search, PersonalStore(store).snapshot().savedSearches.single()); assertEquals(1, PersonalStore(store).snapshot().smartShelves.size)
            assertNull(reopened.session("unrelated", a)); assertNull(reopened.session(queue.id, b))
            assertEquals(b.entryKey, reopened.advance(queue.id, a)!!.current!!.entryKey); assertNull(reopened.advance(queue.id, a))
            assertEquals(b.entryKey, PlaybackQueueStore(store).session(queue.id, b)!!.current!!.entryKey)
            reopened.end(queue.id); assertNull(reopened.snapshot())
        } finally { store.prefs.edit().clear().commit() }
    }
    @Test fun markingAnOldShelfEntryCompleteDoesNotChangeItsAddedDate() {
        val store = LibraryStore(context, "next-date-${System.nanoTime()}", "no-legacy-${System.nanoTime()}")
        try {
            val old = System.currentTimeMillis() - 60L * 86400000
            val video = Video("old", site = "r34v")
            val data = PersonalLibrary(shelves = listOf(Shelf(PersonalLibrary.WATCH_LATER, "稍后看", listOf(ShelfEntry(video, at = old)))))
            store.prefs.edit().putString(PersonalStore.KEY, store.gson.toJson(data)).commit()
            store.personal.complete(video, PersonalLibrary.WATCH_LATER, true)
            val value = store.personal.shelves().single().entries.single()
            assertTrue(value.completed); assertEquals(old, value.addedAt); assertTrue(value.at > old)
        } finally { store.prefs.edit().clear().commit() }
    }
}
