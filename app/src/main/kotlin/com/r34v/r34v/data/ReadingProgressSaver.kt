package com.r34v.r34v.data

import kotlinx.coroutines.*

/** Coalesce scrolling writes; lifecycle exits flush the latest position synchronously. */
internal class ReadingProgressSaver(private val scope: CoroutineScope, private val save: (ReadingProgress) -> Unit) {
    private var pending: ReadingProgress? = null
    private var last: ReadingProgress? = null
    private var job: Job? = null
    @Synchronized fun record(value: ReadingProgress) {
        if (last?.let { it.entry == value.entry && it.page == value.page && it.pageUrl == value.pageUrl && it.offset == value.offset } == true) return
        last = value; pending = value
        if (job?.isActive != true) {
            job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { delay(750); flush() }
            job?.start()
        }
    }
    @Synchronized fun flush() {
        job?.cancel(); job = null
        pending?.let { save(it); pending = null }
    }
}
