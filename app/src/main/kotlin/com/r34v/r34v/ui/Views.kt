package com.r34v.r34v.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
    val red = Color.rgb(255, 89, 100)
    val text = Color.rgb(243, 243, 239)
    val secondary = Color.rgb(198, 198, 194)
    val muted = Color.rgb(160, 160, 160)
    val faint = Color.rgb(102, 102, 102)
    val border = Color.rgb(34, 34, 34)
}
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.column(padding: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Context.label(text: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    includeFontPadding = false
    setPadding(0, dp(5), 0, dp(5))
}
fun rounded(color: Int, radius: Float = 8f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun Context.action(text: String, accent: Boolean = false, click: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 14f
    typeface = Typeface.create("sans-serif", if (accent) Typeface.BOLD else Typeface.NORMAL)
    minHeight = dp(44); minimumHeight = dp(44); minWidth = 0; minimumWidth = dp(44)
    setPadding(dp(if (accent) 22 else 14), dp(10), dp(if (accent) 22 else 14), dp(10)); includeFontPadding = false
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Palette.faint, if (accent) Palette.canvas else Palette.text)))
    backgroundTintList = null; stateListAnimator = null; elevation = 0f
    background = RippleDrawable(ColorStateList.valueOf(0x30ffffff), rounded(if (accent) Palette.text else Palette.raised, dp(6).toFloat()), null)
    setOnClickListener { click() }; contentDescription = text
}
fun Context.quietAction(text: String, click: () -> Unit) = action(text, click = click).apply {
    background = RippleDrawable(ColorStateList.valueOf(0x22ffffff), rounded(Color.TRANSPARENT, dp(6).toFloat()), null)
    setTextColor(Palette.secondary)
}
fun Context.outlineAction(text: String, click: () -> Unit) = action(text, click = click).apply {
    background = RippleDrawable(ColorStateList.valueOf(0x22ffffff), rounded(Color.TRANSPARENT, dp(6).toFloat()).apply { setStroke(dp(1), 0xff555555.toInt()) }, null)
}
fun Context.chip(text: String, selected: Boolean = false, click: () -> Unit) = action(text, click = click).apply {
    isSelected = selected
    setTextColor(if (selected) Palette.canvas else Palette.secondary)
    background = RippleDrawable(ColorStateList.valueOf(0x30ffffff), rounded(if (selected) Palette.text else Palette.canvas, dp(22).toFloat()).apply { if (!selected) setStroke(dp(1), Palette.border) }, null)
}
fun Context.iconAction(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
    setImageResource(icon); imageTintList = ColorStateList.valueOf(Palette.text)
    background = RippleDrawable(ColorStateList.valueOf(0x22ffffff), null, null)
    setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = description
    setOnClickListener { click() }
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
}
fun Context.choiceAction(text: String, click: () -> Unit) = chip(text, click = click).apply {
    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, com.r34v.r34v.R.drawable.ic_chevron_down, 0)
    compoundDrawablePadding = dp(6)
}
fun View.enterQuietly() {
    val enabled = if (android.os.Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    if (enabled) { alpha = 0f; animate().alpha(1f).setDuration(160).start() }
}
fun Context.line() = View(this).apply { setBackgroundColor(Palette.border); layoutParams = LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)) }
fun Context.surface(content: LinearLayout) = content.apply {
    background = rounded(Palette.surface, dp(8).toFloat())
}
fun Context.inputField(hintText: String) = EditText(this).apply {
    hint = hintText; isSingleLine = true; textSize = 16f
    setTextColor(Palette.text); setHintTextColor(Palette.faint)
    background = rounded(Palette.raised, dp(6).toFloat()); minHeight = dp(48)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}
fun LinearLayout.space(height: Int = 12) = addView(Space(context), LinearLayout.LayoutParams(1, context.dp(height)))
fun Context.scroll(content: View) = ScrollView(this).apply { isFillViewport = true; addView(content) }
fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
fun ComponentActivity.showError(error: Throwable) = toast(error.message ?: "操作失败，请重试")

class RatioImage(context: Context, private val ratio: Float = 16f / 9f) : ImageView(context) {
    init { scaleType = ScaleType.CENTER_CROP; setBackgroundColor(Palette.surface) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((width / ratio).toInt(), MeasureSpec.EXACTLY))
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
    fun load(activity: ComponentActivity, image: ImageView, url: String) {
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
                        val bytes = if (file.isFile) file.readBytes() else app.network.client.readResponse(Request.Builder().url(url).build()) { response ->
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
fun ComponentActivity.videoTile(video: com.r34v.r34v.data.Video, menu: List<Pair<String, () -> Unit>> = emptyList(), click: () -> Unit): LinearLayout = column().apply {
    val cover = RatioImage(this@videoTile).apply { background = rounded(Palette.surface, dp(8).toFloat()); clipToOutline = true }
    val frame = FrameLayout(this@videoTile).apply {
        addView(cover, FrameLayout.LayoutParams(-1, -2))
        if (menu.isNotEmpty()) {
            val more = iconAction(com.r34v.r34v.R.drawable.ic_more, "${video.title} 的更多操作") {}.apply {
                background = RippleDrawable(ColorStateList.valueOf(0x30ffffff), rounded(0xb0000000.toInt(), dp(22).toFloat()), null)
            }
            more.setOnClickListener { PopupMenu(this@videoTile, more).apply { menu.forEach { (title, callback) -> this.menu.add(title).setOnMenuItemClickListener { callback(); true } }; show() } }
            addView(more, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); marginEnd = dp(4) })
        }
    }
    addView(frame, LinearLayout.LayoutParams(-1, -2)); Images.load(this@videoTile, cover, video.thumb)
    addView(label(video.title, 14f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    addView(label(listOf(video.duration, video.views, video.rating).filter { it.isNotBlank() }.joinToString(" · "), 12f, Palette.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    setPadding(dp(5), dp(5), dp(5), dp(12)); setOnClickListener { click() }; contentDescription = video.title
}
