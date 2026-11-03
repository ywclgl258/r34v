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
import com.r34v.r34v.data.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var body: FrameLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = column().apply { setBackgroundColor(Palette.canvas) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        toolbar = row().apply { minimumHeight = dp(56); setPadding(dp(20), dp(4), dp(12), dp(4)) }
        body = FrameLayout(this)
        navigation = row().apply { minimumHeight = dp(64); setBackgroundColor(Palette.canvas) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(line())
        root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        savedInstanceState?.getString("request")?.let { runCatching { app.library.gson.fromJson(it, FeedRequest::class.java) }.getOrNull()?.let { value -> request = value } }
        libraryTab = savedInstanceState?.getString("libraryTab") ?: "记录"
        favoriteTab = savedInstanceState?.getString("favoriteTab") ?: "视频"
        searchQuery = savedInstanceState?.getString("searchQuery").orEmpty()
        searchScope = savedInstanceState?.getInt("searchScope") ?: 0
        navigationTab = savedInstanceState?.getString("navigationTab") ?: "home"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backStack.isNotEmpty()) { val old = backStack.removeLast(); request = old.second; navigate(old.first, false) }
                else if (screen != "home") navigate("home", false) else finish()
            }
        })
        val link = intent.getStringExtra("link")
        val match = link?.let { Regex("/(tags|models|categories)/([^/?#]+)/?").find(it) }
        if (savedInstanceState == null && match != null) {
            request = FeedRequest(when (match.groupValues[1]) { "models" -> FeedKind.MODEL; "categories" -> FeedKind.CATEGORY; else -> FeedKind.TAG }, match.groupValues[2])
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
    }
    override fun onDestroy() { screenScope.cancel(); super.onDestroy() }
    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && screen == "library") navigate("library", false)
    }
    private fun columns() = if (resources.configuration.screenWidthDp >= 600) 3 else 2
    private fun navigate(target: String, push: Boolean = true) {
        (body.getChildAt(0) as? ScrollView)?.let { scrollPositions[screen] = it.scrollY }
        if (push && screen != target) backStack.addLast(screen to request)
        screenScope.cancel(); screenScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        if (target in listOf("home", "explore", "search", "library")) navigationTab = target
        screen = target; feedList = null; toolbar.removeAllViews(); body.removeAllViews(); navigation.removeAllViews()
        val title = when (target) { "home" -> "R34V"; "explore" -> "发现"; "search" -> "搜索"; "library" -> "片库"; "settings" -> "设置"; else -> request.kind.title }
        if (target in listOf("feed", "index", "settings")) toolbar.addView(iconAction(R.drawable.ic_back, "返回") { onBackPressedDispatcher.onBackPressed() })
        toolbar.addView(label(title, if (target == "home") 26f else 22f, if (target == "home") Palette.red else Palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(iconAction(R.drawable.ic_download, "下载") { libraryTab = "缓存"; navigate("library") })
        toolbar.addView(iconAction(R.drawable.ic_settings, "设置") { navigate("settings") })
        for ((key, name, icon) in listOf(Triple("home", "首页", R.drawable.ic_home), Triple("explore", "发现", R.drawable.ic_explore), Triple("search", "搜索", R.drawable.ic_search), Triple("library", "片库", R.drawable.ic_bookmark))) {
            val active = navigationTab == key
            navigation.addView(column().apply {
                gravity = Gravity.CENTER; minimumHeight = dp(64); isSelected = active; isFocusable = true
                setPadding(0, dp(9), 0, dp(9))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff), null, null)
                addView(ImageView(this@MainActivity).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(if (active) Palette.text else Palette.muted) }, LinearLayout.LayoutParams(dp(23), dp(23)))
                addView(label(name, 11f, if (active) Palette.text else Palette.muted, active).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
                setOnClickListener { backStack.clear(); navigate(key, false) }; contentDescription = name
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        when (target) { "home" -> home(); "explore" -> explore(); "search" -> search(); "library" -> library(); "settings" -> settings(); "feed" -> feed(); "index" -> index(request.target) }
    }
    private fun show(view: View) {
        body.removeAllViews(); body.addView(view, FrameLayout.LayoutParams(-1, -1)); view.enterQuietly()
        if (view is ScrollView) scrollPositions[screen]?.let { y -> view.post { view.scrollTo(0, y) } }
    }
    private fun loading() = show(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(ProgressBar(this@MainActivity)) })
    private fun failure(error: Throwable, retry: () -> Unit) {
        if (error is CancellationException) throw error
        show(column(24).apply { gravity = Gravity.CENTER; addView(label(error.message ?: "加载失败", 15f, Palette.muted)); addView(action("重试", true, retry)) })
    }
    private fun openFeed(value: FeedRequest) {
        backStack.addLast(screen to request); request = value; navigate("feed", false)
    }
    private fun play(video: Video, offlineId: String? = null, localPath: String? = null) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("video", store.gson.toJson(video)).putExtra("download", offlineId).putExtra("local", localPath))
    }
    private data class Tile(val full: Boolean = true, val create: () -> View)
    private inner class Tiles(val items: MutableList<Tile>) : RecyclerView.Adapter<Tiles.Holder>() {
        inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(this@MainActivity).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) })
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) { holder.frame.removeAllViews(); holder.frame.addView(items[position].create()) }
    }
    private fun grid(items: MutableList<Tile>): Pair<RecyclerView, Tiles> {
        val adapter = Tiles(items)
        val layout = GridLayoutManager(this, columns()).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (items[position].full) spanCount else 1 }
        }
        val list = RecyclerView(this).apply { layoutManager = layout; this.adapter = adapter; setPadding(dp(11), 0, dp(11), dp(16)); clipToPadding = false; itemAnimator = null }
        feedList = list
        return list to adapter
    }
    private fun heading(text: String) = Tile { label(text, 17f, bold = true).apply { setPadding(dp(5), dp(28), dp(5), dp(12)) } }
    private fun video(video: Video) = Tile(false) { videoTile(video, listOf((if (store.isFavorite("video", video.id)) "取消收藏" else "收藏") to { favoriteVideo(video) })) { play(video) }.apply { setOnLongClickListener { favoriteVideo(video); true } } }
    private fun favoriteVideo(video: Video) {
        val added = store.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb, video.duration))
        toast(if (added) "已收藏" else "已取消收藏"); feedList?.adapter?.notifyDataSetChanged()
    }
    private fun home() {
        loading()
        screenScope.launch {
            try {
                val hot = async {
                    try { withTimeoutOrNull(10000) { app.repository.feed(FeedRequest(FeedKind.POPULAR)) } }
                    catch (error: Exception) { if (error is CancellationException) throw error; null }
                }
                val latest = app.repository.feed(FeedRequest())
                val top = hot.await()?.videos.orEmpty().filter(store::allowed).ifEmpty { latest.videos.filter(store::allowed) }
                val content = column().apply { setPadding(0, dp(4), 0, dp(24)) }
                content.addView(FlowLayout(this@MainActivity).apply {
                    setPadding(dp(16), 0, dp(16), dp(14))
                    listOf("热门" to FeedKind.POPULAR, "最新" to FeedKind.LATEST, "高分" to FeedKind.RATED).forEach { (title, kind) -> addView(chip(title) { openFeed(FeedRequest(kind)) }) }
                })
                if (latest.cached) content.addView(label("当前显示离线列表缓存", 12f, Palette.muted).apply { setPadding(dp(16), 0, dp(16), dp(12)) })
                top.firstOrNull()?.let { content.addView(featured(it)) }
                val resume = store.history().filter { it.resumable }.take(8)
                if (resume.isNotEmpty()) {
                    content.addView(shelfHeading("继续观看", "全部") { libraryTab = "记录"; navigate("library") })
                    content.addView(shelf(resume.map { watch ->
                        videoTile(watch.video().copy(duration = "剩余 ${timeText((watch.duration - watch.position).coerceAtLeast(0) * 1000)}"), listOf("移除观看记录" to { confirm("移除这条观看记录？") { store.removeWatch(watch.id); home() } })) { play(watch.video()) }.apply {
                            addView(watchProgress(watch))
                            setOnLongClickListener { confirm("移除这条观看记录？") { store.removeWatch(watch.id); home() }; true }
                        }
                    }, wide = true))
                }
                content.addView(shelfHeading("最新更新", "查看全部") { openFeed(FeedRequest()) })
                val videos = latest.videos.filter(store::allowed)
                if (videos.isEmpty()) content.addView(label("暂时没有可显示的视频", 14f, Palette.muted).apply { setPadding(dp(16), dp(12), dp(16), dp(24)) })
                else content.addView(shelf(videos.take(10).map { value -> videoTile(value) { play(value) }.apply { setOnLongClickListener { favoriteVideo(value); true } } }))
                show(scroll(content))
            } catch (error: Exception) { failure(error, ::home) }
        }
    }
    private fun featured(value: Video): View = column().apply {
        setPadding(dp(16), 0, dp(16), dp(8))
        addView(RatioImage(this@MainActivity).apply {
            background = rounded(Palette.surface, dp(8).toFloat()); clipToOutline = true
            Images.load(this@MainActivity, this, value.thumb)
            setOnClickListener { play(value) }; contentDescription = "播放 ${value.title}"
        }, LinearLayout.LayoutParams(-1, -2))
        space(10)
        addView(label("今日精选", 12f, Palette.red, true))
        addView(label(value.title, 22f, bold = true))
        addView(label(listOf(value.duration, value.views, value.rating).filter { it.isNotBlank() }.joinToString(" · "), 12f, Palette.muted))
        space(8)
        addView(row().apply {
            addView(action("▶  播放", true) { play(value) }, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = dp(8) })
            val favorite = Favorite("video", value.id, value.title, value.url, value.thumb, value.duration)
            addView(outlineAction(if (store.isFavorite(favorite.kind, favorite.target)) "✓ 已收藏" else "＋ 收藏") { }.apply {
                setOnClickListener { val added = store.toggleFavorite(favorite); text = if (added) "✓ 已收藏" else "＋ 收藏"; contentDescription = text; toast(if (added) "已收藏" else "已取消收藏") }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    private fun shelfHeading(title: String, more: String, click: () -> Unit) = row().apply {
        setPadding(dp(16), dp(18), dp(8), dp(4))
        addView(label(title, 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(quietAction("$more ›", click))
    }
    private fun shelf(cards: List<View>, wide: Boolean = false): View {
        val cardWidth = dp((resources.configuration.screenWidthDp * if (wide) .68f else .52f).toInt().coerceAtMost(if (wide) 330 else 270))
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(11), 0, dp(11), 0)
            addView(row().apply { gravity = Gravity.TOP; cards.forEach { addView(it, LinearLayout.LayoutParams(cardWidth, -2)) } })
        }
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
        var adapter: Tiles? = null
        fun load(reset: Boolean = false) {
            if (loadingMore) return
            loadingMore = true
            screenScope.launch {
                try {
                    val result = app.repository.feed(request.copy(page = if (reset) 1 else page))
                    if (reset) items.clear()
                    if (items.isEmpty()) {
                        if (result.cached) items.add(Tile { label("当前显示离线列表缓存", 13f, Palette.muted) })
                        items.add(Tile { row().apply {
                            addView(label(request.target.ifBlank { request.kind.title }, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(action(request.sort.title) { chooseSort() }); addView(action("刷新") { page = 1; load(true) })
                        } })
                    }
                    val known = items.size
                    result.videos.filter(store::allowed).forEach { items.add(video(it)) }
                    total = if (request.kind == FeedKind.FOR_YOU) result.current else result.total
                    page = result.current + 1
                    if (known == items.size && result.videos.isEmpty()) items.add(Tile { label("没有找到视频", 14f, Palette.muted) })
                    if (adapter == null) {
                        val (list, a) = grid(items); adapter = a; show(list)
                        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                val layout = recyclerView.layoutManager as GridLayoutManager
                                if (dy > 0 && layout.findLastVisibleItemPosition() >= items.size - 7 && page <= total) load()
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
        val options = Sort.entries.filter { request.kind == FeedKind.SEARCH || it != Sort.RELEVANCE }
        AlertDialog.Builder(this).setTitle("排序方式").setItems(options.map { it.title }.toTypedArray()) { _, which ->
            request = request.copy(sort = options[which], page = 1); navigate("feed", false)
        }.show()
    }
    private fun explore() {
        fun browse(segment: String) { backStack.addLast(screen to request); request = FeedRequest(target = segment); navigate("index", false) }
        fun entry(title: String, subtitle: String, click: () -> Unit) = row().apply {
            minimumHeight = dp(if (subtitle.isBlank()) 56 else 72); setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(column().apply { addView(label(title, 16f, bold = true)); if (subtitle.isNotBlank()) addView(label(subtitle, 12f, Palette.muted)) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_chevron_down); rotation = -90f }, LinearLayout.LayoutParams(dp(20), dp(20))); setOnClickListener { click() }; isFocusable = true; contentDescription = title
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22ffffff), rounded(Palette.surface, dp(8).toFloat()), null)
        }
        show(scroll(column(16).apply {
            addView(label("找到下一部想看的", 22f, bold = true)); addView(label("从榜单、分类和创作者开始", 14f, Palette.muted)); space(18)
            addView(label("精选榜单", 18f, bold = true)); space(8)
            addView(entry("热门榜", "看看大家最近在看什么") { openFeed(FeedRequest(FeedKind.POPULAR)) }); space(8)
            addView(FlowLayout(this@MainActivity).apply { listOf("最新更新" to FeedKind.LATEST, "高分榜" to FeedKind.RATED, "为你推荐" to FeedKind.FOR_YOU).forEach { (name, kind) -> addView(chip(name) { openFeed(FeedRequest(kind)) }) } })
            space(24); addView(label("浏览内容", 18f, bold = true)); space(8)
            addView(surface(column().apply {
                addView(entry("分类", "") { browse("categories") }); addView(line())
                addView(entry("角色与作品", "") { browse("works") }); addView(line())
                addView(entry("作者排行", "") { browse("artists") })
            }))
            space(16)
            addView(FlowLayout(this@MainActivity).apply { addView(outlineAction("作者索引") { browse("models") }); addView(outlineAction("标签索引") { browse("tags") }) })
        }))
    }
    private fun index(segment: String) {
        loading()
        screenScope.launch {
            try {
                val links = when {
                    segment == "works" -> app.repository.works()
                    segment == "artists" -> app.repository.ranking("/top-model/?sort=az")
                    segment.startsWith("work:") -> app.repository.ranking("/top-characters/${segment.removePrefix("work:")}/")
                    else -> app.repository.index(segment)
                }
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
        val content = column(16)
        val input = inputField("搜索视频、标签或作者").apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            setText(searchQuery); setSelection(text.length); contentDescription = "搜索关键词"
            doAfterTextChanged { searchQuery = it.toString() }
        }
        fun submit() {
            val query = input.text.toString().trim(); if (query.isBlank()) { input.requestFocus(); return }
            searchQuery = query; store.addSearch(query)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            val kind = when (searchScope) { 1 -> FeedKind.TAG; 2 -> FeedKind.MODEL; else -> FeedKind.SEARCH }
            openFeed(FeedRequest(kind, if (kind == FeedKind.SEARCH) query else query.lowercase().replace(' ', '-'), if (kind == FeedKind.SEARCH) Sort.RELEVANCE else Sort.NEWEST))
        }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        content.addView(row().apply {
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconAction(R.drawable.ic_search, "提交搜索") { submit() })
        }); content.space(12)
        val scopes = FlowLayout(this)
        fun renderScopes() {
            scopes.removeAllViews()
            listOf("视频", "标签", "作者").forEachIndexed { index, name -> scopes.addView(chip(name, searchScope == index) { searchScope = index; renderScopes() }) }
        }
        renderScopes(); content.addView(scopes); content.space(24)
        content.addView(row().apply { addView(label("最近搜索", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f)); if (store.searches().isNotEmpty()) addView(quietAction("清空") { store.clearSearches(); navigate("search", false) }) })
        val recent = store.searches()
        if (recent.isEmpty()) content.addView(label("搜索过的关键词会保存在这里", 14f, Palette.muted))
        else content.addView(FlowLayout(this).apply { recent.forEach { query -> addView(chip(query) { input.setText(query); submit() }) } })
        show(scroll(content))
    }
    private fun library() {
        val container = column()
        val task = when (libraryTab) { "记录" -> "观看"; "缓存" -> "下载"; else -> "收藏" }
        container.addView(FlowLayout(this).apply {
            setPadding(dp(16), dp(6), dp(16), dp(12))
            listOf("观看", "收藏", "下载").forEach { name -> addView(chip(name, task == name) {
                libraryTab = when (name) { "观看" -> "记录"; "下载" -> "缓存"; else -> favoriteTab }; navigate("library", false)
            }) }
        })
        if (task == "收藏") container.addView(FlowLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
            listOf("视频", "角色", "作者", "标签").forEach { name -> addView(chip(name, libraryTab == name) { favoriteTab = name; libraryTab = name; navigate("library", false) }) }
        })
        val listFrame = FrameLayout(this); container.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f)); show(container)
        fun display(items: MutableList<Tile>) {
            val position = (listFrame.getChildAt(0) as? RecyclerView)?.layoutManager?.onSaveInstanceState()
            listFrame.removeAllViews()
            if (items.isEmpty()) items.add(Tile { column(24).apply { gravity = Gravity.CENTER; addView(label("这里还没有内容", 18f, bold = true)); addView(label(when (libraryTab) { "记录" -> "播放视频后，进度会自动保存在这里"; "缓存" -> "在播放详情中下载，即可离线观看"; else -> "收藏的视频与条目会保存在这里" }, 14f, Palette.muted)) } })
            val list = grid(items).first; listFrame.addView(list); position?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
        when (libraryTab) {
            "记录" -> {
                val items = mutableListOf<Tile>()
                if (store.history().isNotEmpty()) items.add(Tile { quietAction("清空观看记录") { confirm("清空观看记录？") { store.clearHistory(); navigate("library", false) } } })
                store.history().forEach { watch -> items.add(Tile(false) {
                    videoTile(watch.video().copy(duration = if (watch.finished) "已看完" else "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}"), listOf("移除观看记录" to { confirm("删除这条记录？") { store.removeWatch(watch.id); navigate("library", false) } })) { play(watch.video()) }.apply {
                        addView(watchProgress(watch))
                        setOnLongClickListener { confirm("删除这条记录？") { store.removeWatch(watch.id); navigate("library", false) }; true }
                    }
                }) }
                display(items)
            }
            "缓存" -> screenScope.launch {
                while (isActive) {
                    val downloads = withContext(Dispatchers.IO) { app.playback.downloads() }
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
                val kind = when (libraryTab) { "视频" -> "video"; "角色" -> "character"; "作者" -> "model"; else -> "tag" }
                val items = store.favorites().filter { it.kind == kind }.map { favorite ->
                    if (kind == "video") Tile(false) { videoTile(Video(favorite.target, favorite.title, Site.absolute(favorite.path), favorite.thumb, favorite.subtitle), listOf("取消收藏" to { store.toggleFavorite(favorite); navigate("library", false) })) { play(Video(favorite.target, favorite.title, Site.absolute(favorite.path), favorite.thumb)) }.apply { setOnLongClickListener { store.toggleFavorite(favorite); navigate("library", false); true } } }
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
                if (value.isNotBlank()) addView(label(value, 12f, Palette.muted).apply { setPadding(dp(8), 0, dp(8), 0) })
                addView(label("›", 22f, Palette.muted)); setOnClickListener { click() }; contentDescription = "$text $value"
            })
        }
        fun toggle(text: String, key: String, default: Boolean = false, network: Boolean = false) {
            if (group.childCount > 0) group.addView(line())
            group.addView(Switch(this).apply { this.text = text; textSize = 14.5f; setTextColor(Palette.text); setPadding(dp(2), dp(10), dp(2), dp(10)); minHeight = dp(58); isChecked = store.bool(key, default)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Palette.text, Palette.muted))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(Palette.red, Palette.raised))
                trackDrawable = android.graphics.drawable.GradientDrawable().apply { setSize(dp(44), dp(28)); cornerRadius = dp(14).toFloat(); setColor(android.content.res.ColorStateList(states, intArrayOf(Palette.red, Palette.raised))) }
                thumbDrawable = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setSize(dp(24), dp(24)); setColor(android.content.res.ColorStateList(states, intArrayOf(Palette.text, Palette.muted))) }
                setOnCheckedChangeListener { _, checked -> store.set(key, checked); if (network) app.network.invalidate() }
            })
        }
        title("显示")
        toggle("标签优先显示中文词典", "translate_tags", true)
        title("内容过滤")
        item("屏蔽词", "${store.blockedTags().size} 个") { blockedTags() }
        title("账户")
        item(if (store.username.isBlank()) "登录站点账户" else "退出登录", store.username) {
            if (store.username.isBlank()) login() else confirm("退出当前账户？") { app.network.session.clear(); navigate("settings", false) }
        }
        title("播放")
        item("默认清晰度", store.string("quality", "720p")) { choose("默认清晰度", listOf("360p", "480p", "720p", "1080p", "2160p")) { store.set("quality", it); navigate("settings", false) } }
        item("默认倍速", "${store.string("native_speed", "1.0")}×") { choose("默认倍速", listOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0")) { store.set("native_speed", it); navigate("settings", false) } }
        toggle("进入播放后自动全屏", "auto_fullscreen")
        toggle("播放时自动加入完整缓存", "auto_download")
        group.addView(label("播放时自动缓存已读数据；完整缓存支持后台下载和离线播放。", 12f, Palette.muted))
        title("网络")
        toggle("使用 DoH 解析 DNS", "doh", true, true)
        toggle("站点 IP 直连", "direct_ip", true, true)
        toggle("视频 CDN 使用直连解析", "cdn_direct", true, true)
        group.addView(label("直连复用已验证 IP。使用 VPN 或代理时保留其域名路由。", 12f, Palette.muted))
        item("HTTP / SOCKS5 代理") { edit("代理地址（留空使用系统网络）", store.string("player_proxy")) { value ->
            val valid = value.isBlank() || runCatching { val uri = java.net.URI(value); uri.scheme in listOf("http", "socks5") && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null }.getOrDefault(false)
            if (valid) { store.set("player_proxy", value); app.network.invalidate(); toast("已保存") } else toast("格式：http://主机:端口 或 socks5://主机:端口")
        } }
        item("自定义站点 IP") { edit("IPv4 地址，用逗号分隔", store.string("custom_site_ips")) { store.set("custom_site_ips", it); app.network.invalidate() } }
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
    private fun blockedTags() {
        val tags = store.blockedTags()
        AlertDialog.Builder(this).setTitle("屏蔽词：点击移除").setItems(tags.toTypedArray()) { _, which -> store.toggleBlocked(tags[which]); blockedTags() }
            .setPositiveButton("添加") { _, _ -> edit("输入标签 slug 或标题关键词", "") { if (it.isNotBlank() && it !in store.blockedTags()) store.toggleBlocked(it.lowercase()); navigate("settings", false) } }.setNegativeButton("关闭", null).show()
    }
    private fun choose(title: String, values: List<String>, select: (String) -> Unit) { AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()) { _, which -> select(values[which]) }.show() }
    private fun edit(title: String, value: String, save: (String) -> Unit) { val input = inputField(title).apply { setText(value) }; AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("保存") { _, _ -> save(input.text.toString().trim()) }.setNegativeButton("取消", null).show() }
    private fun confirm(title: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(title).setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show() }
}
