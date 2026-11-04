package com.r34v.r34v.ui

import android.content.Context
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private var pausedByUser = false
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    private val accessibilityListener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        if (enabled) stopAuto() else restartAuto()
    }

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
            val indicators = context.row().apply {
                gravity = Gravity.CENTER
                entries.forEachIndexed { index, entry ->
                    val indicator = View(context).apply {
                        background = rounded(Palette.faint, context.dp(2).toFloat())
                        layoutParams = FrameLayout.LayoutParams(context.dp(6), context.dp(4), Gravity.CENTER)
                    }
                    dots.add(indicator)
                    addView(FrameLayout(context).apply {
                        isFocusable = true
                        buttonSemantics()
                        contentDescription = "切换到第 ${index + 1} 部：${entry.video.title}"
                        addView(indicator)
                        setOnClickListener { showSlide(index) }
                        background = context.controlBackground(android.graphics.Color.TRANSPARENT, radius = 24)
                    }, LayoutParams(context.dp(48), context.dp(48)))
                }
            }
            addView(context.row().apply {
                setPadding(context.dp(8), 0, context.dp(8), 0)
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false; isFillViewport = true
                    addView(indicators)
                }, LayoutParams(0, -2, 1f))
                addView(context.iconAction(R.drawable.ic_pause, "暂停轮播") { }.apply {
                    fun update() {
                        setImageResource(if (pausedByUser) R.drawable.ic_play else R.drawable.ic_pause)
                        contentDescription = if (pausedByUser) "继续轮播" else "暂停轮播"
                    }
                    setOnClickListener {
                        pausedByUser = !pausedByUser
                        update()
                        if (pausedByUser) stopAuto() else restartAuto()
                    }
                    update()
                })
            }, LayoutParams(-1, -2))
        }
        updateDots()
    }

    private fun slide(entry: CarouselEntry): View {
        val video = entry.video
        val wide = resources.configuration.screenWidthDp >= 600
        return LinearLayout(context).apply {
            orientation = if (wide) HORIZONTAL else VERTICAL
            gravity = Gravity.TOP
            addView(FrameLayout(context).apply {
                isFocusable = true
                buttonSemantics()
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
            }, if (wide) LayoutParams(0, -2, 1.2f) else LayoutParams(-1, -2))
            addView(context.column().apply {
                setPadding(context.dp(20), 0, context.dp(20), context.dp(4))
                space(6)
                addView(context.label(entry.kicker, 12f, Palette.accentText, true).apply { letterSpacing = 0.05f })
                addView(context.label(video.title, 24f, bold = true).apply {
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
            }, if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
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
        if (!isAttachedToWindow || !hasWindowFocus() || pausedByUser || accessibility.isTouchExplorationEnabled ||
            windowVisibility != View.VISIBLE || entries.size < 2 || !context.animationsEnabled()) return
        autoJob = scope.launch {
            while (isActive) {
                delay(6500)
                if (!isShown || windowVisibility != View.VISIBLE || !getGlobalVisibleRect(android.graphics.Rect())) continue
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
        accessibility.addTouchExplorationStateChangeListener(accessibilityListener)
        restartAuto()
    }

    override fun onDetachedFromWindow() {
        stopAuto()
        accessibility.removeTouchExplorationStateChangeListener(accessibilityListener)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) restartAuto() else stopAuto()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) restartAuto() else stopAuto()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val current = flipper.displayedChild
        flipper.removeAllViews()
        entries.forEach { flipper.addView(slide(it)) }
        flipper.displayedChild = current
        updateDots()
    }
}
