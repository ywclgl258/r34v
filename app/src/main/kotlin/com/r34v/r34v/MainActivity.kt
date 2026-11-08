@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.offline.Download
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    private val feeds by viewModels<FeedStates>()
    private val crossSearch by viewModels<CrossSearchState>()
    private val app get() = application as R34Application
    private val store get() = app.library
    private var siteId by mutableStateOf("")
    private var screen by mutableStateOf("home")
    private var request by mutableStateOf(FeedRequest())
    private var libraryTab by mutableStateOf("片库")
    private var favoriteTab by mutableStateOf("视频")
    private var searchQuery by mutableStateOf("")
    private var searchScope by mutableIntStateOf(0)
    private var multipleSources by mutableStateOf(false)
    private var searchSites by mutableStateOf(listOf<String>())
    private var searchCategory by mutableStateOf("")
    private var searchLanguages by mutableStateOf<Map<String, String>>(emptyMap())
    private var searchCondition by mutableStateOf(SearchCondition())
    private var importingUri by mutableStateOf<Uri?>(null)
    private var importingDirectory by mutableStateOf(false)
    private var navigationTab by mutableStateOf("home")
    private var revision by mutableIntStateOf(0)
    private var resumed by mutableStateOf(false)
    private var tutorial by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private val backStack = ArrayDeque<Pair<String, FeedRequest>>()
    private fun showNotice(text: String) { notice = text }
    private val dialogs = ComposeDialogs()
    private val archivePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importingDirectory = false; importingUri = it } }
    private val directoryPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { importingDirectory = true; importingUri = it } }
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) navigate("home", false)
    }
    private fun loginOfficial() = officialLogin.launch(Intent(this, SiteAuthActivity::class.java).putExtra("site", siteId))
    private val storageAccess = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { storageOptions() }
    private val legacyStorageAccess = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { storageOptions() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        siteId = store.siteId
        searchSites = store.string("cross_search_sites").split(',').filter { SiteRegistry.byId(it) != null }.ifEmpty { searchableSiteIds(app.network) }
        if (savedInstanceState != null) {
            screen = savedInstanceState.getString("screen") ?: "home"
            navigationTab = savedInstanceState.getString("navigationTab") ?: "home"
            request = runCatching { store.gson.fromJson(savedInstanceState.getString("request"), FeedRequest::class.java) }.getOrNull() ?: FeedRequest()
            searchQuery = savedInstanceState.getString("searchQuery").orEmpty(); searchScope = savedInstanceState.getInt("searchScope")
            multipleSources = savedInstanceState.getBoolean("multipleSources")
            searchSites = savedInstanceState.getStringArrayList("searchSites")?.filter { id -> SiteRegistry.all.any { it.id == id } }?.takeIf { it.isNotEmpty() } ?: searchSites
            searchCategory = savedInstanceState.getString("searchCategory").orEmpty()
            searchLanguages = runCatching { store.gson.fromJson(savedInstanceState.getString("searchLanguages"), object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type) as Map<String,String> }.getOrDefault(emptyMap())
            searchCondition = runCatching { store.gson.fromJson(savedInstanceState.getString("searchCondition"), SearchCondition::class.java) }.getOrNull() ?: SearchCondition(searchQuery)
            importingUri = savedInstanceState.getString("importingUri")?.let(Uri::parse); importingDirectory = savedInstanceState.getBoolean("importingDirectory")
            libraryTab = savedInstanceState.getString("libraryTab") ?: "片库"; favoriteTab = savedInstanceState.getString("favoriteTab") ?: "视频"
            savedInstanceState.getStringArrayList("backScreens").orEmpty().zip(savedInstanceState.getStringArrayList("backRequests").orEmpty()).forEach { (route, json) ->
                runCatching { store.gson.fromJson(json, FeedRequest::class.java) }.getOrNull()?.let { backStack.addLast(route to it) }
            }
        } else intent.getStringExtra("link")?.let { link ->
            val site = link.toHttpUrlOrNull()?.host?.let(SiteRegistry::forHost)
            val linked = site?.let { AdapterRegistry.adapter(it, app.network).linkRequest(link) }
            if (site != null && linked != null) {
                store.switchSite(site.id); siteId = site.id
                val section = AdapterRegistry.adapter(site, app.network).indexSection(linked)
                request = if (section == null) linked else FeedRequest(target = section)
                screen = if (section == null) "feed" else "index"
            }
        }
        if (intent.getBooleanExtra("downloads", false)) { libraryTab = "下载"; navigate("library", false); intent.removeExtra("downloads") }
        setContent { R34Theme { AppContent() } }
        openLocalImport(intent)
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) receiveShare(intent)
        if (!BuildConfig.DEBUG && store.bool("auto_update", true) && System.currentTimeMillis() - store.prefs.getLong("update_checked_at", 0) > 12 * 60 * 60 * 1000) {
            lifecycleScope.launch { UpdateController(this@MainActivity, app, dialogs).check(true) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) receiveShare(intent)
        openLocalImport(intent)
        if (intent.getBooleanExtra("downloads", false)) { libraryTab = "下载"; navigate("library"); intent.removeExtra("downloads") }
    }
    override fun onResume() { super.onResume(); resumed = true; revision++; app.galleryDownloads.resumePending() }
    override fun onPause() { resumed = false; tutorial = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen); outState.putString("navigationTab", navigationTab); outState.putString("request", store.gson.toJson(request))
        outState.putString("searchQuery", searchQuery); outState.putInt("searchScope", searchScope); outState.putString("libraryTab", libraryTab); outState.putString("favoriteTab", favoriteTab)
        outState.putBoolean("multipleSources", multipleSources)
        outState.putStringArrayList("searchSites", ArrayList(searchSites))
        outState.putString("searchCategory", searchCategory); outState.putString("searchLanguages", store.gson.toJson(searchLanguages)); outState.putString("searchCondition", store.gson.toJson(searchCondition))
        outState.putString("importingUri", importingUri?.toString()); outState.putBoolean("importingDirectory", importingDirectory)
        outState.putStringArrayList("backScreens", ArrayList(backStack.map { it.first })); outState.putStringArrayList("backRequests", ArrayList(backStack.map { store.gson.toJson(it.second) }))
        super.onSaveInstanceState(outState)
    }
    private fun navigate(target: String, push: Boolean = true) {
        tutorial = false; dialogs.dismiss()
        if (push && screen != target) backStack.addLast(screen to request)
        screen = target
        if (target in listOf("home", "explore", "search", "library")) navigationTab = target
        if (target == "siteHome") navigationTab = "explore"
    }
    private fun back() {
        if (backStack.isNotEmpty()) { val previous = backStack.removeLast(); request = previous.second; navigate(previous.first, false) }
        else if (screen != "home") navigate("home", false) else finish()
    }
    private fun switchSite(site: SiteContext) {
        if (site.id == siteId) return
        dialogs.dismiss(); tutorial = false; store.switchSite(site.id); siteId = site.id
        backStack.clear(); request = FeedRequest(); searchQuery = ""; searchScope = 0; libraryTab = "记录"; favoriteTab = if (site.mediaKind == MediaKind.GALLERY) site.category.itemNoun else "视频"
        navigate("siteHome", false); showNotice("已切换到 ${site.name}")
    }
    private fun openFeed(value: FeedRequest) {
        backStack.addLast(screen to request)
        val section = AdapterRegistry.adapter(SiteRegistry.byIdOrDefault(siteId), app.network).indexSection(value)
        request = if (section == null) value else FeedRequest(target = section)
        navigate(if (section == null) "feed" else "index", false)
    }
    private fun browse(segment: String) { backStack.addLast(screen to request); request = FeedRequest(target = segment); navigate("index", false) }
    private fun play(video: Video, offlineId: String? = null, localPath: String? = null, read: Boolean = false, offline: Boolean = false, sourceRoot: String = "", page: Int = 0) {
        if (video.site == SiteRegistry.LOCAL.id) {
            if (page > 0) startActivity(Intent(this, GalleryActivity::class.java).putExtra("gallery", store.gson.toJson(video)).putExtra("read", true).putExtra("page", page))
            else MediaRouter.open(this, video, read = true)
            return
        }
        store.personal.markRead(video.site, setOf(video.entryKey))
        val gallery = video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY
        startActivity(if (gallery) Intent(this, GalleryActivity::class.java).putExtra("gallery", store.gson.toJson(video.copy(mediaKind = MediaKind.GALLERY))).putExtra("read", read).putExtra("offline", offline).putExtra("root", sourceRoot).putExtra("page", page)
        else Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video)).putExtra("download", offlineId).putExtra("local", localPath))
    }

    /** Plays a cache entry found by the disk scan: no Media3 download record exists for it. */
    private fun playOffline(record: LibraryRecord) {
        if (record.isComic) play(record.video, read = true, offline = true, sourceRoot = record.sourceRoot)
        else startActivity(Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(record.video))
            .putExtra("cached", record.cacheKey).putExtra("root", record.sourceRoot))
    }

    private suspend fun libraryScan(): Int = withContext(Dispatchers.IO) {
        val known = app.playback.downloads().mapNotNull { task ->
            app.playback.metadata(task)?.let { meta ->
                LibraryRecord(kind = LibraryRecord.KIND_VIDEO, site = meta.site, id = meta.video.id, quality = meta.quality,
                    cacheKey = task.request.id, title = meta.video.title, url = meta.video.url, thumb = meta.video.thumb,
                    duration = meta.video.duration, bytes = task.bytesDownloaded, at = System.currentTimeMillis())
            }
        }
        app.index.dropCorruptSidecars()
        app.index.scan(app.playback::cached, known).count { it.state == LibraryRecord.STATE_COMPLETED }
    }
    private fun toggleSubscription(entry: Subscription) {
        if (entry.slug.isBlank()) return
        val followed = store.toggleSubscription(entry, siteId)
        revision++
        showNotice(if (followed) "已订阅 ${entry.title}" else "已取消订阅 ${entry.title}")
    }

    /** Jumps from a followed tag back into its normal feed, keeping the current site. */
    private fun openTag(entry: Subscription) {
        if (entry.slug.isBlank()) { request = FeedRequest(target = "tags"); navigate("index", false); return }
        openFeed(FeedRequest(FeedKind.TAG, entry.slug))
    }

    private fun favorite(video: Video) {
        val kind = if (video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY) "gallery" else "video"
        showNotice(if (store.toggleFavorite(Favorite(kind, video.id, video.title, video.url, video.thumb, video.duration, site = video.site), video.site)) "已收藏" else "已取消收藏"); revision++
    }
    private fun downloadBatch(videos: List<Video>) {
        val downloadable = videos.filter { it.site != SiteRegistry.LOCAL.id }.distinctBy { it.entryKey }
        if (downloadable.isEmpty()) { showNotice("所选内容已经在本地，无需下载"); return }
        dialogs.confirm("下载 ${downloadable.size} 部作品？", "加入下载", "视频使用默认清晰度 ${store.string("quality", "720p")}，图集下载整本。") {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
            runCatching { app.batchDownloads.start(downloadable); libraryTab = "下载"; navigate("library"); showNotice("正在加入下载，可在下载中心查看进度") }
                .onFailure { dialogs.message("无法开始批量下载", it.message.orEmpty()) }
        }
    }
    private fun savedSearch(value: SavedSearch?) {
        if (value == null) { navigate("search"); return }
        searchQuery = value.query; searchCondition = value.condition
        searchSites = value.sources.filter { id -> SiteRegistry.all.any { it.id == id } }
        searchCategory = value.category; searchLanguages = value.languages; multipleSources = true
        if (searchSites.isEmpty()) { showNotice("搜索来源已不可用，请重新选择"); navigate("search") } else navigate("multiSearch")
    }
    private fun saveSearch(onSaved: () -> Unit = {}) {
        val condition = searchCondition.copy(query = searchQuery).normalized()
        if (condition.isEmpty) { showNotice("请输入关键词或添加筛选条件"); return }
        val sources = if (multipleSources) searchSites else listOf(siteId)
        dialogs.edit("保存搜索方案", searchQuery.ifBlank { "高级条件" }, validate = { if (it.isBlank() || it.length > 80) "名称需要 1–80 个字" else null }) { name ->
            store.personal.saveSearch(SavedSearch(name = name, query = condition.query, sources = sources, category = searchCategory,
                languages = sources.associateWith { searchLanguages[it] ?: store.galleryLanguage(it).name }, includeTags = condition.includeTags,
                excludeTags = condition.excludeTags, filter = condition.filter))
            showNotice("已保存到常用搜索"); revision++; onSaved()
        }
    }
    private fun openChannel(channel: DiscoveryChannel) {
        channel.savedSearch?.let { savedSearch(it); return }
        val target = SiteRegistry.byId(channel.site) ?: return
        switchSite(target); openFeed(FeedRequest(channel.kind, channel.target))
    }
    private fun pinCurrentChannel() {
        if (request.kind !in listOf(FeedKind.SEARCH, FeedKind.TAG, FeedKind.CATEGORY, FeedKind.MODEL, FeedKind.CHARACTER)) return
        dialogs.edit("固定到发现", AdapterRegistry.adapter(SiteRegistry.byIdOrDefault(siteId), app.network).feedTitle(request).ifBlank { request.target },
            validate = { if (it.isBlank() || it.length > 80) "名称需要 1–80 个字" else null }) { name ->
            store.personal.pinFeed(siteId, request, name); revision++; showNotice("已固定到首页的我的频道")
        }
    }
    private fun manageChannels() {
        dialogs.content = {
            val personalRevision by store.personal.changes.collectAsState()
            val channels = remember(personalRevision) { store.personal.snapshot().channels.sortedBy { it.order } }
            AlertDialog(dialogs::dismiss, title = { Text("管理发现频道") }, text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (channels.isEmpty()) item { Copy("在保存搜索、分类或作者页面选择“固定到发现”。") }
                    items(channels, key = { it.id }) { channel ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(channel.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Copy(if (channel.hidden) "已隐藏" else channel.savedSearch?.let { "保存搜索" } ?: "${SiteRegistry.byIdOrDefault(channel.site).name} · ${channel.kind.title}")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextAction({ store.personal.moveChannel(channel.id, -1) }) { Text("上移") }
                                TextAction({ store.personal.moveChannel(channel.id, 1) }) { Text("下移") }
                                TextAction({ store.personal.hideChannel(channel.id, !channel.hidden) }) { Text(if (channel.hidden) "显示" else "隐藏") }
                                TextAction({ store.personal.removeChannel(channel.id) }) { Text("删除") }
                            }
                        }
                    }
                }
            }, confirmButton = { TextAction(dialogs::dismiss) { Text("完成") } })
        }
    }
    private fun importLocal() { dialogs.choose("导入本地漫画", listOf("选择 CBZ / ZIP 文件", "选择图片目录")) { index ->
        if (index == 0) archivePicker.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) else directoryPicker.launch(null)
    } }
    private fun openLocalImport(value: Intent) {
        if (!value.getBooleanExtra("import_local", false)) return
        value.removeExtra("import_local"); backStack.clear(); libraryTab = "片库"; navigate("library", false); importLocal()
    }
    private fun receiveShare(value: Intent) {
        val text = value.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(); value.removeExtra(Intent.EXTRA_TEXT)
        confirmLink(text)
    }
    private fun confirmLink(text: String) {
        val link = runCatching { incomingLink(text) }.getOrElse { dialogs.message("无法识别作品链接", it.message.orEmpty()); return }
        dialogs.confirm("在 R34V 中打开？", "识别并打开", "${link.site.name}\n${link.url}") { openIncoming(link) }
    }
    private fun pasteLink() { dialogs.edit("从链接打开作品", hint = "粘贴支持站点的完整链接", validate = { runCatching { incomingLink(it) }.exceptionOrNull()?.message }) { confirmLink(it) } }
    private fun openIncoming(link: IncomingLink) {
        val adapter = AdapterRegistry.adapter(link.site, app.network)
        val target = adapter.linkRequest(link.url)
        if (target != null) { store.switchSite(link.site.id); siteId = link.site.id; openFeed(target); return }
        dialogs.content = {
            var error by remember { mutableStateOf<String?>(null) }; var retry by remember { mutableIntStateOf(0) }
            LaunchedEffect(link.url, retry) { error = null; try {
                val video = withContext(Dispatchers.IO) { if (link.site.mediaKind == MediaKind.GALLERY) adapter.gallery(link.url).entry else adapter.detail(link.url).video }
                dialogs.dismiss(); play(video.copy(site = link.site.id, mediaKind = link.site.mediaKind))
            } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "作品识别失败" } }
            AlertDialog({ dialogs.dismiss() }, title = { Text("打开 ${link.site.name} 作品") }, text = { if (error == null) Loading(title = "正在识别作品…") else Text(error.orEmpty()) },
                confirmButton = { if (error != null) TextAction({ retry++ }) { Text("重试") } }, dismissButton = { TextAction({ dialogs.dismiss() }) { Text("取消") } })
        }
    }
    @Composable private fun AppContent() {
        val site = SiteRegistry.byIdOrDefault(siteId)
        val adapter = remember(site) { AdapterRegistry.adapter(site, app.network) }
        val drawer = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
        var languageVisible by remember(site, screen) { mutableStateOf(true) }
        var language by remember(site) { mutableStateOf(store.galleryLanguage()) }
        var scrollDistance by remember(site, screen) { mutableFloatStateOf(0f) }
        val holder = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }
        val density = LocalDensity.current
        var sourceTopInset by remember { mutableStateOf(132.dp) }
        val immersiveHome = screen == "siteHome" && site.mediaKind == MediaKind.VIDEO
        val landscape = windowSizeDp().width > windowSizeDp().height
        val compactHeader = windowSizeDp().width < 360 || LocalConfiguration.current.fontScale > 1.3f
        val condensedTop = immersiveHome && !languageVisible
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); notice = null } }
        fun scroll(dy: Float, top: Boolean) {
            if (top || dy < 0) { languageVisible = true; scrollDistance = 0f }
            else { scrollDistance += dy; if (scrollDistance > dp(18)) languageVisible = false }
        }
        BackHandler { when { tutorial -> tutorial = false; drawer.isOpen -> scope.launch { drawer.close() }; else -> back() } }
        LaunchedEffect(screen, resumed, drawer.isOpen) {
            if (resumed && screen == "home" && !drawer.isOpen && store.needsSiteSwitchTutorial) {
                withFrameNanos { }; if (store.consumeSiteSwitchTutorial()) tutorial = true
            }
        }
        CompositionLocalProvider(LocalSourceTopInset provides sourceTopInset) {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp), drawerContainerColor = SurfaceColor) {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp).then(Modifier.semantics { contentDescription = "站点列表" })) {
                    item { Title("切换站点", Modifier.padding(12.dp), true) }
                    // Grouped by what the site serves: one flat list of eight names said nothing
                    // about whether a tap leads to a video, a comic or a photo set.
                    SiteCategory.entries.forEach { category ->
                        val group = SiteRegistry.ofCategory(category)
                        if (group.isEmpty()) return@forEach
                        item(key = "group:${category.name}") {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                                Text(category.title, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White)
                                Text("${category.blurb} · ${group.size} 个站点", color = MutedColor, fontSize = 11.sp)
                            }
                        }
                        items(group, key = { it.id }) { target ->
                            Surface(color = if (target.id == site.id) RaisedColor else Color.Transparent, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(target.id == site.id, role = Role.Tab) { switchSite(target); scope.launch { drawer.close() } }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(target.name)
                                    if (target.id == "exhentai") Copy("需登录及账号权限")
                                    else if (target.accessNote.isNotBlank()) Copy(target.accessNote)
                                }
                            }
                        }
                    }
                }
            }
        }) {
            val appTopBar: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().background(if (immersiveHome && !condensedTop)
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Black.copy(alpha = .5f), Color.Transparent))
                    else Brush.verticalGradient(listOf(CanvasColor, CanvasColor))).onSizeChanged { sourceTopInset = with(density) { it.height.toDp() } }.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (screen in listOf("settings", "feed", "index", "subscriptions", "multiSearch", "backup", "siteHome", "updates", "searchUpdates")) IconAction(R.drawable.ic_back, "返回") { back() }
                        if (screen in listOf("home", "siteHome")) { BrandWordmark(); Spacer(Modifier.width(6.dp)) }
                        else if (screen == "library" && libraryTab == "片库") Text("我的片库", Modifier.weight(1f), fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        else Text(when (screen) { "search" -> "搜索"; "multiSearch" -> "跨站搜索"; "backup" -> "备份与恢复"; "updates", "searchUpdates" -> "更新"; "explore" -> "发现"; "library" -> "片库"; "settings" -> "设置"; "subscriptions" -> "订阅"; "feed" -> adapter.feedTitle(request); else -> "浏览索引" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (screen in listOf("search", "explore")) 24.sp else 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        if (screen != "library" || libraryTab != "片库") Box {
                            TextAction({ tutorial = false; scope.launch { drawer.open() } }, Modifier.heightIn(min = 48.dp).then(if (tutorial) Modifier.background(Color.White.copy(alpha = .16f), RoundedCornerShape(14.dp)) else Modifier).semantics { contentDescription = "切换站点，当前 ${site.name}" }) {
                                if (!compactHeader) Text(site.name, Modifier.widthIn(max = if (screen == "home") 112.dp else 100.dp), color = MutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                                Glyph(R.drawable.ic_chevron_down, null, Modifier.padding(start = if (compactHeader) 0.dp else 4.dp).size(if (compactHeader) 20.dp else 12.dp))
                            }
                            DropdownMenu(tutorial, { tutorial = false }, Modifier.widthIn(max = 320.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Title("这里可以切换站点"); Copy("点击顶部的站点名称或小箭头，即可切换视频站和漫画站。"); Copy("收藏、历史和下载会按站点分别保存。")
                                    Action("打开站点列表", primary = true) { tutorial = false; scope.launch { drawer.open() } }; TextAction({ tutorial = false }) { Text("知道了") }
                                }
                            }
                        }
                        if (screen in listOf("home", "siteHome")) { Spacer(Modifier.weight(1f)); IconAction(R.drawable.ic_search, "搜索内容") { navigate("search") } }
                        if (screen == "feed") IconAction(R.drawable.ic_add, "固定当前频道", click = ::pinCurrentChannel)
                        if (screen == "library") IconAction(R.drawable.ic_bookmark, "订阅") { navigate("subscriptions") }
                        if (screen != "settings") ProfileAction { navigate("settings") }
                    }
                    if (compactHeader && screen != "home" && !(screen == "library" && libraryTab == "片库") && !(screen == "search" && multipleSources)) Copy("${site.category.title} · ${site.name}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    if (immersiveHome && !landscape) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("精选内容", selected = true) { navigate("siteHome", false) }
                        Choice("热门榜") { openFeed(FeedRequest(FeedKind.POPULAR)) }
                        Choice("分类") { navigate("explore") }
                    }
                    // Translation language only exists on the manga sites; a photo archive has no
                    // translations, and showing the bar there would promise a filter that is absent.
                    AnimatedVisibility(adapter.supportsLanguage && screen in listOf("siteHome", "feed", "search", "explore") && !(screen == "search" && multipleSources) && languageVisible) {
                        Box(Modifier.padding(horizontal = 16.dp)) { Choices(listOf("全部", "中文", "日文", "中＋日"), language.ordinal) { index -> language = GalleryLanguage.entries[index]; store.setGalleryLanguage(language); request = request.copy(language = language, page = 1, cursor = "") } }
                    }
                }
            }
            Scaffold(containerColor = CanvasColor, contentWindowInsets = if (immersiveHome) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = RaisedColor, contentColor = Color.White, shape = RoundedCornerShape(16.dp)) } },
                topBar = { if (!immersiveHome) appTopBar() }, bottomBar = {
                    CinemaNavigation(navigationTab) { key -> backStack.clear(); if (key == "library") libraryTab = "片库"; navigate(key, false) }
                }) { padding ->
                Box(Modifier.fillMaxSize()) {
                Box(Modifier.padding(start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()).fillMaxSize().imePadding()) {
                    // Revision is observable because the persisted store intentionally remains framework independent.
                    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
                    val exGate = site.id == "exhentai" && !app.network.session.hasGallerySession(site)
                    if (screen !in listOf("home", "settings", "search", "multiSearch", "backup", "updates", "searchUpdates") && !(screen == "library" && libraryTab in listOf("下载", "缓存", "片单", "片库")) && exGate) LoginGate()
                    else key(site.id) { holder.SaveableStateProvider("${site.id}|$screen|${if (screen == "feed" || screen == "index") request.id else ""}") {
                        when (screen) {
                            "home" -> PersonalHomeScreen(app, rememberPersonalCatalog(app, revision, resumed), { MediaRouter.open(this@MainActivity, it) },
                                waiting = { libraryTab = "片单"; navigate("library") }, updates = { navigate("updates") }, search = ::savedSearch, discover = { navigate("siteHome") },
                                channel = ::openChannel, manageChannels = ::manageChannels)
                            "siteHome", "feed" -> {
                                val effective = (if (screen == "siteHome") adapter.homeRequest() else request).copy(language = language)
                                val blocked = store.blockedTags(site.id)
                                val feedState = remember(site.id, screen, effective, blocked) { feeds.page(site.id, screen == "siteHome", effective, blocked) }
                                key(feedState) { FeedScreen(app, site, effective, screen == "siteHome", feedState, dialogs, { play(it) }, ::favorite, ::scroll,
                                permissionDenied = { revision++ }, saved = store.favorites(site.id).filter { it.kind == "video" }.map { "${it.site}:${it.target}" }.toSet(),
                                continuing = store.history(site.id).filter { it.resumable && store.allowed(it.video()) }.take(12), browse = ::openFeed,
                                subscribe = { toggleSubscription(it) },
                                subscribed = request.kind == FeedKind.TAG && store.isSubscribed(request.target, site.id),
                                sort = { request = it }) }
                            }
                            "explore" -> Column { Action("${site.name} · 最新内容", modifier = Modifier.fillMaxWidth().padding(16.dp)) { navigate("siteHome") }; Box(Modifier.weight(1f)) { ExploreScreen(adapter, ::openFeed, ::browse) } }
                            "index" -> IndexScreen(app, site, request.target, dialogs, ::openFeed, ::browse, ::toggleSubscription)
                            "search" -> SearchScreen(adapter)
                            "multiSearch" -> CrossSearchScreen(app, crossSearch, searchQuery, searchSites, { play(it) }, ::favorite, revision, searchCategory, searchLanguages, { searchCategory = it }, ::saveSearch, condition = searchCondition)
                            "updates", "searchUpdates" -> GlobalInboxScreen(app, { play(it) }, dialogs, manageSubscriptions = { navigate("subscriptions") }, initialSearch = screen == "searchUpdates")
                            "library" -> LibraryScreen(site)
                            "subscriptions" -> SubscriptionScreen(app, site, dialogs, { play(it) }, ::favorite,
                                store.favorites(site.id).filter { it.kind in listOf("video", "gallery") }.map { "${it.site}:${it.target}" }.toSet(),
                                openTag = { openTag(it) }, revision = { revision++ })
                            "settings" -> SettingsScreen(site)
                            "backup" -> BackupScreen(app) {
                                searchSites = store.string("cross_search_sites").split(',').filter { SiteRegistry.byId(it) != null }.ifEmpty { searchableSiteIds(app.network) }
                                revision++
                            }
                        }
                    } }
                }
                if (immersiveHome) appTopBar()
                }
            }
        }
        }
        dialogs.Host()
        importingUri?.let { uri -> LocalImportDialog(app, uri, importingDirectory, { importingUri = null }) { video -> importingUri = null; revision++; libraryTab = "片库"; navigate("library"); play(video, read = true) } }
    }
    @Composable private fun LoginGate() {
        Column { EmptyState("ExHentai 必须登录", "未登录时原站可能只显示白屏。请通过官方论坛登录，并确认账号具备 ExHentai 访问权限。", "官方网页登录", ::loginOfficial)
            TextAction({ navigate("settings") }, Modifier.padding(horizontal = 24.dp)) { Text("站点设置 / 导入 Cookie") }
        }
    }
    @Composable private fun SearchScreen(adapter: SiteAdapter) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val gallery = adapter.site.mediaKind == MediaKind.GALLERY
        val noun = adapter.itemNoun
        var error by remember { mutableStateOf(false) }; val keyboard = LocalSoftwareKeyboardController.current; val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        fun submit() {
            val query = searchQuery.trim(); val condition = searchCondition.copy(query = query).normalized()
            if (condition.isEmpty) { error = true; focus.requestFocus(); return }
            searchQuery = query; searchCondition = condition; if (query.isNotBlank()) store.addSearch(query); focusManager.clearFocus(); keyboard?.hide()
            if (multipleSources) navigate("multiSearch") else openFeed(adapter.searchRequest(query, when (searchScope) { 1 -> FeedKind.TAG; 2 -> FeedKind.MODEL; else -> FeedKind.SEARCH }).copy(
                includeTags = condition.includeTags, excludeTags = condition.excludeTags, filter = condition.filter))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalUiDensity.current.pagePadding), verticalArrangement = Arrangement.spacedBy(if (LocalUiDensity.current.compact) 10.dp else 14.dp)) {
            TextField(searchQuery, { searchQuery = it; error = false }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text("搜索关键词") }, singleLine = true, isError = error,
                shape = RoundedCornerShape(10.dp), leadingIcon = { Glyph(R.drawable.ic_search, null) },
                colors = filledFieldColors(),
                supportingText = if (error) ({ Text("请输入关键词") }) else null,
                trailingIcon = { Row { if (searchQuery.isNotEmpty()) IconAction(R.drawable.ic_close, "清除关键词") { searchQuery = "" }; IconAction(R.drawable.ic_chevron_right, "提交搜索") { submit() } } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            ContentTabs(listOf("当前站点", "多个站点"), if (multipleSources) 1 else 0) { multipleSources = it == 1 }
            if (multipleSources) TextAction({ dialogs.searchSources(app, searchSites) { searchSites = it; store.set("cross_search_sites", it.joinToString(",")) } }) { Text("选择来源 · ${searchSites.size} 个站点") }
            // 4KHD only has a plain keyword search, so it gets one scope instead of three chips
            // that would all quietly do the same thing.
            if (!multipleSources && adapter.site.category != SiteCategory.PHOTO) Choices(if (gallery) listOf(noun, "标签", adapter.creatorTitle) else listOf("视频", "标签", "作者"), searchScope) { searchScope = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction({ dialogs.advancedSearch(app, searchCondition.copy(query = searchQuery), if (multipleSources) searchSites else listOf(siteId)) { value ->
                    searchCondition = value; searchQuery = value.query; revision++
                } }) { Text("标签与高级筛选") }
                if (multipleSources) TextAction({ dialogs.choose("漫画语言条件", GalleryLanguage.entries.map { it.title }) { selected -> searchLanguages = searchSites.filter { AdapterRegistry.adapter(SiteRegistry.byIdOrDefault(it), app.network).supportsLanguage }.associateWith { GalleryLanguage.entries[selected].name } } }) { Text("漫画语言") }
                AnimatedVisibility(!searchCondition.copy(query = searchQuery).isEmpty) {
                    TextAction({ saveSearch { keyboard?.hide(); focusManager.clearFocus() } }) { Text("保存搜索方案") }
                }
            }
            val personalRevision by store.personal.changes.collectAsState()
            val saved = remember(personalRevision) { store.personal.snapshot().savedSearches }
            if (saved.isNotEmpty()) {
                HorizontalDivider(color = RaisedColor)
                Title("常用搜索")
                NavigationRow("保存搜索更新与未读", R.drawable.ic_updates) { navigate("searchUpdates") }
                saved.forEach { value -> Row(Modifier.fillMaxWidth().contentPanel(8), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable { savedSearch(value) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(R.drawable.ic_search, null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(value.name, style = MaterialTheme.typography.titleSmall)
                            Copy(value.query.ifBlank { "${value.sources.size} 个来源 · 已保存筛选" })
                        }
                    }
                    IconAction(R.drawable.ic_more, "管理搜索 ${value.name}") { dialogs.choose(value.name, listOf("重命名", "固定到发现", "删除搜索方案")) { action ->
                        when (action) {
                            0 -> dialogs.edit("重命名搜索方案", value.name, validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { store.personal.saveSearch(value.copy(name = it)) }
                            1 -> { store.personal.pinSearch(value); showNotice("已固定到首页的我的频道") }
                            else -> dialogs.confirm("删除「${value.name}」？") { store.personal.removeSearch(value.id) }
                        }
                    } }
                } }
            }
            HorizontalDivider(color = RaisedColor)
            Row(verticalAlignment = Alignment.CenterVertically) { Title("最近搜索", Modifier.weight(1f)); if (store.searches().isNotEmpty()) TextAction({ store.clearSearches(); revision++ }) { Text("清空") } }
            if (store.searches().isEmpty()) Copy("搜索过的关键词会保存在这里")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { store.searches().forEach { query -> Choice(query) { searchQuery = query; submit() } } }
        }
    }
    @Composable private fun LibraryScreen(site: SiteContext) {
        val catalog = if (libraryTab in listOf("片库", "片单")) rememberPersonalCatalog(app, revision, resumed) else emptyList()
        if (libraryTab == "片库") {
            LibraryHubScreen(app, dialogs, catalog, { MediaRouter.open(this@MainActivity, it) }, task = { label ->
                if (label == "更新") navigate("updates") else if (label == "标签订阅") navigate("subscriptions") else { favoriteTab = if (site.mediaKind == MediaKind.GALLERY) site.category.itemNoun else "视频"; libraryTab = if (label == "收藏") favoriteTab else label }
            }, search = { navigate("search") }, importLocal = ::importLocal, openLink = ::pasteLink, download = ::downloadBatch,
                image = { favorite -> play(favorite.video, read = true, page = favorite.page) })
            return
        }
        if (libraryTab == "片单") {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                ShelfScreen(app, dialogs, { MediaRouter.open(this@MainActivity, it) }, ::downloadBatch,
                    catalog = catalog, onBack = { libraryTab = "片库" }) { navigate("search") }
            }
            return
        }
        val reading = site.mediaKind == MediaKind.GALLERY
        val noun = site.category.itemNoun
        val verb = site.category.verb
        val task = if (libraryTab == "记录") 0 else if (libraryTab in listOf("下载", "缓存")) 2 else 1
        val creator = AdapterRegistry.adapter(site, app.network).creatorTitle
        val categories = if (reading) listOf(noun, creator, "标签") else listOf("视频", "角色", "作者", "标签")
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            TextAction({ libraryTab = "片库" }) { Text("返回我的片库") }
            if (task != 2) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_bookmark, null, Modifier.size(18.dp))
                Text("我的片库", Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Copy(site.name, Modifier.padding(start = 8.dp))
            }
            Choices(if (reading) listOf(verb, "收藏", "下载", "片单") else listOf("观看", "收藏", "下载", "片单"), task) { libraryTab = when (it) { 0 -> "记录"; 2 -> "下载"; 3 -> "片单"; else -> favoriteTab } }
            if (task == 1) Choices(categories, categories.indexOf(favoriteTab)) { favoriteTab = categories[it]; libraryTab = favoriteTab }
            if (task == 2) DownloadCenter(app, resumed, dialogs,
                video = { entry, id, path -> play(entry, id, path) },
                offline = { record -> playOffline(record) },
                comic = { play(it, read = true, offline = true) },
                discover = { navigate("explore", false) },
                account = { owner -> SiteRegistry.byId(owner)?.let(::switchSite); navigate("settings") },
                rescan = { withContext(Dispatchers.IO) { libraryScan() } })
            else {
                val records = if (task == 0) {
                    if (reading) store.readings().map { LibraryCard(it.entry.copy(duration = "第 ${it.page} / ${it.pages} 页"), "移除${verb}记录", { store.removeReading(it.entry.id); revision++ }, read = true) }
                    else store.history().map { watch -> LibraryCard(watch.video().copy(duration = if (watch.finished) "已看完" else "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}"), "移除观看记录", { store.removeWatch(watch.id); revision++ }, progress = if (watch.duration > 0) watch.position.toFloat() / watch.duration else 0f) }
                } else {
                    val kind = when (favoriteTab) { noun, "视频" -> if (reading) "gallery" else "video"; "角色" -> "character"; "作者", "上传者", "画师", "模特" -> "model"; else -> "tag" }
                    store.favorites().filter { it.kind == kind && kind in listOf("video", "gallery") }.map { f -> LibraryCard(Video(f.target, f.title, site.absolute(f.path), f.thumb, f.subtitle, site = f.site, mediaKind = site.mediaKind), "取消收藏", { store.toggleFavorite(f); revision++ }) }
                }
                if (task == 1 && favoriteTab !in listOf("视频", noun)) LazyColumn {
                    val kind = when (favoriteTab) { "角色" -> "character"; "作者", "上传者", "画师", "模特" -> "model"; else -> "tag" }
                    val entries = store.favorites().filter { it.kind == kind }
                    if (entries.isEmpty()) item { LibraryEmpty("还没有收藏内容") }
                    items(entries, key = { it.key }) { f -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAction({ openFeed(FeedRequest(if (kind == "model") FeedKind.MODEL else FeedKind.TAG, f.target)) }, Modifier.weight(1f)) { Text(f.title) }
                        IconAction(R.drawable.ic_close, "取消收藏 ${f.title}") { store.toggleFavorite(f); revision++ }
                    } }
                } else MediaLibrary(records, if (task == 0 && records.isNotEmpty()) ({ dialogs.confirm(if (reading) "清空${verb}记录？" else "清空观看记录？") { if (reading) store.clearReadings() else store.clearHistory(); revision++ } }) else null,
                    { LibraryEmpty(if (task == 0) { if (reading) "还没有${verb}记录" else "还没有观看记录" } else "还没有收藏内容") }) { card -> play(card.video, read = card.read) }
            }
        }
    }
    @Composable private fun LibraryEmpty(title: String) { EmptyState(title, "内容与进度会保存在当前站点片库中", "去发现") { navigate("explore", false) } }
    @Composable private fun SettingsScreen(site: SiteContext) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val adapter = AdapterRegistry.adapter(site, app.network)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalUiDensity.current.pagePadding), verticalArrangement = Arrangement.spacedBy(if (LocalUiDensity.current.compact) 10.dp else 12.dp)) {
            Title("显示"); Toggle("减少透明效果", "reduce_transparency"); Copy("使用深色实底控件，保持清晰对比。")
            Toggle("标签优先显示中文词典", "translate_tags", true)
            Title("内容过滤"); Setting("屏蔽词", "${store.blockedTags().size} 个") { blockedTags() }
            Title("账户")
            if (Capability.SESSION_IMPORT in adapter.capabilities) {
                Setting("官方网页登录", "${site.name} 官方论坛", ::loginOfficial)
                Setting("导入站点会话", if (app.network.session.hasGallerySession(site)) "已导入" else "未导入") { credentials(site, true) }
                if (app.network.session.hasGallerySession(site)) Setting("退出本站会话") { dialogs.confirm("清除 ${site.name} 的账号会话？") { app.network.session.clear(site); revision++ } }
            } else if (Capability.LOGIN in adapter.capabilities) Setting(if (store.username.isBlank()) "登录站点账户" else "退出登录", store.username) {
                if (store.username.isBlank()) credentials(site, false) else dialogs.confirm("退出当前账户？") { app.network.session.clear(site); revision++ }
            } else Copy("${site.name} 当前支持公开浏览，无需登录。")
            Title("播放")
            Setting("默认清晰度", store.string("quality", "720p")) { val values = listOf("360p", "480p", "720p", "1080p", "2160p"); dialogs.choose("默认清晰度", values, values.indexOf(store.string("quality", "720p"))) { store.set("quality", values[it]); revision++ } }
            Setting("默认倍速", "${store.string("native_speed", "1.0")}×") { val values = listOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0"); dialogs.choose("默认倍速", values, values.indexOf(store.string("native_speed", "1.0"))) { store.set("native_speed", values[it]); revision++ } }
            Toggle("进入播放后自动全屏", "auto_fullscreen"); Toggle("播放时自动下载", "auto_download"); Copy("主动保存的视频与整本漫画统一在下载中心管理，支持后台下载与离线打开。")
            Title("网络"); Copy("自动跟随系统代理；未设置代理时使用系统 DNS 直连。代理连接失败时会显示错误。")
            Copy(runCatching { app.network.routeDescription() }.getOrDefault("系统代理设置暂不可用"))
            Setting("网络诊断") { lifecycleScope.launch { dialogs.message("网络诊断", "正在发起实时请求…"); val pending = dialogs.content; val report = app.network.diagnose(); if (dialogs.content === pending) dialogs.message("网络诊断", report) } }
            Title("本地数据"); Setting("清理播放临时数据") { dialogs.confirm("清理播放临时数据？", body = "已下载的视频和漫画会保留。") { lifecycleScope.launch { withContext(Dispatchers.IO) { app.playback.clearStreaming() }; showNotice("已清理") } } }
            Setting("备份与恢复", "收藏、订阅、进度与片单") { navigate("backup") }
            if (site.mediaKind == MediaKind.GALLERY) Setting("清理本站阅读临时数据") { dialogs.confirm("清理阅读临时数据？", body = "整本下载、阅读记录和收藏会保留。") { lifecycleScope.launch { withContext(Dispatchers.IO) { app.galleries.clear(site) }; showNotice("已清理") } } }
            Title("下载位置")
            Setting("当前下载位置", app.storage.describe(), ::storageOptions)
            Copy("放在公共目录时，卸载应用不会删除已下载的视频和漫画；重新安装后会自动出现在下载中心。放在应用内则随应用一起被删除。")
            Title("关于"); Text("R34V ${BuildConfig.VERSION_NAME}"); Copy("应用不托管内容，与原站无关联。")
            Toggle("自动检查更新", "auto_update", true); Setting("检查更新") { lifecycleScope.launch { UpdateController(this@MainActivity, app, dialogs).check(false) } }
        }
    }
    /** Download location: explains what survives an uninstall, then moves the files. */
    private fun storageOptions() {
        if (!app.storage.supported()) { dialogs.message("下载位置", "未检测到可写的外部存储卷，下载会保存在应用内。"); return }
        val shared = app.storage.applied == StorageRoot.Mode.SHARED
        val status = app.storage.status()
        dialogs.content = {
            var progress by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            fun change(mode: StorageRoot.Mode) {
                if (mode == StorageRoot.Mode.SHARED && !app.storage.granted()) {
                    progress = "请允许存储访问，返回后可再次点击迁移；也可以关闭此窗口"
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 30) storageAccess.launch(app.storage.permissionIntent()!!)
                        else legacyStorageAccess.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }.onFailure { progress = "无法打开权限请求：${it.message}" }
                    return
                }
                busy = true
                lifecycleScope.launch {
                    try {
                        progress = app.changeDownloadLocation(mode) { progress = it }
                        revision++; showNotice("下载位置已准备切换，请重启应用")
                    } catch (e: Exception) { if (e is CancellationException) throw e; progress = e.message ?: "迁移失败，原下载仍保留" }
                    finally { busy = false }
                }
            }
            fun action(label: String) = when {
                label.startsWith("改到公共目录") -> change(StorageRoot.Mode.SHARED)
                label.startsWith("确认权限有效") -> progress = if (app.storage.granted()) "权限已就绪，可以改到公共目录并迁移现有下载" else "尚未获得权限；Android 11 及以上需要“所有文件访问”"
                label.startsWith("查看/重新扫描") -> { navigate("library", false); libraryTab = "下载"; dialogs.dismiss() }
                else -> change(StorageRoot.Mode.PRIVATE)
            }
            val actions = if (shared) listOf("查看/重新扫描已下载内容", "确认权限有效", "改回应用内存储")
                else listOf("改到公共目录（卸载后保留）", "查看/重新扫描已下载内容", "确认权限有效")
            AlertDialog({ if (!busy) dialogs.dismiss() }, title = { Text("下载位置") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(app.storage.describe())
                    if (status.path.isNotBlank() && status.path !in app.storage.describe()) Copy(status.path)
                    if (status.note.isNotBlank()) Copy(status.note)
                    Copy(if (shared) "公共目录中的内容不会随卸载删除；重新安装后会自动识别并出现在下载中心。重新安装后需要再次允许“所有文件访问”。"
                        else "当前在应用内，卸载会连同下载一起删除。改到公共目录后可以保留。")
                    Copy("登录状态不随文件保留：重装后需要重新登录或重新导入 Cookie。")
                    actions.forEach { label ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !busy) { action(label) }.padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f)); Glyph(R.drawable.ic_chevron_right, null)
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (progress.isNotBlank()) Copy(progress)
                }
            }, confirmButton = { TextAction({ dialogs.dismiss() }, enabled = !busy) { Text("关闭") } })
        }
    }
    @Composable private fun Setting(label: String, value: String = "", click: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceColor)
            .clickable(onClick = click).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp); if (value.isNotBlank()) Copy(value, Modifier.widthIn(max = 140.dp).padding(start = 8.dp)); Glyph(R.drawable.ic_chevron_right, null, Modifier.padding(start = 8.dp).size(14.dp))
        }
    }
    @Composable private fun Toggle(label: String, key: String, default: Boolean = false) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceColor)
            .toggleable(store.bool(key, default), role = Role.Switch) { store.set(key, it); revision++ }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp); Switch(store.bool(key, default), null, colors = SwitchDefaults.colors(checkedTrackColor = BrandColor, checkedThumbColor = Color.White, uncheckedTrackColor = ControlColor, uncheckedThumbColor = MutedColor, checkedBorderColor = Color.Transparent, uncheckedBorderColor = Color.Transparent))
        }
    }
    private fun blockedTags() {
        val tags = store.blockedTags()
        dialogs.choose("屏蔽词：点击移除", tags + "添加屏蔽词") { i -> if (i < tags.size) { store.toggleBlocked(tags[i]); revision++; blockedTags() }
            else dialogs.edit("输入标签 slug 或标题关键词") { value -> if (value.isNotBlank() && value !in store.blockedTags()) store.toggleBlocked(value.lowercase()); revision++ }
        }
    }
    private fun credentials(site: SiteContext, importing: Boolean) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialogs.content = {
            var username by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            DisposableEffect(Unit) { onDispose { username = ""; secret = ""; window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
            AlertDialog({ if (!busy) dialogs.dismiss() }, title = { Text(if (importing) "导入 ${site.name} 会话" else "登录") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (importing) Copy("复制已登录本站浏览器的 Cookie。验证账号权限后才保存本站会话。") else TextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, shape = RoundedCornerShape(16.dp), colors = filledFieldColors(), singleLine = true)
                    TextField(secret, { secret = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = filledFieldColors(), label = { Text(if (importing) "本站 Cookie" else "密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = !importing, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error); if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = { TextAction({ busy = true; error = ""; scope.launch {
                try { if (importing) withContext(Dispatchers.IO) { AdapterRegistry.adapter(site, app.network).importSession(secret) } else app.network.login(username, secret)
                    secret = ""; dialogs.dismiss(); revision++; if (importing) navigate("home", false); showNotice("登录成功")
                } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "登录失败" } finally { busy = false }
            } }, enabled = !busy && secret.isNotBlank() && (importing || username.isNotBlank())) { Text(if (importing) "验证并导入" else "登录") } }, dismissButton = { TextAction({ dialogs.dismiss() }, enabled = !busy) { Text("取消") } })
        }
    }
}
