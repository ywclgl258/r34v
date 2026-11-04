package com.r34v.r34v.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.r34v.r34v.data.SiteContext

/**
 * Left-hand site switcher.
 *
 * Opened by tapping the site name in the top bar. Tapping the scrim beside the panel, tapping a
 * site row, or pressing back closes it.
 *
 * Owns the page content and drawer in one measured hierarchy. DrawerLayout handles the scrim,
 * touch interception and edge drag; a closed drawer never covers the page with an invisible view.
 */
class SiteSwitcher(
    private val context: Context,
    content: View,
    private val sites: () -> List<SiteContext>,
    private val active: () -> SiteContext,
    private val onSelect: (SiteContext) -> Unit,
) : DrawerLayout(context) {

    private val panel: FrameLayout
    /** Container for site rows; rebuilt on every open so the current marker stays accurate. */
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val isShowing: Boolean get() = isDrawerVisible(panel)

    init {
        setBackgroundColor(Palette.canvas)
        setScrimColor(0xad000000.toInt())
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel = buildPanel()
        addView(panel, LayoutParams(context.dp(320), LayoutParams.MATCH_PARENT).apply { gravity = GravityCompat.START })
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        renderRows()
        addDrawerListener(object : SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) { renderRows() }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        panel.layoutParams.width = (width * 0.78f).toInt()
            .coerceIn(context.dp(240), context.dp(360))
            .coerceAtMost((width - context.dp(64)).coerceAtLeast(0))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun buildPanel(): FrameLayout {
        val content = context.column().apply {
            setBackgroundColor(Palette.surface)
            setPadding(context.dp(18), context.dp(24), context.dp(14), context.dp(24))
            addView(context.label("切换站点", 20f, bold = true))
            addView(context.label("选择要浏览的站点", 12f, Palette.muted))
            space(14)
            addView(rows)
            space(22)
            addView(context.label("每个站点的片库、搜索记录和下载相互独立。", 12f, Palette.muted))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Palette.surface)
            isClickable = true
            setOnClickListener { }
            addView(content)
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Palette.surface)
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** Rebuilt on every open so the current-site marker reflects the live state. */
    private fun renderRows() {
        rows.removeAllViews()
        val current = active().id
        for (site in sites()) rows.addView(siteRow(site, site.id == current))
    }

    private fun siteRow(site: SiteContext, selected: Boolean): View {
        return context.row().apply {
            minimumHeight = context.dp(52)
            setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            background = RippleDrawable(
                ColorStateList.valueOf(0x30ffffff),
                GradientDrawable().apply { setColor(if (selected) Palette.raised else Color.TRANSPARENT); cornerRadius = context.dp(4).toFloat() },
                null,
            )
            addView(context.column().apply {
                addView(context.label(site.name, 14f, if (selected) Palette.text else Palette.secondary, bold = selected))
                if (site.accessNote.isNotBlank()) addView(context.label(site.accessNote, 12f, Palette.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) {
                addView(View(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.red) }
                }, LinearLayout.LayoutParams(context.dp(8), context.dp(8)))
            }
            isFocusable = true
            isSelected = selected
            contentDescription = if (selected) "${site.name}（当前）" else site.name
            setOnClickListener {
                if (!selected) onSelect(site)
                renderRows()
                close()
            }
        }
    }

    override fun open() {
        renderRows()
        openDrawer(panel, context.animationsEnabled())
    }

    override fun close() {
        closeDrawer(panel, context.animationsEnabled())
    }
}
