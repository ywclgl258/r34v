package com.r34v.r34v.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.r34v.r34v.data.*

/** Content and its scroll anchors have one lifetime; large lists never enter the saved Bundle. */
class FeedState(request: FeedRequest) {
    var videos by mutableStateOf<List<Video>>(emptyList())
    var ranked by mutableStateOf<List<Pair<Video, String>>>(emptyList())
    var popular by mutableStateOf<List<Video>>(emptyList())
    var rated by mutableStateOf<List<Video>>(emptyList())
    var next by mutableIntStateOf(request.page)
    var total by mutableIntStateOf(request.page)
    var cursor by mutableStateOf(request.cursor)
    var cached by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var emptyPages by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var restartRequired by mutableStateOf(false)
    var loadedAt = 0L
    var generation = 0
    val cursors = mutableSetOf<String>()
    val list = LazyListState()
    val grid = LazyStaggeredGridState()
    fun reset() {
        generation++; videos = emptyList(); ranked = emptyList(); popular = emptyList(); rated = emptyList()
        next = 1; total = 1; cursor = ""; loaded = false; cached = false; emptyPages = 0; cursors.clear()
        error = null; restartRequired = false; loadedAt = 0
    }
}

class FeedStates : ViewModel() {
    private data class Key(val site: String, val home: Boolean, val request: FeedRequest, val blocked: List<String>)
    private val pages = object : LinkedHashMap<Key, FeedState>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FeedState>?) = size > 8
    }
    fun page(site: String, home: Boolean, request: FeedRequest, blocked: List<String>): FeedState {
        val key = Key(site, home, request, blocked.toList())
        val old = pages[key]
        return old?.takeIf { !it.loaded || System.currentTimeMillis() - it.loadedAt < 5 * 60_000 }
            ?: FeedState(request).also { pages[key] = it }
    }
}
