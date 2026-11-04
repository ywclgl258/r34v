package com.r34v.r34v.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.readResponse
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object Palette {
    val canvas = Color.BLACK
    val surface = Color.rgb(20, 20, 20)
    val raised = Color.rgb(31, 31, 31)
    val control = Color.rgb(42, 42, 42)
    val red = Color.rgb(229, 9, 20)
    val accentText = Color.rgb(255, 102, 112)
    val loading = Color.rgb(228, 105, 93)
    val text = Color.WHITE
    val secondary = Color.rgb(179, 179, 179)
    val muted = Color.rgb(166, 166, 166)
    val faint = Color.rgb(102, 102, 102)
    val border = Color.rgb(31, 31, 31)
    val outline = Color.rgb(116, 116, 116)
}
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun View.buttonSemantics() = apply {
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }
}
fun Context.column(padding: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Context.label(text: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    includeFontPadding = false
    setPadding(0, dp(5), 0, dp(5))
}
fun rounded(color: Int, radius: Float = 4f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
/** Native ripple with a visible keyboard focus ring, independent of the selected state. */
fun Context.controlBackground(color: Int, radius: Int = 6, outline: Boolean = false): RippleDrawable {
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(if (color == Color.TRANSPARENT) color else Palette.raised, dp(radius).toFloat()))
        addState(intArrayOf(android.R.attr.state_focused), rounded(color, dp(radius).toFloat()).apply { setStroke(dp(2), Palette.text) })
        addState(intArrayOf(), rounded(color, dp(radius).toFloat()).apply { if (outline) setStroke(dp(1), Palette.outline) })
    }
    return RippleDrawable(ColorStateList.valueOf(0x30ffffff), states, null)
}
fun Context.action(text: String, accent: Boolean = false, click: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 14f
    typeface = Typeface.create("sans-serif", if (accent) Typeface.BOLD else Typeface.NORMAL)
    minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48); minimumWidth = dp(48)
    setPadding(dp(if (accent) 22 else 18), dp(10), dp(if (accent) 22 else 18), dp(10)); includeFontPadding = false
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Palette.faint, if (accent) Palette.canvas else Palette.text)))
    backgroundTintList = null; stateListAnimator = null; elevation = 0f
    background = controlBackground(if (accent) Palette.text else Palette.control)
    setOnClickListener { click() }; contentDescription = text
}
fun Button.leadingIcon(icon: Int) = apply {
    val drawable = androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()
    drawable?.setBounds(0, 0, context.dp(18), context.dp(18))
    setCompoundDrawablesRelative(drawable, null, null, null)
    compoundDrawableTintList = ColorStateList.valueOf(currentTextColor)
    compoundDrawablePadding = context.dp(8)
}
fun Context.quietAction(text: String, click: () -> Unit) = action(text, click = click).apply {
    background = controlBackground(Color.TRANSPARENT)
    setTextColor(Palette.secondary)
}
fun Context.outlineAction(text: String, click: () -> Unit) = action(text, click = click).apply {
    background = controlBackground(Palette.surface, outline = true)
}
fun Context.chip(text: String, selected: Boolean = false, click: () -> Unit) = action(text, click = click).apply {
    isSelected = selected
    setTextColor(if (selected) Palette.canvas else Palette.secondary)
    background = controlBackground(if (selected) Palette.text else Palette.surface, radius = 24, outline = !selected)
}
fun Context.iconAction(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
    setImageResource(icon); imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Palette.faint, Palette.text))
    background = controlBackground(Color.TRANSPARENT, radius = 24)
    setPadding(dp(12), dp(12), dp(12), dp(12)); contentDescription = description
    setOnClickListener { click() }
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
}
fun Context.choiceAction(text: String, click: () -> Unit) = chip(text, click = click).apply {
    textSize = 13f
    setPadding(dp(12), dp(6), dp(10), dp(6))
    background = RippleDrawable(ColorStateList.valueOf(0x22ffffff),
        android.graphics.drawable.InsetDrawable(controlBackground(Palette.surface, outline = true), 0, dp(4), 0, dp(4)), null)
    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, com.r34v.r34v.R.drawable.ic_chevron_down, 0)
    compoundDrawableTintList = ColorStateList.valueOf(Palette.muted)
    compoundDrawablePadding = dp(5)
}
fun Context.animationsEnabled() = if (android.os.Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
    else android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
fun View.enterQuietly() {
    if (context.animationsEnabled()) { alpha = 0f; animate().alpha(1f).setDuration(160).start() }
}
fun Context.line() = View(this).apply { setBackgroundColor(Palette.border); layoutParams = LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)) }
fun Context.surface(content: LinearLayout) = content.apply {
    background = rounded(Palette.surface, dp(3).toFloat())
}
fun Context.inputField(hintText: String) = EditText(this).apply {
    hint = hintText; isSingleLine = true; textSize = 15f
    setTextColor(Palette.text); setHintTextColor(Palette.muted)
    background = controlBackground(Palette.surface, outline = true); minHeight = dp(52)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}
fun LinearLayout.space(height: Int = 12) = addView(Space(context), LinearLayout.LayoutParams(1, context.dp(height)))
fun Context.scroll(content: View) = ScrollView(this).apply { isFillViewport = true; addView(content) }
fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
fun ComponentActivity.showError(error: Throwable) = toast(error.message ?: "操作失败，请重试")

fun Context.emptyState(icon: Int, title: String, message: String, actionTitle: String, click: () -> Unit) = column(24).apply {
    gravity = Gravity.CENTER
    addView(ImageView(context).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(Palette.secondary)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Palette.surface, dp(32).toFloat())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(64), dp(64)))
    space(16)
    addView(label(title, 20f, bold = true).apply { gravity = Gravity.CENTER; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
    addView(label(message, 14f, Palette.muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f) })
    space(20)
    addView(action(actionTitle, true, click))
}

