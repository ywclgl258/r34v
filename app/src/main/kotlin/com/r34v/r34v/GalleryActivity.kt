@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.*

class GalleryActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private var entry by mutableStateOf(Video())
    private val site get() = SiteRegistry.byIdOrDefault(entry.site)
    private val itemNoun: String get() = if (site.id == SiteRegistry.IWARA.id && entry.mediaKind == MediaKind.GALLERY) "图库" else site.category.itemNoun
    private val actionVerb: String get() = if (site.id == SiteRegistry.IWARA.id && entry.mediaKind == MediaKind.GALLERY) "浏览" else site.category.verb
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
    private var readerMode by mutableStateOf(ReaderMode.STRIP)
    private var readerPreferences by mutableStateOf(ReaderPreferences())
    private var readingBehavior by mutableStateOf(ReadingBehavior())
    private var autoPageRunning by mutableStateOf(false)
    private var slideshowRunning by mutableStateOf(false)
    private var thumbnails by mutableStateOf(false)
    private var pageJob: Job? = null
    private val images = mutableStateMapOf<Int, GalleryImage>()
    private val dialogs = ComposeDialogs()
    private val progressSaver by lazy { ReadingProgressSaver(lifecycleScope, store::saveReading) }
    private fun saveProgress() {
        saveReader?.invoke()
        detail?.let { gallery ->
            progressSaver.record(ReadingProgress(entry, page.coerceIn(1, gallery.pageCount), gallery.pageCount,
                images[page]?.pageUrl.orEmpty(), offset = stripOffset))
        }
        progressSaver.flush()
    }
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) load(reading) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = runCatching { store.gson.fromJson(intent.getStringExtra("gallery"), Video::class.java) }.getOrNull() ?: run { finish(); return }
        if ((site.mediaKind != MediaKind.GALLERY && entry.mediaKind != MediaKind.GALLERY) || entry.id.isBlank()) { finish(); return }
        readerPreferences = store.personal.readerPreferences(entry)
        readingBehavior = store.personal.readingBehavior(site.id)
        readerMode = ReaderMode.entries.firstOrNull { it.name == readerPreferences.mode } ?: store.personal.readerMode(site.id)
        store.personal.markRead(site.id, setOf(entry.entryKey))
        page = savedInstanceState?.getInt("page") ?: intent.getIntExtra("page", 0).takeIf { it > 0 } ?: store.reading(entry.id, site.id)?.page ?: 1
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
            val gallery = if (entry.site == SiteRegistry.LOCAL.id) withContext(Dispatchers.IO) { app.localLibrary.detail(entry) } else app.galleries.detail(entry, offlineOnly, sourceRoot)
            detail = gallery; entry = gallery.entry
            page = if (intent.getIntExtra("page", 0) > 0) page.coerceIn(1, gallery.pageCount) else when (readerPreferences.start) {
                ReaderStart.FIRST -> 1
                ReaderStart.LAST -> gallery.pageCount
                ReaderStart.RESUME -> page.coerceIn(1, gallery.pageCount)
            }
            if (sourceRoot.isBlank() && entry.site != SiteRegistry.LOCAL.id) sourceRoot = withContext(Dispatchers.IO) { app.galleries.readingRoot(entry) }
            if (resume) reader(page, stripOffset) else { reading = false; controlsVisible = true }
        } catch (e: Exception) { if (e is CancellationException) throw e; if (e is GalleryLoginRequiredException) { app.network.session.clear(site); gate = true } else error = e.message ?: "${itemNoun}加载失败" } }
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
        if (reading && detail != null && !gate && error == null) {
            ReaderScene()
            dialogs.Host()
            if (thumbnails) ReaderThumbnails(detail!!.pageCount, page, ::loadStripPage, { thumbnails = false }) { target -> thumbnails = false; reader(target) }
            return
        }
        Column(Modifier.fillMaxSize().background(CanvasColor).then(if (reading && !controlsVisible) Modifier.displayCutoutPadding() else Modifier.safeDrawingPadding())) {
            if (!reading || controlsVisible || gate || error != null) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_back, "返回") { if (reading) { saveProgress(); reading = false; controlsVisible = true } else finish() }
                Text(if (reading) entry.title else site.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (reading) {
                    IconAction(R.drawable.ic_search, "页面缩略图总览") { thumbnails = true }
                    IconAction(R.drawable.ic_bookmark, "页码书签", click = ::bookmarks)
                    IconAction(R.drawable.ic_more, "阅读模式", click = ::readingOptions)
                    IconAction(R.drawable.ic_fullscreen, "全屏${actionVerb}") { controlsVisible = false }
                }
            }
            when {
                gate -> EmptyState("ExHentai 必须登录并具备访问权限", "请在官方页面完成登录，再验证本站访问权限。", "官方网页登录") { officialLogin.launch(Intent(this@GalleryActivity, SiteAuthActivity::class.java).putExtra("site", site.id)) }
                error != null -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    EmptyState("${itemNoun}加载失败", error.orEmpty()) { load(reading) }
                    if (entry.site == SiteRegistry.LOCAL.id) Action("重新选择文件 / 目录", primary = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        startActivity(Intent(this@GalleryActivity, MainActivity::class.java).putExtra("import_local", true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                }
                detail == null -> Loading("正在加载${itemNoun}")
                else -> Overview()
            }
            if (gate || error != null) QueueNextAction(this@GalleryActivity, entry, intent.getStringExtra("queue_session"), dialogs)
        }
        dialogs.Host()
        if (thumbnails && detail != null) ReaderThumbnails(detail!!.pageCount, page, ::loadStripPage, { thumbnails = false }) { target -> thumbnails = false; reader(target) }
    }
    @Composable private fun ReaderScene() {
        val gallery = detail ?: return
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val imageSaved = store.personal.isImageFavorite(entry, page)
        val backdrop = rememberLayerBackdrop()
        val positionChanged: (Int, GalleryImage?, Int) -> Unit = { current, image, offset ->
            page = current; stripOffset = offset
            progressSaver.record(ReadingProgress(entry, current, gallery.pageCount, image?.pageUrl.orEmpty(), offset = offset))
        }
        LaunchedEffect(autoPageRunning, page, images[page], readingBehavior.autoPageSeconds, gallery.pageCount) {
            if (autoPageRunning && readingBehavior.autoPageSeconds > 0 && images[page] != null) {
                withFrameNanos { }
                delay(readingBehavior.autoPageSeconds * 1000L)
                if (!autoPageRunning) return@LaunchedEffect
                if (page < gallery.pageCount) reader(page + 1) else autoPageRunning = false
            }
        }
        LaunchedEffect(slideshowRunning, page, images[page], readingBehavior.slideshowSeconds, readingBehavior.slideshowRandom, gallery.pageCount) {
            if (slideshowRunning && readingBehavior.slideshowSeconds > 0 && images[page] != null) {
                withFrameNanos { }
                delay(readingBehavior.slideshowSeconds * 1000L)
                if (!slideshowRunning) return@LaunchedEffect
                val target = if (readingBehavior.slideshowRandom) {
                    if (gallery.pageCount <= 1) 0 else (1..gallery.pageCount).filter { it != page }.random()
                } else if (page < gallery.pageCount) page + 1 else 0
                if (target > 0) reader(target) else slideshowRunning = false
            }
        }
        CompositionLocalProvider(LocalLiquidBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().background(CanvasColor).displayCutoutPadding()) {
                Box(Modifier.fillMaxSize().liquidContent(backdrop)) {
                    if (readerMode == ReaderMode.STRIP) ComicReader(gallery.pageCount, jump, ::loadStripPage,
                        { autoPageRunning = false; slideshowRunning = false; controlsVisible = !controlsVisible }, positionChanged, { saveReader = it }, readerPreferences.fit, readerPreferences.cropPercent)
                    else PagedComicReader(gallery.pageCount, jump, readerMode, ::loadStripPage,
                        { autoPageRunning = false; slideshowRunning = false; controlsVisible = !controlsVisible }, positionChanged, { saveReader = it }, readerPreferences.fit, readerPreferences.cropPercent, readerPreferences.doubleCover)
                }
                androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                    enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    Row(Modifier.padding(12.dp).widthIn(max = 760.dp).fillMaxWidth().liquidSurface(28), verticalAlignment = Alignment.CenterVertically) {
                        IconAction(R.drawable.ic_back, "返回") { saveProgress(); reading = false; controlsVisible = true }
                        Text(entry.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (app.queue.session(intent.getStringExtra("queue_session"), entry) != null)
                            IconAction(R.drawable.ic_chevron_right, "片单接续", click = ::queueTools)
                        IconAction(if (imageSaved) R.drawable.ic_check else R.drawable.ic_bookmark, if (imageSaved) "已收藏当前图片，点击取消" else "收藏当前图片",
                            if (imageSaved) Modifier.background(BrandColor.copy(alpha = .22f), CircleShape) else Modifier, click = ::toggleCurrentImageFavorite)
                        IconAction(R.drawable.ic_more, "阅读工具") { readerTools() }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                    enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    Column(Modifier.padding(12.dp).widthIn(max = 760.dp).fillMaxWidth().liquidSurface(28).padding(8.dp)) {
                        if (autoPageRunning || slideshowRunning) TextAction({ autoPageRunning = false; slideshowRunning = false }, Modifier.fillMaxWidth()) {
                            Text(if (slideshowRunning) "幻灯片播放中 · 点击暂停" else "自动翻页中 · 点击暂停")
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ reader((page - 1).coerceAtLeast(1)) }, enabled = page > 1, modifier = Modifier.size(48.dp)) { Glyph(R.drawable.ic_back, "上一页") }
                            TextAction(::jumpDialog, Modifier.weight(1f)) { Text("$page / ${gallery.pageCount}") }
                            IconButton({ reader((page + 1).coerceAtMost(gallery.pageCount)) }, enabled = page < gallery.pageCount, modifier = Modifier.size(48.dp)) { Glyph(R.drawable.ic_chevron_right, "下一页") }
                        }
                        if (gallery.pageCount > 1) {
                            var slider by remember(page) { mutableFloatStateOf(page.toFloat()) }
                            Slider(slider, { slider = it }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp).semantics { contentDescription = "${itemNoun}${actionVerb}进度" }, valueRange = 1f..gallery.pageCount.toFloat(),
                                colors = SliderDefaults.colors(thumbColor = androidx.compose.ui.graphics.Color.White, activeTrackColor = BrandColor, inactiveTrackColor = ControlColor),
                                thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                                track = { state -> Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .2f))) {
                                    Box(Modifier.fillMaxWidth(((state.value - 1f) / (gallery.pageCount - 1)).coerceIn(0f, 1f)).fillMaxHeight().background(BrandColor))
                                } },
                                onValueChangeFinished = { reader(slider.toInt().coerceIn(1, gallery.pageCount)) })
                        }
                    }
                }
            }
        }
    }
    private fun readerTools() {
        val hasQueue = app.queue.session(intent.getStringExtra("queue_session"), entry) != null
        val imageSaved = store.personal.isImageFavorite(entry, page)
        dialogs.choose("阅读工具", listOf("页面缩略图总览", "页码书签", if (imageSaved) "取消收藏当前图片" else "收藏当前图片", "图集幻灯片", "阅读模式", "阅读行为", "本书显示设置", "全屏${actionVerb}") + if (hasQueue) listOf("片单接续") else emptyList()) { index ->
            when (index) {
                0 -> { dialogs.dismiss(); thumbnails = true }
                1 -> bookmarks()
                2 -> toggleCurrentImageFavorite()
                3 -> slideshowOptions()
                4 -> readingOptions()
                5 -> readingBehaviorOptions()
                6 -> readerDisplayOptions()
                7 -> { dialogs.dismiss(); controlsVisible = false }
                8 -> queueTools()
            }
        }
    }
    private fun toggleCurrentImageFavorite() {
        val image = images[page]
        val pageUrl = image?.pageUrl ?: detail?.pageLinks?.firstOrNull { it.index == page }?.url ?: return
        store.personal.toggleImageFavorite(entry, page, pageUrl, image?.imageUrl.orEmpty())
        revision++; toast(if (store.personal.isImageFavorite(entry, page)) "已收藏第 $page 张图片" else "已取消图片收藏")
    }
    private fun slideshowOptions() {
        val interval = if (readingBehavior.slideshowSeconds > 0) "每 ${readingBehavior.slideshowSeconds} 秒" else "未设置"
        dialogs.choose("图集幻灯片", listOf("播放间隔：$interval", "顺序：${if (readingBehavior.slideshowRandom) "随机" else "顺序"}", if (slideshowRunning) "暂停幻灯片" else "开始幻灯片")) { index ->
            when (index) {
                0 -> {
                    val values = listOf(0, 3, 5, 10, 20, 30)
                    dialogs.choose("幻灯片间隔", values.map { if (it == 0) "未设置" else "每 $it 秒" }, values.indexOf(readingBehavior.slideshowSeconds)) { selected ->
                        readingBehavior = readingBehavior.copy(slideshowSeconds = values[selected]).also { store.personal.setReadingBehavior(site.id, it) }
                    }
                }
                1 -> { readingBehavior = readingBehavior.copy(slideshowRandom = !readingBehavior.slideshowRandom).also { store.personal.setReadingBehavior(site.id, it) }; slideshowOptions() }
                2 -> {
                    if (slideshowRunning) slideshowRunning = false else {
                        if (readingBehavior.slideshowSeconds <= 0) readingBehavior = readingBehavior.copy(slideshowSeconds = 5).also { store.personal.setReadingBehavior(site.id, it) }
                        autoPageRunning = false; slideshowRunning = true; controlsVisible = true
                    }
                }
            }
        }
    }
    private fun readingBehaviorOptions() {
        val choices = listOf("音量键翻页：${if (readingBehavior.volumeKeys) "开启" else "关闭"}",
            "音量键方向：${if (readingBehavior.reverseVolume) "反向" else "正向"}",
            if (readingBehavior.autoPageSeconds > 0) "自动翻页：每 ${readingBehavior.autoPageSeconds} 秒" else "自动翻页：关闭") +
            if (autoPageRunning) listOf("暂停自动翻页") else emptyList()
        dialogs.choose("阅读行为", choices) { index ->
            when (index) {
                0 -> readingBehavior = readingBehavior.copy(volumeKeys = !readingBehavior.volumeKeys).also { store.personal.setReadingBehavior(site.id, it); autoPageRunning = false }
                1 -> readingBehavior = readingBehavior.copy(reverseVolume = !readingBehavior.reverseVolume).also { store.personal.setReadingBehavior(site.id, it) }
                2 -> {
                    val values = listOf(0, 3, 5, 10, 20, 30)
                    dialogs.choose("自动翻页间隔", values.map { if (it == 0) "关闭" else "每 $it 秒，成功显示后推进" }, values.indexOf(readingBehavior.autoPageSeconds)) { selected ->
                        readingBehavior = readingBehavior.copy(autoPageSeconds = values[selected]).also { store.personal.setReadingBehavior(site.id, it) }
                        autoPageRunning = readingBehavior.autoPageSeconds > 0 && reading
                    }
                    return@choose
                }
                else -> autoPageRunning = false
            }
            readingBehaviorOptions()
        }
    }
    private fun readerDisplayOptions() {
        fun save(value: ReaderPreferences) {
            readerPreferences = value; store.personal.setReaderPreferences(entry, value)
            readerMode = ReaderMode.entries.firstOrNull { it.name == value.mode } ?: store.personal.readerMode(site.id)
        }
        dialogs.content = {
            AlertDialog(dialogs::dismiss, title = { Text("本书显示设置") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Selector("阅读模式 · ${readerMode.title}") {
                        dialogs.choose("本书阅读模式", ReaderMode.entries.map { it.title }, readerMode.ordinal) { selected -> save(readerPreferences.copy(mode = ReaderMode.entries[selected].name)); readerDisplayOptions() }
                    }
                    Selector("缩放适配 · ${readerPreferences.fit.title}") {
                        dialogs.choose("缩放适配", ReaderFit.entries.map { it.title }, readerPreferences.fit.ordinal) { save(readerPreferences.copy(fit = ReaderFit.entries[it])); readerDisplayOptions() }
                    }
                    Selector("起始位置 · ${readerPreferences.start.title}") {
                        dialogs.choose("打开本书时", ReaderStart.entries.map { it.title }, readerPreferences.start.ordinal) { save(readerPreferences.copy(start = ReaderStart.entries[it])); readerDisplayOptions() }
                    }
                    Selector("手动裁边 · ${readerPreferences.cropPercent}%") {
                        val values = listOf(0, 2, 5, 10, 15, 20)
                        dialogs.choose("裁边比例", values.map { "$it%" }, values.indexOf(readerPreferences.cropPercent)) { save(readerPreferences.copy(cropPercent = values[it])); readerDisplayOptions() }
                    }
                    Selector(if (readerPreferences.doubleCover) "封面与第二页合并" else "封面单独成页") {
                        dialogs.choose("双页封面规则", listOf("封面与第二页合并", "封面单独成页"), if (readerPreferences.doubleCover) 0 else 1) { save(readerPreferences.copy(doubleCover = it == 0)); readerDisplayOptions() }
                    }
                    TextAction({
                        store.personal.clearReaderPreferences(entry); readerPreferences = ReaderPreferences(); readerMode = store.personal.readerMode(site.id); dialogs.dismiss()
                    }) { Text("恢复来源默认") }
                }
            }, confirmButton = { TextAction(dialogs::dismiss) { Text("完成") } })
        }
    }
    private fun queueTools() {
        dialogs.content = {
            AlertDialog(dialogs::dismiss, title = { Text("片单接续") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { QueueNextAction(this@GalleryActivity, entry, intent.getStringExtra("queue_session"), dialogs) }
            }, confirmButton = { TextAction(dialogs::dismiss) { Text("关闭") } })
        }
    }
    @Composable private fun Overview() {
        val gallery = detail ?: return
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val noun = itemNoun
        val verb = actionVerb
        val progress = store.reading(entry.id, site.id)
        val startPage = when (readerPreferences.start) { ReaderStart.FIRST -> 1; ReaderStart.LAST -> gallery.pageCount; ReaderStart.RESUME -> progress?.page ?: 1 }
        val startOffset = if (readerPreferences.start == ReaderStart.RESUME) progress?.offset ?: 0 else 0
        val startLabel = when {
            readerPreferences.start == ReaderStart.RESUME && progress != null -> "继续$verb · 第 $startPage 页"
            readerPreferences.start == ReaderStart.RESUME -> "开始$verb"
            else -> "开始$verb · 第 $startPage 页"
        }
        val downloadRevision by app.galleryDownloads.changes.collectAsState()
        val download = remember(downloadRevision, entry.id, entry.site) { app.galleryDownloads.task(entry) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val metadata = listOf("${gallery.pageCount} ${if (entry.mediaKind == MediaKind.GALLERY && site.category != SiteCategory.COMIC) "张" else "页"}", gallery.language.takeIf { site.category == SiteCategory.COMIC }.orEmpty(), entry.rating).filter(String::isNotBlank).joinToString(" · ")
            if (!offlineOnly) MediaResumeHero(entry, metadata, startLabel) { reader(startPage, startOffset) }
            else {
                Copy(if (gallery.downloadedPages.size == gallery.pageCount) "已下载整本 · ${gallery.pageCount} 页 · 离线$verb"
                    else "部分保存 · ${gallery.downloadedPages.size} / ${gallery.pageCount} 页 · 仅已存页可离线$verb")
                Title(entry.title, large = true)
                Action(startLabel, true, modifier = Modifier.fillMaxWidth()) { reader(startPage, startOffset) }
            }
            if (gallery.alternativeTitle.isNotBlank() && gallery.alternativeTitle != entry.title) Copy(gallery.alternativeTitle)
            if (offlineOnly) Copy(metadata)
            Action(if (store.isFavorite("gallery", entry.id, site.id)) "已收藏$noun" else "收藏$noun", modifier = Modifier.fillMaxWidth()) { store.toggleFavorite(Favorite("gallery", entry.id, entry.title, entry.url, entry.thumb, entry.duration, site = site.id), site.id); revision++ }
            if (!offlineOnly && gallery.embeddedVideos.isNotEmpty()) {
                if (gallery.embeddedVideos.size > 1) Title("文章内视频 · ${gallery.embeddedVideos.size} 个")
                gallery.embeddedVideos.forEach { media ->
                    Action(if (gallery.embeddedVideos.size == 1) "观看文章内视频" else "观看${media.title}",
                        icon = R.drawable.ic_play, modifier = Modifier.fillMaxWidth()) {
                        saveProgress()
                        startActivity(Intent(this@GalleryActivity, PlayerActivity::class.java).putExtra("video", store.gson.toJson(media)))
                    }
                }
            }
            SaveForLater(entry, app, dialogs)
            QueueNextAction(this@GalleryActivity, entry, intent.getStringExtra("queue_session"), dialogs)
            Action("页面缩略图总览") { thumbnails = true }
            if (entry.site != SiteRegistry.LOCAL.id) {
            Action(when (download?.state) {
                GalleryDownloadState.COMPLETED -> if (download.partial) "继续保存所选页" else "离线$verb"
                GalleryDownloadState.DOWNLOADING, GalleryDownloadState.QUEUED -> "暂停下载 · ${download.pages} / ${download.total} 页"
                GalleryDownloadState.PAUSED -> "继续下载"
                GalleryDownloadState.RETRY_WAIT -> "等待限流 · 点击继续"
                GalleryDownloadState.FAILED -> "重试下载"
                GalleryDownloadState.REMOVING -> "正在删除下载"
                null -> if (entry.mediaKind == MediaKind.GALLERY && site.category != SiteCategory.COMIC) "下载全部图片" else "下载整本"
            }, enabled = download?.state != GalleryDownloadState.REMOVING, modifier = Modifier.fillMaxWidth()) {
                if (download?.state == GalleryDownloadState.COMPLETED && download.partial != true) { offlineOnly = true; load(true) }
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
            TextAction(::selectPages) { Text("选择页码下载") }
            if (download?.state == GalleryDownloadState.FAILED) Copy(download.error)
            }
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
        if (entry.site == SiteRegistry.LOCAL.id) return app.localLibrary.page(entry.id, target).also { images[target] = it.image }
        val gallery = detail ?: error("${itemNoun}信息尚未载入")
        val remembered = store.reading(entry.id, site.id)?.takeIf { it.page == target }?.pageUrl
        val cached = withContext(Dispatchers.IO) { app.galleries.cachedPageUrl(entry, target, sourceRoot) }
        val url = cached ?: images[target]?.pageUrl ?: images[target - 1]?.nextUrl ?: images[target + 1]?.previousUrl ?: remembered
            ?: if (offlineOnly) error("下载图页目录缺失，请重新下载") else AdapterRegistry.adapter(site, app.network).galleryPageLink(gallery, target)
        return app.galleries.page(site, url, force, galleryUrl = entry.url, entry = entry, offlineOnly = offlineOnly, sourceRoot = sourceRoot).also { images[target] = it.image }
    }
    private fun selectPages() {
        val count = detail?.pageCount ?: return
        dialogs.edit("选择页码下载", "1-${minOf(3, count)}", "例如 1,3,5-8，共 $count 页", validate = { raw ->
            runCatching { parsePageSelection(raw, count) }.exceptionOrNull()?.message
        }) { raw ->
            val pages = parsePageSelection(raw, count)
            lifecycleScope.launch {
                try {
                    app.galleryDownloads.add(entry, pages); toast("已选择 ${pages.size} 页，可在下载中心查看")
                } catch (e: Exception) { if (e is CancellationException) throw e; showError(e) }
            }
        }
    }
    private fun jumpDialog() {
        val count = detail?.pageCount ?: return
        dialogs.edit("跳到指定页", page.toString(), "1 – $count", keyboard = androidx.compose.ui.text.input.KeyboardType.Number, validate = { if (it.toIntOrNull() in 1..count) null else "请输入 1 – $count" }) { reader(it.toInt()) }
    }
    private fun readingOptions() {
        dialogs.choose("阅读模式", ReaderMode.entries.map { it.title }, readerMode.ordinal) { index ->
            saveProgress(); readerMode = ReaderMode.entries[index]; store.personal.setReaderMode(site.id, readerMode)
            jump = ReaderJump(jump.serial + 1, page, if (readerMode == ReaderMode.STRIP) stripOffset else 0)
        }
    }
    private fun bookmarks() {
        saveProgress()
        val marks = store.personal.bookmarks(entry)
        val current = marks.any { it.page == page }
        dialogs.choose("页码书签", listOf(if (current) "移除本页书签" else "添加第 $page 页书签") + marks.map { "跳到第 ${it.page} 页" } + if (marks.isNotEmpty()) listOf("删除书签…") else emptyList()) { index ->
            when {
                index == 0 -> store.personal.toggleBookmark(entry, page, stripOffset)
                index <= marks.size -> marks[index - 1].let { reader(it.page.coerceIn(1, detail!!.pageCount), it.offset) }
                else -> dialogs.choose("删除书签", marks.map { "第 ${it.page} 页" }) { store.personal.toggleBookmark(entry, marks[it].page) }
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (reading && readingBehavior.volumeKeys && keyCode in setOf(android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && detail != null) {
            val forward = (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) != readingBehavior.reverseVolume
            autoPageRunning = false; slideshowRunning = false
            val target = if (event.repeatCount > 0) { if (forward) detail!!.pageCount else 1 }
                else (page + if (forward) 1 else -1).coerceIn(1, detail!!.pageCount)
            reader(target)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onSaveInstanceState(outState: Bundle) { saveProgress(); outState.putBoolean("offline", offlineOnly); outState.putInt("page", page); outState.putInt("stripOffset", stripOffset); outState.putBoolean("reading", reading); outState.putBoolean("controlsVisible", controlsVisible); super.onSaveInstanceState(outState) }
    override fun onStop() { autoPageRunning = false; slideshowRunning = false; saveProgress(); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyBars() }
}
