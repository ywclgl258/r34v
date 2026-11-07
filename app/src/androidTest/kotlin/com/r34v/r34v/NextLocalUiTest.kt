package com.r34v.r34v

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextLocalUiTest : ComposeUiCase() {
    @Test fun missingLocalFilesCanBeReimportedWithoutLosingProgressOrBookmarks() = ComposeFixture().use { f ->
        val (file, uri) = NextFeatureStorageTest().archive("local-recovery", shadeOffset = 70)
        val video = runBlocking { f.app.localLibrary.importArchive(uri) }
        val monitor = f.ins.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        try {
            f.app.library.saveReading(ReadingProgress(video, 2, 3, "r34local://${video.id}/2"))
            f.app.library.personal.toggleBookmark(video, 2)
            runBlocking { f.app.localLibrary.remove(video.id) }
            f.launch(); waitHome()
            f.ins.startActivitySync(Intent(f.ins.targetContext, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("gallery", f.app.library.gson.toJson(video)).putExtra("read", true))
            waitText("本地文件不存在，请重新导入同一文件或目录")
            ui.onNodeWithText("重新选择文件 / 目录").performScrollTo().performClick()
            waitText("导入本地漫画"); clickText("选择 CBZ / ZIP 文件")
            ui.waitUntil(20000) { ui.onAllNodesWithTag("reader-page-2").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("2 / 3").assertIsDisplayed()
            assertEquals(2, f.app.library.reading(video.id, "local")!!.page)
            assertEquals(2, f.app.library.personal.bookmarks(video).single().page)
            assertTrue(f.galleryCalls.isEmpty())
            f.shot("liquid-local-recovery")
        } finally { f.ins.removeMonitor(monitor); runBlocking { f.app.localLibrary.remove(video.id) }; file.delete() }
    }
    @Test fun selectedArchiveOpensTheExistingReaderWithoutAnySiteRequests() = ComposeFixture().use { f ->
        val before = f.app.localLibrary.books().map { it.id }.toSet()
        val (file, uri) = NextFeatureStorageTest().archive("local-ui")
        val monitor = f.ins.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        try {
            f.launch(); ui.waitUntil(15000) { ui.onAllNodesWithTag("personal-home").fetchSemanticsNodes().isNotEmpty() }
            clickText("片库"); ui.onNodeWithTag("library-hub").performScrollToNode(hasText("导入 CBZ / ZIP / 图片目录"))
            clickText("导入 CBZ / ZIP / 图片目录"); clickText("选择 CBZ / ZIP 文件")
            // A repeated import intentionally reopens the existing copy and saved page.
            ui.waitUntil(20000) { ui.onAllNodesWithContentDescription("阅读工具").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(f.galleryCalls.isEmpty()); assertTrue(f.app.localLibrary.books().count { it.id !in before } in 0..1)
            assertTrue(listOf("1 / 3", "2 / 3", "3 / 3").any { ui.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() })
            f.shot("next-local-reader")
        } finally {
            f.ins.removeMonitor(monitor)
            if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("retainLocalReviewSample") != "true") runBlocking { f.app.localLibrary.books().filter { it.id !in before }.forEach { f.app.localLibrary.remove(it.id) } }
            file.delete()
        }
    }
    @Test fun systemDocumentTreeImportsImagesInNaturalOrder() = ComposeFixture().use { f ->
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = f.app.contentResolver
        val path = "Download/r34v-dir-${f.nonce}"
        val documents = mutableListOf<android.net.Uri>()
        val before = f.app.localLibrary.books().map { it.id }.toSet()
        try {
            for (page in listOf(10, 2, 1)) {
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$page.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                })!!; documents += uri
                val image = Bitmap.createBitmap(21, 31, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(page, page, page)) }
                resolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$path")
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$path"))
                    return null
                }
            }
            f.ins.addMonitor(monitor)
            try {
                f.launch(); ui.waitUntil(15000) { ui.onAllNodesWithTag("personal-home").fetchSemanticsNodes().isNotEmpty() }
                clickText("片库"); ui.onNodeWithText("导入 CBZ / ZIP / 图片目录").performScrollTo().performClick(); clickText("选择图片目录")
                systemButton(f, "Use this folder")
                systemButton(f, "Allow", readerMayAlreadyBeOpen = true)
                ui.waitUntil(20000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
            } finally { f.ins.removeMonitor(monitor) }
            val video = f.app.localLibrary.books().first { it.id !in before }.let(f.app.localLibrary::video)
            assertEquals(3, f.app.localLibrary.detail(video).pageCount)
            runBlocking { val first = f.app.localLibrary.page(video.id, 1); assertEquals(1, Color.red(first.bitmap.getPixel(0,0))); first.bitmap.recycle() }
        } finally {
            runBlocking { f.app.localLibrary.books().filter { it.id !in before }.forEach { f.app.localLibrary.remove(it.id) } }
            documents.forEach { resolver.delete(it, null, null) }
        }
    }
    private fun systemButton(f: ComposeFixture, text: String, readerMayAlreadyBeOpen: Boolean = false) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val nodes = f.ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (target in nodes.filter { it.isEnabled && it.isVisibleToUser }) {
                var clickable: android.view.accessibility.AccessibilityNodeInfo? = target
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            if (readerMayAlreadyBeOpen && runCatching { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)) return
            android.os.SystemClock.sleep(100)
        }
        error("System document picker button not found: $text")
    }
}
