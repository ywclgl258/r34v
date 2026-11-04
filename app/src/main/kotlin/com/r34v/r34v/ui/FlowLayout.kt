package com.r34v.r34v.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Small wrapping container for variable-width native tag buttons. */
class FlowLayout(context: Context) : ViewGroup(context) {
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = paddingTop; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i); if (child.visibility == View.GONE) continue
            val childWidth = MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(0), MeasureSpec.AT_MOST)
            child.measure(childWidth, getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, child.layoutParams.height))
            if (x + child.measuredWidth > maxWidth && x > 0) { x = 0; y += rowHeight + context.dp(8); rowHeight = 0 }
            x += child.measuredWidth + context.dp(8); rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(y + rowHeight + paddingBottom, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft; var y = paddingTop; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i); if (child.visibility == View.GONE) continue
            if (x + child.measuredWidth > width - paddingRight && x > paddingLeft) { x = paddingLeft; y += rowHeight + context.dp(8); rowHeight = 0 }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + context.dp(8); rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
