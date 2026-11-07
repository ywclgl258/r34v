package com.r34v.r34v

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.GalleryExportModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryExportSystemUiTest : ComposeUiCase() {
    @Test fun selectedOriginalsSaveThroughRealSystemDirectoryPicker(): Unit = ComposeFixture().use { f ->
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = f.app.contentResolver
        val path = "Download/r34v-export-${f.nonce}"
        val seed = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "placeholder.txt"); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain"); put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        })!!
        resolver.openOutputStream(seed)!!.use { it.write("fixture".toByteArray()) }
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$path")
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$path")
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
                return null
            }
        }
        f.ins.addMonitor(monitor)
        try {
            val activity = f.launchGallery(read = true)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
            readerTool("图片保存、分享与 CBZ"); clickText("选择图片保存")
            ui.onNode(hasSetTextAction()).performTextReplacement("1,3"); clickText("确定")
            // Keep the Compose test clock advancing while preparation triggers the picker effect.
            ui.waitUntil(15000) { var opening = false; f.ins.runOnMainSync { opening = ViewModelProvider(activity)[GalleryExportModel::class.java].delivering }; opening }
            systemButton(f, "Use this folder"); systemButton(f, "Allow")
            ui.waitUntil(15000) { var done = false; f.ins.runOnMainSync { val model = ViewModelProvider(activity)[GalleryExportModel::class.java]; done = !model.busy && model.prepared == null }; done }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "primary:$path")
            val names = mutableListOf<String>()
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name.endsWith(".png")) {
                        names += name
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                        assertEquals("image/png", com.r34v.r34v.data.GalleryExporter.imageMime(bytes))
                    }
                }
            }
            assertEquals(2, names.size); assertTrue(names.any { it.endsWith("000001.png") }); assertTrue(names.any { it.endsWith("000003.png") })
            f.shot("batch-system-export")
        } finally {
            f.ins.removeMonitor(monitor)
            runCatching { DocumentsContract.deleteDocument(resolver, folder) }
            runCatching { resolver.delete(seed, null, null) }
        }
    }
    private fun systemButton(f: ComposeFixture, text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            f.ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty().filter { it.isEnabled && it.isVisibleToUser }.forEach { node ->
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(100)
        }
        f.shot("batch-picker-timeout")
        fun dump(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 15) return
            println("PICKER $depth ${node.packageName} ${node.text} ${node.contentDescription} ${node.viewIdResourceName} visible=${node.isVisibleToUser} enabled=${node.isEnabled}")
            (0 until node.childCount).forEach { dump(node.getChild(it), depth + 1) }
        }
        dump(f.ins.uiAutomation.rootInActiveWindow)
        error("System document picker button not found: $text")
    }
}
