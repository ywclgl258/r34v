package com.r34v.r34v

import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ReadingProgressSaverTest {
    @Test fun hundredsOfOffsetsProduceOneWriteAndExitFlushesTheLatestPosition() = runBlocking {
        val writes = CopyOnWriteArrayList<ReadingProgress>()
        val saver = ReadingProgressSaver(this) { writes += it }
        val entry = Video("reader", site = "ehentai")
        repeat(500) { saver.record(ReadingProgress(entry, 1, 100, "page", offset = -it)) }
        assertEquals(0, writes.size)
        withTimeout(2000) { while (writes.isEmpty()) delay(10) }
        assertEquals(1, writes.size); assertEquals(-499, writes.single().offset)
        saver.record(ReadingProgress(entry, 2, 100, "page-2", offset = -123))
        saver.flush()
        assertEquals(2, writes.size); assertEquals(2, writes.last().page); assertEquals(-123, writes.last().offset)
        saver.record(writes.last().copy(at = System.currentTimeMillis() + 1)); saver.flush()
        assertEquals("Lifecycle flushes of an unchanged position must not rewrite history", 2, writes.size)
    }

    @Test fun failedDiskWriteRetainsLatestPendingValueWithoutCrashing() = runBlocking {
        val attempts = AtomicInteger()
        val writes = CopyOnWriteArrayList<ReadingProgress>()
        val entry = Video("reader", site = "ehentai")
        val original = ReadingProgress(entry, 7, 12, "page-7", offset = -120)
        val saver = ReadingProgressSaver(this) { value ->
            if (attempts.incrementAndGet() <= 2) throw IOException("disk full")
            writes += value
        }
        saver.record(original)
        saver.flush()
        assertTrue(writes.isEmpty())
        saver.record(original)
        saver.flush()
        assertTrue(writes.isEmpty())
        // A failed exit may record an unchanged position on the next launch.
        saver.record(original)
        saver.flush()
        assertEquals(3, attempts.get())
        assertEquals(original.copy(at = writes.single().at), writes.single())
    }

    @Test fun failedOlderOffsetCannotOverwriteLatestPosition() = runBlocking {
        val values = CopyOnWriteArrayList<ReadingProgress>()
        val saver = ReadingProgressSaver(this) { values += it }
        val entry = Video("reader", site = "ehentai")
        val progress = ReadingProgress(entry, 4, 8, "page-4", offset = -80)
        saver.record(progress)
        saver.record(progress.copy(page = 5, pageUrl = "page-5", offset = 0))
        saver.flush()
        assertEquals(5, values.single().page)
        saver.flush()
        assertEquals(1, values.size)
    }

    @Test fun canceledBackgroundWriteStaysPendingButLifecycleFlushStillSaves() = runBlocking {
        val calls = AtomicInteger()
        val job = SupervisorJob()
        val saver = ReadingProgressSaver(CoroutineScope(job)) { calls.incrementAndGet() }
        val value = ReadingProgress(Video("reader", site = "ehentai"), 1, 8, "page")
        saver.record(value)
        job.cancel()
        delay(850)
        assertEquals(0, calls.get())
        saver.flush()
        assertEquals("Lifecycle exit still needs a synchronous final write", 1, calls.get())
    }
}
