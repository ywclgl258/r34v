package com.r34v.r34v.ui

import android.content.Context
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ViewFlipper
import androidx.activity.ComponentActivity
import com.r34v.r34v.R
import com.r34v.r34v.data.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class CarouselEntry(val video: Video, val kicker: String)

class VideoCarousel(
    private val activity: ComponentActivity,
    private val entries: List<CarouselEntry>,
    private val scope: CoroutineScope,
    private val isSaved: (String) -> Boolean,
    private val toggleFavorite: (Video) -> Boolean,
    private val onPlay: (Video) -> Unit
) : LinearLayout(activity) {
    private val flipper = ViewFlipper(context)
    private val dots = mutableListOf<View>()
    private var autoJob: Job? = null

    init {
        require(entries.isNotEmpty())
        orientation = VERTICAL
        entries.forEach { entry -> flipper.addView(slide(entry)) }
        addView(flipper, LayoutParams(-1, -2))
        if (context.animationsEnabled()) {
            flipper.inAnimation = AlphaAnimation(0f, 1f).apply { duration = 180 }
            flipper.outAnimation = AlphaAnimation(1f, 0f).apply { duration = 120 }
        }
        if (entries.size > 1) {
            addView(context.row().apply {
                gravity = Gravity.CENTER
                entries.forEachIndexed { index, entry ->
                    val indicator = View(context).apply {
                        background = rounded(Palette.faint, context.dp(2).toFloat())
                        layoutParams = FrameLayout.LayoutParams(context.dp(6), context.dp(4), Gravity.CENTER)
                    }
                    dots.add(indicator)
                    addView(FrameLayout(context).apply {
                        isFocusable = true
                        contentDescription = "切换到第 ${index + 1} 部：${entry.video.title}"
                        addView(indicator)
                        setOnClickListener { showSlide(index) }
                    }, LayoutParams(context.dp(44), context.dp(44)))
                }
            }, LayoutParams(-1, context.dp(44)))
        }
        updateDots()
    }

    private fun slide(entry: CarouselEntry): View {
        val video = entry.video
        return context.column().apply {
            addView(FrameLayout(context).apply {
                isFocusable = true
                val cover = RatioImage(context).apply { Images.load(activity, this, video.thumb) }
                addView(cover, FrameLayout.LayoutParams(-1, -2))
                addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(android.graphics.Color.TRANSPARENT, 0xcc000000.toInt()))
                }, FrameLayout.LayoutParams(-1, context.dp(72), Gravity.BOTTOM))
                contentDescription = "播放 ${video.title}"
                setOnClickListener { onPlay(video) }
                val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        performClick()
                        return true
                    }
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                        val distance = e2.x - (e1?.x ?: e2.x)
                        if (abs(distance) < context.dp(48) || abs(velocityX) < abs(velocityY)) return false
                        showSlide(flipper.displayedChild + if (distance < 0) 1 else -1)
                        return true
                    }
                })
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) stopAuto()
                    val handled = detector.onTouchEvent(event)
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) restartAuto()
                    handled
                }
            }, LayoutParams(-1, -2))
            addView(context.column().apply {
                setPadding(context.dp(20), 0, context.dp(20), context.dp(4))
                space(6)
                addView(context.label(entry.kicker, 11f, Palette.red, true).apply { letterSpacing = 0.1f })
                addView(context.label(video.title, 28f, bold = true).apply {
                    minLines = 2
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(context.label(listOf(video.duration, video.views, video.rating).filter { it.isNotBlank() }.joinToString(" · "), 12f, Palette.muted))
                space(8)
                addView(context.row().apply {
                    addView(context.action("播放", true) { onPlay(video) }.leadingIcon(R.drawable.ic_play), LayoutParams(0, -2, 1.2f).apply { marginEnd = context.dp(10) })
                    val saved = isSaved(video.id)
                    addView(context.outlineAction(if (saved) "已收藏" else "收藏") { }.leadingIcon(if (saved) R.drawable.ic_check else R.drawable.ic_add).apply {
                        setOnClickListener {
                            val added = toggleFavorite(video)
                            text = if (added) "已收藏" else "收藏"
                            leadingIcon(if (added) R.drawable.ic_check else R.drawable.ic_add)
                            contentDescription = text
                            activity.toast(if (added) "已收藏" else "已取消收藏")
                        }
                    }, LayoutParams(0, -2, 1f))
                })
            }, LayoutParams(-1, -2))
        }
    }

    private fun showSlide(index: Int) {
        val count = flipper.childCount
        flipper.displayedChild = (index % count + count) % count
        updateDots()
        restartAuto()
    }

    private fun updateDots() {
        dots.forEachIndexed { index, dot ->
            val active = index == flipper.displayedChild
            dot.background = rounded(if (active) Palette.red else Palette.faint, context.dp(2).toFloat())
            dot.layoutParams = dot.layoutParams.apply { width = context.dp(if (active) 20 else 6) }
            (dot.parent as? View)?.isSelected = active
        }
    }

    private fun restartAuto() {
        autoJob?.cancel()
        autoJob = null
        if (!isAttachedToWindow || windowVisibility != View.VISIBLE || entries.size < 2 || !context.animationsEnabled()) return
        autoJob = scope.launch {
            while (isActive) {
                delay(6500)
                if (!isShown || windowVisibility != View.VISIBLE) continue
                flipper.showNext()
                updateDots()
            }
        }
    }

    private fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartAuto()
    }

    override fun onDetachedFromWindow() {
        stopAuto()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) restartAuto() else stopAuto()
    }
}
