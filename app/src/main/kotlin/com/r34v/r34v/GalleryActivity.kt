@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.r34v.r34v

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

class GalleryActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private var entry by mutableStateOf(Video())
    private val site get() = SiteRegistry.byIdOrDefault(entry.site)
    private var detail by mutableStateOf<GalleryDetail?>(null)
    private var error by mutableStateOf<String?>(null)
    private var gate by mutableStateOf(false)
    private var offlineOnly = false
    private var sourceRoot = ""
    private var reading by mutableStateOf(false)
    private var controlsVisible by mutableStateOf(true)
    private var page by mutableIntStateOf(1)
    private var stripOffset = 0
    private var jump by mutableStateOf(ReaderJump(0, 1))
    private var revision by mutableIntStateOf(0)
    private var saveReader: (() -> Unit)? = null
    private var pageJob: Job? = null
    private val images = mutableMapOf<Int, GalleryImage>()
    private val dialogs = ComposeDialogs()
    private val progressSaver by lazy { ReadingProgressSaver(lifecycleScope, store::saveReading) }
    private fun saveProgress() { saveReader?.invoke(); progressSaver.flush() }
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) load(reading) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = runCatching { store.gson.fromJson(intent.getStringExtra("gallery"), Video::class.java) }.getOrNull() ?: run { finish(); return }
        if (site.mediaKind != MediaKind.GALLERY || entry.id.isBlank()) { finish(); return }
        page = savedInstanceState?.getInt("page") ?: store.reading(entry.id, site.id)?.page ?: 1
        stripOffset = savedInstanceState?.getInt("stripOffset") ?: store.reading(entry.id, site.id)?.offset ?: 0
        reading = savedInstanceState?.getBoolean("reading") ?: intent.getBooleanExtra("read", false)
        offlineOnly = savedInstanceState?.getBoolean("offline") ?: intent.getBooleanExtra("offline", false)
        sourceRoot = intent.getStringExtra("root").orEmpty()
        controlsVisible = savedInstanceState?.getBoolean("controlsVisible") ?: true
        jump = ReaderJump(0, page, stripOffset)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { R34Theme { Content() } }
        load(reading)
    }
    private fun load(resume: Boolean) {
        gate = !offlineOnly && site.id == "exhentai" && !app.network.session.hasGallerySession(site)
        if (gate) return
        error = null; pageJob?.cancel()
        pageJob = lifecycleScope.launch { try {
            val gallery = app.galleries.detail(entry, offlineOnly, sourceRoot); detail = gallery; entry = gallery.entry; page = page.coerceIn(1, gallery.pageCount)
            if (sourceRoot.isBlank()) sourceRoot = withContext(Dispatchers.IO) { app.galleries.readingRoot(entry) }
            if (resume) reader(page, stripOffset) else { reading = false; controlsVisible = true }
        } catch (e: Exception) { if (e is CancellationException) throw e; if (e is GalleryLoginRequiredException) { app.network.session.clear(site); gate = true } else error = e.message ?: "${site.category.itemNoun}加载失败" } }
    }
    private fun reader(target: Int, offset: Int = 0) { page = target; stripOffset = offset; jump = ReaderJump(jump.serial + 1, target, offset); reading = true }
    private fun applyBars() {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (reading && !controlsVisible) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
    }
    @Composable private fun Content() {
        BackHandler { when { reading && !controlsVisible -> controlsVisible = true; reading -> { saveProgress(); reading = false; controlsVisible = true }; else -> finish() } }
        SideEffect { applyBars() }
        Column(Modifier.fillMaxSize().background(CanvasColor).then(if (reading && !controlsVisible) Modifier.displayCutoutPadding() else Modifier.safeDrawingPadding())) {
            if (!reading || controlsVisible || gate || error != null) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_back, "返回") { if (reading) { saveProgress(); reading = false; controlsVisible = true } else finish() }
                Text(if (reading) entry.title else site.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (reading) IconAction(R.drawable.ic_fullscreen, "全屏${site.category.verb}") { controlsVisible = false }
            }
            when {
                gate -> EmptyState("ExHentai 必须登录并具备访问权限", "请在官方页面完成登录，再验证本站访问权限。", "官方网页登录") { officialLogin.launch(Intent(this@GalleryActivity, SiteAuthActivity::class.java).putExtra("site", site.id)) }
                error != null -> EmptyState("${site.category.itemNoun}加载失败", error.orEmpty()) { load(reading) }
                detail == null -> Loading("正在加载${site.category.itemNoun}")
                reading -> {
                    val gallery = detail!!
                    Box(Modifier.weight(1f)) { ComicReader(gallery.pageCount, jump, ::loadStripPage, { controlsVisible = !controlsVisible }, { current, image, offset ->
                        page = current; stripOffset = offset
                        if (image != null) progressSaver.record(ReadingProgress(entry, current, gallery.pageCount, image.pageUrl, offset = offset))
                    }, { saveReader = it }) }
                    if (controlsVisible) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Copy("单击画面隐藏菜单", Modifier.weight(1f)); TextAction(::jumpDialog) { Text("$page / ${gallery.pageCount}") } }
                        if (gallery.pageCount > 1) {
                            var slider by remember(page) { mutableFloatStateOf(page.toFloat()) }
                            Slider(slider, { slider = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "${site.category.itemNoun}${site.category.verb}进度" }, valueRange = 1f..gallery.pageCount.toFloat(), colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = BrandColor, inactiveTrackColor = RaisedColor), onValueChangeFinished = { reader(slider.toInt().coerceIn(1, gallery.pageCount)) })
                        }
                    }
                }
                else -> Overview()
            }
        }
        dialogs.Host()
    }
    @Composable private fun Overview() {
        val gallery = detail ?: return
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val noun = site.category.itemNoun
        val verb = site.category.verb
        val progress = store.reading(entry.id, site.id)
        val downloadRevision by app.galleryDownloads.changes.collectAsState()
        val download = remember(downloadRevision, entry.id, entry.site) { app.galleryDownloads.task(entry) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!offlineOnly) SiteImage(entry.thumb, site, Modifier.fillMaxWidth().height(250.dp), true)
            else Copy("已下载整本 · ${gallery.pageCount} 页 · 离线$verb")
            Title(entry.title, large = true)
            if (gallery.alternativeTitle.isNotBlank() && gallery.alternativeTitle != entry.title) Copy(gallery.alternativeTitle)
            Copy(listOf(entry.duration, gallery.language, entry.rating).filter(String::isNotBlank).joinToString(" · "))
            Action(if (progress == null) "开始$verb" else "继续$verb · 第 ${progress.page} 页", true, modifier = Modifier.fillMaxWidth()) { reader(progress?.page ?: 1, progress?.offset ?: 0) }
            Action(if (store.isFavorite("gallery", entry.id, site.id)) "已收藏$noun" else "收藏$noun", modifier = Modifier.fillMaxWidth()) { store.toggleFavorite(Favorite("gallery", entry.id, entry.title, entry.url, entry.thumb, entry.duration, site = site.id), site.id); revision++ }
            Action(when (download?.state) {
                GalleryDownloadState.COMPLETED -> "离线$verb"
                GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED -> "暂停下载 · ${download.pages} / ${download.total} 页"
                GalleryDownloadState.PAUSED -> "继续下载"
                GalleryDownloadState.FAILED -> "重试下载"
                GalleryDownloadState.REMOVING -> "正在删除下载"
                null -> if (site.category == SiteCategory.PHOTO) "下载全部图片" else "下载整本"
            }, enabled = download?.state != GalleryDownloadState.REMOVING, modifier = Modifier.fillMaxWidth()) {
                if (download?.state == GalleryDownloadState.COMPLETED) { offlineOnly = true; load(true) }
                else lifecycleScope.launch { try {
                    if (download?.state in listOf(GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED)) app.galleryDownloads.pause(download!!.id)
                    else {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
                        app.galleryDownloads.add(entry); toast("已加入下载，可在统一下载中心查看")
                    }
                } catch (e: Exception) { if (e is CancellationException) throw e; showError(e) } }
            }
            TextAction({ startActivity(Intent(this@GalleryActivity, MainActivity::class.java).putExtra("downloads", true)) }) { Glyph(R.drawable.ic_download, null); Spacer(Modifier.width(8.dp)); Text("管理下载") }
            if (download?.state == GalleryDownloadState.FAILED) Copy(download.error)
            if (gallery.cached && !offlineOnly) Copy("当前使用已保存的$noun 信息；已保存图页可离线$verb，整本下载可在下载中心查看。")
            Copy(gallery.description)
            gallery.groups.forEach { (name, links) -> Title(name); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { links.forEach { link ->
                Box(Modifier.combinedClickable(onClick = { startActivity(Intent(this@GalleryActivity, MainActivity::class.java).putExtra("link", link.url)) }, onLongClick = {
                    store.toggleFavorite(Favorite(if (name == "上传者" || name == "画师") "model" else "tag", link.slug, link.label, link.url, site = site.id), site.id); toast("已更新收藏")
                }).padding(12.dp).heightIn(min = 24.dp)) { Text(link.label) }
            } } }
            Action("跳到指定页", click = ::jumpDialog)
        }
    }
    private suspend fun loadStripPage(target: Int, force: Boolean): GalleryRepository.RenderedPage {
        val gallery = detail ?: error("${site.category.itemNoun}信息尚未载入")
        val remembered = store.reading(entry.id, site.id)?.takeIf { it.page == target }?.pageUrl
        val cached = withContext(Dispatchers.IO) { app.galleries.cachedPageUrl(entry, target, sourceRoot) }
        val url = cached ?: images[target]?.pageUrl ?: images[target - 1]?.nextUrl ?: images[target + 1]?.previousUrl ?: remembered
            ?: if (offlineOnly) error("下载图页目录缺失，请重新下载") else AdapterRegistry.adapter(site, app.network).galleryPageLink(gallery, target)
        return app.galleries.page(site, url, force, galleryUrl = entry.url, entry = entry, offlineOnly = offlineOnly, sourceRoot = sourceRoot).also { images[target] = it.image }
    }
    private fun jumpDialog() {
        val count = detail?.pageCount ?: return
        dialogs.edit("跳到指定页", page.toString(), "1 – $count", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, validate = { if (it.toIntOrNull() in 1..count) null else "请输入 1 – $count" }) { reader(it.toInt()) }
    }
    override fun onSaveInstanceState(outState: Bundle) { saveProgress(); outState.putBoolean("offline", offlineOnly); outState.putInt("page", page); outState.putInt("stripOffset", stripOffset); outState.putBoolean("reading", reading); outState.putBoolean("controlsVisible", controlsVisible); super.onSaveInstanceState(outState) }
    override fun onStop() { saveProgress(); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyBars() }
}
