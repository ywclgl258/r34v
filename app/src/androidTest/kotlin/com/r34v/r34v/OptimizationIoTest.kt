@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private fun countDownloadQueries(f: ComposeFixture, check: (WritableDownloadIndex, AtomicInteger) -> Unit) {
        val manager = f.app.playback.manager
        val field = DownloadManager::class.java.getDeclaredField("downloadIndex").apply { isAccessible = true }
        val original = field.get(manager) as WritableDownloadIndex
        val reads = AtomicInteger()
        val counting = object : WritableDownloadIndex by original {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                reads.incrementAndGet()
                return original.getDownloads(*states)
            }
        }
        f.ins.runOnMainSync { field.set(manager, counting) }
        try { check(original, reads) } finally { f.ins.runOnMainSync { field.set(manager, original) } }
    }

    @Test fun idleDownloadCenterQueriesOnlyOnEvents() = ComposeFixture().use { f ->
        countDownloadQueries(f) { index, reads ->
            val args = InstrumentationRegistry.getArguments()
            val interval = args.getString("downloadIdleProbeMs")?.toLong() ?: 3500L
            val rows = args.getString("downloadQueueProbeRows")?.toIntOrNull()?.coerceIn(0, 500) ?: 0
            val sampleTitle = "队列样本 ${f.nonce}"
            val samples = (1..rows).map { number ->
                val clip = f.video("r34v", index = number).copy(title = "$sampleTitle $number")
                val request = DownloadRequest.Builder(f.app.playback.key(clip, "720p"), Uri.parse(clip.url))
                    .setData(f.app.library.gson.toJson(DownloadMeta(clip, "720p", clip.site)).toByteArray()).build()
                val time = System.currentTimeMillis() + number
                Download(request, Download.STATE_STOPPED, time, time, -1, 1, Download.FAILURE_REASON_NONE)
            }
            try {
                samples.dropLast(1).forEach(index::putDownload)
                f.launchDownloads(); waitText("下载中心")
                ui.waitUntil(15000) { reads.get() > 0 }
                val center = ui.onNodeWithTag("download-center")
                fun waitForProbeCount(count: Int) {
                    val label = if (count > 0) "$count 项下载" else "没有符合条件的下载"
                    ui.waitUntil(15000) {
                        runCatching { center.performScrollToIndex(1) }.isSuccess &&
                            ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                    }
                }
                if (samples.isNotEmpty()) {
                    ui.onNode(hasSetTextAction()).performTextReplacement(sampleTitle)
                    closeKeyboard(f)
                    waitForProbeCount(rows - 1)
                } else {
                    ui.waitUntil(15000) { runCatching { center.performScrollToNode(hasText("重新扫描")) }.isSuccess }
                    center.performScrollToIndex(0)
                }
                val initial = reads.get()
                val start = SystemClock.elapsedRealtime()
                ui.waitUntil(interval + 5000) { SystemClock.elapsedRealtime() - start >= interval }
                val idleReads = reads.get() - initial
                println("Download index reads over ${interval}ms idle: $idleReads")
                val beforeRefresh = reads.get()
                val marker = samples.lastOrNull()
                marker?.let(index::putDownload)
                val refreshStarted = SystemClock.elapsedRealtime()
                f.app.index.invalidate()
                ui.waitUntil(10000) { reads.get() > beforeRefresh }
                if (marker != null) {
                    waitForProbeCount(rows)
                    println("Download queue refresh for $rows rows: ${SystemClock.elapsedRealtime() - refreshStarted}ms")
                }
                assertEquals("The idle download center must not query the video index", 0, idleReads)
            } finally { samples.forEach { index.removeDownload(it.request.id) } }
        }
    }

    @Test fun onlyTransferringVideoRefreshesProgressWithoutQueryingTheIndex() = ComposeFixture().use { f ->
        countDownloadQueries(f) { _, reads ->
            val clip = f.video("r34v")
            val stream = runBlocking { f.app.repository.detail(clip.url).streams.first() }
            val key = f.app.playback.key(clip, stream.label)
            val row = hasTestTag("download-video:$key")
            val status = hasAnyAncestor(row) and hasText("下载中", substring = true)
            fun percent(): Int = ui.onAllNodes(status).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                ?.substringAfter("下载中 · ")?.substringBefore('%')?.toIntOrNull() ?: 0
            f.videoThrottleMillis = 240
            try {
                f.launchDownloads(); waitText("下载中心")
                f.ins.runOnMainSync { f.app.playback.add(clip, stream) }
                val center = ui.onNodeWithTag("download-center")
                ui.waitUntil(15000) { runCatching { center.performScrollToNode(row) }.isSuccess }
                ui.waitUntil(10000) { percent() > 0 }
                val firstPercent = percent()
                val activeReads = reads.get()
                ui.waitUntil(10000) { percent() > firstPercent }
                assertEquals("Progress updates must use the running transfer, without another index query", activeReads, reads.get())

                f.ins.runOnMainSync { f.app.playback.pause(key, true) }
                val paused = hasAnyAncestor(row) and hasText("已暂停", substring = true)
                ui.waitUntil(10000) { ui.onAllNodes(paused).fetchSemanticsNodes().isNotEmpty() }
                val pausedReads = reads.get()
                val pauseStarted = SystemClock.elapsedRealtime()
                ui.waitUntil(8000) { SystemClock.elapsedRealtime() - pauseStarted >= 2400 }
                assertEquals("A paused transfer must not cause periodic queries", pausedReads, reads.get())

                f.ins.runOnMainSync { f.app.playback.pause(key, false) }
                ui.waitUntil(10000) { percent() > 0 }
                val complete = hasAnyAncestor(row) and hasText("已完成 ·", substring = true)
                ui.waitUntil(20000) { ui.onAllNodes(complete).fetchSemanticsNodes().isNotEmpty() }
                f.ins.runOnMainSync { f.app.playback.remove(key) }
                ui.waitUntil(10000) { ui.onAllNodes(row).fetchSemanticsNodes().isEmpty() }
            } finally {
                f.ins.runOnMainSync { f.app.playback.remove(key) }
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun idleProgressTicksDoNotRescanBooksButInvalidationDoes() = ComposeFixture().use { f ->
        f.launch(); waitHome()
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")) }
        ui.waitUntil(20000) { f.app.galleryDownloads.task(f.video("ehentai"))?.state == GalleryDownloadState.COMPLETED }
        f.launchDownloads()
        val row = hasTestTag("download-gallery:${f.app.galleryDownloads.key(f.video("ehentai"))}")
        val complete = hasAnyAncestor(row) and hasText("已完成 ·", substring = true)
        ui.onNodeWithTag("download-center").performScrollToNode(row)
        ui.waitUntil(15000) { ui.onAllNodes(complete).fetchSemanticsNodes().isNotEmpty() }
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
        f.app.library.switchSite("ehentai")
        val activity = f.launchGallery(read = true)
        ui.waitUntil(15000) { f.imageCalls.containsAll(listOf(1, 2, 3, 4)) }
        val writes = AtomicInteger()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "ehentai:reading_history_v1") writes.incrementAndGet()
        }
        f.app.library.prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            repeat(3) { ui.onNodeWithTag("comic-strip").performTouchInput { swipeUp(durationMillis = 300) } }
            val expectedPage = (2..8).single { ui.onAllNodesWithText("$it / 8").fetchSemanticsNodes().isNotEmpty() }
            val offset = GalleryActivity::class.java.getDeclaredField("stripOffset").apply { isAccessible = true }
            val expectedOffset = ui.runOnIdle { offset.getInt(activity) }
            assertTrue(f.ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            ui.waitUntil(5000) { ui.onAllNodesWithTag("comic-strip").fetchSemanticsNodes().isEmpty() }
            val saved = checkNotNull(f.app.library.reading(f.video("ehentai").id, "ehentai"))
            assertEquals(expectedPage, saved.page)
            assertEquals(expectedOffset, saved.offset)
            assertTrue("Scrolling must not write history once per frame", writes.get() <= 8)
            println("Optimization I/O: reader exit page=${saved.page}, offset=${saved.offset}, writes=${writes.get()}")
        } finally { f.app.library.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun backBeforeFirstPageDecodeFlushesPendingPosition() = ComposeFixture().use { f ->
        f.imageDelayMillis = 5000
        f.app.library.switchSite("ehentai"); f.launchGallery(read = true)
        ui.waitUntil(15000) { f.imageCalls.contains(1) }
        back(f)
        try { ui.waitUntil(5000) { f.app.library.reading(f.video("ehentai").id, "ehentai") != null } }
        catch (e: Throwable) { f.shot("pending-progress-timeout"); throw e }
        assertNotNull("Exit must save the current position before the first bitmap arrives",
            f.app.library.reading(f.video("ehentai").id, "ehentai"))
    }
}
