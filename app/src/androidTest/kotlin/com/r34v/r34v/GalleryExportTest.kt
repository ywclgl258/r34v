package com.r34v.r34v

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class GalleryExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(shade: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(shade, shade, shade)) }
            .let { it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle() }
        output.toByteArray()
    }
    @Test fun cbzKeepsOriginalBytesAndNumericPageOrderAndCanBeImported(): Unit = runBlocking {
        val root = File(context.cacheDir, "exports/test-${System.nanoTime()}")
        val exporter = GalleryExporter(root); val local = LocalLibrary(context)
        val bytes = mapOf(1 to png(91), 2 to png(92), 10 to png(100))
        var imported: Video? = null
        try {
            val prepared = exporter.prepare("../bad:/title", listOf(10, 2, 1), ExportMode.CBZ, { bytes.getValue(it) })
            val archive = prepared.files.single().file
            assertTrue(archive.canonicalPath.startsWith(root.canonicalPath))
            ZipFile(archive).use { zip ->
                assertEquals(listOf("000001.png", "000002.png", "000010.png"), zip.entries().asSequence().map { it.name }.toList())
                bytes.forEach { (page, original) -> assertArrayEquals(original, zip.getInputStream(zip.getEntry("%06d.png".format(page))).readBytes()) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
            imported = local.importArchive(uri)
            assertEquals(3, local.detail(imported!!).pageCount)
            assertArrayEquals(bytes.getValue(10), File(java.net.URI(local.imageUri(imported!!.id, 3)!!)).readBytes())
        } finally { imported?.let { local.remove(it.id) }; root.deleteRecursively() }
    }
    @Test fun selectedImagesUseOriginalEncodingAndShareableContentUris(): Unit = runBlocking {
        val root = File(context.cacheDir, "exports/test-${System.nanoTime()}"); val bytes = png(111)
        try {
            val result = GalleryExporter(root).prepare("图片", listOf(2, 4), ExportMode.SHARE_IMAGES, { bytes })
            assertEquals(2, result.files.size)
            result.files.forEach {
                assertEquals("image/png", it.mime); assertEquals("png", it.file.extension)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it.file)
                assertEquals("content", uri.scheme)
                context.contentResolver.openInputStream(uri)!!.use { stream -> assertArrayEquals(bytes, stream.readBytes()) }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun supportedModernImagesKeepBytesAndContainerTypeThroughCbz() = runBlocking {
        val formats = listOf("avif", "heic", "gif", "webp")
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        for (format in formats) {
            val original = testAssets.open("gallery-formats/neutral.$format").use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(original, 0, original.size, options)
            if (options.outWidth <= 0) {
                // HEIF is optional on older devices; a truncated container is still never accepted.
                val truncated = runCatching {
                    GalleryExporter(File(context.cacheDir, "exports/skip-${System.nanoTime()}")).prepare("truncated", listOf(1),
                        ExportMode.CBZ, { original.copyOf(original.size - 8) })
                }
                assertTrue(format, truncated.isFailure)
                continue
            }
            val root = File(context.cacheDir, "exports/$format-${System.nanoTime()}")
            val local = LocalLibrary(context)
            var imported: Video? = null
            try {
                val prepared = GalleryExporter(root).prepare("modern", listOf(1), ExportMode.CBZ, { original })
                val archive = prepared.files.single().file
                val expected = if (format == "avif" || galleryContainerMime(original) == "image/avif") "avif" else format
                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("000001.$expected") ?: error("missing $expected in ${archive.length()}")
                    assertArrayEquals(original, zip.getInputStream(entry).readBytes())
                }
                imported = local.importArchive(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive))
                assertArrayEquals(original, File(java.net.URI(local.imageUri(imported.id, 1)!!)).readBytes())
                assertTrue(format, isCompleteGalleryImage(File(java.net.URI(local.imageUri(imported.id, 1)!!))))
            } finally { imported?.let { local.remove(it.id) }; root.deleteRecursively() }
        }
    }

    @Test fun failedOrCancelledExportRemovesPartialArchive(): Unit = runBlocking {
        val root = File(context.cacheDir, "exports/test-${System.nanoTime()}"); val exporter = GalleryExporter(root)
        try {
            val failed = runCatching { exporter.prepare("broken", listOf(1, 2), ExportMode.CBZ, { if (it == 2) error("missing") else png(121) }) }
            assertTrue(failed.isFailure); assertTrue(root.listFiles().orEmpty().isEmpty())
            val entered = CompletableDeferred<Unit>()
            val job = launch { exporter.prepare("cancelled", listOf(1, 2), ExportMode.CBZ, { entered.complete(Unit); awaitCancellation() }) }
            entered.await(); job.cancelAndJoin()
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertTrue(runCatching { exporter.prepare("invalid", listOf(1), ExportMode.CBZ, { "not an image".toByteArray() }) }.isFailure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
