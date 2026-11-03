@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
        toolbar = row().apply { setPadding(dp(16), 0, dp(12), 0) }
        body = FrameLayout(this)
        navigation = row().apply { setBackgroundColor(Palette.surface) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
        savedInstanceState?.getString("request")?.let { runCatching { app.library.gson.fromJson(it, FeedRequest::class.java) }.getOrNull()?.let { value -> request = value } }
        libraryTab = savedInstanceState?.getString("libraryTab") ?: "记录"
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
        if (push && screen != target) backStack.addLast(screen to request)
        screenScope.cancel(); screenScope = CoroutineScope(SupervisorJob(lifecycleScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        screen = target; feedList = null; toolbar.removeAllViews(); body.removeAllViews(); navigation.removeAllViews()
        val title = when (target) { "home" -> "R34V"; "explore" -> "发现"; "search" -> "搜索"; "library" -> "片库"; "settings" -> "设置"; else -> request.kind.title }
        if (target == "feed" || target == "index") toolbar.addView(action("‹") { onBackPressedDispatcher.onBackPressed() })
        toolbar.addView(label(title, 24f, if (target == "home") Palette.red else Palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(action(if (target == "search") "设置" else "搜索") { navigate(if (target == "search") "settings" else "search") })
        for ((key, name) in listOf("home" to "首页", "explore" to "发现", "library" to "片库", "settings" to "设置")) {
            navigation.addView(label(name, 14f, if (target == key) Palette.red else Palette.muted, target == key).apply {
                gravity = Gravity.CENTER; setOnClickListener { backStack.clear(); navigate(key, false) }
                contentDescription = name
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        when (target) { "home" -> home(); "explore" -> explore(); "search" -> search(); "library" -> library(); "settings" -> settings(); "feed" -> feed(); "index" -> index(request.target) }
    }
    private fun show(view: View) { body.removeAllViews(); body.addView(view, FrameLayout.LayoutParams(-1, -1)) }
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
    private fun heading(text: String) = Tile { label(text, 20f, bold = true).apply { setPadding(dp(5), dp(20), dp(5), dp(10)) } }
    private fun video(video: Video) = Tile(false) { videoTile(video) { play(video) }.apply { setOnLongClickListener { favoriteVideo(video); true } } }
    private fun favoriteVideo(video: Video) {
        val added = store.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb, video.duration))
        toast(if (added) "已收藏" else "已取消收藏")
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
                val top = hot.await()?.videos.orEmpty().filter(store::allowed).take(6)
                val items = mutableListOf<Tile>()
                if (latest.cached) items.add(Tile { label("当前显示离线列表缓存", 13f, Palette.muted) })
                if (top.isNotEmpty()) items.add(Tile { carousel(top) })
                val resume = store.history().filter { it.resumable }.take(6)
                if (resume.isNotEmpty()) {
                    items.add(heading("继续观看")); resume.forEach { watch -> items.add(Tile(false) {
                        videoTile(watch.video().copy(duration = "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}")) { play(watch.video()) }
                    }) }
                }
                items.add(heading("最新更新")); latest.videos.filter(store::allowed).forEach { items.add(video(it)) }
                items.add(Tile { action("查看全部更新") { openFeed(FeedRequest()) } })
                show(grid(items).first)
            } catch (error: Exception) { failure(error, ::home) }
        }
    }
    private fun carousel(videos: List<Video>): View {
        val frame = FrameLayout(this)
        val image = RatioImage(this, 1.5f); frame.addView(image)
        val caption = column(16).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00141414, 0xee000000.toInt())) }
        val title = label("", 24f, bold = true); caption.addView(label("热门精选", 12f, Palette.red, true)); caption.addView(title)
        val controls = row(); var index = 0; var touchedAt = 0L
        fun bind() { val value = videos[index]; title.text = value.title; Images.load(this, image, value.thumb); frame.setOnClickListener { play(value) } }
        controls.addView(action("‹") { touchedAt = System.currentTimeMillis(); index = (index + videos.size - 1) % videos.size; bind() })
        controls.addView(action("立即播放", true) { play(videos[index]) }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(action("›") { touchedAt = System.currentTimeMillis(); index = (index + 1) % videos.size; bind() })
        caption.addView(controls); frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)); bind()
        screenScope.launch { while (isActive) { delay(6000); if (System.currentTimeMillis() - touchedAt >= 12000 && frame.isAttachedToWindow) { index = (index + 1) % videos.size; bind() } } }
        return frame
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
        show(scroll(column(20).apply {
            addView(label("发现你的下一部", 25f, bold = true)); addView(label("排行榜与站点索引", 14f, Palette.muted)); space(18)
            for (kind in listOf(FeedKind.LATEST, FeedKind.RATED, FeedKind.POPULAR, FeedKind.FOR_YOU)) addView(action(kind.title) { openFeed(FeedRequest(kind)) })
            space(20); addView(label("浏览索引", 20f, bold = true))
            for ((segment, title) in listOf("categories" to "分类", "works" to "角色排行 · 按作品", "artists" to "作者排行", "models" to "作者索引", "tags" to "标签索引")) addView(action(title) {
                backStack.addLast(screen to request); request = FeedRequest(target = segment); navigate("index", false)
            })
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
        val content = column(20)
        val input = EditText(this).apply { hint = "搜索视频、标签或作者"; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH; setTextColor(Palette.text); setHintTextColor(Palette.muted); inputType = InputType.TYPE_CLASS_TEXT }
        val target = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("视频", "标签", "作者")) }
        fun submit() {
            val query = input.text.toString().trim(); if (query.isBlank()) return
            store.addSearch(query)
            val kind = when (target.selectedItemPosition) { 1 -> FeedKind.TAG; 2 -> FeedKind.MODEL; else -> FeedKind.SEARCH }
            openFeed(FeedRequest(kind, if (kind == FeedKind.SEARCH) query else query.lowercase().replace(' ', '-'), if (kind == FeedKind.SEARCH) Sort.RELEVANCE else Sort.NEWEST))
        }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        content.addView(input); content.addView(target); content.addView(action("搜索", true) { submit() }); content.space(24)
        content.addView(row().apply { addView(label("最近搜索", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f)); addView(action("清空") { store.clearSearches(); navigate("search", false) }) })
        store.searches().forEach { query -> content.addView(action(query) { input.setText(query); submit() }) }
        show(scroll(content))
    }
    private fun library() {
        val container = column()
        val tabRow = row()
        listOf("记录", "视频", "角色", "作者", "标签", "缓存").forEach { title -> tabRow.addView(action(title, libraryTab == title) { libraryTab = title; navigate("library", false) }) }
        container.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })
        val listFrame = FrameLayout(this); container.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f)); show(container)
        fun display(items: MutableList<Tile>) {
            val position = (listFrame.getChildAt(0) as? RecyclerView)?.layoutManager?.onSaveInstanceState()
            listFrame.removeAllViews()
            if (items.isEmpty()) items.add(Tile { column(24).apply { gravity = Gravity.CENTER; addView(label("这里还没有内容", 18f, bold = true)); addView(label(if (libraryTab == "记录") "播放视频后，进度会自动保存在这里" else "在视频或条目上长按即可收藏", 14f, Palette.muted)) } })
            val list = grid(items).first; listFrame.addView(list); position?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
        when (libraryTab) {
            "记录" -> {
                val items = mutableListOf<Tile>()
                if (store.history().isNotEmpty()) items.add(Tile { action("清空观看记录") { confirm("清空观看记录？") { store.clearHistory(); navigate("library", false) } } })
                store.history().forEach { watch -> items.add(Tile(false) {
                    videoTile(watch.video().copy(duration = if (watch.finished) "已看完" else "${timeText(watch.position * 1000)} / ${timeText(watch.duration * 1000)}")) { play(watch.video()) }.apply {
                        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = if (watch.duration > 0) (watch.position * 1000 / watch.duration).toInt() else 0 })
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
                            addView(row().apply {
                                if (download.state == Download.STATE_COMPLETED) addView(action("离线播放", true) { play(meta.video, download.request.id) })
                                else addView(action(if (download.state == Download.STATE_STOPPED || download.state == Download.STATE_FAILED) "继续" else "暂停") {
                                    if (download.state == Download.STATE_FAILED) screenScope.launch { runCatching { app.repository.detail(meta.video.url) }.onSuccess { detail -> app.playback.add(meta.video, detail.streams.firstOrNull { it.label == meta.quality } ?: detail.streams.first()) }.onFailure(::showError) }
                                    else app.playback.pause(download.request.id, download.state != Download.STATE_STOPPED)
                                })
                                addView(action("删除") { confirm("删除这份缓存？") { app.playback.remove(download.request.id) } })
                            })
                        } })
                    }
                    for (local in store.localVideos()) items.add(Tile { column(8).apply {
                        addView(label(local.title, 16f, bold = true)); addView(label("${local.quality} · 已迁移的离线缓存", 12f, Palette.muted))
                        addView(row().apply { addView(action("离线播放", true) { play(Video(local.id, local.title, store.watch(local.id)?.path.orEmpty(), local.thumb), localPath = local.path) }); addView(action("删除") { confirm("删除这份缓存？") { store.removeLocal(local.id) } }) })
                    } })
                    display(items); delay(2000)
                }
            }
            else -> {
                val kind = when (libraryTab) { "视频" -> "video"; "角色" -> "character"; "作者" -> "model"; else -> "tag" }
                val items = store.favorites().filter { it.kind == kind }.map { favorite ->
                    if (kind == "video") Tile(false) { videoTile(Video(favorite.target, favorite.title, Site.absolute(favorite.path), favorite.thumb, favorite.subtitle)) { play(Video(favorite.target, favorite.title, Site.absolute(favorite.path), favorite.thumb)) }.apply { setOnLongClickListener { store.toggleFavorite(favorite); navigate("library", false); true } } }
                    else Tile { action(favorite.title) { openFeed(FeedRequest(if (kind == "model") FeedKind.MODEL else FeedKind.TAG, favorite.target)) }.apply { setOnLongClickListener { store.toggleFavorite(favorite); navigate("library", false); true } } }
                }.toMutableList()
                display(items)
            }
        }
    }
    private fun settings() {
        val content = column(20)
        fun title(text: String) { content.space(18); content.addView(label(text, 20f, bold = true)) }
        fun toggle(text: String, key: String, default: Boolean = false, network: Boolean = false) {
            content.addView(Switch(this).apply { this.text = text; textSize = 15f; setTextColor(Palette.text); setPadding(0, dp(10), 0, dp(10)); isChecked = store.bool(key, default)
                setOnCheckedChangeListener { _, checked -> store.set(key, checked); if (network) app.network.invalidate() }
            })
        }
        title("账户")
        content.addView(label(if (store.username.isBlank()) "尚未登录" else store.username, 16f))
        content.addView(action(if (store.username.isBlank()) "登录站点账户" else "退出登录") {
            if (store.username.isBlank()) login() else confirm("退出当前账户？") { app.network.session.clear(); navigate("settings", false) }
        })
        title("播放")
        content.addView(action("默认清晰度 · ${store.string("quality", "720p")}") { choose("默认清晰度", listOf("360p", "480p", "720p", "1080p", "2160p")) { store.set("quality", it); navigate("settings", false) } })
        content.addView(action("默认倍速 · ${store.string("native_speed", "1.0")}×") { choose("默认倍速", listOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0")) { store.set("native_speed", it); navigate("settings", false) } })
        toggle("进入播放后自动全屏", "auto_fullscreen")
        toggle("播放时自动加入完整缓存", "auto_download")
        content.addView(label("播放时自动缓存已读数据；完整缓存支持后台下载和离线播放。", 13f, Palette.muted))
        title("网络")
        toggle("使用 DoH 解析 DNS", "doh", true, true)
        toggle("允许自定义站点 IP", "direct_ip", true, true)
        content.addView(label("检测到 VPN 或配置代理时使用系统域名路由。", 13f, Palette.muted))
        content.addView(action("HTTP / SOCKS5 代理") { edit("代理地址（留空使用系统网络）", store.string("player_proxy")) { value ->
            val valid = value.isBlank() || runCatching { val uri = java.net.URI(value); uri.scheme in listOf("http", "socks5") && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null }.getOrDefault(false)
            if (valid) { store.set("player_proxy", value); app.network.invalidate(); toast("已保存") } else toast("格式：http://主机:端口 或 socks5://主机:端口")
        } })
        content.addView(action("自定义站点 IP") { edit("IPv4 地址，用逗号分隔", store.string("custom_site_ips")) { store.set("custom_site_ips", it); app.network.invalidate() } })
        content.addView(action("网络诊断") {
            content.addView(label("正在测试站点连接…", 13f, Palette.muted))
            screenScope.launch { val start = System.currentTimeMillis(); runCatching { app.repository.feed(FeedRequest()) }.onSuccess { toast("连接成功 · ${System.currentTimeMillis() - start} ms · ${it.videos.size} 条视频") }.onFailure(::showError) }
        })
        title("内容与存储")
        toggle("标签优先显示中文词典", "translate_tags", true)
        content.addView(action("屏蔽词（${store.blockedTags().size}）") { blockedTags() })
        content.addView(action("清理播放临时缓存") { confirm("清理播放缓存？已下载的视频会保留。") { screenScope.launch { withContext(Dispatchers.IO) { app.playback.clearStreaming() }; toast("已清理") } } })
        title("关于")
        content.addView(label("R34V ${BuildConfig.VERSION_NAME}", 16f, bold = true)); content.addView(label("Kotlin · Android 原生 · Media3\n应用不托管内容，与原站无关联。", 13f, Palette.muted))
        toggle("自动检查更新", "auto_update", true)
        content.addView(action("检查更新") { lifecycleScope.launch { UpdateController(this@MainActivity, app).check(false) } })
        show(scroll(content))
    }
    private fun login() {
        val form = column(20)
        val username = EditText(this).apply { hint = "用户名"; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT }
        val password = EditText(this).apply { hint = "密码"; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(username); form.addView(password)
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
    private fun edit(title: String, value: String, save: (String) -> Unit) { val input = EditText(this).apply { setText(value); isSingleLine = true }; AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("保存") { _, _ -> save(input.text.toString().trim()) }.setNegativeButton("取消", null).show() }
    private fun confirm(title: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(title).setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show() }
}
