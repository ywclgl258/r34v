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

/** Continuous, width-fitted pages. Only attached rows fetch images or own decoded bitmaps. */
class ComicStripView(
    context: Context,
    private val pageCount: Int,
    private val scope: CoroutineScope,
    private val load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage,
    private val progress: (Int, GalleryImage?, Int) -> Unit,
) : RecyclerView(context) {
    private val stripLayout = LinearLayoutManager(context).apply { isItemPrefetchEnabled = false }
    private val permits = Semaphore(2)
    private val ratios = mutableMapOf<Int, Float>()
    private val images = mutableMapOf<Int, GalleryImage>()
    private val holders = mutableSetOf<PageHolder>()
    private var reportedPage = -1
    private var restore: Pair<Int, Int>? = null
    val currentPage get() = if (childCount > 0 && !canScrollVertically(1) && stripLayout.findLastVisibleItemPosition() == pageCount - 1)
        pageCount else (stripLayout.findFirstVisibleItemPosition() + 1).coerceIn(1, pageCount)
    val readingOffset get() = stripLayout.findViewByPosition(currentPage - 1)?.top ?: 0

    private inner class PageHolder(val frame: FrameLayout) : ViewHolder(frame) {
        val image = ZoomPageView(context, continuous = true)
        var number = 0
        var job: Job? = null
        var bitmap: Bitmap? = null
        init { frame.addView(image, FrameLayout.LayoutParams(-1, -2)) }
        fun clear() {
            job?.cancel(); job = null
            image.clearPage(); bitmap?.recycle(); bitmap = null
            while (frame.childCount > 1) frame.removeViewAt(1)
        }
        fun fetch(force: Boolean = false) {
            if (job?.isActive == true || bitmap != null) return
            while (frame.childCount > 1) frame.removeViewAt(1)
            frame.addView(ProgressBar(context), FrameLayout.LayoutParams(context.dp(36), context.dp(36), Gravity.CENTER))
            job = scope.launch {
                try {
                    val rendered = permits.withPermit { load(number, force) }
                    bitmap = rendered.bitmap; images[number] = rendered.image
                    ratios[number] = rendered.bitmap.width.toFloat() / rendered.bitmap.height
                    image.showPage(rendered.bitmap)
                    while (frame.childCount > 1) frame.removeViewAt(1)
                    restore?.takeIf { it.first == number }?.let { (page, offset) ->
                        restore = null; stripLayout.scrollToPositionWithOffset(page - 1, offset)
                    }
                    post { report(true) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    while (frame.childCount > 1) frame.removeViewAt(1)
                    frame.addView(context.column(24).apply {
                        gravity = Gravity.CENTER
                        addView(context.label("第 $number 页：${error.message ?: "图片读取失败"}", 14f, Palette.muted))
                        space(); addView(context.action("重试此页") { fetch(true) })
                    }, FrameLayout.LayoutParams(-1, -1))
                }
            }
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
            }).also { holders.add(it) }
            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                holder.clear(); holder.number = position + 1
                holder.image.reserveRatio(ratios[holder.number] ?: (2f / 3f))
                holder.image.contentDescription = "漫画第 ${holder.number} 页，双击或双指缩放"
            }
            override fun onViewAttachedToWindow(holder: PageHolder) { holder.fetch() }
            override fun onViewDetachedFromWindow(holder: PageHolder) { holder.clear() }
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
        if (childCount == 0) return
        val number = currentPage
        if (force || number != reportedPage) { reportedPage = number; progress(number, images[number], readingOffset) }
    }
    fun scrollToPage(number: Int, offset: Int = 0) {
        val target = number.coerceIn(1, pageCount)
        stopScroll(); restore = target to offset
        stripLayout.scrollToPositionWithOffset(target - 1, offset)
        post { report(true) }
    }
    fun savePosition() = report(true)
    fun release() {
        holders.forEach { it.clear() }; holders.clear(); adapter = null
    }
}
