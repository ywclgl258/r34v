package com.r34v.r34v

import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

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
}
