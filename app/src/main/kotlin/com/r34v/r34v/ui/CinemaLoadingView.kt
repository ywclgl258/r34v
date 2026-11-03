package com.r34v.r34v.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min

class CinemaLoadingView(context: Context, compact: Boolean = false, translucent: Boolean = false) : FrameLayout(context) {
    private val mark = LoadingMark(context)
    private val title = context.label("正在连接放映厅", if (compact) 15f else 21f)
    private val detail = context.label("建立播放通道", 13f, Palette.muted)
    private val state = context.label("缓冲中", 12f, Palette.muted)

    init {
        setBackgroundColor(if (translucent) 0xd8000000.toInt() else Color.rgb(12, 10, 9))
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        title.gravity = Gravity.CENTER
        title.letterSpacing = if (compact) 0.02f else 0.06f
        title.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        detail.gravity = Gravity.CENTER
        detail.letterSpacing = 0.06f
        state.letterSpacing = 0.08f

        val ruleColor = 0x12f3ece1
        addView(View(context).apply { setBackgroundColor(ruleColor) }, LayoutParams(-1, context.dp(1).coerceAtLeast(1), Gravity.TOP).apply {
            setMargins(context.dp(24), context.dp(24), context.dp(24), 0)
            if (compact) visibility = View.GONE
        })
        val scene = context.column().apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(24), context.dp(32), context.dp(24), context.dp(32))
            addView(mark, LinearLayout.LayoutParams(context.dp(if (compact) 104 else 156), context.dp(if (compact) 104 else 156)))
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(if (compact) 10 else 20) })
            if (!compact) addView(detail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(4) })
        }
        addView(scene, LayoutParams(-1, -1, Gravity.CENTER))
        addView(context.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(state, LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.label("请稍候", 12f, Palette.muted).apply { letterSpacing = 0.08f })
        }, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(context.dp(24), 0, context.dp(24), context.dp(24))
            if (compact) visibility = View.GONE
        })
        addView(View(context).apply { setBackgroundColor(ruleColor) }, LayoutParams(-1, context.dp(1).coerceAtLeast(1), Gravity.BOTTOM).apply {
            setMargins(context.dp(24), 0, context.dp(24), context.dp(12))
            if (compact) visibility = View.GONE
        })
    }

    fun showStage(stage: String, description: String, footer: String = "缓冲中", ready: Boolean = false) {
        animate().cancel()
        alpha = 1f
        visibility = View.VISIBLE
        title.text = stage
        detail.text = description
        state.text = footer
        state.setTextColor(if (ready) Palette.loading else Palette.muted)
        mark.ready = ready
        mark.contentDescription = if (ready) "视频可以播放" else stage
        mark.syncAnimations()
        mark.invalidate()
    }

    fun finishAndHide() {
        showStage("即将开始", "放映准备完成", "可以播放", ready = true)
        if (!context.animationsEnabled()) {
            visibility = View.GONE
            return
        }
        animate().alpha(0f).setDuration(220).withEndAction {
            visibility = View.GONE
            alpha = 1f
        }.start()
    }

    fun hideImmediately() {
        animate().cancel()
        visibility = View.GONE
        alpha = 1f
    }
}

private class LoadingMark(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val triangle = Path()
    private var rotation = 0f
    private var pulse = 0f
    private var spin: ValueAnimator? = null
    private var breathe: ValueAnimator? = null
    private var glow: RadialGradient? = null
    var ready = false
        set(value) {
            field = value
            syncAnimations()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = min(w, h).toFloat()
        glow = RadialGradient(w / 2f, h / 2f, size * .64f,
            intArrayOf(0x4ce4695d, 0x17e4695d, 0x00e4695d),
            floatArrayOf(0f, .44f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.shader = glow
        paint.alpha = ((.34 + pulse * .16) * 255).toInt()
        canvas.drawCircle(cx, cy, size * .64f, paint)
        paint.shader = null

        val radius = size * .39f
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = context.dp(1).coerceAtLeast(1).toFloat()
        paint.color = Color.WHITE
        paint.alpha = 36
        canvas.drawArc(arc, 0f, 360f, false, paint)
        paint.color = Palette.loading
        paint.alpha = 255
        paint.strokeWidth = context.dp(2).toFloat()
        canvas.drawArc(arc, rotation - 90f, if (ready) 360f else 82f, false, paint)

        val scale = .97f + pulse * .05f
        val halfWidth = size * .085f * scale
        val halfHeight = size * .105f * scale
        triangle.reset()
        triangle.moveTo(cx - halfWidth * .65f, cy - halfHeight)
        triangle.lineTo(cx + halfWidth, cy)
        triangle.lineTo(cx - halfWidth * .65f, cy + halfHeight)
        triangle.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(243, 236, 225)
        paint.alpha = ((.62 + pulse * .34) * 255).toInt().coerceAtMost(255)
        canvas.drawPath(triangle, paint)
    }

    fun syncAnimations() {
        val animate = isAttachedToWindow && isShown && !ready && context.animationsEnabled()
        if (!animate) {
            spin?.cancel()
            breathe?.cancel()
            spin = null
            breathe = null
            return
        }
        if (spin == null) {
            spin = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 2800
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (breathe == null) {
            breathe = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimations()
    }

    override fun onDetachedFromWindow() {
        spin?.cancel()
        breathe?.cancel()
        spin = null
        breathe = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimations()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimations()
    }
}
