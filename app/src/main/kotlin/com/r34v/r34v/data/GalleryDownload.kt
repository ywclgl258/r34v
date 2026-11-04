package com.r34v.r34v.data

enum class GalleryDownloadState { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED, REMOVING }

data class GalleryDownload(
    val id: String,
    val entry: Video,
    val state: GalleryDownloadState = GalleryDownloadState.QUEUED,
    val pages: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val error: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)
