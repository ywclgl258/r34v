package com.r34v.r34v

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

class GalleryActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private lateinit var entry: Video
    private val site get() = SiteRegistry.byIdOrDefault(entry.site)
    private var detail: GalleryDetail? = null
    private val images = mutableMapOf<Int, GalleryImage>()
    private var page = 1
    private var reading = false
    private var pageJob: Job? = null
    private lateinit var root: LinearLayout
    private var strip: ComicStripView? = null
    private var stripOffset = 0
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private var readerToolbar: View? = null
    private var readerControls: View? = null
    private var controlsVisible = true
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) load(reading)
    }
    private fun loginGate() {
        root.removeAllViews(); root.addView(toolbar(site.name))
        root.addView(column(24).apply {
            addView(label("ExHentai 必须登录并具备访问权限", 19f, bold = true)); space()
            addView(action("官方网页登录", true) { officialLogin.launch(Intent(this@GalleryActivity, SiteAuthActivity::class.java).putExtra("site", site.id)) })
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = runCatching { store.gson.fromJson(intent.getStringExtra("gallery"), Video::class.java) }.getOrNull() ?: run { finish(); return }
        if (site.mediaKind != MediaKind.GALLERY) { finish(); return }
        page = savedInstanceState?.getInt("page") ?: store.reading(entry.id, site.id)?.page ?: 1
        stripOffset = savedInstanceState?.getInt("stripOffset") ?: store.reading(entry.id, site.id)?.offset ?: 0
        val resume = savedInstanceState?.getBoolean("reading") ?: intent.getBooleanExtra("read", false)
        controlsVisible = savedInstanceState?.getBoolean("controlsVisible") ?: true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = column().apply { setBackgroundColor(Palette.canvas) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val types = WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime() or
                if (reading && !controlsVisible) 0 else WindowInsetsCompat.Type.systemBars()
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when { reading && !controlsVisible -> setReaderControlsVisible(true); reading -> overview(); else -> finish() }
            }
        })
        if (site.id == SiteRegistry.EXHENTAI.id && !app.network.session.hasGallerySession(site)) loginGate() else load(resume)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        strip?.savePosition()
        outState.putInt("page", page); outState.putInt("stripOffset", stripOffset); outState.putBoolean("reading", reading)
        outState.putBoolean("controlsVisible", controlsVisible)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { strip?.savePosition(); super.onStop() }
    override fun onDestroy() { strip?.release(); pageJob?.cancel(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && reading && !controlsVisible) applyReaderControls()
    }
    private fun toolbar(title: String) = row().apply {
        minimumHeight = dp(56); setPadding(dp(8), dp(4), dp(16), dp(4))
        addView(iconAction(R.drawable.ic_back, "返回") { onBackPressedDispatcher.onBackPressed() })
        addView(label(title, 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun load(resume: Boolean) {
        root.removeAllViews(); root.addView(toolbar(entry.title)); root.addView(ProgressBar(this))
        pageJob?.cancel()
        pageJob = lifecycleScope.launch {
            try {
                detail = app.galleries.detail(entry); entry = detail!!.entry; page = page.coerceIn(1, detail!!.pageCount)
                if (resume) reader(page) else overview()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is GalleryLoginRequiredException) { app.network.session.clear(site); loginGate(); return@launch }
                root.removeAllViews(); root.addView(toolbar(entry.title))
                root.addView(column(24).apply { addView(label(error.message ?: "画廊加载失败", 15f, Palette.muted)); space(); addView(action("重试") { load(resume) }) })
            }
        }
    }
    private fun overview() {
        strip?.savePosition(); strip?.release(); strip = null
        pageJob?.cancel(); reading = false
        controlsVisible = true; applyReaderControls()
        val gallery = detail ?: return
        root.removeAllViews(); root.addView(toolbar(site.name))
        val content = column(20)
        content.addView(ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; maxHeight = dp(280)
            Images.load(this@GalleryActivity, this, entry.thumb, site)
        }, LinearLayout.LayoutParams(-1, dp(250)))
        content.addView(label(entry.title, 23f, bold = true))
        if (gallery.alternativeTitle.isNotBlank() && gallery.alternativeTitle != entry.title) content.addView(label(gallery.alternativeTitle, 14f, Palette.secondary))
        content.addView(label(listOf(entry.duration, gallery.language, entry.rating).filter { it.isNotBlank() }.joinToString(" · "), 13f, Palette.muted))
        content.space(16)
        val progress = store.reading(entry.id, site.id)
        content.addView(action(if (progress == null) "开始阅读" else "继续阅读 · 第 ${progress.page} 页", true) { reader(progress?.page ?: 1, progress?.offset ?: 0) }.leadingIcon(R.drawable.ic_bookmark))
        content.space(8)
        fun renderFavorite(): Button = outlineAction(if (store.isFavorite("gallery", entry.id, site.id)) "已收藏画廊" else "收藏画廊") {
            store.toggleFavorite(Favorite("gallery", entry.id, entry.title, entry.url, entry.thumb, entry.duration, site = site.id), site.id)
            overview()
        }
        content.addView(renderFavorite())
        if (gallery.cached) { content.space(); content.addView(label("当前使用已保存的画廊信息；已缓存图页可离线阅读。", 13f, Palette.muted)) }
        content.space(20); content.addView(label(gallery.description, 13f, Palette.muted))
        for ((name, links) in gallery.groups) {
            content.space(18); content.addView(label(name, 17f, bold = true))
            content.addView(FlowLayout(this).apply { links.forEach { link ->
                addView(chip(link.label) { startActivity(Intent(this@GalleryActivity, MainActivity::class.java).putExtra("link", link.url)) }.apply {
                    setOnLongClickListener {
                        store.toggleFavorite(Favorite(if (name == "上传者" || name == "画师") "model" else "tag", link.slug, link.label, link.url, site = site.id), site.id)
                        toast("已更新收藏"); true
                    }
                })
            } })
        }
        content.space(20); content.addView(quietAction("跳到指定页") { jump() })
        root.addView(scroll(content), LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun reader(target: Int, offset: Int = stripOffset) {
        val gallery = detail ?: return
        reading = true
        strip?.release()
        root.removeAllViews()
        readerToolbar = toolbar(entry.title).apply {
            addView(iconAction(R.drawable.ic_fullscreen, "全屏阅读") { setReaderControlsVisible(false) })
        }.also { root.addView(it) }
        val reader = ComicStripView(this, gallery.pageCount, lifecycleScope, ::loadStripPage, { current, image, scrollOffset ->
            page = current; stripOffset = scrollOffset
            pageLabel.text = "$page / ${gallery.pageCount}"; seek.progress = page - 1
            if (image != null) store.saveReading(ReadingProgress(entry, page, gallery.pageCount, image.pageUrl, offset = scrollOffset))
        }, { setReaderControlsVisible(!controlsVisible) })
        strip = reader
        root.addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = column()
        controls.addView(row().apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(label("单击画面隐藏菜单", 13f, Palette.muted), LinearLayout.LayoutParams(0, -2, 1f))
            pageLabel = label("", 14f, Palette.text, true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(48); minimumWidth = dp(48)
                background = controlBackground(android.graphics.Color.TRANSPARENT)
                isFocusable = true; setOnClickListener { jump() }; contentDescription = "跳转页码"
            }
            addView(pageLabel)
        })
        seek = SeekBar(this).apply {
            max = gallery.pageCount - 1; contentDescription = "漫画阅读进度"; progressTintList = android.content.res.ColorStateList.valueOf(Palette.red)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) pageLabel.text = "${value + 1} / ${gallery.pageCount}" }
                override fun onStopTrackingTouch(seekBar: SeekBar) { reader.scrollToPage(seekBar.progress + 1) }
            })
        }
        controls.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        readerControls = controls; root.addView(controls)
        applyReaderControls()
        pageLabel.text = "$target / ${gallery.pageCount}"; seek.progress = target - 1
        reader.scrollToPage(target, offset)
    }
    private fun setReaderControlsVisible(visible: Boolean) {
        if (!reading || controlsVisible == visible) return
        val reader = strip ?: return
        val anchor = reader.currentPage; val offset = reader.readingOffset
        controlsVisible = visible; applyReaderControls()
        reader.post { if (strip === reader) reader.scrollToPage(anchor, offset) }
    }
    private fun applyReaderControls() {
        val fullscreen = reading && !controlsVisible
        readerToolbar?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        readerControls?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        val bars = WindowCompat.getInsetsController(window, root)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
    }
    private suspend fun loadStripPage(target: Int, force: Boolean): GalleryRepository.RenderedPage {
        val gallery = detail ?: error("画廊信息尚未载入")
        val remembered = store.reading(entry.id, site.id)?.takeIf { it.page == target }?.pageUrl
        val cached = withContext(Dispatchers.IO) { app.galleries.cachedPageUrl(entry, target) }
        val url = cached ?: images[target]?.pageUrl ?: images[target - 1]?.nextUrl ?: images[target + 1]?.previousUrl ?: remembered
            ?: AdapterRegistry.adapter(site, app.network).galleryPageLink(gallery, target)
        return app.galleries.page(site, url, force, galleryUrl = entry.url).also { images[target] = it.image }
    }
    private fun jump() {
        val gallery = detail ?: return
        val input = inputField("1 – ${gallery.pageCount}").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(page.toString()); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle("跳到指定页").setView(column(20).apply { addView(input) }).setPositiveButton("阅读", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target = input.text.toString().toIntOrNull()
            if (target == null || target !in 1..gallery.pageCount) { input.error = "请输入 1 – ${gallery.pageCount}"; return@setOnClickListener }
            dialog.dismiss(); if (reading) strip?.scrollToPage(target) else reader(target, 0)
        } }; dialog.show()
    }
}
