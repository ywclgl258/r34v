package com.r34v.r34v

import com.r34v.r34v.data.HostGate
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class HostGateTest {
    @Test fun startsAreStaggeredByTheGapAndCatchUpAfterIdle() {
        val gate = HostGate()
        assertEquals(0L, gate.reserve(1_000, 400))
        assertEquals(400L, gate.reserve(1_000, 400))
        assertEquals(0L, gate.reserve(1_800, 400))      // 恰好在已预约的起点上到达
        assertEquals(399L, gate.reserve(1_801, 400))    // 晚到 1ms，等待相应缩短
        assertEquals(0L, gate.reserve(9_000, 400))      // 空闲之后立即放行
    }

    @Test fun imageRequestsReserveTheNextSlotWithTheirOwnGap() {
        val gate = HostGate()
        assertEquals(0L, gate.reserve(0, 400))     // 页面
        assertEquals(400L, gate.reserve(0, 150))   // 随后到达的图片排在页面之后
        assertEquals(150L, gate.reserve(400, 150)) // 图片槽位还没到
        assertEquals(0L, gate.reserve(700, 150))   // 到达时图片槽位已就绪
    }

    @Test fun concurrentReserversNeverShareASlot() {
        val gate = HostGate()
        val waits = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val threads = (1..8).map { thread {
            waits += gate.reserve(0, 100)
        } }
        threads.forEach { it.join() }
        // 8 个并发起点必须落在 8 个互不相同的槽位上。
        assertEquals((0L..7L).map { it * 100 }.sorted(), waits.sorted())
    }
}
