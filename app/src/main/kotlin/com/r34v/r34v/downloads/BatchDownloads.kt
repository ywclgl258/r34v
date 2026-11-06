package com.r34v.r34v.downloads

import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class BatchProgress(val total: Int = 0, val processed: Int = 0, val added: Int = 0, val errors: List<String> = emptyList(), val running: Boolean = false)

class BatchDownloads(private val app: R34Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    val progress = MutableStateFlow(BatchProgress())
    fun start(videos: List<Video>) {
        check(job?.isActive != true) { "已有一批作品正在加入下载，请在下载中心查看" }
        val entries = videos.distinctBy { it.entryKey }
        progress.value = BatchProgress(total = entries.size, running = true)
        job = scope.launch {
            try {
                for (video in entries) {
                    ensureActive()
                    try {
                        if (SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY) app.galleryDownloads.add(video)
                        else {
                            val detail = app.repository.detail(video.url)
                            val preferred = app.library.string("quality", "720p")
                            val height = Regex("\\d+").find(preferred)?.value?.toIntOrNull() ?: 720
                            val stream = detail.streams.firstOrNull { it.label == preferred }
                                ?: detail.streams.filter { it.height <= height }.maxByOrNull { it.height }
                                ?: detail.streams.minByOrNull { it.height } ?: error("没有可下载的片源")
                            app.playback.add(video, stream)
                        }
                        progress.value = progress.value.let { it.copy(processed = it.processed + 1, added = it.added + 1) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        progress.value = progress.value.let { it.copy(processed = it.processed + 1,
                            errors = it.errors + "${SiteRegistry.byIdOrDefault(video.site).name} · ${video.title}：${e.message ?: "添加失败"}") }
                    }
                }
            } finally { progress.value = progress.value.copy(running = false) }
        }
    }
    fun cancel() { job?.cancel() }
}
