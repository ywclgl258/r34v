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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

class MainActivity : ComponentActivity() {
    private val feeds by viewModels<FeedStates>()
    private val crossSearch by viewModels<CrossSearchState>()
    private val app get() = application as R34Application
    private val store get() = app.library
    private var siteId by mutableStateOf("")
    private var screen by mutableStateOf("siteHome")
    private var request by mutableStateOf(FeedRequest())
    private var libraryTab by mutableStateOf("片库")
    private var searchQuery by mutableStateOf("")
    private var searchScope by mutableIntStateOf(0)
    private var multipleSources by mutableStateOf(false)
    private var searchSites by mutableStateOf(listOf<String>())
    private var searchCategory by mutableStateOf("")
    private var searchLanguages by mutableStateOf<Map<String, String>>(emptyMap())
    private var searchCondition by mutableStateOf(SearchCondition())
    private var importingUri by mutableStateOf<Uri?>(null)
    private var importingDirectory by mutableStateOf(false)
    private var importingVideo by mutableStateOf(false)
    private var navigationTab by mutableStateOf("siteHome")
    private var revision by mutableIntStateOf(0)
    private var resumed by mutableStateOf(false)
    private var tutorial by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private data class Route(val screen: String, val request: FeedRequest, val site: String, val language: GalleryLanguage?)
    private var routeLanguage by mutableStateOf<GalleryLanguage?>(null)
    private val backStack = ArrayDeque<Route>()
    private fun currentRoute() = Route(screen, request, siteId, routeLanguage)
    private fun showNotice(text: String) { notice = text }
    private val dialogs = ComposeDialogs()
    private val archivePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importingDirectory = false; importingVideo = false; importingUri = it } }
    private val directoryPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { importingDirectory = true; importingVideo = false; importingUri = it } }
    private val videoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importingDirectory = false; importingVideo = true; importingUri = it } }
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            revision++
            if (screen != "siteAccount") navigate("siteHome", false)
        }
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
            screen = savedInstanceState.getString("screen") ?: "siteHome"
            siteId = savedInstanceState.getString("routeSite")?.takeIf { SiteRegistry.byId(it) != null } ?: siteId
            navigationTab = savedInstanceState.getString("navigationTab") ?: "siteHome"
            request = runCatching { store.gson.fromJson(savedInstanceState.getString("request"), FeedRequest::class.java) }.getOrNull() ?: FeedRequest()
            routeLanguage = GalleryLanguage.entries.firstOrNull { it.name == savedInstanceState.getString("routeLanguage") }
            searchQuery = savedInstanceState.getString("searchQuery").orEmpty(); searchScope = savedInstanceState.getInt("searchScope")
            multipleSources = savedInstanceState.getBoolean("multipleSources")
            searchSites = savedInstanceState.getStringArrayList("searchSites")?.filter { id -> SiteRegistry.all.any { it.id == id } }?.takeIf { it.isNotEmpty() } ?: searchSites
            searchCategory = savedInstanceState.getString("searchCategory").orEmpty()
            searchLanguages = runCatching { store.gson.fromJson(savedInstanceState.getString("searchLanguages"), object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type) as Map<String,String> }.getOrDefault(emptyMap())
            searchCondition = runCatching { store.gson.fromJson(savedInstanceState.getString("searchCondition"), SearchCondition::class.java) }.getOrNull() ?: SearchCondition(searchQuery)
            importingUri = savedInstanceState.getString("importingUri")?.let(Uri::parse); importingDirectory = savedInstanceState.getBoolean("importingDirectory"); importingVideo = savedInstanceState.getBoolean("importingVideo")
            val savedLibraryTab = savedInstanceState.getString("libraryTab") ?: "片库"
            libraryTab = savedLibraryTab.takeIf { it in listOf("片库", "片单", "记录", "下载", "缓存") } ?: "收藏"
            run {
                val screens = savedInstanceState.getStringArrayList("backScreens").orEmpty()
                val requests = savedInstanceState.getStringArrayList("backRequests").orEmpty()
                val sites = savedInstanceState.getStringArrayList("backSites").orEmpty()
                val languages = savedInstanceState.getStringArrayList("backLanguages").orEmpty()
                screens.indices.forEach { index ->
                    val json = requests.getOrNull(index) ?: return@forEach
                    val owner = sites.getOrNull(index).orEmpty().ifBlank { siteId }
                    val language = GalleryLanguage.entries.firstOrNull { it.name == languages.getOrNull(index) }
                    runCatching { store.gson.fromJson(json, FeedRequest::class.java) }.getOrNull()
                        ?.let { backStack.addLast(Route(screens[index], it, owner, language)) }
                }
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
        outState.putString("screen", screen); outState.putString("routeSite", siteId); outState.putString("navigationTab", navigationTab); outState.putString("request", store.gson.toJson(request))
        outState.putString("searchQuery", searchQuery); outState.putInt("searchScope", searchScope); outState.putString("libraryTab", libraryTab)
        outState.putBoolean("multipleSources", multipleSources)
        outState.putStringArrayList("searchSites", ArrayList(searchSites))
        outState.putString("searchCategory", searchCategory); outState.putString("searchLanguages", store.gson.toJson(searchLanguages)); outState.putString("searchCondition", store.gson.toJson(searchCondition))
        outState.putString("importingUri", importingUri?.toString()); outState.putBoolean("importingDirectory", importingDirectory); outState.putBoolean("importingVideo", importingVideo)
        outState.putString("routeLanguage", routeLanguage?.name)
        outState.putStringArrayList("backScreens", ArrayList(backStack.map { it.screen }))
        outState.putStringArrayList("backRequests", ArrayList(backStack.map { store.gson.toJson(it.request) }))
        outState.putStringArrayList("backSites", ArrayList(backStack.map { it.site }))
        outState.putStringArrayList("backLanguages", ArrayList(backStack.map { it.language?.name.orEmpty() }))
        super.onSaveInstanceState(outState)
    }
    private fun navigate(target: String, push: Boolean = true) {
        tutorial = false; dialogs.dismiss()
        if (target == "siteFunctions") notice = null
        if (screen != target) {
            if (push) backStack.addLast(currentRoute())
            routeLanguage = null
        }
        screen = target
        if (target in listOf("siteHome", "home", "explore", "search", "library")) navigationTab = target
    }
    private fun back() {
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeLast()
            if (SiteRegistry.byId(previous.site) != null) siteId = previous.site
            request = previous.request
            navigate(previous.screen, false)
            routeLanguage = previous.language
        } else if (screen != "siteHome") {
            siteId = store.siteId
            navigate("siteHome", false)
        } else finish()
    }
    private fun switchSite(site: SiteContext) {
        if (site.id == siteId && site.id == store.siteId) return
        val destination = if (screen == "siteFunctions") "siteFunctions" else "siteHome"
        dialogs.dismiss(); tutorial = false; store.switchSite(site.id); siteId = site.id
        backStack.clear(); request = FeedRequest(); searchQuery = ""; searchScope = 0; libraryTab = "记录"
        if (destination == "siteFunctions") navigationTab = "siteHome"
        navigate(destination, false)
        if (destination == "siteHome") showNotice("已切换到 ${site.name}")
    }
    private fun openFeed(value: FeedRequest) = openSourceFeed(value, SiteRegistry.byIdOrDefault(siteId))

    private fun openSourceFeed(value: FeedRequest, owner: SiteContext) = openSourceFeed(value, owner, language = null)

    private fun openSourceFeed(value: FeedRequest, owner: SiteContext, language: GalleryLanguage?) {
        val section = AdapterRegistry.adapter(owner, app.network).indexSection(value)
        openSourcePage(if (section == null) "feed" else "index", if (section == null) value else FeedRequest(target = section), owner, language)
    }

    private fun openSourcePage(route: String, value: FeedRequest, owner: SiteContext, language: GalleryLanguage? = null) {
        backStack.addLast(currentRoute())
        siteId = owner.id
        request = value
        navigate(route, false)
        routeLanguage = language
    }

    private fun browse(segment: String) = openSourcePage("index", FeedRequest(target = segment), SiteRegistry.byIdOrDefault(siteId))
    private fun play(video: Video, offlineId: String? = null, localPath: String? = null, read: Boolean = false,
        offline: Boolean = false, sourceRoot: String = "", page: Int = 0, autoplay: Boolean = true) {
        if (video.site == SiteRegistry.LOCAL.id) {
            if (!video.isGalleryWork) {
                // 功能 F37：本地视频走 PlayerActivity 的 local 分支，路径以登记记录为准。
                val record = store.localVideos(SiteRegistry.LOCAL.id).firstOrNull { it.id == video.id }
                if (record == null) { showNotice("本地视频文件不存在，请重新导入"); return }
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video))
                    .putExtra("local", record.path).putExtra("autoplay", autoplay))
                return
            }
            if (page > 0) startActivity(Intent(this, GalleryActivity::class.java).putExtra("gallery", store.gson.toJson(video)).putExtra("read", true).putExtra("page", page))
            else MediaRouter.open(this, video, read = true)
            return
        }
        store.personal.markRead(video.site, setOf(video.entryKey))
        val gallery = video.isGalleryWork
        startActivity(if (gallery) Intent(this, GalleryActivity::class.java).putExtra("gallery", store.gson.toJson(video.copy(mediaKind = MediaKind.GALLERY))).putExtra("read", read).putExtra("offline", offline).putExtra("root", sourceRoot).putExtra("page", page)
        else Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video)).putExtra("download", offlineId)
            .putExtra("local", localPath).putExtra("autoplay", autoplay))
    }

    /** Plays a cache entry found by the disk scan: no Media3 download record exists for it. */
    private fun playOffline(record: LibraryRecord) {
        if (record.isComic) play(record.video, read = true, offline = true, sourceRoot = record.sourceRoot)
        else startActivity(Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(record.video))
            .putExtra("cached", record.cacheKey).putExtra("root", record.sourceRoot))
    }

    private suspend fun libraryScan(): Int = withContext(Dispatchers.IO) {
        val known = app.playback.downloads().mapNotNull { app.playback.libraryRecord(it) }
        app.index.dropCorruptSidecars()
        app.index.scan(app.playback::cached, known).count { it.state == LibraryRecord.STATE_COMPLETED }
    }
    private fun toggleSubscription(entry: Subscription) {
        if (entry.slug.isBlank()) return
        val followed = store.toggleSubscription(entry, siteId)
        revision++
        showNotice(if (followed) "已订阅 ${entry.title}" else "已取消订阅 ${entry.title}")
    }

    private fun openTag(entry: Subscription) {
        val owner = SiteRegistry.byId(entry.site) ?: SiteRegistry.byIdOrDefault(siteId)
        val adapter = AdapterRegistry.adapter(owner, app.network)
        if (entry.slug.isNotBlank()) {
            val linked = adapter.linkRequest(owner.absolute(entry.url))
            openSourceFeed(linked?.takeIf { it.kind == FeedKind.TAG && it.target == entry.slug } ?: FeedRequest(FeedKind.TAG, entry.slug), owner)
            return
        }
        val directory = adapter.sections.firstOrNull { it.feed == null && it.key in setOf("tags", "genres", "series") }
        when {
            directory != null -> openSourcePage("index", FeedRequest(target = directory.key), owner)
            Capability.TAG in adapter.capabilities -> dialogs.edit("查找 ${owner.name} 标签", hint = "输入标签名", validate = {
                if (it.isBlank()) "请输入标签名" else null
            }) { openSourceFeed(adapter.searchRequest(it.trim(), FeedKind.TAG), owner) }
            else -> showNotice("${owner.name} 暂无可浏览的标签入口")
        }
    }

    private fun openFavorite(favorite: Favorite, video: Video?) {
        val owner = SiteRegistry.byId(favorite.site)
        if (owner == null) { showNotice("此收藏的来源已移除"); return }
        if (video != null) {
            MediaRouter.open(this, video, read = false)
        } else {
            val adapter = AdapterRegistry.adapter(owner, app.network)
            val linked = adapter.linkRequest(owner.absolute(favorite.path))
            openSourceFeed(linked ?: FeedRequest(if (favorite.kind == "model") FeedKind.MODEL else FeedKind.TAG, favorite.target), owner)
        }
    }

    private fun favorite(video: Video) {
        val kind = if (video.isGalleryWork) "gallery" else "video"
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
    private val searchScopes = listOf(FeedKind.SEARCH, FeedKind.TAG, FeedKind.MODEL)

    private fun searchKind(site: SiteContext): FeedKind =
        if (site.category == SiteCategory.PHOTO) FeedKind.SEARCH else searchScopes.getOrElse(searchScope) { FeedKind.SEARCH }

    private fun savedSearch(value: SavedSearch?) {
        if (value == null) { navigate("search"); return }
        searchQuery = value.query
        searchCondition = value.condition
        val owner = value.singleSite
        multipleSources = owner == null
        if (owner != null) {
            searchScope = searchScopes.indexOf(value.singleSiteScope ?: FeedKind.SEARCH).coerceAtLeast(0)
            val adapter = AdapterRegistry.adapter(owner, app.network)
            val savedRequest = value.feedRequest(adapter)
            openSourceFeed(savedRequest, owner, language = savedRequest.language.takeIf { adapter.supportsLanguage })
            return
        }
        searchSites = value.sources.filter { SiteRegistry.byId(it) != null }
        searchCategory = value.category
        searchLanguages = value.languages
        if (searchSites.isEmpty()) {
            searchSites = searchableSiteIds(app.network)
            showNotice("搜索来源已不可用，请重新选择")
            navigate("search")
        } else navigate("multiSearch")
    }
    private fun saveSearch(onSaved: () -> Unit = {}) {
        val condition = searchCondition.copy(query = searchQuery).normalized()
        if (condition.isEmpty) { showNotice("请输入关键词或添加筛选条件"); return }
        val owner = SiteRegistry.byIdOrDefault(siteId).takeUnless { multipleSources }
        val sources = owner?.let { listOf(it.id) } ?: searchSites
        val category = owner?.category?.name ?: searchCategory
        val languages = sources.associateWith { id ->
            searchLanguages[id]?.takeIf { owner == null } ?: store.galleryLanguage(id).name
        }
        val scope = owner?.let(::searchKind)
        dialogs.edit("保存搜索方案", searchQuery.ifBlank { "高级条件" }, validate = { if (it.isBlank() || it.length > 80) "名称需要 1–80 个字" else null }) { name ->
            store.personal.saveSearch(SavedSearch(name = name, query = condition.query, sources = sources, category = category,
                languages = languages, includeTags = condition.includeTags, excludeTags = condition.excludeTags, filter = condition.filter,
                singleSiteScope = scope))
            showNotice("已保存到常用搜索"); revision++; onSaved()
        }
    }
    private fun openChannel(channel: DiscoveryChannel) {
        channel.savedSearch?.let { savedSearch(it); return }
        val target = SiteRegistry.byId(channel.site) ?: run { showNotice("该频道的来源站点已移除"); return }
        openSourceFeed(channel.feedRequest, target)
    }
    private fun pinCurrentChannel() {
        if (request.kind !in listOf(FeedKind.SEARCH, FeedKind.TAG, FeedKind.CATEGORY, FeedKind.MODEL, FeedKind.CHARACTER)) return
        val adapter = AdapterRegistry.adapter(SiteRegistry.byIdOrDefault(siteId), app.network)
        val mediaLabel = if (siteId == SiteRegistry.IWARA.id) {
            if (request.contentKind == MediaKind.GALLERY) "图库" else "视频"
        } else ""
        val defaultName = adapter.feedTitle(request).ifBlank { request.target }
        dialogs.edit("固定到我的频道", listOf(mediaLabel, defaultName).filter(String::isNotBlank).joinToString(" · "),
            validate = { if (it.isBlank() || it.length > 80) "名称需要 1–80 个字" else null }) { name ->
            store.personal.pinFeed(siteId, request, name); revision++; showNotice("已固定到“我的”中的频道")
        }
    }
    private fun manageChannels() {
        dialogs.content = {
            val personalRevision by store.personal.changes.collectAsState()
            val channels = remember(personalRevision) { store.personal.snapshot().channels.sortedBy { it.order } }
            AlertDialog(dialogs::dismiss, title = { Text("管理我的频道") }, text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (channels.isEmpty()) item { Copy("在保存搜索、分类或作者页面选择“固定到我的频道”。") }
                    items(channels, key = { it.id }) { channel ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(channel.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Copy(if (channel.hidden) "已隐藏" else channel.savedSearch?.let { "保存搜索" }
                                ?: SiteRegistry.byId(channel.site)?.let { owner ->
                                    val channelKind = if (owner.id != SiteRegistry.IWARA.id) channel.kind.title
                                    else "${if (channel.feedRequest.contentKind == MediaKind.GALLERY) "图库" else "视频"} · ${channel.kind.title}"
                                    "${owner.name} · $channelKind"
                                } ?: "来源站点已移除")
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
    private fun importLocal() { dialogs.choose("导入本地内容", listOf("选择 CBZ / ZIP 漫画", "选择图片目录", "选择本地视频")) { index ->
        when (index) {
            0 -> archivePicker.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream"))
            1 -> directoryPicker.launch(null)
            else -> videoPicker.launch(arrayOf("video/*"))
        }
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
        if (target != null) { openSourceFeed(target, link.site); return }
        dialogs.content = {
            var error by remember { mutableStateOf<String?>(null) }; var retry by remember { mutableIntStateOf(0) }
            LaunchedEffect(link.url, retry) { error = null; try {
                val gallery = Video(url = link.url, site = link.site.id).isGalleryWork ||
                    (link.site.id == SiteRegistry.IWARA.id && IwaraProtocol.isGalleryUrl(link.site, link.url))
                val video = withContext(Dispatchers.IO) { if (gallery) adapter.gallery(link.url).entry else adapter.detail(link.url).video }
                dialogs.dismiss(); play(video.copy(site = link.site.id, mediaKind = if (gallery) MediaKind.GALLERY else video.mediaKind))
            } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "作品识别失败" } }
            AlertDialog({ dialogs.dismiss() }, title = { Text("打开 ${link.site.name} 作品") }, text = { if (error == null) Loading(title = "正在识别作品…") else Text(error.orEmpty()) },
                confirmButton = { if (error != null) TextAction({ retry++ }) { Text("重试") } }, dismissButton = { TextAction({ dialogs.dismiss() }) { Text("取消") } })
        }
    }
    @Composable private fun AppContent() {
        val site = SiteRegistry.byIdOrDefault(siteId)
        val adapter = remember(site) { AdapterRegistry.adapter(site, app.network) }
        val drawer = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
        var pinnedSites by remember { mutableStateOf(store.pinnedSiteIds()) }
        var languageVisible by remember(site, screen) { mutableStateOf(true) }
        val siteLanguage = remember(site.id, revision) { store.galleryLanguage(site.id) }
        val language = routeLanguage ?: siteLanguage
        fun changeLanguage(value: GalleryLanguage) {
            if (screen == "search" || routeLanguage == null) store.setGalleryLanguage(value, site.id)
            else routeLanguage = value
            if (screen == "feed") request = request.copy(language = value, page = 1, cursor = "")
            revision++
        }
        var scrollDistance by remember(site, screen) { mutableFloatStateOf(0f) }
        val holder = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }
        val palettes = remember { mutableStateMapOf<String, ArtworkPalette>() }
        val fallback = remember(site.category) { artworkPalette(site.category) }
        val palette = palettes[site.id] ?: fallback
        val compactHeader = windowSizeDp().width < 360 || LocalConfiguration.current.fontScale > 1.3f
        val downloading = screen == "library" && libraryTab in listOf("下载", "缓存")
        val favorites = screen == "library" && libraryTab == "收藏"
        val subscriptions = screen == "subscriptions"
        val siteManagement = screen in listOf("siteFavorites", "siteDownloads", "siteSubscriptions", "siteAccount")
        val usesPageSource = downloading || favorites || subscriptions || siteManagement
        val ime = WindowInsets.ime
        val density = LocalDensity.current
        val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); notice = null } }
        fun scroll(dy: Float, top: Boolean) {
            if (top || dy < 0) { languageVisible = true; scrollDistance = 0f }
            else { scrollDistance += dy; if (scrollDistance > dp(18)) languageVisible = false }
        }
        BackHandler {
            when {
                tutorial -> tutorial = false
                drawer.isOpen -> scope.launch { drawer.close() }
                downloading || favorites -> libraryTab = "片库"
                else -> back()
            }
        }
        LaunchedEffect(screen, resumed, drawer.isOpen) {
            if (resumed && screen == "siteHome" && !drawer.isOpen && store.needsSiteSwitchTutorial) {
                withFrameNanos { }; if (store.consumeSiteSwitchTutorial()) tutorial = true
            }
        }
        ArtworkColors(palette) {
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            val closeDrawer: () -> Unit = { scope.launch { drawer.close() } }
            SiteSwitcher(site, palette, pinnedSites, { pinnedSites = store.togglePinnedSite(it) }, drawer.isOpen, closeDrawer) { target ->
                switchSite(target)
                closeDrawer()
            }
        }) {
            val appTopBar: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (downloading || favorites) IconAction(R.drawable.ic_back, "返回我的片库") { libraryTab = "片库" }
                        else if (siteManagement || screen in listOf("siteFunctions", "settings", "feed", "index", "subscriptions", "multiSearch", "backup", "updates", "searchUpdates")) {
                            IconAction(R.drawable.ic_back, "返回") { back() }
                        }
                        if (screen == "siteHome") { BrandWordmark(); Spacer(Modifier.weight(1f)) }
                        else {
                            val topTitle = when (screen) {
                                "home" -> "我的"
                                "search" -> "搜索"
                                "multiSearch" -> "跨站搜索"
                                "backup" -> "备份与恢复"
                                "updates", "searchUpdates" -> "更新"
                                "explore" -> "发现"
                                "library" -> when { downloading -> "下载中心"; favorites -> "收藏"; libraryTab == "片库" -> "我的片库"; else -> "片库" }
                                "settings" -> "设置"
                                "subscriptions" -> "标签订阅"
                                "siteFunctions" -> "站点功能"
                                "siteFavorites" -> "${site.name} · 收藏"
                                "siteDownloads" -> "${site.name} · 下载"
                                "siteSubscriptions" -> "${site.name} · 标签订阅"
                                "siteAccount" -> "${site.name} · 账户"
                                "feed" -> adapter.feedTitle(request)
                                "index" -> coserDirectoryTitle(site, request.target) ?: categoryDirectoryTitle(adapter, request.target) ?: "浏览索引"
                                else -> "浏览索引"
                            }
                            Text(topTitle, Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = palette.ink,
                                style = if (screen in listOf("home", "search", "explore") || screen == "library") MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
                        }
                        if (!usesPageSource && (screen != "library" || libraryTab != "片库")) Box {
                            val openSites: () -> Unit = { tutorial = false; scope.launch { drawer.open() } }
                            RentalSiteSwitch(site.name, compactHeader, openSites)
                            DropdownMenu(tutorial, { tutorial = false }, Modifier.widthIn(max = 320.dp)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Title("这里可以切换站点"); Copy("点击顶部的站点名称或小箭头，即可切换视频站和漫画站。"); Copy("收藏、历史和下载会按站点分别保存。")
                                    Action("打开站点列表", primary = true) { tutorial = false; scope.launch { drawer.open() } }; TextAction({ tutorial = false }) { Text("知道了") }
                                }
                            }
                        }
                        if (screen == "siteHome") {
                            IconAction(R.drawable.ic_search, "搜索内容") { navigate("search") }
                            IconAction(R.drawable.ic_explore, "本站功能") { navigate("siteFunctions") }
                        }
                        if (screen == "explore") TextAction({ navigate("siteFunctions") }) { Text("本站功能") }
                        if (screen == "feed") IconAction(R.drawable.ic_add, "固定当前频道", click = ::pinCurrentChannel)
                        if (screen == "library" && !downloading) IconAction(R.drawable.ic_bookmark, "订阅") { navigate("subscriptions") }
                        if (screen != "settings") ProfileAction { navigate("settings") }
                    }
                    if (compactHeader && !usesPageSource && screen != "home" && !(screen == "library" && libraryTab == "片库") && !(screen == "search" && multipleSources)) Copy("${site.category.title} · ${site.name}", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                    // Translation language only exists on the manga sites; a photo archive has no
                    // translations, and showing the bar there would promise a filter that is absent.
                    AnimatedVisibility(adapter.supportsLanguage && screen in listOf("siteHome", "feed", "search") && !(screen == "search" && multipleSources) && languageVisible) {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            Choices(listOf("全部", "中文", "日文", "中＋日"), language.ordinal) { changeLanguage(GalleryLanguage.entries[it]) }
                        }
                    }
                }
            }
            Scaffold(modifier = Modifier.artworkBackground(palette), containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = {
                    SnackbarHost(snackbar) { data ->
                        Snackbar(data, containerColor = palette.raised, contentColor = palette.ink, shape = ArtworkShape)
                    }
                },
                topBar = appTopBar, bottomBar = {
                    if (!keyboardVisible) {
                        CinemaNavigation(navigationTab) { key ->
                            backStack.clear()
                            siteId = store.siteId
                            if (key == "library") libraryTab = "片库"
                            navigate(key, false)
                        }
                    }
                }) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()
                    .artworkReveal(Triple(site.id, screen, libraryTab.takeIf { screen == "library" }))) {
                    // Revision is observable because the persisted store intentionally remains framework independent.
                    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
                    val exGate = site.id == "exhentai" && !app.network.session.hasGallerySession(site)
                    if (exGate && screen in listOf("siteHome", "feed", "explore", "index")) LoginGate()
                    else key(site.id) { holder.SaveableStateProvider("${site.id}|$screen|${if (screen == "feed" || screen == "index") request.id else ""}") {
                        when (screen) {
                            "home" -> PersonalHomeScreen(app, rememberPersonalCatalog(app, revision, resumed), { MediaRouter.open(this@MainActivity, it) },
                                waiting = { libraryTab = "片单"; navigate("library") }, updates = { navigate("updates") }, search = ::savedSearch, discover = { navigate("siteHome") },
                                channel = ::openChannel, manageChannels = ::manageChannels)
                            "siteHome", "feed" -> {
                                val effective = (if (screen == "siteHome") adapter.homeRequest() else request).copy(language = language)
                                val blocked = store.blockedTags(site.id)
                                val accountGeneration = accountFeedGeneration(site, effective)
                                val feedState = remember(site.id, screen, effective, blocked, accountGeneration) {
                                    feeds.page(site.id, screen, effective, blocked, accountGeneration = accountGeneration)
                                }
                                key(feedState) { FeedScreen(app, site, effective, screen == "siteHome", feedState, dialogs,
                                    { play(it, autoplay = screen == "siteHome") }, ::favorite, ::scroll,
                                permissionDenied = { revision++ }, saved = store.favorites(site.id).filter { it.kind == "video" }.map { "${it.site}:${it.target}" }.toSet(),
                                browse = ::openFeed,
                                    openDetails = { play(it, autoplay = false) },
                                    resumeGallery = { play(it, read = true) },
                                    onArtwork = { palettes[site.id] = it },
                                subscribe = { toggleSubscription(it) },
                                subscribed = request.kind == FeedKind.TAG && store.isSubscribed(request.target, site.id),
                                accountGeneration = accountGeneration,
                                sort = { request = it }) }
                            }
                            "explore" -> {
                                val filterKey = "${site.id}:explore_filter_v1"
                                var savedFilter by rememberSaveable { mutableStateOf(store.prefs.getString(filterKey, null)) }
                                val selected = remember(adapter, savedFilter) {
                                    runCatching { store.gson.fromJson(savedFilter, FeedRequest::class.java) }.getOrNull() ?: adapter.homeRequest()
                                }
                                val effective = selected.copy(language = language)
                                val blocked = store.blockedTags(site.id)
                                val accountGeneration = accountFeedGeneration(site, effective)
                                val feedState = remember(site.id, effective, blocked, accountGeneration) {
                                    feeds.page(site.id, "explore", effective, blocked, accountGeneration = accountGeneration)
                                }
                                ExploreScreen(app, adapter, effective, feedState, dialogs, { play(it, autoplay = false) }, ::favorite, ::scroll,
                                    changeFeed = {
                                        val filter = store.gson.toJson(it.copy(page = 1, cursor = ""))
                                        savedFilter = filter
                                        store.prefs.edit { putString(filterKey, filter) }
                                    },
                                    changeLanguage = ::changeLanguage, browse = ::browse, permissionDenied = { revision++ },
                                    accountGeneration = accountGeneration)
                            }
                            "index" -> IndexScreen(app, site, request.target, dialogs, ::openFeed, ::browse, ::toggleSubscription)
                            "search" -> SearchScreen(adapter)
                            "multiSearch" -> CrossSearchScreen(app, crossSearch, searchQuery, searchSites, { play(it) }, ::favorite,
                                revision, searchCategory, searchLanguages, { searchCategory = it }, { saveSearch() },
                                condition = searchCondition, openDirectory = ::openSourceFeed)
                            "updates", "searchUpdates" -> GlobalInboxScreen(app, { play(it) }, dialogs,
                                manageSubscriptions = { navigate("subscriptions") }, initialSearch = screen == "searchUpdates", openDirectory = ::openSourceFeed)
                            "library" -> LibraryScreen(site)
                            "siteFunctions" -> {
                                val page = remember(adapter) { siteFunctionPage(adapter) }
                                val behavior = store.personal.readingBehavior(site.id)
                                val readerMode = if (site.mediaKind == MediaKind.GALLERY || page.search.any { it.gallery }) store.personal.readerMode(site.id) else null
                                SiteFunctionsScreen(site, page, language, readerMode, behavior.originalImages,
                                    accountStatus = accountStatus(site, adapter), accountRequired = exGate, dialogs = dialogs,
                                    openSection = { section ->
                                        val feed = section.feed
                                        when {
                                            feed == null -> browse(section.key)
                                            feed.kind == FeedKind.SUBSCRIPTIONS && accountStatus(site, adapter) == "未登录" -> navigate("siteAccount")
                                            else -> openSourceFeed(feed, site)
                                        }
                                    }, search = { option, query ->
                                        val feed = adapter.searchRequest(query, option.kind)
                                        openSourceFeed(if (option.gallery) feed.copy(contentKind = MediaKind.GALLERY) else feed, site)
                                    }, openFavorites = { navigate("siteFavorites") }, openDownloads = { navigate("siteDownloads") },
                                    openSubscriptions = { navigate("siteSubscriptions") }, openAccount = { navigate("siteAccount") },
                                    setLanguage = ::changeLanguage, setReaderMode = {
                                        store.personal.setReaderMode(site.id, it); revision++
                                    }, setOriginals = {
                                        store.personal.setReadingBehavior(site.id, behavior.copy(originalImages = it)); revision++
                                    })
                            }
                            "siteFavorites" -> LibraryFavorites(site.id)
                            "siteDownloads" -> Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) { LibraryDownloads(site.id) }
                            "siteSubscriptions", "subscriptions" -> SubscriptionScreen(app, site, dialogs, { play(it) }, ::favorite,
                                openTag = ::openTag, revision = { revision++ }, lockedSite = site.id.takeIf { screen == "siteSubscriptions" })
                            "siteAccount" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(LocalUiDensity.current.pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AccountControls(site, adapter)
                            }
                            "settings" -> SettingsScreen(site)
                            "backup" -> BackupScreen(app) {
                                searchSites = store.string("cross_search_sites").split(',').filter { SiteRegistry.byId(it) != null }.ifEmpty { searchableSiteIds(app.network) }
                                revision++
                            }
                        }
                    } }
                }
            }
        }
        dialogs.Host()
        importingUri?.let { uri -> LocalImportDialog(app, uri, importingDirectory, importingVideo, { importingUri = null }) { video ->
            importingUri = null; revision++; libraryTab = "片库"; navigate("library")
            if (!video.isGalleryWork) {
                // 功能 F37：登记播放记录，PlayerActivity 的本地分支按 id+path 校验。
                val book = app.localLibrary.book(video.id)
                if (book != null) {
                    val dir = File(app.localLibrary.rootDirectory(), video.id)
                    val file = File(dir, book.files.first())
                    val thumb = book.thumb.takeIf { it.isNotBlank() }?.let { File(dir, it).absolutePath }.orEmpty()
                    // 第二个参数决定写入哪个站点分区：本地导入必须落在 local 分区，
                    // 否则播放登记会写到当前浏览站点，play() 在 local 分区查不到而报文件不存在。
                    app.library.addLocalVideo(LocalVideo(video.id, video.title, file.absolutePath, thumb, site = SiteRegistry.LOCAL.id), SiteRegistry.LOCAL.id)
                }
            }
            play(video, read = true)
        } }
        }
    }
    @Composable private fun accountFeedGeneration(site: SiteContext, request: FeedRequest): Long? =
        if (site.id == SiteRegistry.IWARA.id && request.kind == FeedKind.SUBSCRIPTIONS) {
            app.network.iwaraSessionGeneration.collectAsState().value
        } else null

    @Composable private fun LoginGate() {
        Column { EmptyState("ExHentai 必须登录", "未登录时原站可能只显示白屏。请通过官方论坛登录，并确认账号具备 ExHentai 访问权限。", "官方网页登录", ::loginOfficial)
            TextAction({ navigate("settings") }, Modifier.padding(horizontal = 24.dp)) { Text("站点设置 / 导入 Cookie") }
        }
    }
    @Composable private fun SearchScreen(adapter: SiteAdapter) {
        val colors = MaterialTheme.colorScheme
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val gallery = adapter.site.mediaKind == MediaKind.GALLERY
        val noun = adapter.itemNoun
        var error by remember { mutableStateOf(false) }; val keyboard = LocalSoftwareKeyboardController.current; val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        fun submit() {
            val query = searchQuery.trim(); val condition = searchCondition.copy(query = query).normalized()
            if (condition.isEmpty) { error = true; focus.requestFocus(); return }
            searchQuery = query; searchCondition = condition; if (query.isNotBlank()) store.addSearch(query); focusManager.clearFocus(); keyboard?.hide()
            if (multipleSources) navigate("multiSearch") else openFeed(condition.feedRequest(adapter, searchKind(adapter.site), store.galleryLanguage(adapter.site.id)))
        }
        val recent = store.searches()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp).padding(top = 14.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(searchQuery, { searchQuery = it; error = false }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text("搜索关键词") }, singleLine = true, isError = error,
                shape = ArtworkShape, leadingIcon = { Glyph(R.drawable.ic_search, null) },
                colors = rentalSearchFieldColors(),
                supportingText = if (error) ({ Text("请输入关键词") }) else null,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) IconAction(R.drawable.ic_close, "清除关键词") { searchQuery = "" }
                        IconButton(onClick = ::submit) {
                            Box(Modifier.size(34.dp).background(colors.primary, ArtworkPill), contentAlignment = Alignment.Center) {
                                CompositionLocalProvider(LocalContentColor provides colors.onPrimary) { Glyph(R.drawable.ic_chevron_right, "提交搜索", Modifier.size(17.dp)) }
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            // 功能 F02：词典候选进主搜索框。点候选即按当前范围提交；与高级搜索共用一套词典。
            val candidates = remember(searchQuery) { TagNames.suggestions(app, searchQuery) }
            if (candidates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { (slug, translated) -> Choice(if (translated == slug) slug else "$slug · $translated") { searchQuery = slug; submit() } }
            }
            Column(Modifier.fillMaxWidth().artworkPanel()
                .padding(horizontal = 10.dp, vertical = 3.dp)) {
                ContentTabs(listOf("当前站点", "多个站点"), if (multipleSources) 1 else 0) { multipleSources = it == 1 }
                HorizontalDivider(color = colors.outline)
                if (multipleSources) TextAction({ dialogs.searchSources(app, searchSites) { searchSites = it; store.set("cross_search_sites", it.joinToString(",")) } }) { Text("选择来源 · ${searchSites.size} 个站点") }
                if (!multipleSources && adapter.site.id == SiteRegistry.GALLERYEPIC.id) Copy("按 Coser 名称搜索，在目录中查看结果。")
                if (!multipleSources && adapter.site.category != SiteCategory.PHOTO) Choices(if (gallery) listOf(noun, "标签", adapter.creatorTitle) else listOf("视频", "标签", "作者"), searchScope) { searchScope = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAction({ dialogs.advancedSearch(app, searchCondition.copy(query = searchQuery), if (multipleSources) searchSites else listOf(siteId)) { value ->
                        searchCondition = value; searchQuery = value.query; revision++
                    } }) { Text("标签与高级筛选") }
                    if (multipleSources) TextAction({ dialogs.choose("漫画语言条件", GalleryLanguage.entries.map { it.title }) { selected -> searchLanguages = searchSites.filter { AdapterRegistry.adapter(SiteRegistry.byIdOrDefault(it), app.network).supportsLanguage }.associateWith { GalleryLanguage.entries[selected].name } } }) { Text("漫画语言") }
                    AnimatedVisibility(!searchCondition.copy(query = searchQuery).isEmpty) {
                        TextAction({ saveSearch { keyboard?.hide(); focusManager.clearFocus() } }) { Text("保存搜索方案") }
                    }
                }
            }
            val personalRevision by store.personal.changes.collectAsState()
            val saved = remember(personalRevision) { store.personal.snapshot().savedSearches }
            if (saved.isNotEmpty()) {
                HorizontalDivider(color = colors.outline)
                Title("常用搜索")
                NavigationRow("保存搜索更新与未读", R.drawable.ic_updates) { navigate("searchUpdates") }
                saved.forEach { value -> Row(Modifier.fillMaxWidth().artworkPanel(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable { savedSearch(value) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(R.drawable.ic_bookmark, null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(value.name, style = MaterialTheme.typography.titleSmall)
                            Copy(value.query.ifBlank { "${value.sources.size} 个来源 · 已保存筛选" })
                        }
                    }
                    IconAction(R.drawable.ic_more, "管理搜索 ${value.name}") { dialogs.choose(value.name, listOf("重命名", "固定到我的频道", "删除搜索方案")) { action ->
                        when (action) {
                            0 -> dialogs.edit("重命名搜索方案", value.name, validate = { if (it.isBlank() || it.length > 80) "请输入 1–80 个字" else null }) { store.personal.renameSearch(value.id, it) }
                            1 -> { store.personal.pinSearch(value); showNotice("已固定到“我的”中的频道") }
                            else -> dialogs.confirm("删除「${value.name}」？") { store.personal.removeSearch(value.id) }
                        }
                    } }
                } }
            }
            HorizontalDivider(color = colors.outline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Title("最近搜索", Modifier.weight(1f))
                if (recent.isNotEmpty()) TextAction({ store.clearSearches(); revision++ }) { Text("清空", color = colors.onSurfaceVariant) }
            }
            if (recent.isEmpty()) Copy("暂无搜索记录", Modifier.padding(vertical = 8.dp))
            Column {
                recent.forEach { query ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { searchQuery = query; submit() }
                        .padding(horizontal = 4.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { Glyph(R.drawable.ic_history, null, Modifier.size(17.dp)) }
                        Text(query, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { Glyph(R.drawable.ic_chevron_right, null, Modifier.size(14.dp)) }
                    }
                    HorizontalDivider(color = colors.outline.copy(alpha = .60f))
                }
            }
        }
    }
    @Composable private fun LibraryScreen(site: SiteContext) {
        val catalog = if (libraryTab in listOf("片库", "片单")) rememberPersonalCatalog(app, revision, resumed).orEmpty() else emptyList()
        if (libraryTab == "片库") {
            LibraryHubScreen(app, dialogs, catalog, { MediaRouter.open(this@MainActivity, it) }, task = { label ->
                when (label) {
                    "更新" -> navigate("updates")
                    "标签订阅" -> navigate("subscriptions")
                    else -> libraryTab = label
                }
            }, search = { navigate("search") }, importLocal = ::importLocal, openLink = ::pasteLink, download = ::downloadBatch,
                image = { favorite -> play(favorite.video, read = true, page = favorite.page) })
            return
        }
        if (libraryTab == "片单") {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                ShelfScreen(app, dialogs, { MediaRouter.open(this@MainActivity, it) }, ::downloadBatch,
                    catalog = catalog, onBack = { libraryTab = "片库" }) { navigate("search") }
            }
            return
        }
        if (libraryTab == "收藏") {
            LibraryFavorites()
            return
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            if (libraryTab in listOf("下载", "缓存")) {
                LibraryDownloads()
            } else {
                val reading = site.mediaKind == MediaKind.GALLERY
                val verb = site.category.verb
                TextAction({ libraryTab = "片库" }) { Text("返回我的片库") }
                ContentTabs(listOf(if (reading) verb else "观看", "收藏", "下载", "片单"), 0) {
                    libraryTab = listOf("记录", "收藏", "下载", "片单")[it]
                }
                val records = if (reading) store.readings().map {
                    LibraryCard(it.entry.copy(duration = "第 ${it.page} / ${it.pages} 页"), "移除${verb}记录", { store.removeReading(it.entry.id); revision++ }, read = true)
                } else store.history().map { watch ->
                    LibraryCard(watch.video().copy(duration = if (watch.finished) "已看完" else "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}"),
                        "移除观看记录", { store.removeWatch(watch.id); revision++ }, progress = if (watch.duration > 0) watch.position.toFloat() / watch.duration else 0f)
                }
                MediaLibrary(records, if (records.isNotEmpty()) ({
                    dialogs.confirm("清空${verb}记录？") { if (reading) store.clearReadings() else store.clearHistory(); revision++ }
                }) else null, { LibraryEmpty("还没有${verb}记录") }) { card -> play(card.video, read = card.read) }
            }
        }
    }
    @Composable private fun LibraryFavorites(lockedSite: String? = null) {
        val favorites = remember(revision, lockedSite) {
            val owners = if (lockedSite == null) SiteRegistry.librarySources.map { it.id } + SiteRegistry.retiredVideoHosts.keys
                else listOf(lockedSite)
            owners.flatMap { owner -> store.favorites(owner).map { it.copy(site = owner) } }.distinctBy(Favorite::key)
        }
        FavoritesScreen(favorites, store.personal, dialogs, ::openFavorite, remove = {
            store.removeFavorite(it); revision++
        }, discover = { if (lockedSite == null) navigate("explore") else back() }, lockedSite = lockedSite)
    }

    @Composable private fun LibraryDownloads(lockedSite: String? = null) {
        DownloadCenter(app, resumed, dialogs,
            video = { entry, id, path -> play(entry, id, path) }, offline = ::playOffline,
            comic = { play(it, read = true, offline = true) },
            discover = { if (lockedSite == null) navigate("explore", false) else back() },
            account = { owner -> SiteRegistry.byId(owner)?.let { openSourcePage("siteAccount", FeedRequest(), it) } },
            rescan = { withContext(Dispatchers.IO) { libraryScan() } }, lockedSite = lockedSite)
    }

    @Composable private fun LibraryEmpty(title: String) {
        LibraryNotice(title, "内容与进度会保存在当前站点片库中") {
            Action("去发现", primary = true) { navigate("explore", false) }
        }
    }
    @Composable private fun SettingsScreen(site: SiteContext) {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val adapter = AdapterRegistry.adapter(site, app.network)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = LocalUiDensity.current.pagePadding).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsSection("显示") {
                Toggle("减少透明效果", "reduce_transparency", description = "使用不透明控件，保持文字清晰。")
                Toggle("标签优先显示中文词典", "translate_tags", true)
            }
            SettingsSection("内容过滤") {
                Setting("屏蔽词", "${blockedWords(site.id).size} 个") { blockedTags(site.id) }
            }
            SettingsSection("账户") { AccountControls(site, adapter) }
            SettingsSection("播放") {
                val quality = store.string("quality", "720p")
                Setting("默认清晰度", quality) {
                    val values = listOf("360p", "480p", "720p", "1080p", "2160p")
                    dialogs.choose("默认清晰度", values, values.indexOf(quality)) { store.set("quality", values[it]); revision++ }
                }
                val speed = store.string("native_speed", "1.0")
                Setting("默认倍速", "$speed×") {
                    val values = listOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0")
                    dialogs.choose("默认倍速", values, values.indexOf(speed)) { store.set("native_speed", values[it]); revision++ }
                }
                Toggle("进入播放后自动全屏", "auto_fullscreen")
                Toggle("播放时自动下载", "auto_download", description = "下载任务可在下载中心管理，完成后可离线打开。")
                Toggle("边播边写播放缓存", "stream_cache",
                    description = "开启后可复用最多 512 MB 的临时播放缓存，适合重看。默认关闭，减轻弱网下的缓冲压力。")
                Toggle("回退播放使用 seek 缓存", "seek_cache", true,
                    description = "MP4 回拖缓存上限 64 MiB，换视频时清理。关闭流式缓存时，HLS 点播会话最多缓存 32 MiB，离开播放时清理。")
            }
            SettingsSection("网络") {
                SettingsNote("自动跟随系统代理。未设置代理时使用系统 DNS 直连，连接失败时会提示。")
                SettingsNote(runCatching { app.network.routeDescription() }.getOrDefault("系统代理设置暂不可用"))
                Setting("网络诊断", "含媒体测速") { lifecycleScope.launch {
                    dialogs.message("网络诊断", "正在实测页面和媒体链路…")
                    val pending = dialogs.content
                    val report = withContext(Dispatchers.IO) { app.playbackDiagnostics.report(site, store.string("quality", "720p")) }
                    if (dialogs.content === pending) dialogs.message("网络诊断", report)
                } }
            }
            SettingsSection("本地数据") {
                Setting("清理播放临时数据") {
                    dialogs.confirm("清理播放临时数据？", body = "已下载的视频和漫画会保留。") {
                        lifecycleScope.launch { withContext(Dispatchers.IO) { app.playback.clearStreaming() }; showNotice("已清理") }
                    }
                }
                Setting("重复文件检查", "整本内容指纹比对", ::duplicateScan)
                Setting("备份与恢复", "收藏、订阅、进度与片单") { navigate("backup") }
                if (site.mediaKind == MediaKind.GALLERY) Setting("清理本站阅读临时数据") {
                    dialogs.confirm("清理阅读临时数据？", body = "整本下载、阅读记录和收藏会保留。") {
                        lifecycleScope.launch { withContext(Dispatchers.IO) { app.galleries.clear(site) }; showNotice("已清理") }
                    }
                }
            }
            SettingsSection("下载位置") {
                Setting("当前下载位置", app.storage.describe(), ::storageOptions)
                SettingsNote("公共目录中的下载可在重装后重新识别。应用内的下载随卸载一起删除。")
            }
            SettingsSection("关于") {
                SettingsNote("R34V ${BuildConfig.VERSION_NAME}\n应用不托管内容，与原站无关联。")
                Toggle("自动检查更新", "auto_update", true)
                Setting("检查更新") { lifecycleScope.launch { UpdateController(this@MainActivity, app, dialogs).check(false) } }
            }
        }
    }
    private fun accountStatus(site: SiteContext, adapter: SiteAdapter): String? = when {
        Capability.SESSION_IMPORT in adapter.capabilities -> if (app.network.session.hasGallerySession(site)) "会话可用" else "未登录"
        Capability.LOGIN in adapter.capabilities -> if (app.network.authToken(site.id) != null || store.username(site.id).isNotBlank()) "已登录" else "未登录"
        else -> null
    }

    @Composable private fun AccountControls(site: SiteContext, adapter: SiteAdapter) {
        val status = accountStatus(site, adapter)
        when {
            Capability.SESSION_IMPORT in adapter.capabilities -> {
                val imported = status == "会话可用"
                Setting("官方网页登录", "${site.name} 官方论坛", ::loginOfficial)
                Setting("导入站点会话", if (imported) "已导入" else "未导入") { credentials(site, true) }
                if (imported) Setting("退出本站会话") {
                    dialogs.confirm("清除 ${site.name} 的账号会话？") { app.network.session.clear(site); revision++ }
                }
            }
            Capability.LOGIN in adapter.capabilities -> {
                val loggedIn = status == "已登录"
                Setting(if (loggedIn) "退出登录" else "登录站点账户", store.username(site.id).ifBlank { if (loggedIn) "已登录" else "" }) {
                    if (!loggedIn) credentials(site, false) else dialogs.confirm("退出当前账户？") {
                        app.network.clearAuthToken(site.id); app.network.session.clear(site); store.setUsername("", site.id); revision++
                    }
                }
            }
            else -> SettingsNote("${site.name} 当前支持公开浏览，无需登录。")
        }
    }

    /** 功能 F41：整本内容指纹比对。只报告候选；删除由用户逐本决定，走各来源既有安全删除路径。 */
    private fun duplicateScan() {
        dialogs.content = {
            var progress by remember { mutableStateOf("正在收集本地内容…") }
            var scanning by remember { mutableStateOf(true) }
            var groups by remember { mutableStateOf(listOf<DuplicateGroup>()) }
            var busyCopy by remember { mutableStateOf<Triple<String, String, String>?>(null) }
            fun remove(copy: DuplicateCopy) {
                if (busyCopy != null) return
                busyCopy = copy.key
                lifecycleScope.launch {
                    try {
                        if (copy.source == "download") app.galleryDownloads.removeDuplicate(copy) else app.localLibrary.remove(copy.id)
                        groups = groups.mapNotNull { group ->
                            group.copy(copies = group.copies.filterNot { it.key == copy.key }).takeIf { it.copies.size > 1 }
                        }
                        showNotice("副本已删除")
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        showNotice(error.message ?: "删除失败，请重试")
                    } finally { busyCopy = null }
                }
            }
            LaunchedEffect(Unit) {
                val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
                try {
                    withContext(Dispatchers.IO) {
                        val shouldStop = { stopped.get() || !isActive }
                        val refs = app.galleries.duplicateBooks(shouldStop) + app.localLibrary.duplicateBooks(shouldStop)
                        progress = "正在比对 ${refs.size} 本内容指纹…"
                        groups = DuplicateScan.scan(refs, shouldStop)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    progress = error.message ?: "扫描失败"
                } finally {
                    stopped.set(true)
                    scanning = false
                }
            }
            val wasted = groups.sumOf { it.wastedBytes }
            AlertDialog({ if (busyCopy == null) dialogs.dismiss() }, title = { Text("重复文件检查") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (scanning) {
                        Text(progress)
                        Copy("按整本内容指纹比对（逐页字节），不按标题；只报告候选，不会自动删除任何文件。")
                    } else if (groups.isEmpty()) {
                        Text("没有发现内容完全相同的整本副本。")
                    } else {
                        Text("发现 ${groups.size} 组完全相同的副本，多占 ${humanBytes(wasted)}。每组保留一本，其余由你逐本决定。")
                        groups.forEachIndexed { index, group ->
                            Title("第 ${index + 1} 组 · ${group.copies.size} 本 · 每本 ${group.copies.first().pages} 页 · 多占 ${humanBytes(group.wastedBytes)}")
                            group.copies.forEach { copy ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.weight(1f)) {
                                        Text(copy.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val location = when {
                                            copy.source != "download" -> "本地"
                                            copy.sourceRoot == app.filesDir.absolutePath -> "应用内下载"
                                            else -> "公共目录下载"
                                        }
                                        Copy("$location · ${SiteRegistry.displayName(copy.site)} · ${humanBytes(copy.sizeBytes)}")
                                    }
                                    TextButton(onClick = { remove(copy) }, enabled = busyCopy == null) {
                                        Text(if (busyCopy == copy.key) "删除中…" else "删除此副本")
                                    }
                                }
                            }
                        }
                        Copy("删除走下载中心/本地库的既有安全路径；元数据、下载记录和索引同步清理。")
                    }
                }
            }, shape = ArtworkShape, containerColor = MaterialTheme.colorScheme.surfaceContainer,
                confirmButton = { TextAction({ dialogs.dismiss() }) { Text("关闭") } })
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
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(ArtworkPill)
                            .clickable(enabled = !busy, role = Role.Button) { action(label) }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, Modifier.weight(1f))
                            Glyph(R.drawable.ic_chevron_right, null, Modifier.size(16.dp))
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (progress.isNotBlank()) Copy(progress)
                }
            }, shape = ArtworkShape, containerColor = MaterialTheme.colorScheme.surfaceContainer,
                confirmButton = { TextAction({ dialogs.dismiss() }, enabled = !busy) { Text("关闭") } })
        }
    }
    @Composable private fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, Modifier.padding(start = 4.dp).semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth().artworkPanel().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }

    @Composable private fun SettingsNote(text: String) {
        Copy(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }

    @Composable private fun Setting(label: String, value: String = "", click: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(ArtworkShape)
            .clickable(onClick = click, role = Role.Button).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (value.isNotBlank()) Text(value, Modifier.widthIn(max = 132.dp), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                Glyph(R.drawable.ic_chevron_right, null, Modifier.size(15.dp))
            }
        }
    }

    @Composable private fun Toggle(label: String, key: String, default: Boolean = false, description: String = "") {
        val colors = MaterialTheme.colorScheme
        val checked = store.bool(key, default)
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(ArtworkShape)
            .toggleable(checked, role = Role.Switch) { store.set(key, it); revision++ }
            .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary,
                uncheckedTrackColor = colors.background, uncheckedThumbColor = colors.onSurfaceVariant,
                checkedBorderColor = Color.Transparent, uncheckedBorderColor = colors.outline,
            ))
        }
    }
    private fun normalizeBlockedWord(value: String) = value.trim().lowercase()

    private fun blockedWords(owner: String) = store.blockedTags(owner).map(::normalizeBlockedWord).filter(String::isNotBlank).distinct()

    private fun blockedTags(owner: String) {
        val tags = blockedWords(owner)
        dialogs.choose("屏蔽词：点击移除", tags + "添加屏蔽词") { index ->
            val tag = tags.getOrNull(index)
            if (tag != null) {
                store.blockedTags(owner).distinct().filter { normalizeBlockedWord(it) == tag }.forEach { store.toggleBlocked(it, owner) }
                revision++
                blockedTags(owner)
            } else dialogs.edit("输入标签 slug 或标题关键词", validate = {
                if (normalizeBlockedWord(it).isBlank()) "请输入屏蔽词" else null
            }) { value ->
                val normalized = normalizeBlockedWord(value)
                if (store.blockedTags(owner).none { normalizeBlockedWord(it) == normalized }) {
                    store.toggleBlocked(normalized, owner)
                    revision++
                }
            }
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
                    if (importing) Copy("复制已登录本站浏览器的 Cookie。验证账号权限后才保存本站会话。") else TextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, shape = ArtworkShape, colors = rentalSearchFieldColors(), singleLine = true)
                    TextField(secret, { secret = it }, Modifier.fillMaxWidth(), shape = ArtworkShape, colors = rentalSearchFieldColors(), label = { Text(if (importing) "本站 Cookie" else "密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = !importing, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error); if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, shape = ArtworkShape, containerColor = MaterialTheme.colorScheme.surfaceContainer,
                confirmButton = { TextAction({ busy = true; error = ""; scope.launch {
                try { if (importing) withContext(Dispatchers.IO) { AdapterRegistry.adapter(site, app.network).importSession(secret) } else app.network.login(site, username, secret)
                    secret = ""; dialogs.dismiss(); revision++; if (importing) navigate("home", false); showNotice("登录成功")
                } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "登录失败" } finally { busy = false }
            } }, enabled = !busy && secret.isNotBlank() && (importing || username.isNotBlank())) { Text(if (importing) "验证并导入" else "登录") } }, dismissButton = { TextAction({ dialogs.dismiss() }, enabled = !busy) { Text("取消") } })
        }
    }
}
