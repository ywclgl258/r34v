package com.r34v.r34v.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** A zoomable page; continuous mode fits its width and leaves vertical swipes to the strip. */
class ZoomPageView(context: Context, private val turn: (Int) -> Unit = {}, private val continuous: Boolean = false) : ImageView(context) {
    private val transform = Matrix()
    private var pageWidth = 0
    private var pageHeight = 0
    private var relativeScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var aspectRatio = 2f / 3f
    val isZoomed get() = relativeScale > 1.02f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom((relativeScale * detector.scaleFactor).coerceIn(1f, 5f), detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoom(if (isZoomed) 1f else 2.5f, e.x, e.y); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (continuous || isZoomed || e1 == null || abs(e2.x - e1.x) < context.dp(64) || abs(velocityX) < abs(velocityY)) return false
            turn(if (e2.x < e1.x) 1 else -1)
            return true
        }
    })
    init { scaleType = ScaleType.MATRIX; setBackgroundColor(Palette.canvas); isFocusable = true }
    fun reserveRatio(ratio: Float) { aspectRatio = ratio.coerceAtLeast(.01f); requestLayout() }
    fun clearPage() { setImageDrawable(null); pageWidth = 0; pageHeight = 0; relativeScale = 1f }
    fun showPage(bitmap: Bitmap) {
        pageWidth = bitmap.width; pageHeight = bitmap.height; aspectRatio = pageWidth.toFloat() / pageHeight
        setImageBitmap(bitmap); requestLayout(); fit()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!continuous) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width / aspectRatio).toInt().coerceAtLeast(1))
    }
    private fun fit() {
        if (width <= 0 || height <= 0 || pageWidth <= 0 || pageHeight <= 0) return
        relativeScale = 1f
        val scale = if (continuous) width.toFloat() / pageWidth else min(width.toFloat() / pageWidth, height.toFloat() / pageHeight)
        transform.setScale(scale, scale)
        transform.postTranslate((width - pageWidth * scale) / 2, (height - pageHeight * scale) / 2)
        imageMatrix = transform
    }
    private fun zoom(target: Float, x: Float, y: Float) {
        if (target <= 1f) { fit(); return }
        transform.postScale(target / relativeScale, target / relativeScale, x, y)
        relativeScale = target
        clamp()
    }
    private fun clamp() {
        val bounds = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
        transform.mapRect(bounds)
        fun offset(start: Float, end: Float, size: Int) = if (end - start <= size) (size - start - end) / 2 else when { start > 0 -> -start; end < size -> size - end; else -> 0f }
        transform.postTranslate(offset(bounds.left, bounds.right, width), offset(bounds.top, bounds.bottom, height))
        imageMatrix = transform
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (continuous) parent?.requestDisallowInterceptTouchEvent(isZoomed || event.pointerCount > 1)
        pinch.onTouchEvent(event); gestures.onTouchEvent(event)
        if (continuous && (isZoomed || pinch.isInProgress)) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { lastX = event.x; lastY = event.y }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !pinch.isInProgress && event.pointerCount == 1 && isZoomed) {
            transform.postTranslate(event.x - lastX, event.y - lastY); clamp()
        }
        lastX = event.x; lastY = event.y
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
}
