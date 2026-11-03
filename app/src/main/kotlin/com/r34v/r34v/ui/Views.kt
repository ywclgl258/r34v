package com.r34v.r34v.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.R34Application
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object Palette {
    val canvas = Color.BLACK
    val surface = Color.rgb(20, 20, 20)
    val raised = Color.rgb(31, 31, 31)
    val red = Color.rgb(229, 9, 20)
    val text = Color.WHITE
    val muted = Color.rgb(165, 165, 165)
}
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.column(padding: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Context.label(text: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    setPadding(0, dp(5), 0, dp(5))
}
fun rounded(color: Int, radius: Float = 8f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun Context.action(text: String, accent: Boolean = false, click: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 13f; minHeight = dp(44); minimumWidth = dp(48)
    setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(if (accent) Palette.red else Palette.raised)
    setOnClickListener { click() }; contentDescription = text
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
    fun load(activity: ComponentActivity, image: ImageView, url: String) {
        (image.getTag(com.r34v.r34v.R.id.image_job) as? Job)?.cancel()
        image.tag = url; image.setImageDrawable(null)
        memory.get(url)?.let { image.setImageBitmap(it); return }
        if (!url.startsWith("https://")) return
        val app = activity.application as R34Application
        val job = activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                    val directory = File(activity.cacheDir, "images").apply { mkdirs() }
                    val file = File(directory, name)
                    val bytes = if (file.isFile) file.readBytes() else app.network.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.bytes()?.takeIf { it.size <= 8 * 1024 * 1024 }?.also { data -> file.writeBytes(data) }
                    }
                    if (directory.listFiles().orEmpty().sumOf { it.length() } > 64L * 1024 * 1024) directory.listFiles()?.sortedBy { it.lastModified() }?.take(40)?.forEach { it.delete() }
                    bytes?.let {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                        val sample = Integer.highestOneBit((bounds.outWidth / 700).coerceAtLeast(1))
                        BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }.getOrNull()
            }
            if (bitmap != null) { memory.put(url, bitmap); if (image.tag == url) image.setImageBitmap(bitmap) }
        }
        image.setTag(com.r34v.r34v.R.id.image_job, job)
    }
}
fun ComponentActivity.videoTile(video: com.r34v.r34v.data.Video, click: () -> Unit): LinearLayout = column().apply {
    val cover = RatioImage(this@videoTile)
    addView(cover, LinearLayout.LayoutParams(-1, -2)); Images.load(this@videoTile, cover, video.thumb)
    addView(label(video.title, 14f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    addView(label(listOf(video.duration, video.views, video.rating).filter { it.isNotBlank() }.joinToString(" · "), 11f, Palette.muted))
    setPadding(dp(5), dp(5), dp(5), dp(12)); setOnClickListener { click() }; contentDescription = video.title
}
