package com.r34v.r34v.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Small wrapping container for variable-width native tag buttons. */
class FlowLayout(context: Context) : ViewGroup(context) {
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec); var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i); if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            if (x + child.measuredWidth > maxWidth && x > 0) { x = 0; y += rowHeight; rowHeight = 0 }
            x += child.measuredWidth + context.dp(6); rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(maxWidth, resolveSize(y + rowHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i); if (child.visibility == View.GONE) continue
            if (x + child.measuredWidth > width && x > 0) { x = 0; y += rowHeight; rowHeight = 0 }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + context.dp(6); rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
