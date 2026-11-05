package com.r34v.r34v

import android.os.FileObserver
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OptimizationIoTest : ComposeUiCase() {
    @Suppress("DEPRECATION")
    @Test fun idleProgressTicksDoNotRescanBooksButInvalidationDoes() = ComposeFixture().use { f ->
        f.launch(); waitHome()
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")) }
        ui.waitUntil(20000) { f.app.galleryDownloads.task(f.video("ehentai"))?.state == GalleryDownloadState.COMPLETED }
        f.launchDownloads()
        ui.waitUntil(15000) { ui.onAllNodesWithText("已下载 · 可离线打开", substring = true).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { delay(1200) }
        val id = MessageDigest.getInstance("SHA-256").digest(f.video("ehentai").id.toByteArray()).joinToString("") { "%02x".format(it) }
        val book = File(f.app.storage.layout().comics, "ehentai/$id")
        val reads = AtomicInteger()
        val observer = object : FileObserver(book.absolutePath, OPEN) {
            override fun onEvent(event: Int, path: String?) { if (path == "book.json") reads.incrementAndGet() }
        }
        observer.startWatching()
        try {
            runBlocking { delay(2400) }
            val idleReads = reads.get()
            assertEquals("Three idle progress ticks must not reopen the book index", 0, idleReads)
            f.app.index.invalidate()
            ui.waitUntil(10000) { reads.get() > 0 }
            println("Optimization I/O: idle book-index reads in 2400ms=$idleReads; explicit invalidation reads=${reads.get()}")
        } finally { observer.stopWatching() }
    }
    @Test fun scrollingCoalescesHistoryWritesAndBackFlushesTheFinalPosition() = ComposeFixture().use { f ->
        f.app.library.switchSite("ehentai"); f.launchGallery(read = true)
        ui.waitUntil(15000) { f.imageCalls.containsAll(listOf(1, 2, 3, 4)) }
        val writes = AtomicInteger()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "ehentai:reading_history_v1") writes.incrementAndGet()
        }
        f.app.library.prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            repeat(3) { ui.onNodeWithTag("comic-strip").performTouchInput { swipeUp(durationMillis = 300) } }
            back(f)
            assertTrue(f.app.library.reading(f.video("ehentai").id, "ehentai")!!.page >= 2)
            assertTrue("Scrolling must not write history once per frame", writes.get() <= 8)
            println("Optimization I/O: three reader gestures plus exit writes=${writes.get()}")
        } finally { f.app.library.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
