package com.r34v.r34v.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.r34v.r34v.data.GalleryLanguage

/** A single, compact filter row which yields space to vertically scrolling content. */
class GalleryLanguageBar(context: Context, private val select: (GalleryLanguage) -> Unit) : FrameLayout(context) {
    private val choices = context.row().apply { setPadding(context.dp(12), 0, context.dp(12), 0) }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(choices, LayoutParams(-2, -1))
        contentDescription = "漫画语言分类"
    }
    private var animation: ValueAnimator? = null
    private var collapsed = false
    private var direction = 0
    private var distance = 0

    init { addView(scroll, LayoutParams(-1, context.dp(48))); clipChildren = true }

    fun configure(selected: GalleryLanguage) {
        animation?.cancel(); animation = null
        collapsed = false; direction = 0; distance = 0
        layoutParams = layoutParams.apply { height = context.dp(48) }
        choices.removeAllViews()
        GalleryLanguage.entries.forEachIndexed { index, language ->
            val title = when (language) {
                GalleryLanguage.ALL -> "全部"
                GalleryLanguage.CHINESE -> "中文"
                GalleryLanguage.JAPANESE -> "日文"
                GalleryLanguage.CHINESE_JAPANESE -> "中＋日"
            }
            val active = language == selected
            choices.addView(context.chip(title, active) { select(language) }.apply {
                textSize = 13f; isSingleLine = true
                contentDescription = language.title
                background = InsetDrawable(context.controlBackground(if (active) Palette.text else Palette.surface, radius = 18, outline = !active), 0, context.dp(6), 0, context.dp(6))
                setPaddingRelative(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            }, LinearLayout.LayoutParams(-2, context.dp(48)).apply { if (index > 0) marginStart = context.dp(6) })
        }
        scroll.post {
            val active = choices.getChildAt(selected.ordinal)
            scroll.scrollTo((active.right - scroll.width + context.dp(12)).coerceAtLeast(0), 0)
        }
    }

    fun onContentScrolled(dy: Int, atTop: Boolean) {
        if (visibility == View.GONE && !collapsed) return
        if (atTop) { distance = 0; setCollapsed(false); return }
        if (dy == 0) return
        val sign = if (dy > 0) 1 else -1
        if (direction != sign) distance = 0
        direction = sign; distance += kotlin.math.abs(dy)
        if (distance >= context.dp(18)) { setCollapsed(dy > 0); distance = 0 }
    }

    private fun setCollapsed(value: Boolean) {
        if (value == collapsed) return
        collapsed = value; animation?.cancel()
        val start = height
        val end = if (value) 0 else context.dp(48)
        visibility = View.VISIBLE
        if (!context.animationsEnabled()) {
            layoutParams = layoutParams.apply { height = end }
            visibility = if (value) View.GONE else View.VISIBLE
            return
        }
        animation = ValueAnimator.ofInt(start, end).apply {
            duration = 160
            addUpdateListener {
                layoutParams = layoutParams.apply { height = it.animatedValue as Int }
                if (collapsed && it.animatedValue == 0) visibility = View.GONE
            }
            start()
        }
    }
    override fun onDetachedFromWindow() { animation?.cancel(); super.onDetachedFromWindow() }
}
