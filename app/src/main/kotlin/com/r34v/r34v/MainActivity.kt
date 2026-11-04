@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.InputType
import androidx.core.widget.doAfterTextChanged
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.offline.Download
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.Capability
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var languageBar: GalleryLanguageBar
    private lateinit var navigation: LinearLayout
    private var screen = "home"
    private var request = FeedRequest()
    private var libraryTab = "记录"
    private var favoriteTab = "视频"
    private var searchQuery = ""
    private var searchScope = 0
    private var navigationTab = "home"
    private val scrollPositions = mutableMapOf<String, Int>()
    private var screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backStack = ArrayDeque<Pair<String, FeedRequest>>()
    private var feedList: RecyclerView? = null
    private lateinit var switcher: SiteSwitcher
    private val officialLogin = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) navigate("home", false)
    }
    private fun launchOfficialLogin() = officialLogin.launch(Intent(this, SiteAuthActivity::class.java).putExtra("site", SiteRegistry.current.id))

    /** Swaps the active site: storage namespace, network and every cached list move together. */
    private fun switchSite(site: SiteContext) {
        if (site.id == SiteRegistry.current.id) return
        store.switchSite(site.id)
        // Routing is per URL; a site switch no longer rewrites or invalidates network settings.
        scrollPositions.clear()
        backStack.clear()
        request = FeedRequest()
        libraryTab = "记录"
        favoriteTab = if (site.mediaKind == MediaKind.GALLERY) "画廊" else "视频"
        searchQuery = ""
        searchScope = 0
        navigate("home", false)
        toast("已切换到 ${site.name}")
    }

    private fun openSiteSwitcher() {
        if (::switcher.isInitialized) switcher.open()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra("link")
        val linkedSite = link?.toHttpUrlOrNull()?.host?.let(SiteRegistry::forHost)
        val linkedRequest = if (link != null && linkedSite != null) AdapterRegistry.adapter(linkedSite, app.network).linkRequest(link) else null
        if (savedInstanceState == null && linkedRequest != null && linkedSite != null) store.switchSite(linkedSite.id)
        root = column().apply { setBackgroundColor(Palette.canvas) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        toolbar = row().apply { minimumHeight = dp(64); setPadding(dp(16), dp(4), dp(8), dp(4)) }
        body = FrameLayout(this)
        languageBar = GalleryLanguageBar(this) { language ->
            if (store.galleryLanguage() != language) {
                store.setGalleryLanguage(language)
                request = request.copy(language = language, page = 1, cursor = "")
                scrollPositions.remove(screen)
                navigate(screen, false)
            }
        }
        navigation = row().apply { minimumHeight = dp(64); setBackgroundColor(Palette.canvas) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(languageBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(line())
        root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        switcher = SiteSwitcher(this, root, { SiteRegistry.all }, { SiteRegistry.current }) { site -> switchSite(site) }
        setContentView(switcher)
        savedInstanceState?.getString("request")?.let { runCatching { app.library.gson.fromJson(it, FeedRequest::class.java) }.getOrNull()?.let { value -> request = value } }
        libraryTab = savedInstanceState?.getString("libraryTab") ?: "记录"
        favoriteTab = savedInstanceState?.getString("favoriteTab") ?: "视频"
        searchQuery = savedInstanceState?.getString("searchQuery").orEmpty()
        searchScope = savedInstanceState?.getInt("searchScope") ?: 0
        navigationTab = savedInstanceState?.getString("navigationTab") ?: "home"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::switcher.isInitialized && switcher.isShowing) { switcher.close(); return }
                if (backStack.isNotEmpty()) { val old = backStack.removeLast(); request = old.second; navigate(old.first, false) }
                else if (screen != "home") navigate("home", false) else finish()
            }
        })
        if (savedInstanceState == null && linkedRequest != null) {
            request = linkedRequest
            navigate("feed", false)
        } else navigate(savedInstanceState?.getString("screen") ?: "home", false)
        if (!BuildConfig.DEBUG && store.bool("auto_update", true) && System.currentTimeMillis() - store.prefs.getLong("update_checked_at", 0) > 12 * 60 * 60 * 1000) {
            lifecycleScope.launch { UpdateController(this@MainActivity, app).check(silent = true) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen); outState.putString("request", store.gson.toJson(request)); outState.putString("libraryTab", libraryTab)
        outState.putString("navigationTab", navigationTab); outState.putString("favoriteTab", favoriteTab); outState.putString("searchQuery", searchQuery); outState.putInt("searchScope", searchScope)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (feedList?.layoutManager as? GridLayoutManager)?.spanCount = columns()
        (feedList?.layoutManager as? StaggeredGridLayoutManager)?.spanCount = columns()
    }
    override fun onDestroy() { screenScope.cancel(); super.onDestroy() }
    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && screen == "library") navigate("library", false)
        else if (::body.isInitialized && screen != "settings" && needsExLogin()) navigate("home", false)
    }
    private fun needsExLogin() = SiteRegistry.current.id == SiteRegistry.EXHENTAI.id && !app.network.session.hasGallerySession(SiteRegistry.EXHENTAI)
    private fun exLoginGate() = show(scroll(column(24).apply {
        addView(label("ExHentai 必须登录", 23f, bold = true)); space()
        addView(label("未登录时原站可能只显示白屏。请通过官方论坛登录，并确认账号具备 ExHentai 访问权限。", 15f, Palette.muted))
        space(20); addView(action("官方网页登录", true) { launchOfficialLogin() })
        space(8); addView(outlineAction("站点设置") { navigate("settings", false) })
        space(); addView(label("也可在站点设置中导入已有 Cookie；登录成功后会验证本站访问权限。", 13f, Palette.muted))
    }))
    private fun columns() = if (resources.configuration.screenWidthDp >= 600) 3 else 2
    private fun navigate(target: String, push: Boolean = true) {
        (body.getChildAt(0) as? ScrollView)?.let { scrollPositions[screen] = it.scrollY }
        if (push && screen != target) backStack.addLast(screen to request)
        screenScope.cancel(); screenScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        if (target in listOf("home", "explore", "search", "library")) navigationTab = target
        screen = target; feedList = null; toolbar.removeAllViews(); body.removeAllViews(); navigation.removeAllViews()
        val isHome = target == "home"
        val title = when (target) { "home" -> SiteRegistry.current.name; "explore" -> "发现"; "search" -> "搜索"; "library" -> "片库"; "settings" -> "设置"; "feed" -> AdapterRegistry.adapter(SiteRegistry.current, app.network).feedTitle(request); else -> request.kind.title }
        if (target in listOf("feed", "index", "settings")) toolbar.addView(iconAction(R.drawable.ic_back, "返回") { onBackPressedDispatcher.onBackPressed() })
        if (!isHome) toolbar.addView(label(title, 21f, Palette.text, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(row().apply {
            isFocusable = true
            minimumHeight = dp(48)
            minimumWidth = dp(48); buttonSemantics()
            contentDescription = "切换站点，当前 ${SiteRegistry.current.name}"
            setPadding(dp(2), dp(4), dp(10), dp(4))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22ffffff),
                rounded(android.graphics.Color.TRANSPARENT, dp(4).toFloat()), null)
            addView(label(SiteRegistry.current.name, 14f, Palette.secondary, true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(120)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-2, -2))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.secondary)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(4) })
            setOnClickListener { openSiteSwitcher() }
        }, LinearLayout.LayoutParams(-2, -2))
        if (isHome) toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(iconAction(R.drawable.ic_download, if (SiteRegistry.current.mediaKind == MediaKind.GALLERY) "图页缓存" else "下载") { libraryTab = "缓存"; navigate("library") })
        toolbar.addView(iconAction(R.drawable.ic_settings, "设置") { navigate("settings") })
        for ((key, name, icon) in listOf(Triple("home", "首页", R.drawable.ic_home), Triple("explore", "发现", R.drawable.ic_explore), Triple("search", "搜索", R.drawable.ic_search), Triple("library", "片库", R.drawable.ic_bookmark))) {
            val active = navigationTab == key
            navigation.addView(column().apply {
                gravity = Gravity.CENTER; minimumHeight = dp(64); isSelected = active; isFocusable = true
                buttonSemantics()
                setPadding(0, dp(9), 0, dp(9))
                background = controlBackground(android.graphics.Color.TRANSPARENT)
                addView(FrameLayout(this@MainActivity).apply {
                    if (active) background = rounded(Palette.raised, dp(16).toFloat())
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(if (active) Palette.text else Palette.muted)
                    }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(56), dp(32)))
                addView(label(name, 11f, if (active) Palette.text else Palette.muted, active).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setOnClickListener { backStack.clear(); navigate(key, false) }; contentDescription = name
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        languageBar.configure(store.galleryLanguage())
        languageBar.visibility = if (SiteRegistry.current.mediaKind == MediaKind.GALLERY && target in listOf("home", "explore", "search", "feed") && !needsExLogin()) View.VISIBLE else View.GONE
        if (target != "settings" && needsExLogin()) { exLoginGate(); return }
        when (target) { "home" -> home(); "explore" -> explore(); "search" -> search(); "library" -> library(); "settings" -> settings(); "feed" -> feed(); "index" -> index(request.target) }
    }
    private fun show(view: View) {
        body.removeAllViews(); body.addView(view, FrameLayout.LayoutParams(-1, -1)); view.enterQuietly()
        if (view is ScrollView) {
            view.setOnScrollChangeListener { _, _, y, _, previousY -> languageBar.onContentScrolled(y - previousY, !view.canScrollVertically(-1)) }
            scrollPositions[screen]?.let { y -> view.post { view.scrollTo(0, y) } }
        }
    }
    private fun loading() = show(CinemaLoadingView(this).apply { showStage("正在装载内容", "整理最新片架", "加载中") })
    private fun failure(error: Throwable, retry: () -> Unit) {
        if (error is CancellationException) throw error
        if (error is GalleryLoginRequiredException && SiteRegistry.current.id == SiteRegistry.EXHENTAI.id) {
            app.network.session.clear(SiteRegistry.EXHENTAI); exLoginGate(); return
        }
        show(scroll(emptyState(R.drawable.ic_explore, "内容加载失败", error.message ?: "请检查网络连接后重试", "重试", retry).apply {
            if (error.message.orEmpty().let { it.contains("会话") || it.contains("权限") }) addView(quietAction("站点设置") { navigate("settings") })
        }))
    }
    private fun openFeed(value: FeedRequest) {
        backStack.addLast(screen to request); request = value; navigate("feed", false)
    }
    private fun play(video: Video, offlineId: String? = null, localPath: String? = null, read: Boolean = false) {
        if (isGallery(video)) {
            startActivity(Intent(this, GalleryActivity::class.java).putExtra("gallery", store.gson.toJson(video.copy(mediaKind = MediaKind.GALLERY))).putExtra("read", read))
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video)).putExtra("download", offlineId).putExtra("local", localPath))
    }
    private data class Tile(val full: Boolean = true, val create: () -> View)
    private inner class Tiles(val items: MutableList<Tile>, val masonry: Boolean = false) : RecyclerView.Adapter<Tiles.Holder>() {
        inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(this@MainActivity).apply {
            layoutParams = if (masonry) StaggeredGridLayoutManager.LayoutParams(-1, -2) else RecyclerView.LayoutParams(-1, -2)
        })
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            (holder.frame.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = items[position].full
            holder.frame.removeAllViews(); holder.frame.addView(items[position].create())
        }
    }
    private fun grid(items: MutableList<Tile>): Pair<RecyclerView, Tiles> {
        val masonry = SiteRegistry.current.mediaKind == MediaKind.GALLERY
        val adapter = Tiles(items, masonry)
        val layout = if (masonry) StaggeredGridLayoutManager(columns(), StaggeredGridLayoutManager.VERTICAL) else GridLayoutManager(this, columns()).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (items[position].full) spanCount else 1 }
        }
        val list = RecyclerView(this).apply { layoutManager = layout; this.adapter = adapter; setPadding(dp(11), 0, dp(11), dp(16)); clipToPadding = false; itemAnimator = null }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                languageBar.onContentScrolled(dy, !recyclerView.canScrollVertically(-1))
            }
        })
        feedList = list
        return list to adapter
    }
    private fun lastVisible(list: RecyclerView) = when (val layout = list.layoutManager) {
        is StaggeredGridLayoutManager -> layout.findLastVisibleItemPositions(null).maxOrNull() ?: 0
        is GridLayoutManager -> layout.findLastVisibleItemPosition()
        else -> 0
    }
    private fun heading(text: String) = Tile { label(text, 20f, bold = true).apply { setPadding(dp(5), dp(24), dp(5), dp(12)); ViewCompat.setAccessibilityHeading(this, true) } }
    private fun isGallery(video: Video) = video.mediaKind == MediaKind.GALLERY || SiteRegistry.byId(video.site)?.mediaKind == MediaKind.GALLERY
    private fun video(video: Video) = Tile(false) { videoTile(video, listOf((if (store.isFavorite(if (isGallery(video)) "gallery" else "video", video.id)) "取消收藏" else "收藏") to { favoriteVideo(video) })) { play(video) }.apply { setOnLongClickListener { favoriteVideo(video); true } } }
    private fun favoriteVideo(video: Video) {
        val added = store.toggleFavorite(Favorite(if (isGallery(video)) "gallery" else "video", video.id, video.title, video.url, video.thumb, video.duration, site = video.site), video.site)
        toast(if (added) "已收藏" else "已取消收藏"); feedList?.adapter?.notifyDataSetChanged()
    }
    /** The site's own default entry, so the home screen is not KVS-shaped on every site. */
    private fun homeRequest(page: Int = 1): FeedRequest {
        return filteredRequest(AdapterRegistry.adapter(SiteRegistry.current, app.network).homeRequest(page))
    }

    private fun filteredRequest(value: FeedRequest) = if (SiteRegistry.current.mediaKind == MediaKind.GALLERY)
        value.copy(language = store.galleryLanguage()) else value

    private fun home() {
        loading()
        val items = mutableListOf<Tile>()
        var page = 1
        var cursor = ""
        var total = 1
        var loadingMore = false
        var adapter: Tiles? = null
        val kvs = SiteRegistry.current.id == SiteRegistry.R34V.id
        fun load() {
            if (loadingMore) return
            loadingMore = true
            screenScope.launch {
                // Ranking rows only exist on KVS sites; hanime1 exposes sorts instead.
                val popular = if (page == 1 && kvs) async {
                    try { withTimeoutOrNull(10000) { app.repository.feed(FeedRequest(FeedKind.POPULAR)) } }
                    catch (error: Exception) { if (error is CancellationException) throw error; null }
                } else null
                val rated = if (page == 1 && kvs) async {
                    try { withTimeoutOrNull(10000) { app.repository.feed(FeedRequest(FeedKind.RATED)) } }
                    catch (error: Exception) { if (error is CancellationException) throw error; null }
                } else null
                try {
                    val result = app.repository.feed(homeRequest(page).copy(cursor = cursor))
                    val videos = result.videos.filter(store::allowed)
                    if (items.isEmpty()) {
                        val popularVideos = withTimeoutOrNull(600) { popular?.await() }?.videos.orEmpty().filter(store::allowed)
                        val ratedVideos = withTimeoutOrNull(600) { rated?.await() }?.videos.orEmpty().filter(store::allowed)
                        popular?.cancel(); rated?.cancel()
                        val ranked = popularVideos.take(3).mapIndexed { index, video -> CarouselEntry(video, "热门榜 · 第 ${index + 1} 位") } +
                            ratedVideos.take(3).mapIndexed { index, video -> CarouselEntry(video, "高分榜 · 第 ${index + 1} 位") }
                        val entries = ranked.ifEmpty { videos.take(1).map { CarouselEntry(it, "最新精选") } }
                        if (entries.isNotEmpty() && SiteRegistry.current.mediaKind != MediaKind.GALLERY) {
                            val carousel = VideoCarousel(this@MainActivity, entries, screenScope,
                                { id -> store.isFavorite("video", id) },
                                { value -> store.toggleFavorite(Favorite("video", value.id, value.title, value.url, value.thumb, value.duration)) },
                                { value -> play(value) })
                            items.add(Tile { carousel })
                        }
                        if (result.cached) items.add(Tile { label("当前显示离线列表缓存", 12f, Palette.muted).apply { setPadding(dp(16), dp(8), dp(16), dp(12)) } })
                        items.add(heading(if (SiteRegistry.current.mediaKind == MediaKind.GALLERY) "最新画廊" else if (kvs) "最新视频" else "最新上传"))
                    }
                    videos.forEach { items.add(video(it)) }
                    total = result.total
                    page = result.current + 1
                    cursor = result.nextCursor.orEmpty()
                    if (result.current == 1 && result.videos.isEmpty()) items.add(Tile { label(if (SiteRegistry.current.mediaKind == MediaKind.GALLERY) "当前语言下暂无画廊，可切换语言查看" else "暂时没有可显示的视频", 14f, Palette.muted).apply { setPadding(dp(16), dp(12), dp(16), dp(24)) } })
                    if (adapter == null) {
                        val (list, a) = grid(items); adapter = a; show(list)
                        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                if (dy > 0 && lastVisible(recyclerView) >= items.size - 7 && page <= total) load()
                            }
                        })
                    } else adapter?.notifyDataSetChanged()
                    if (page <= total && result.videos.isEmpty()) total = page - 1
                } catch (error: Exception) {
                    popular?.cancel(); rated?.cancel()
                    if (error is CancellationException) throw error
                    if (adapter == null) failure(error) { loadingMore = false; load() }
                    else { toast(error.message ?: "下一页加载失败"); items.add(Tile { action("重试下一页") { items.removeAt(items.lastIndex); load() } }); adapter?.notifyDataSetChanged() }
                } finally { loadingMore = false }
            }
        }
        load()
    }
    private fun watchProgress(watch: Watch) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progress = if (watch.duration > 0) (watch.position * 1000 / watch.duration).toInt() else 0
        progressTintList = android.content.res.ColorStateList.valueOf(Palette.red)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.raised)
        layoutParams = LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) }
    }
    private fun feed() {
        loading()
        val items = mutableListOf<Tile>()
        var page = request.page; var total = page; var loadingMore = false
        var cursor = request.cursor
        var adapter: Tiles? = null
        fun load(reset: Boolean = false) {
            if (loadingMore) return
            loadingMore = true
            screenScope.launch {
                try {
                    val result = app.repository.feed(filteredRequest(request.copy(page = if (reset) 1 else page, cursor = if (reset) "" else cursor)))
                    if (reset) items.clear()
                    if (items.isEmpty()) {
                        if (result.cached) items.add(Tile { label("当前显示离线列表缓存", 13f, Palette.muted) })
                        items.add(Tile { row().apply {
                            val siteAdapter = AdapterRegistry.adapter(SiteRegistry.current, app.network)
                            addView(label(siteAdapter.feedTitle(request), 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(action(siteAdapter.sortTitle(request.sort)) { chooseSort() }); addView(action("刷新") { page = 1; load(true) })
                        } })
                    }
                    val known = items.size
                    result.videos.filter(store::allowed).forEach { items.add(video(it)) }
                    total = if (request.kind == FeedKind.FOR_YOU) result.current else result.total
                    page = result.current + 1
                    cursor = result.nextCursor.orEmpty()
                    if (known == items.size && result.videos.isEmpty()) items.add(Tile { label(if (SiteRegistry.current.mediaKind == MediaKind.GALLERY) "当前语言下没有找到画廊，可切换语言或修改搜索条件" else "没有找到视频", 14f, Palette.muted) })
                    if (adapter == null) {
                        val (list, a) = grid(items); adapter = a; show(list)
                        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                if (dy > 0 && lastVisible(recyclerView) >= items.size - 7 && page <= total) load()
                            }
                        })
                    } else adapter?.notifyDataSetChanged()
                    if (page <= total && result.videos.isEmpty()) total = page - 1
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (adapter == null) failure(error) { loadingMore = false; load() }
                    else { toast(error.message ?: "下一页加载失败"); items.add(Tile { action("重试下一页") { items.removeAt(items.lastIndex); load() } }); adapter?.notifyDataSetChanged() }
                } finally { loadingMore = false }
            }
        }
        load()
    }
    private fun chooseSort() {
        val adapter = AdapterRegistry.adapter(SiteRegistry.current, app.network)
        val options = adapter.sorts.filter { request.kind == FeedKind.SEARCH || it != Sort.RELEVANCE }
        AlertDialog.Builder(this).setTitle("排序方式").setItems(options.map(adapter::sortTitle).toTypedArray()) { _, which ->
            request = request.copy(sort = options[which], page = 1, cursor = ""); navigate("feed", false)
        }.show()
    }
    private fun explore() {
        val site = SiteRegistry.current
        val adapter = AdapterRegistry.adapter(site, app.network)
        fun browse(segment: String) { backStack.addLast(screen to request); request = FeedRequest(target = segment); navigate("index", false) }
        fun entry(title: String, subtitle: String, click: () -> Unit) = row().apply {
            minimumHeight = dp(if (subtitle.isBlank()) 56 else 72); setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(column().apply { addView(label(title, 16f, bold = true)); if (subtitle.isNotBlank()) addView(label(subtitle, 12f, Palette.muted)) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_chevron_down); rotation = -90f }, LinearLayout.LayoutParams(dp(20), dp(20))); setOnClickListener { click() }; isFocusable = true; contentDescription = title
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff), rounded(Palette.surface, dp(3).toFloat()), null)
        }
        // Entries come from the site itself, so a new site needs no UI change here.
        val genres = adapter.sections.filter { it.feed?.kind == FeedKind.CATEGORY }
        val ranked = adapter.sections.filter { it.feed != null && it !in genres }
        val catalogEntries = adapter.sections.filter { it.feed == null && adapter.sectionUrl(it.key) != null }
        show(scroll(column(16).apply {
            addView(label("找到下一部想看的", 22f, bold = true))
            addView(label("从榜单、分类和创作者开始", 14f, Palette.muted)); space(18)
            if (ranked.isNotEmpty()) {
                addView(label("精选榜单", 18f, bold = true)); space(8)
                addView(entry(ranked.first().title, ranked.first().subtitle) { openFeed(ranked.first().feed!!) }); space(8)
                addView(FlowLayout(this@MainActivity).apply {
                    ranked.drop(1).forEach { section -> addView(chip(section.title) { openFeed(section.feed!!) }) }
                })
            }
            if (genres.isNotEmpty()) {
                space(24); addView(label(adapter.catalogueTitle, 18f, bold = true)); space(8)
                addView(FlowLayout(this@MainActivity).apply {
                    genres.forEach { section -> addView(chip(section.title) { openFeed(section.feed!!) }) }
                })
            }
            if (catalogEntries.isNotEmpty()) {
                space(24); addView(label("浏览内容", 18f, bold = true)); space(8)
                addView(surface(column().apply {
                    catalogEntries.forEachIndexed { index, section ->
                        if (index > 0) addView(line())
                        addView(entry(section.title, section.subtitle) { browse(section.key) })
                    }
                }))
            }
        }))
    }

    private fun index(segment: String) {
        val adapter = AdapterRegistry.adapter(SiteRegistry.current, app.network)
        if (adapter.sectionUrl(segment) == null) {
            show(column(24).apply {
                addView(label("当前站点不支持此索引", 16f, Palette.muted))
                addView(action("浏览本站内容") { navigate("explore", false) })
            })
            return
        }
        loading()
        screenScope.launch {
            try {
                val links = app.repository.index(segment)
                val items = mutableListOf(heading(when (segment) { "works" -> "选择作品"; "artists" -> "作者排行"; else -> "浏览索引" }))
                if (links.isEmpty()) items.add(Tile { label("没有找到条目", 14f, Palette.muted) })
                links.forEachIndexed { i, link -> items.add(Tile {
                    row().apply {
                        setPadding(dp(8), dp(10), dp(8), dp(10)); background = rounded(Palette.surface)
                        if (link.thumb.isNotBlank()) addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; Images.load(this@MainActivity, this, link.thumb) }, LinearLayout.LayoutParams(dp(66), dp(66)))
                        addView(column(10).apply { addView(label(if (segment == "artists" || segment.startsWith("work:")) "${i + 1}. ${link.label}" else link.label, 16f, bold = true)); if (link.subtitle.isNotBlank()) addView(label(link.subtitle, 12f, Palette.muted)) }, LinearLayout.LayoutParams(0, -2, 1f))
                        setOnClickListener {
                            if (segment == "works") { backStack.addLast(screen to request); request = FeedRequest(target = "work:${link.slug}"); navigate("index", false) }
                            else openFeed(FeedRequest(when { segment == "categories" -> FeedKind.CATEGORY; segment == "models" || segment == "artists" -> FeedKind.MODEL; else -> FeedKind.TAG }, link.slug))
                        }
                        setOnLongClickListener {
                            val kind = if (segment == "models" || segment == "artists") "model" else if (segment.startsWith("work:")) "character" else "tag"
                            if (segment != "works" && segment != "categories") toast(if (store.toggleFavorite(Favorite(kind, link.slug, link.label, link.url, link.thumb))) "已收藏" else "已取消收藏")
                            true
                        }
                    }
                }) }
                show(grid(items).first)
            } catch (error: Exception) { failure(error) { index(segment) } }
        }
    }
    private fun search() {
        val gallery = SiteRegistry.current.mediaKind == MediaKind.GALLERY
        val content = column(16)
        content.addView(label(if (gallery) "寻找想读的画廊" else "找到想看的内容", 24f, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) })
        content.addView(label("在 ${SiteRegistry.current.name} 中搜索", 14f, Palette.muted)); content.space(20)
        val creatorTitle = AdapterRegistry.adapter(SiteRegistry.current, app.network).creatorTitle
        val input = inputField(if (gallery) "搜索画廊、标签或$creatorTitle" else "搜索视频、标签或作者").apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            setText(searchQuery); setSelection(text.length); contentDescription = "搜索关键词"
        }
        val clear = iconAction(R.drawable.ic_close, "清除关键词") { input.text.clear(); input.requestFocus() }.apply {
            visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
        }
        input.doAfterTextChanged {
            searchQuery = it.toString(); input.error = null
            clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        fun submit() {
            val query = input.text.toString().trim(); if (query.isBlank()) { input.error = "请输入关键词"; input.requestFocus(); return }
            searchQuery = query; store.addSearch(query)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            val kind = when (searchScope) { 1 -> FeedKind.TAG; 2 -> FeedKind.MODEL; else -> FeedKind.SEARCH }
            openFeed(AdapterRegistry.adapter(SiteRegistry.current, app.network).searchRequest(query, kind))
        }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        content.addView(row().apply {
            addView(FrameLayout(this@MainActivity).apply {
                addView(input, FrameLayout.LayoutParams(-1, -2))
                input.setPadding(dp(14), dp(12), dp(52), dp(12))
                addView(clear, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconAction(R.drawable.ic_search, "提交搜索") { submit() })
        }); content.space(12)
        val scopes = FlowLayout(this)
        fun renderScopes() {
            scopes.removeAllViews()
            (if (gallery) listOf("画廊", "标签", creatorTitle) else listOf("视频", "标签", "作者")).forEachIndexed { index, name -> scopes.addView(chip(name, searchScope == index) { searchScope = index; renderScopes() }) }
        }
        renderScopes(); content.addView(scopes); content.space(24)
        content.addView(row().apply { addView(label("最近搜索", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f)); if (store.searches().isNotEmpty()) addView(quietAction("清空") { store.clearSearches(); navigate("search", false) }) })
        val recent = store.searches()
        if (recent.isEmpty()) content.addView(label("搜索过的关键词会保存在这里", 14f, Palette.muted))
        else content.addView(FlowLayout(this).apply { recent.forEach { query -> addView(chip(query) { input.setText(query); submit() }) } })
        show(scroll(content))
    }
    private fun library() {
        val gallerySite = SiteRegistry.current.mediaKind == MediaKind.GALLERY
        val creatorTitle = AdapterRegistry.adapter(SiteRegistry.current, app.network).creatorTitle
        val container = column()
        val task = when (libraryTab) { "记录" -> if (gallerySite) "阅读" else "观看"; "缓存" -> if (gallerySite) "图页缓存" else "下载"; else -> "收藏" }
        container.addView(FlowLayout(this).apply {
            setPadding(dp(16), dp(6), dp(16), dp(12))
            (if (gallerySite) listOf("阅读", "收藏", "图页缓存") else listOf("观看", "收藏", "下载")).forEach { name -> addView(chip(name, task == name) {
                libraryTab = when (name) { "观看", "阅读" -> "记录"; "下载", "图页缓存" -> "缓存"; else -> if (gallerySite && favoriteTab == "视频") "画廊" else favoriteTab }; navigate("library", false)
            }) }
        })
        if (task == "收藏") container.addView(FlowLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
            (if (gallerySite) listOf("画廊", creatorTitle, "标签") else listOf("视频", "角色", "作者", "标签")).forEach { name -> addView(chip(name, libraryTab == name) { favoriteTab = name; libraryTab = name; navigate("library", false) }) }
        })
        val listFrame = FrameLayout(this); container.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f)); show(container)
        fun display(items: MutableList<Tile>) {
            val position = (listFrame.getChildAt(0) as? RecyclerView)?.layoutManager?.onSaveInstanceState()
            listFrame.removeAllViews()
            if (items.isEmpty()) items.add(Tile {
                emptyState(if (task == "下载") R.drawable.ic_download else R.drawable.ic_bookmark,
                    when (libraryTab) { "记录" -> if (gallerySite) "还没有阅读记录" else "还没有观看记录"; "缓存" -> "还没有下载内容"; else -> "还没有收藏内容" },
                    when (libraryTab) { "记录" -> if (gallerySite) "阅读后，页码会自动保存在这里" else "播放后，进度会自动保存在这里"; "缓存" -> "在播放详情中下载，即可离线观看"; else -> "遇到喜欢的内容，点一下收藏就能留在这里" },
                    "去发现") { navigate("explore", false) }
            })
            val list = grid(items).first; listFrame.addView(list); position?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
        when (libraryTab) {
            "记录" -> {
                val items = mutableListOf<Tile>()
                if (gallerySite) {
                    if (store.readings().isNotEmpty()) items.add(Tile { quietAction("清空阅读记录") { confirm("清空阅读记录？") { store.clearReadings(); navigate("library", false) } } })
                    store.readings().forEach { progress -> items.add(Tile(false) {
                        videoTile(progress.entry.copy(duration = "第 ${progress.page} / ${progress.pages} 页"), listOf("移除阅读记录" to { store.removeReading(progress.entry.id); navigate("library", false) })) { play(progress.entry, read = true) }
                    }) }
                } else {
                if (store.history().isNotEmpty()) items.add(Tile { quietAction("清空观看记录") { confirm("清空观看记录？") { store.clearHistory(); navigate("library", false) } } })
                store.history().forEach { watch -> items.add(Tile(false) {
                    videoTile(watch.video().copy(duration = if (watch.finished) "已看完" else "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}"), listOf("移除观看记录" to { confirm("删除这条记录？") { store.removeWatch(watch.id); navigate("library", false) } })) { play(watch.video()) }.apply {
                        addView(watchProgress(watch))
                        setOnLongClickListener { confirm("删除这条记录？") { store.removeWatch(watch.id); navigate("library", false) }; true }
                    }
                }) }
                }
                display(items)
            }
            "缓存" -> screenScope.launch {
                if (gallerySite) {
                    val site = SiteRegistry.current
                    val info = withContext(Dispatchers.IO) { app.galleries.cacheInfo(site) }
                    display(mutableListOf(Tile { column(16).apply {
                        addView(label("已缓存 ${info.first} 页 · ${info.second / (1024 * 1024)} MB", 18f, bold = true))
                        addView(label("已读图页保存在本机，可从阅读记录离线重看；尚未缓存的页面需要联网。", 14f, Palette.muted))
                        space(); addView(quietAction("清理图页缓存") { confirm("清理本站已缓存图页？阅读记录和收藏会保留。") {
                            screenScope.launch { withContext(Dispatchers.IO) { app.galleries.clear(site) }; navigate("library", false) }
                        } })
                    } }))
                    return@launch
                }
                while (isActive) {
                    // Only this site's downloads: another site's cache is not browsable from here.
                    val downloads = withContext(Dispatchers.IO) { app.playback.downloadsFor() }
                    val items = mutableListOf<Tile>()
                    for (download in downloads) {
                        val meta = app.playback.metadata(download) ?: continue
                        items.add(Tile { column(8).apply {
                            val status = when (download.state) { Download.STATE_COMPLETED -> "已缓存 · 可离线播放"; Download.STATE_DOWNLOADING -> "下载中 ${download.percentDownloaded.coerceAtLeast(0f).toInt()}%"; Download.STATE_STOPPED -> "已暂停"; Download.STATE_FAILED -> "下载失败"; Download.STATE_REMOVING -> "正在删除"; else -> "等待网络" }
                            addView(label(meta.video.title, 16f, bold = true)); addView(label("${meta.quality} · $status", 12f, Palette.muted))
                            addView(FlowLayout(this@MainActivity).apply {
                                if (download.state == Download.STATE_COMPLETED) addView(action("离线播放", true) { play(meta.video, download.request.id) })
                                else addView(action(if (download.state == Download.STATE_STOPPED || download.state == Download.STATE_FAILED) "继续" else "暂停") {
                                    if (download.state == Download.STATE_FAILED) screenScope.launch { runCatching { app.repository.detail(meta.video.url) }.onSuccess { detail -> app.playback.add(meta.video, detail.streams.firstOrNull { it.label == meta.quality } ?: detail.streams.first()) }.onFailure(::showError) }
                                    else app.playback.pause(download.request.id, download.state != Download.STATE_STOPPED)
                                })
                                addView(quietAction("删除") { confirm("删除这份缓存？") { app.playback.remove(download.request.id) } })
                            })
                        } })
                    }
                    for (local in store.localVideos()) items.add(Tile { column(8).apply {
                        addView(label(local.title, 16f, bold = true)); addView(label("${local.quality} · 已迁移的离线缓存", 12f, Palette.muted))
                        addView(row().apply { addView(action("离线播放", true) { play(Video(local.id, local.title, store.watch(local.id)?.path.orEmpty(), local.thumb), localPath = local.path) }); addView(quietAction("删除") { confirm("删除这份缓存？") { store.removeLocal(local.id) } }) })
                    } })
                    display(items); delay(2000)
                }
            }
            else -> {
                val kind = when (libraryTab) { "视频" -> "video"; "画廊" -> "gallery"; "角色" -> "character"; "作者", "上传者", "画师" -> "model"; else -> "tag" }
                val items = store.favorites().filter { it.kind == kind }.map { favorite ->
                    val site = SiteRegistry.byIdOrDefault(favorite.site)
                    val url = if (favorite.path.startsWith("http")) favorite.path else site.absolute(favorite.path)
                    if (kind == "video" || kind == "gallery") Tile(false) { videoTile(Video(favorite.target, favorite.title, url, favorite.thumb, favorite.subtitle, site = site.id, mediaKind = site.mediaKind), listOf("取消收藏" to { store.toggleFavorite(favorite); navigate("library", false) })) { play(Video(favorite.target, favorite.title, url, favorite.thumb, site = site.id, mediaKind = site.mediaKind)) }.apply { setOnLongClickListener { store.toggleFavorite(favorite); navigate("library", false); true } } }
                    else Tile { action(favorite.title) { openFeed(FeedRequest(if (kind == "model") FeedKind.MODEL else FeedKind.TAG, favorite.target)) }.apply { setOnLongClickListener { store.toggleFavorite(favorite); navigate("library", false); true } } }
                }.toMutableList()
                display(items)
            }
        }
    }
    private fun settings() {
        val content = column(20).apply { setPadding(dp(20), dp(4), dp(20), dp(32)) }
        var group = column()
        fun title(text: String) {
            content.space(24); content.addView(label(text, 12f, Palette.muted, true).apply { setPadding(dp(2), 0, 0, dp(10)) })
            group = surface(column(14).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }); content.addView(group)
        }
        fun item(text: String, value: String = "", click: () -> Unit) {
            if (group.childCount > 0) group.addView(line())
            group.addView(row().apply {
                minimumHeight = dp(58); isFocusable = true; setPadding(dp(2), dp(8), dp(2), dp(8))
                addView(label(text, 14.5f), LinearLayout.LayoutParams(0, -2, 1f))
                if (value.isNotBlank()) addView(label(value, 12f, Palette.muted).apply {
                    setPadding(dp(8), 0, dp(8), 0)
                    maxWidth = dp((resources.configuration.screenWidthDp * .4f).toInt())
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_chevron_down); rotation = -90f
                    imageTintList = android.content.res.ColorStateList.valueOf(Palette.muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                background = controlBackground(android.graphics.Color.TRANSPARENT)
                setOnClickListener { click() }; contentDescription = "$text $value"
            })
        }
        fun toggle(text: String, key: String, default: Boolean = false) {
            if (group.childCount > 0) group.addView(line())
            group.addView(Switch(this).apply { this.text = text; textSize = 14.5f; setTextColor(Palette.text); setPadding(dp(2), dp(10), dp(2), dp(10)); minHeight = dp(58); isChecked = store.bool(key, default)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Palette.text, Palette.muted))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(Palette.red, Palette.raised))
                trackDrawable = android.graphics.drawable.GradientDrawable().apply { setSize(dp(44), dp(28)); cornerRadius = dp(14).toFloat(); setColor(android.content.res.ColorStateList(states, intArrayOf(Palette.red, Palette.raised))) }
                thumbDrawable = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setSize(dp(24), dp(24)); setColor(android.content.res.ColorStateList(states, intArrayOf(Palette.text, Palette.muted))) }
                setOnCheckedChangeListener { _, checked -> store.set(key, checked) }
            })
        }
        title("显示")
        toggle("标签优先显示中文词典", "translate_tags", true)
        title("内容过滤")
        item("屏蔽词", "${store.blockedTags().size} 个") { blockedTags() }
        title("账户")
        if (Capability.SESSION_IMPORT in AdapterRegistry.adapter(SiteRegistry.current, app.network).capabilities) {
            val site = SiteRegistry.current
            val signedIn = app.network.session.hasGallerySession(site)
            item("官方网页登录", "${site.name} 官方论坛") { launchOfficialLogin() }
            item("导入站点会话", if (signedIn) "已导入" else "未导入") { importGallerySession() }
            if (signedIn) item("退出本站会话") { confirm("清除 ${site.name} 的账号会话？") { app.network.session.clear(site); navigate("settings", false) } }
        } else if (Capability.LOGIN in AdapterRegistry.adapter(SiteRegistry.current, app.network).capabilities) item(if (store.username.isBlank()) "登录站点账户" else "退出登录", store.username) {
            // Signing out clears this site only; another site's session stays intact.
            if (store.username.isBlank()) login() else confirm("退出当前账户？") { app.network.session.clear(SiteRegistry.current); navigate("settings", false) }
        } else group.addView(label("${SiteRegistry.current.name} 当前支持公开浏览，无需登录。", 14f, Palette.muted))
        title("播放")
        item("默认清晰度", store.string("quality", "720p")) { choose("默认清晰度", listOf("360p", "480p", "720p", "1080p", "2160p")) { store.set("quality", it); navigate("settings", false) } }
        item("默认倍速", "${store.string("native_speed", "1.0")}×") { choose("默认倍速", listOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0")) { store.set("native_speed", it); navigate("settings", false) } }
        toggle("进入播放后自动全屏", "auto_fullscreen")
        toggle("播放时自动加入完整缓存", "auto_download")
        group.addView(label("播放时自动缓存已读数据；完整缓存支持后台下载和离线播放。", 12f, Palette.muted))
        title("网络")
        group.addView(label("自动跟随系统代理；未设置代理时使用系统 DNS 直连。代理连接失败时会显示错误。", 14f, Palette.muted))
        group.addView(label(runCatching { app.network.routeDescription() }.getOrDefault("系统代理设置暂不可用"), 12f, Palette.muted))
        item("网络诊断") {
            val dialog = AlertDialog.Builder(this).setTitle("网络诊断").setMessage("正在发起实时请求…").setPositiveButton("关闭", null).show()
            screenScope.launch { val report = app.network.diagnose(); if (dialog.isShowing) dialog.setMessage(report) }
        }
        title("本地数据")
        item("清理播放临时缓存") { confirm("清理播放缓存？已下载的视频会保留。") { screenScope.launch { withContext(Dispatchers.IO) { app.playback.clearStreaming() }; toast("已清理") } } }
        title("关于")
        group.addView(label("R34V ${BuildConfig.VERSION_NAME}", 16f, bold = true)); group.addView(label("应用不托管内容，与原站无关联。", 12f, Palette.muted))
        toggle("自动检查更新", "auto_update", true)
        item("检查更新") { lifecycleScope.launch { UpdateController(this@MainActivity, app).check(false) } }
        show(scroll(content))
    }
    private fun login() {
        val form = column(20)
        val username = inputField("用户名").apply { inputType = InputType.TYPE_CLASS_TEXT }
        val password = inputField("密码").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(username); form.space(10); form.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle("登录").setView(form).setPositiveButton("登录", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE); button.isEnabled = false
            lifecycleScope.launch {
                try { app.network.login(username.text.toString(), password.text.toString()); password.text.clear(); dialog.dismiss(); if (screen == "settings") navigate("settings", false); toast("登录成功") }
                catch (error: Exception) { if (error is CancellationException) throw error; showError(error) }
                finally { button.isEnabled = true }
            }
        } }; dialog.show()
    }
    private fun importGallerySession() {
        val site = SiteRegistry.current
        val input = inputField("ipb_member_id=…; ipb_pass_hash=…; igneous=…").apply {
            isSingleLine = false; minLines = 3; maxLines = 6; isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            contentDescription = "本站 Cookie"
        }
        val form = column(20).apply {
            addView(label("从已登录 ${site.name} 的浏览器复制 Cookie。仅导入本站会话；ExHentai 仍需账号具备访问权限。", 14f, Palette.muted))
            space(); addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle("导入 ${site.name} 会话").setView(form).setPositiveButton("验证并导入", null).setNegativeButton("取消", null).create()
        dialog.setOnDismissListener { input.text.clear() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE); button.isEnabled = false
            val raw = input.text.toString()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { AdapterRegistry.adapter(site, app.network).importSession(raw) }
                    dialog.dismiss(); navigate("home", false); toast("本站会话已导入")
                } catch (error: Exception) { if (error is CancellationException) throw error; showError(error) }
                finally { button.isEnabled = true }
            }
        } }; dialog.show()
    }
    private fun blockedTags() {
        val tags = store.blockedTags()
        AlertDialog.Builder(this).setTitle("屏蔽词：点击移除").setItems(tags.toTypedArray()) { _, which -> store.toggleBlocked(tags[which]); blockedTags() }
            .setPositiveButton("添加") { _, _ -> edit("输入标签 slug 或标题关键词", "") { if (it.isNotBlank() && it !in store.blockedTags()) store.toggleBlocked(it.lowercase()); navigate("settings", false) } }.setNegativeButton("关闭", null).show()
    }
    private fun choose(title: String, values: List<String>, select: (String) -> Unit) { AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()) { _, which -> select(values[which]) }.show() }
    private fun edit(title: String, value: String, save: (String) -> Unit) { val input = inputField(title).apply { setText(value) }; AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("保存") { _, _ -> save(input.text.toString().trim()) }.setNegativeButton("取消", null).show() }
    private fun confirm(title: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(title).setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show() }
}
