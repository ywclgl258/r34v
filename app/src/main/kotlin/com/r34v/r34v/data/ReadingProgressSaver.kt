package com.r34v.r34v.data

import kotlinx.coroutines.*

/** Coalesce scrolling writes; lifecycle exits flush the latest position synchronously. */
internal class ReadingProgressSaver(private val scope: CoroutineScope, private val save: (ReadingProgress) -> Unit) {
    private var pending: ReadingProgress? = null
    private var lastSaved: ReadingProgress? = null
    private var job: Job? = null

    @Synchronized fun record(value: ReadingProgress) {
        val latest = pending ?: lastSaved
        if (latest == null || latest.entry != value.entry || latest.page != value.page ||
            latest.pages != value.pages || latest.pageUrl != value.pageUrl || latest.offset != value.offset
        ) {
            pending = value
        }
        if (pending != null && job?.isActive != true) {
            job = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                delay(750)
                synchronized(this@ReadingProgressSaver) {
                    if (job !== coroutineContext[Job]) return@synchronized
                    job = null
                    writePending(coroutineContext[Job])
                }
            }
        }
    }

    @Synchronized fun flush() {
        job?.cancel()
        job = null
        writePending(null)
    }

    private fun writePending(owner: Job?) {
        val value = pending ?: return
        if (owner != null && !owner.isActive) return
        try {
            save(value)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the latest position available to the next scheduled write or lifecycle flush.
            return
        }
        lastSaved = value
        pending = null
    }
}
