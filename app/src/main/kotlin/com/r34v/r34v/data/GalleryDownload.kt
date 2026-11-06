package com.r34v.r34v.data

enum class GalleryDownloadState { QUEUED, DOWNLOADING, PAUSED, RETRY_WAIT, FAILED, COMPLETED, REMOVING }
enum class GalleryFailureKind { NETWORK, RATE_LIMIT, AUTH, NOT_FOUND, QUOTA, STORAGE, TLS, SOURCE, UNKNOWN }
enum class GalleryDownloadStage { PREPARING, RESOLVING_PAGE, DOWNLOADING_PAGE, COMMITTING }

data class GalleryDownload(
    val id: String,
    val entry: Video,
    val state: GalleryDownloadState = GalleryDownloadState.QUEUED,
    val pages: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val error: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val completedPages: List<Int>? = null,
    val selectedPages: List<Int>? = null,
    val failure: GalleryFailureKind? = null,
    val stage: GalleryDownloadStage? = null,
    val retryAt: Long = 0,
    val priority: Int = 0,
    val group: String? = null,
) {
    val savedPages: Set<Int> get() = completedPages.orEmpty().toSet()
    val partial: Boolean get() = !selectedPages.isNullOrEmpty() && total > 0 && selectedPages!!.size < total
    val requestedPages: Set<Int>? get() = selectedPages?.toSet()
}

class GalleryResponseException(val status: Int, val retryAfter: String? = null, message: String) : com.r34v.r34v.data.adapter.GalleryAccessException(message)
class GalleryRedirectException(message: String) : java.io.IOException(message)
