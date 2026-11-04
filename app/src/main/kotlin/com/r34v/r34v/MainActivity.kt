@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    private val app get() = application as R34Application
    private val store get() = app.library
    private var siteId by mutableStateOf("")
    private var screen by mutableStateOf("home")
    private var request by mutableStateOf(FeedRequest())
    private var libraryTab by mutableStateOf("记录")
    private var favoriteTab by mutableStateOf("视频")
    private var searchQuery by mutableStateOf("")
    private var searchScope by mutableIntStateOf(0)
    private var navigationTab by mutableStateOf("home")
    private var revision by mutableIntStateOf(0)
    private var resumed by mutableStateOf(false)
    private var tutorial by mutableStateOf(false)
    private val backStack = ArrayDeque<Pair<String, FeedRequest>>()
    private val dialogs = ComposeDialogs()
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) navigate("home", false)
    }
    private fun loginOfficial() = officialLogin.launch(Intent(this, SiteAuthActivity::class.java).putExtra("site", siteId))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        siteId = store.siteId
        if (savedInstanceState != null) {
            screen = savedInstanceState.getString("screen") ?: "home"
            navigationTab = savedInstanceState.getString("navigationTab") ?: "home"
            request = runCatching { store.gson.fromJson(savedInstanceState.getString("request"), FeedRequest::class.java) }.getOrNull() ?: FeedRequest()
            searchQuery = savedInstanceState.getString("searchQuery").orEmpty(); searchScope = savedInstanceState.getInt("searchScope")
            libraryTab = savedInstanceState.getString("libraryTab") ?: "记录"; favoriteTab = savedInstanceState.getString("favoriteTab") ?: "视频"
            savedInstanceState.getStringArrayList("backScreens").orEmpty().zip(savedInstanceState.getStringArrayList("backRequests").orEmpty()).forEach { (route, json) ->
                runCatching { store.gson.fromJson(json, FeedRequest::class.java) }.getOrNull()?.let { backStack.addLast(route to it) }
            }
        } else intent.getStringExtra("link")?.let { link ->
            val site = link.toHttpUrlOrNull()?.host?.let(SiteRegistry::forHost)
            val linked = site?.let { AdapterRegistry.adapter(it, app.network).linkRequest(link) }
            if (site != null && linked != null) { store.switchSite(site.id); siteId = site.id; request = linked; screen = "feed" }
        }
        if (intent.getBooleanExtra("downloads", false)) { libraryTab = "下载"; navigate("library", false); intent.removeExtra("downloads") }
        setContent { R34Theme { AppContent() } }
        if (!BuildConfig.DEBUG && store.bool("auto_update", true) && System.currentTimeMillis() - store.prefs.getLong("update_checked_at", 0) > 12 * 60 * 60 * 1000) {
            lifecycleScope.launch { UpdateController(this@MainActivity, app, dialogs).check(true) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("downloads", false)) { libraryTab = "下载"; navigate("library"); intent.removeExtra("downloads") }
    }
    override fun onResume() { super.onResume(); resumed = true; revision++; app.galleryDownloads.resumePending() }
    override fun onPause() { resumed = false; tutorial = false; super.onPause() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("screen", screen); out.putString("navigationTab", navigationTab); out.putString("request", store.gson.toJson(request))
        out.putString("searchQuery", searchQuery); out.putInt("searchScope", searchScope); out.putString("libraryTab", libraryTab); out.putString("favoriteTab", favoriteTab)
        out.putStringArrayList("backScreens", ArrayList(backStack.map { it.first })); out.putStringArrayList("backRequests", ArrayList(backStack.map { store.gson.toJson(it.second) }))
        super.onSaveInstanceState(out)
    }
    private fun navigate(target: String, push: Boolean = true) {
        tutorial = false; dialogs.dismiss()
        if (push && screen != target) backStack.addLast(screen to request)
        screen = target
        if (target in listOf("home", "explore", "search", "library")) navigationTab = target
    }
    private fun back() {
        if (backStack.isNotEmpty()) { val previous = backStack.removeLast(); request = previous.second; navigate(previous.first, false) }
        else if (screen != "home") navigate("home", false) else finish()
    }
    private fun switchSite(site: SiteContext) {
        if (site.id == siteId) return
        dialogs.dismiss(); tutorial = false; store.switchSite(site.id); siteId = site.id
        backStack.clear(); request = FeedRequest(); searchQuery = ""; searchScope = 0; libraryTab = "记录"; favoriteTab = if (site.mediaKind == MediaKind.GALLERY) "画廊" else "视频"
        navigate("home", false); toast("已切换到 ${site.name}")
    }
    private fun openFeed(value: FeedRequest) { backStack.addLast(screen to request); request = value; navigate("feed", false) }
    private fun browse(segment: String) { backStack.addLast(screen to request); request = FeedRequest(target = segment); navigate("index", false) }
    private fun play(video: Video, offlineId: String? = null, localPath: String? = null, read: Boolean = false, offline: Boolean = false) {
        val gallery = video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY
        startActivity(if (gallery) Intent(this, GalleryActivity::class.java).putExtra("gallery", store.gson.toJson(video.copy(mediaKind = MediaKind.GALLERY))).putExtra("read", read).putExtra("offline", offline)
        else Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video)).putExtra("download", offlineId).putExtra("local", localPath))
    }
    private fun favorite(video: Video) {
        val kind = if (video.mediaKind == MediaKind.GALLERY || SiteRegistry.byIdOrDefault(video.site).mediaKind == MediaKind.GALLERY) "gallery" else "video"
        toast(if (store.toggleFavorite(Favorite(kind, video.id, video.title, video.url, video.thumb, video.duration, site = video.site), video.site)) "已收藏" else "已取消收藏"); revision++
    }
    @Composable private fun AppContent() {
        val site = SiteRegistry.byIdOrDefault(siteId)
        val adapter = remember(site) { AdapterRegistry.adapter(site, app.network) }
        val drawer = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
        var languageVisible by remember(site, screen) { mutableStateOf(true) }
        var language by remember(site) { mutableStateOf(store.galleryLanguage()) }
        var scrollDistance by remember(site, screen) { mutableFloatStateOf(0f) }
        val holder = rememberSaveableStateHolder()
        val immersiveHome = screen == "home" && site.mediaKind == MediaKind.VIDEO
        val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
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
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp), drawerContainerColor = SurfaceColor) {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    item { Title("切换站点", Modifier.padding(12.dp), true) }
                    items(SiteRegistry.all, key = { it.id }) { target ->
                        Surface(color = if (target.id == site.id) RaisedColor else Color.Transparent, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(target.id == site.id, role = Role.Tab) { switchSite(target); scope.launch { drawer.close() } }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(target.name); if (target.id == "exhentai") Copy("需登录及账号权限")
                            }
                        }
                    }
                }
            }
        }) {
            val appTopBar: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().background(if (immersiveHome)
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Black.copy(alpha = .5f), Color.Transparent))
                    else Brush.verticalGradient(listOf(CanvasColor, CanvasColor))).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (screen in listOf("settings", "feed", "index")) IconAction(R.drawable.ic_back, "返回") { back() }
                        if (screen == "home") { BrandWordmark(); Spacer(Modifier.width(6.dp)) }
                        else Text(when (screen) { "search" -> "搜索"; "explore" -> "发现"; "library" -> "片库"; "settings" -> "设置"; "feed" -> adapter.feedTitle(request); else -> "浏览索引" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Box {
                            TextAction({ tutorial = false; scope.launch { drawer.open() } }, Modifier.heightIn(min = 48.dp).then(if (tutorial) Modifier.border(2.dp, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) else Modifier).semantics { contentDescription = "切换站点，当前 ${site.name}" }) {
                                Text(site.name, Modifier.widthIn(max = if (screen == "home") 112.dp else 100.dp), color = MutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp); Glyph(R.drawable.ic_chevron_down, null, Modifier.padding(start = 4.dp).size(12.dp))
                            }
                            DropdownMenu(tutorial, { tutorial = false }, Modifier.widthIn(max = 320.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Title("这里可以切换站点"); Copy("点击顶部的站点名称或小箭头，即可切换视频站和漫画站。"); Copy("收藏、历史和下载会按站点分别保存。")
                                    Action("打开站点列表", true) { tutorial = false; scope.launch { drawer.open() } }; TextAction({ tutorial = false }) { Text("知道了") }
                                }
                            }
                        }
                        if (screen == "home") Spacer(Modifier.weight(1f))
                        if (screen == "home") IconAction(R.drawable.ic_search, "搜索内容") { navigate("search") }
                        else IconAction(R.drawable.ic_download, "下载") { libraryTab = "下载"; navigate("library") }
                        if (screen != "settings") ProfileAction { navigate("settings") }
                    }
                    if (immersiveHome && !landscape) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("为你推荐", selected = true) { navigate("home", false) }
                        Choice("热门榜") { openFeed(FeedRequest(FeedKind.POPULAR)) }
                        Choice("分类") { navigate("explore") }
                    }
                    AnimatedVisibility(site.mediaKind == MediaKind.GALLERY && screen in listOf("home", "feed", "search", "explore") && languageVisible) {
                        Box(Modifier.padding(horizontal = 16.dp)) { Choices(listOf("全部", "中文", "日文", "中＋日"), language.ordinal) { index -> language = GalleryLanguage.entries[index]; store.setGalleryLanguage(language); request = request.copy(language = language, page = 1, cursor = "") } }
                    }
                }
            }
            Scaffold(containerColor = CanvasColor, contentWindowInsets = if (immersiveHome) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing,
                topBar = { if (!immersiveHome) appTopBar() }, bottomBar = {
                    CinemaNavigation(navigationTab) { key -> backStack.clear(); navigate(key, false) }
                }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
                    // Revision is observable because the persisted store intentionally remains framework independent.
                    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
                    val exGate = site.id == "exhentai" && !app.network.session.hasGallerySession(site)
                    if (screen != "settings" && !(screen == "library" && libraryTab in listOf("下载", "缓存")) && exGate) LoginGate()
                    else key(site.id) { holder.SaveableStateProvider("${site.id}|$screen|${if (screen == "feed" || screen == "index") request.id else ""}") {
                        when (screen) {
                            "home", "feed" -> key(screen, request, language) { FeedScreen(app, site, if (screen == "home") adapter.homeRequest().copy(language = language) else request.copy(language = language), screen == "home", dialogs, { play(it) }, ::favorite, ::scroll,
                                permissionDenied = { revision++ }, saved = store.favorites(site.id).filter { it.kind == "video" }.map { "${it.site}:${it.target}" }.toSet(),
                                continuing = store.history(site.id).filter { it.resumable && store.allowed(it.video()) }.take(12), browse = ::openFeed) { request = it } }
                            "explore" -> ExploreScreen(adapter, ::openFeed, ::browse)
                            "index" -> IndexScreen(app, site, request.target, dialogs, ::openFeed, ::browse)
                            "search" -> SearchScreen(adapter)
                            "library" -> LibraryScreen(site)
                            "settings" -> SettingsScreen(site)
                        }
                    } }
                    if (immersiveHome) appTopBar()
                }
            }
        }
        dialogs.Host()
    }
    @Composable private fun LoginGate() {
        Column { EmptyState("ExHentai 必须登录", "未登录时原站可能只显示白屏。请通过官方论坛登录，并确认账号具备 ExHentai 访问权限。", "官方网页登录", ::loginOfficial)
            TextAction({ navigate("settings") }, Modifier.padding(horizontal = 24.dp)) { Text("站点设置 / 导入 Cookie") }
        }
    }
    @Composable private fun SearchScreen(adapter: SiteAdapter) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val gallery = adapter.site.mediaKind == MediaKind.GALLERY
        var error by remember { mutableStateOf(false) }; val keyboard = LocalSoftwareKeyboardController.current; val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        fun submit() { val query = searchQuery.trim(); if (query.isBlank()) { error = true; focus.requestFocus(); return }; searchQuery = query; store.addSearch(query); focusManager.clearFocus(); keyboard?.hide(); openFeed(adapter.searchRequest(query, when (searchScope) { 1 -> FeedKind.TAG; 2 -> FeedKind.MODEL; else -> FeedKind.SEARCH })) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Title(if (gallery) "寻找想读的画廊" else "找到想看的内容", large = true); Copy("在 ${adapter.site.name} 中搜索")
            OutlinedTextField(searchQuery, { searchQuery = it; error = false }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text("搜索关键词") }, singleLine = true, isError = error,
                shape = RoundedCornerShape(12.dp), leadingIcon = { Glyph(R.drawable.ic_search, null) },
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = SurfaceColor, focusedContainerColor = RaisedColor, unfocusedBorderColor = Color.White.copy(alpha = .16f), focusedBorderColor = Color.White.copy(alpha = .5f), cursorColor = Color.White),
                supportingText = if (error) ({ Text("请输入关键词") }) else null,
                trailingIcon = { Row { if (searchQuery.isNotEmpty()) IconAction(R.drawable.ic_close, "清除关键词") { searchQuery = "" }; IconAction(R.drawable.ic_search, "提交搜索") { submit() } } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            Choices(if (gallery) listOf("画廊", "标签", adapter.creatorTitle) else listOf("视频", "标签", "作者"), searchScope) { searchScope = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Title("最近搜索", Modifier.weight(1f)); if (store.searches().isNotEmpty()) TextAction({ store.clearSearches(); revision++ }) { Text("清空") } }
            if (store.searches().isEmpty()) Copy("搜索过的关键词会保存在这里")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { store.searches().forEach { query -> Choice(query) { searchQuery = query; submit() } } }
        }
    }
    @Composable private fun LibraryScreen(site: SiteContext) {
        val gallery = site.mediaKind == MediaKind.GALLERY
        val task = if (libraryTab == "记录") 0 else if (libraryTab in listOf("下载", "缓存")) 2 else 1
        val creator = AdapterRegistry.adapter(site, app.network).creatorTitle
        val categories = if (gallery) listOf("画廊", creator, "标签") else listOf("视频", "角色", "作者", "标签")
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (task != 2) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).cinemaGlass().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_bookmark, null) }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("我的片库", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Copy("${site.name} · 收藏与进度，随时回来")
                }
            }
            Choices(if (gallery) listOf("阅读", "收藏", "下载") else listOf("观看", "收藏", "下载"), task) { libraryTab = when (it) { 0 -> "记录"; 2 -> "下载"; else -> favoriteTab } }
            if (task == 1) Choices(categories, categories.indexOf(favoriteTab)) { favoriteTab = categories[it]; libraryTab = favoriteTab }
            if (task == 2) DownloadCenter(app, resumed, dialogs,
                video = { entry, id, path -> play(entry, id, path) }, comic = { play(it, read = true, offline = true) },
                discover = { navigate("explore", false) })
            else {
                val records = if (task == 0) {
                    if (gallery) store.readings().map { LibraryCard(it.entry.copy(duration = "第 ${it.page} / ${it.pages} 页"), "移除阅读记录", { store.removeReading(it.entry.id); revision++ }, read = true) }
                    else store.history().map { watch -> LibraryCard(watch.video().copy(duration = if (watch.finished) "已看完" else "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}"), "移除观看记录", { store.removeWatch(watch.id); revision++ }, progress = if (watch.duration > 0) watch.position.toFloat() / watch.duration else 0f) }
                } else {
                    val kind = when (favoriteTab) { "视频" -> "video"; "画廊" -> "gallery"; "角色" -> "character"; "作者", "上传者", "画师" -> "model"; else -> "tag" }
                    store.favorites().filter { it.kind == kind && kind in listOf("video", "gallery") }.map { f -> LibraryCard(Video(f.target, f.title, site.absolute(f.path), f.thumb, f.subtitle, site = f.site, mediaKind = site.mediaKind), "取消收藏", { store.toggleFavorite(f); revision++ }) }
                }
                if (task == 1 && favoriteTab !in listOf("视频", "画廊")) LazyColumn {
                    val kind = when (favoriteTab) { "角色" -> "character"; "作者", "上传者", "画师" -> "model"; else -> "tag" }
                    val entries = store.favorites().filter { it.kind == kind }
                    if (entries.isEmpty()) item { LibraryEmpty("还没有收藏内容") }
                    items(entries, key = { it.key }) { f -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAction({ openFeed(FeedRequest(if (kind == "model") FeedKind.MODEL else FeedKind.TAG, f.target)) }, Modifier.weight(1f)) { Text(f.title) }
                        IconAction(R.drawable.ic_close, "取消收藏 ${f.title}") { store.toggleFavorite(f); revision++ }
                    } }
                } else MediaLibrary(records, if (task == 0 && records.isNotEmpty()) ({ dialogs.confirm(if (gallery) "清空阅读记录？" else "清空观看记录？") { if (gallery) store.clearReadings() else store.clearHistory(); revision++ } }) else null,
                    { LibraryEmpty(if (task == 0) { if (gallery) "还没有阅读记录" else "还没有观看记录" } else "还没有收藏内容") }) { card -> play(card.video, read = card.read) }
            }
        }
    }
    @Composable private fun LibraryEmpty(title: String) { EmptyState(title, "内容与进度会保存在当前站点片库中", "去发现") { navigate("explore", false) } }
    @Composable private fun SettingsScreen(site: SiteContext) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val adapter = AdapterRegistry.adapter(site, app.network)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("显示"); Toggle("标签优先显示中文词典", "translate_tags", true)
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
            Setting("默认清晰度", store.string("quality", "720p")) { val values = listOf("360p", "480p", "720p", "1080p", "2160p"); dialogs.choose("默认清晰度", values) { store.set("quality", values[it]); revision++ } }
            Setting("默认倍速", "${store.string("native_speed", "1.0")}×") { val values = listOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0"); dialogs.choose("默认倍速", values) { store.set("native_speed", values[it]); revision++ } }
            Toggle("进入播放后自动全屏", "auto_fullscreen"); Toggle("播放时自动下载", "auto_download"); Copy("主动保存的视频与整本漫画统一在下载中心管理，支持后台下载与离线打开。")
            Title("网络"); Copy("自动跟随系统代理；未设置代理时使用系统 DNS 直连。代理连接失败时会显示错误。")
            Copy(runCatching { app.network.routeDescription() }.getOrDefault("系统代理设置暂不可用"))
            Setting("网络诊断") { lifecycleScope.launch { dialogs.message("网络诊断", "正在发起实时请求…"); val pending = dialogs.content; val report = app.network.diagnose(); if (dialogs.content === pending) dialogs.message("网络诊断", report) } }
            Title("本地数据"); Setting("清理播放临时数据") { dialogs.confirm("清理播放临时数据？", body = "已下载的视频和漫画会保留。") { lifecycleScope.launch { withContext(Dispatchers.IO) { app.playback.clearStreaming() }; toast("已清理") } } }
            if (site.mediaKind == MediaKind.GALLERY) Setting("清理本站阅读临时数据") { dialogs.confirm("清理阅读临时数据？", body = "整本下载、阅读记录和收藏会保留。") { lifecycleScope.launch { withContext(Dispatchers.IO) { app.galleries.clear(site) }; toast("已清理") } } }
            Title("关于"); Text("R34V ${BuildConfig.VERSION_NAME}"); Copy("应用不托管内容，与原站无关联。")
            Toggle("自动检查更新", "auto_update", true); Setting("检查更新") { lifecycleScope.launch { UpdateController(this@MainActivity, app, dialogs).check(false) } }
        }
    }
    @Composable private fun Setting(label: String, value: String = "", click: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceColor).clickable(onClick = click).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); if (value.isNotBlank()) Copy(value, Modifier.widthIn(max = 140.dp).padding(start = 8.dp)); Glyph(R.drawable.ic_chevron_right, null, Modifier.padding(start = 8.dp).size(14.dp))
        }
    }
    @Composable private fun Toggle(label: String, key: String, default: Boolean = false) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceColor).clickable { store.set(key, !store.bool(key, default)); revision++ }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(store.bool(key, default), { store.set(key, it); revision++ }, colors = SwitchDefaults.colors(checkedTrackColor = BrandColor, checkedThumbColor = Color.White, uncheckedTrackColor = RaisedColor, uncheckedThumbColor = MutedColor))
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
                    if (importing) Copy("复制已登录本站浏览器的 Cookie。验证账号权限后才保存本站会话。") else OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
                    OutlinedTextField(secret, { secret = it }, label = { Text(if (importing) "本站 Cookie" else "密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = !importing, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error); if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = { TextAction({ busy = true; error = ""; scope.launch {
                try { if (importing) withContext(Dispatchers.IO) { AdapterRegistry.adapter(site, app.network).importSession(secret) } else app.network.login(username, secret)
                    secret = ""; dialogs.dismiss(); revision++; if (importing) navigate("home", false); toast("登录成功")
                } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "登录失败" } finally { busy = false }
            } }, enabled = !busy && secret.isNotBlank() && (importing || username.isNotBlank())) { Text(if (importing) "验证并导入" else "登录") } }, dismissButton = { TextAction({ dialogs.dismiss() }, enabled = !busy) { Text("取消") } })
        }
    }
}
