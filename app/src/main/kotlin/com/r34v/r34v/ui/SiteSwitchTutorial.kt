package com.r34v.r34v.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout

/** A first-use tip anchored to the actual site switcher, independent of feed/network loading. */
class SiteSwitchTutorial(private val context: Context, private val anchor: View, openSites: () -> Unit) {
    private val originalBackground = anchor.background
    private val originalPadding = intArrayOf(anchor.paddingStart, anchor.paddingTop, anchor.paddingEnd, anchor.paddingBottom)
    private val pointer = object : View(context) {
        var tip = context.dp(40).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.raised }
        override fun onDraw(canvas: Canvas) {
            val x = tip.coerceIn(context.dp(14).toFloat(), (width - context.dp(14)).toFloat())
            canvas.drawPath(Path().apply {
                moveTo(x, 0f); lineTo(x + context.dp(9), height.toFloat()); lineTo(x - context.dp(9), height.toFloat()); close()
            }, paint)
        }
    }
    private val content = context.column()
    private val popup: PopupWindow
    val isShowing get() = popup.isShowing

    init {
        content.addView(pointer, LinearLayout.LayoutParams(-1, context.dp(10)))
        val card = context.column(18).apply {
            background = rounded(Palette.raised, context.dp(12).toFloat()).apply { setStroke(context.dp(1), Palette.outline) }
            // A background can replace native padding, so apply the content inset last.
            setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(14))
            addView(context.label("这里可以切换站点", 18f, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) })
            space(6)
            addView(context.label("点击顶部的站点名称或小箭头，即可切换视频站和漫画站。", 14f, Palette.secondary))
            space(4)
            addView(context.label("收藏、历史和下载会按站点分别保存。", 13f, Palette.muted))
            space(12)
            addView(FlowLayout(context).apply {
                addView(context.action("打开站点列表", true) { dismiss(); openSites() })
                addView(context.quietAction("知道了") { dismiss() })
            })
        }
        content.addView(ScrollView(context).apply { addView(card); isVerticalScrollBarEnabled = true }, LinearLayout.LayoutParams(-1, -2))
        popup = PopupWindow(content, -2, -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = context.dp(10).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener {
                anchor.background = originalBackground
                anchor.setPaddingRelative(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3])
            }
        }
    }

    fun show() {
        val visible = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val width = minOf(context.dp(320), visible.width() - context.dp(24)).coerceAtLeast(1)
        val available = (visible.bottom - location[1] - anchor.height - context.dp(12)).coerceAtLeast(1)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        popup.width = width; popup.height = content.measuredHeight
        anchor.background = rounded(Palette.raised, context.dp(8).toFloat()).apply { setStroke(context.dp(2), Palette.text) }
        anchor.setPaddingRelative(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3])
        popup.showAsDropDown(anchor, 0, context.dp(4), Gravity.START)
        content.doOnLayout {
            val popupLocation = IntArray(2).also(content::getLocationOnScreen)
            pointer.tip = location[0] + anchor.width / 2f - popupLocation[0]
            pointer.invalidate()
        }
        content.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    fun dismiss() { if (popup.isShowing) popup.dismiss() }
}
