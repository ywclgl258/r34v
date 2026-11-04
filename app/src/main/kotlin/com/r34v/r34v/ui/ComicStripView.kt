package com.r34v.r34v.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.r34v.r34v.data.GalleryImage
import com.r34v.r34v.data.GalleryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Continuous pages with a moving three-page pre-read window and bounded decoded image memory. */
class ComicStripView(
    context: Context,
    private val pageCount: Int,
    private val scope: CoroutineScope,
    private val load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage,
    private val progress: (Int, GalleryImage?, Int) -> Unit,
    private val toggleControls: () -> Unit = {},
) : RecyclerView(context) {
    private val stripLayout = LinearLayoutManager(context).apply { isItemPrefetchEnabled = false }
    private val permits = Semaphore(2)
    private val readerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val ratios = mutableMapOf<Int, Float>()
    private class PageState {
        var rendered: GalleryRepository.RenderedPage? = null
        var error: Exception? = null
        var job: Job? = null
        var cached = false
    }
    private val states = mutableMapOf<Int, PageState>()
    private val attached = mutableMapOf<Int, PageHolder>()
    private var released = false
    private var reportedPage = -1
    private var restore: Pair<Int, Int>? = null
    val currentPage get() = if (childCount > 0 && !canScrollVertically(1) && stripLayout.findLastVisibleItemPosition() == pageCount - 1)
        pageCount else (stripLayout.findFirstVisibleItemPosition() + 1).coerceIn(1, pageCount)
    val readingOffset get() = stripLayout.findViewByPosition(currentPage - 1)?.top ?: 0

    private inner class PageHolder(val frame: FrameLayout) : ViewHolder(frame) {
        val image = ZoomPageView(context, continuous = true)
        var number = 0
        private var displayed: Bitmap? = null
        init {
            image.setOnClickListener { toggleControls() }
            frame.addView(image, FrameLayout.LayoutParams(-1, -2))
        }
        fun clear() {
            if (attached[number] === this) attached.remove(number)
            image.clearPage(); displayed = null
            while (frame.childCount > 1) frame.removeViewAt(1)
        }
        fun display() {
            val state = states[number]
            if (state?.rendered?.bitmap === displayed && displayed != null) return
            while (frame.childCount > 1) frame.removeViewAt(1)
            val rendered = state?.rendered
            when {
                rendered != null -> {
                    displayed = rendered.bitmap; image.showPage(rendered.bitmap)
                    restore?.takeIf { it.first == number }?.let { (page, offset) ->
                        restore = null; stripLayout.scrollToPositionWithOffset(page - 1, offset)
                    }
                }
                state?.error != null -> frame.addView(context.column(24).apply {
                    gravity = Gravity.CENTER
                    addView(context.label("第 $number 页：${state.error?.message ?: "图片读取失败"}", 14f, Palette.muted))
                    space(); addView(context.action("重试此页") { ensureLoaded(number, force = true); display() })
                }, FrameLayout.LayoutParams(-1, -1))
                else -> frame.addView(ProgressBar(context), FrameLayout.LayoutParams(context.dp(36), context.dp(36), Gravity.CENTER))
            }
        }
    }

    private fun ensureLoaded(number: Int, force: Boolean = false, prefetch: Boolean = false) {
        if (released || number !in 1..pageCount) return
        val state = states.getOrPut(number, ::PageState)
        if (state.job?.isActive == true || state.rendered != null || !force && (state.error != null || prefetch && state.cached)) return
        state.error = null
        state.job = readerScope.launch {
            try {
                val rendered = permits.withPermit { load(number, force) }
                if (released || states[number] !== state) { rendered.bitmap.recycle(); return@launch }
                state.rendered = rendered; state.cached = true
                ratios[number] = rendered.bitmap.width.toFloat() / rendered.bitmap.height
                attached[number]?.display()
                post { report(true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.error = error; attached[number]?.display()
            } finally { state.job = null }
        }
    }

    private fun refreshWindow() {
        if (released) return
        val anchor = restore?.first ?: currentPage
        val keep = (maxOf(1, anchor - 1)..minOf(pageCount, anchor + 3)).toSet() + attached.keys
        states.keys.filter { it !in keep }.forEach { number ->
            states.remove(number)?.let { it.job?.cancel(); it.rendered?.bitmap?.recycle() }
        }
        // Give visible/target pages priority; advance pre-reading only after the anchor is ready.
        ensureLoaded(anchor)
        attached.keys.toList().forEach { ensureLoaded(it) }
        if (states[anchor]?.cached == true) {
            (anchor + 1..minOf(pageCount, anchor + 3)).forEach { ensureLoaded(it, prefetch = true) }
        }
        val protected = attached.keys + anchor
        val buffered = states.filter { it.key !in protected && it.value.rendered != null }.entries.sortedBy { kotlin.math.abs(it.key - anchor) }
        var bytes = 0L
        buffered.forEach { (_, state) ->
            val bitmap = state.rendered!!.bitmap
            if (bytes + bitmap.byteCount <= 24L * 1024 * 1024) bytes += bitmap.byteCount
            else { bitmap.recycle(); state.rendered = null }
        }
    }
    init {
        contentDescription = "条漫阅读，上下滑动连续阅读"
        layoutManager = stripLayout
        setItemViewCacheSize(0); recycledViewPool.setMaxRecycledViews(0, 2)
        itemAnimator = null
        adapter = object : Adapter<PageHolder>() {
            override fun getItemCount() = pageCount
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(FrameLayout(context).apply {
                layoutParams = LayoutParams(-1, -2)
            })
            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                holder.clear(); holder.number = position + 1
                holder.image.reserveRatio(ratios[holder.number] ?: (2f / 3f))
                holder.image.contentDescription = "漫画第 ${holder.number} 页，双击或双指缩放"
            }
            override fun onViewAttachedToWindow(holder: PageHolder) {
                attached[holder.number] = holder; ensureLoaded(holder.number); holder.display()
                post { report(true) }
            }
            override fun onViewDetachedFromWindow(holder: PageHolder) { holder.clear(); post { refreshWindow() } }
            override fun onViewRecycled(holder: PageHolder) { holder.clear() }
        }
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { report() }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) restore = null
                if (newState == SCROLL_STATE_IDLE) report(true)
            }
        })
    }
    private fun report(force: Boolean = false) {
        if (released || childCount == 0) return
        val number = currentPage
        if (force || number != reportedPage) {
            reportedPage = number; progress(number, states[number]?.rendered?.image, readingOffset)
            refreshWindow()
        }
    }
    fun scrollToPage(number: Int, offset: Int = 0) {
        val target = number.coerceIn(1, pageCount)
        stopScroll(); restore = target to offset
        refreshWindow()
        stripLayout.scrollToPositionWithOffset(target - 1, offset)
        post { report(true) }
    }
    fun savePosition() = report(true)
    fun release() {
        released = true; readerScope.cancel()
        attached.values.toList().forEach { it.clear() }; adapter = null
        states.values.forEach { it.rendered?.bitmap?.recycle() }; states.clear()
    }
}
