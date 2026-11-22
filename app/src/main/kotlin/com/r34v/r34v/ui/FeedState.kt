package com.r34v.r34v.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.*
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import java.security.MessageDigest

internal data class FeedPosition(
    @SerializedName("work") val work: String,
    @SerializedName("throughPage") val throughPage: Int,
    @SerializedName("shelf") val shelf: String = "",
)

class FeedState internal constructor(
    request: FeedRequest, initialPosition: FeedPosition? = null,
    private val persistPosition: ((FeedPosition?) -> Unit)? = null,
) {
    var videos by mutableStateOf<List<Video>>(emptyList())
    val homeRankings = mutableStateMapOf<FeedKind, List<Video>>()
    var refreshCount by mutableIntStateOf(0)
        private set
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
    internal var restorePosition by mutableStateOf(initialPosition)
        private set
    internal var restoredWork by mutableStateOf<String?>(null)
        private set
    private var savedPosition = initialPosition

    internal val remembersPosition get() = persistPosition != null

    internal fun savePosition(position: FeedPosition?) {
        if (restorePosition != null || position == savedPosition) return
        restoredWork = null
        savedPosition = position
        persistPosition?.invoke(position)
    }

    internal fun finishRestoring(found: Boolean) {
        restoredWork = restorePosition?.work.takeIf { found }
        restorePosition = null
        if (!found) savePosition(null)
    }

    fun reset() {
        finishRestoring(found = false)
        generation++; refreshCount++; videos = emptyList(); homeRankings.clear()
        next = 1; total = 1; cursor = ""; loaded = false; cached = false; emptyPages = 0; cursors.clear()
        error = null; restartRequired = false; loadedAt = 0
    }
}

class FeedStates(application: Application) : AndroidViewModel(application) {
    private val store = (application as R34Application).library
    private data class Key(val site: String, val screen: String, val request: FeedRequest, val blocked: List<String>)
    private val pages = object : LinkedHashMap<Key, FeedState>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FeedState>?) = size > 8
    }
    fun page(site: String, screen: String, request: FeedRequest, blocked: List<String>): FeedState {
        val key = Key(site, screen, request, blocked.toList())
        val old = pages[key]
        if (old != null && (!old.loaded || System.currentTimeMillis() - old.loadedAt < 5 * 60_000)) return old

        val state = if (screen == "siteHome" || screen == "explore") {
            val id = MessageDigest.getInstance("SHA-256").digest(store.gson.toJson(listOf(site, screen, request, key.blocked)).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val saved = positions()[id]?.takeIf { !it.work.isNullOrBlank() && it.throughPage >= request.page }
            FeedState(request, saved) { position ->
                synchronized(store) {
                    val values = positions()
                    values.remove(id)
                    if (position != null) values[id] = position
                    store.prefs.edit { putString(POSITIONS, store.gson.toJson(values.entries.toList().takeLast(32).associate { it.toPair() })) }
                }
            }
        } else FeedState(request)
        return state.also { pages[key] = it }
    }

    private fun positions(): LinkedHashMap<String, FeedPosition> = runCatching {
        store.gson.fromJson<LinkedHashMap<String, FeedPosition>>(store.prefs.getString(POSITIONS, null), POSITIONS_TYPE)
    }.getOrNull() ?: linkedMapOf()

    private companion object {
        const val POSITIONS = "browse_positions_v1"
        val POSITIONS_TYPE = object : TypeToken<LinkedHashMap<String, FeedPosition>>() {}.type
    }
}