class RatioImage(context: Context, private val ratio: Float = 16f / 9f, private val naturalSize: Boolean = false) : ImageView(context) {
    init { scaleType = ScaleType.CENTER_CROP; setBackgroundColor(Palette.surface) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val natural = drawable?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight }
        val aspect = if (naturalSize && natural != null) natural.coerceIn(.45f, 1.8f) else ratio
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((width / aspect).toInt(), MeasureSpec.EXACTLY))
    }
}

/** Bounded memory + disk thumbnails. Binding token prevents recycled cards showing stale images. */
object Images {
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val requests = Semaphore(3)
    private class Binding(val image: ImageView, val start: () -> Job) : View.OnAttachStateChangeListener {
        var job: Job? = null
        override fun onViewAttachedToWindow(view: View) { if (job?.isActive != true) job = start() }
        override fun onViewDetachedFromWindow(view: View) { job?.cancel(); job = null }
        fun cancel() { job?.cancel(); image.removeOnAttachStateChangeListener(this) }
    }
    fun load(activity: ComponentActivity, image: ImageView, url: String, site: com.r34v.r34v.data.SiteContext? = null) {
        (image.getTag(com.r34v.r34v.R.id.image_job) as? Binding)?.cancel()
        image.tag = url; image.setImageDrawable(null)
        memory.get(url)?.let { image.setImageBitmap(it); return }
        if (!url.startsWith("https://")) return
        val app = activity.application as R34Application
        val binding = Binding(image) {
            activity.lifecycleScope.launch {
                val bitmap = requests.withPermit { withContext(Dispatchers.IO) {
                    memory.get(url) ?: runCatching {
                        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                        val directory = File(activity.cacheDir, "images").apply { mkdirs() }
                        val file = File(directory, name)
                        val bytes = if (file.isFile) file.readBytes() else app.network.client.readResponse(Request.Builder().url(url).tag(com.r34v.r34v.data.SiteContext::class.java, site).build()) { response ->
                            if (!response.isSuccessful) null else response.body?.bytes()?.takeIf { it.size <= 8 * 1024 * 1024 }
                        }?.also { data -> file.writeBytes(data) }
                        if (directory.listFiles().orEmpty().sumOf { it.length() } > 64L * 1024 * 1024) directory.listFiles()?.sortedBy { it.lastModified() }?.take(40)?.forEach { it.delete() }
                        bytes?.let {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                            val sample = Integer.highestOneBit((bounds.outWidth / 700).coerceAtLeast(1))
                            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }.getOrElse { error -> if (error is CancellationException) throw error; null }
                } }
                if (bitmap != null) { memory.put(url, bitmap); if (image.tag == url) image.setImageBitmap(bitmap) }
            }
        }
        image.setTag(com.r34v.r34v.R.id.image_job, binding)
        image.addOnAttachStateChangeListener(binding)
        if (image.isAttachedToWindow) binding.onViewAttachedToWindow(image)
    }
}
private fun String.keepEndingTogether() = if (length < 2 || contains('\n')) this else substring(0, lastIndex) + "\u2060" + substring(lastIndex)
private fun String.keepMetaWordsTogether() = replace("观看", "观\u2060看")
fun ComponentActivity.videoTile(video: com.r34v.r34v.data.Video, menu: List<Pair<String, () -> Unit>> = emptyList(), click: () -> Unit): LinearLayout = column().apply {
    val gallery = video.mediaKind == com.r34v.r34v.data.MediaKind.GALLERY || com.r34v.r34v.data.SiteRegistry.byId(video.site)?.mediaKind == com.r34v.r34v.data.MediaKind.GALLERY
    val cover = RatioImage(this@videoTile, if (gallery) video.thumbAspectRatio else 16f / 9f, gallery && !video.hasThumbDimensions).apply {
        background = rounded(Palette.surface, dp(3).toFloat()); clipToOutline = true
        if (gallery) scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val frame = FrameLayout(this@videoTile).apply {
        addView(cover, FrameLayout.LayoutParams(-1, -2))
        if (menu.isNotEmpty()) {
            val more = iconAction(com.r34v.r34v.R.drawable.ic_more, "${video.title} 的更多操作") {}.apply {
                background = RippleDrawable(ColorStateList.valueOf(0x30ffffff),
                    android.graphics.drawable.InsetDrawable(rounded(0xb0000000.toInt(), dp(16).toFloat()), dp(8)), null)
            }
            more.setOnClickListener { PopupMenu(this@videoTile, more).apply { menu.forEach { (title, callback) -> this.menu.add(title).setOnMenuItemClickListener { callback(); true } }; show() } }
            addView(more, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); marginEnd = dp(4) })
        }
    }
    addView(frame, LinearLayout.LayoutParams(-1, -2)); Images.load(this@videoTile, cover, video.thumb, com.r34v.r34v.data.SiteRegistry.byId(video.site))
    addView(label(video.title.keepEndingTogether(), 14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    addView(label(listOf(video.duration, video.views, video.rating).filter { it.isNotBlank() }.joinToString(" · ").keepMetaWordsTogether(), 12f, Palette.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    setPadding(dp(5), dp(5), dp(5), dp(12)); isFocusable = true
    foreground = RippleDrawable(ColorStateList.valueOf(0x18ffffff), rounded(Color.TRANSPARENT, dp(3).toFloat()), null)
    setOnClickListener { click() }; contentDescription = video.title
}
