@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.r34v.r34v.data.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

class PlayerActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private lateinit var video: Video
    private var detail: Detail? = null
    private var stream: Stream? = null
    private var player: ExoPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var details: ScrollView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private var fullscreen = false
    private var position = 0L
    private var refreshed = false
    private var autoFullscreenApplied = false
    private var offline = false
    private var local: String? = null
    private var offlineId: String? = null
    private var userRate = 1f
    private var pendingError: Throwable? = null
    private var isOpening = false
    private var failureVisible = false
    private var retryButton: Button? = null
    private val expandedSections = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = runCatching { store.gson.fromJson(intent.getStringExtra("video"), Video::class.java) }.getOrNull()
        if (restored == null || restored.id.isBlank()) { finish(); return }
        video = restored
        local = intent.getStringExtra("local"); offlineId = intent.getStringExtra("download"); offline = local != null || offlineId != null
        position = savedInstanceState?.getLong("position") ?: store.watch(video.id)?.takeUnless { it.finished }?.position?.times(1000) ?: 0L
        userRate = store.string("native_speed", "1.0").toFloatOrNull()?.coerceIn(.25f, 3f) ?: 1f
        root = column().apply { setBackgroundColor(Palette.canvas) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, if (fullscreen) 0 else bars.top, bars.right, if (fullscreen) 0 else bars.bottom); insets
        }
        playerFrame = FrameLayout(this).apply { setBackgroundColor(Palette.canvas) }
        playerView = PlayerView(this).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setShowNextButton(false); setShowPreviousButton(false); setShowSubtitleButton(false); keepScreenOn = true }
        playerFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        hint = label("", 17f, bold = true).apply { gravity = Gravity.CENTER; background = rounded(0xb0000000.toInt()); visibility = View.GONE; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        playerFrame.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val fullButton = quietAction("全屏") { toggleFullscreen() }.apply {
            setTextColor(Palette.text)
            background = RippleDrawable(ColorStateList.valueOf(0x30ffffff), rounded(0x70000000, dp(6).toFloat()), null)
        }
        playerFrame.addView(fullButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(8), dp(8), dp(8), 0) })
        playerFrame.addView(iconAction(R.drawable.ic_back, "返回") { onBackPressedDispatcher.onBackPressed() }.apply {
            background = RippleDrawable(ColorStateList.valueOf(0x30ffffff), rounded(0x70000000, dp(6).toFloat()), null)
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), dp(8), 0) })
        root.addView(playerFrame, LinearLayout.LayoutParams(-1, inlineHeight()))
        status = label("正在获取播放地址…", 12f, Palette.muted)
        details = scroll(column())
        root.addView(details, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        renderDetails()
        details.enterQuietly()
        installGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) toggleFullscreen() else finish() }
        })
        load()
        lifecycleScope.launch { while (isActive) { delay(5000); savePosition() } }
    }
    private fun inlineHeight() = (resources.displayMetrics.widthPixels * 9f / 16f).toInt().coerceAtMost((resources.displayMetrics.heightPixels * .55f).toInt())
    private fun load() {
        if (isOpening) return
        isOpening = true; setFailureVisible(false); status.text = "正在加载…"
        lifecycleScope.launch {
            try {
                if (local != null) {
                    val allowed = store.localVideos().any { it.id == video.id && it.path == local }
                    check(allowed) { "离线文件不存在" }
                    stream = Stream("本地", Uri.fromFile(java.io.File(local!!)).toString())
                    renderDetails(); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) open(stream!!, position)
                } else if (offlineId != null) {
                    val download = withContext(Dispatchers.IO) { app.playback.manager.downloadIndex.getDownload(offlineId!!) } ?: error("缓存记录不存在")
                    check(download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) { "缓存尚未完成" }
                    stream = Stream(app.playback.metadata(download)?.quality ?: "缓存", download.request.uri.toString())
                    renderDetails(); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) open(stream!!, position)
                } else {
                    val result = app.repository.detail(video.url)
                    detail = result; video = result.video.copy(thumb = result.video.thumb.ifBlank { video.thumb })
                    val preferred = store.watch(video.id)?.quality?.takeIf { it.isNotBlank() } ?: store.string("quality", "720p")
                    stream = result.streams.firstOrNull { it.label == preferred } ?: result.streams.firstOrNull { it.height <= (Regex("\\d+").find(preferred)?.value?.toIntOrNull() ?: 720) } ?: result.streams.last()
                    renderDetails(); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) open(stream!!, position)
                    if (store.bool("auto_download")) app.playback.add(video, stream!!)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                status.text = error.message ?: "加载失败"; setFailureVisible(true); showError(error)
            } finally { isOpening = false }
        }
    }
    private fun open(value: Stream, start: Long, autoplay: Boolean = true) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { stream = value; position = start; return }
        pendingError = null; setFailureVisible(false)
        val engine = player ?: ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 50000, 400, 800).setPrioritizeTimeOverSizeThresholds(true).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.playback.sourceFactory(offline && local == null)))
            .build().also { exo ->
                player = exo; playerView.player = exo
                exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                exo.setHandleAudioBecomingNoisy(true)
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        status.text = when (playbackState) { Player.STATE_BUFFERING -> "正在缓冲…"; Player.STATE_READY -> "${stream?.label.orEmpty()} · ${if (offline) "离线播放" else "正在播放"}"; Player.STATE_ENDED -> "播放完毕"; else -> "准备播放" }
                        playerView.keepScreenOn = exo.isPlaying || playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
                            if (!autoFullscreenApplied && store.bool("auto_fullscreen")) { autoFullscreenApplied = true; if (!fullscreen) toggleFullscreen() }
                        }
                        if (playbackState == Player.STATE_ENDED) savePosition()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playerView.keepScreenOn = isPlaying
                        if (exo.playbackState == Player.STATE_READY) status.text = "${stream?.label.orEmpty()} · ${if (isPlaying) { if (offline) "离线播放" else "正在播放" } else "已暂停"}"
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        savePosition()
                        if (!offline && !refreshed) {
                            refreshed = true; status.text = "正在刷新播放地址…"
                            lifecycleScope.launch {
                                try {
                                    val fresh = app.repository.detail(video.url); detail = fresh
                                    val selected = fresh.streams.firstOrNull { it.label == stream?.label } ?: fresh.streams.last()
                                    stream = selected; renderDetails(); open(selected, position)
                                } catch (refreshError: Exception) { if (refreshError is CancellationException) throw refreshError; playbackError(refreshError) }
                            }
                        } else playbackError(error)
                    }
                })
            }
        stream = value
        val item = if (local != null) MediaItem.fromUri(value.url) else app.playback.item(video, value)
        engine.setMediaItem(item, start.coerceAtLeast(0)); engine.setPlaybackSpeed(userRate); engine.prepare(); engine.playWhenReady = autoplay
    }
    private fun playbackError(error: Throwable) { pendingError = error; status.text = "播放失败：${error.message}"; setFailureVisible(true); showError(error) }
    private fun setFailureVisible(visible: Boolean) {
        failureVisible = visible
        retryButton?.visibility = if (visible) View.VISIBLE else View.GONE
    }
    private fun restartPlayback() {
        refreshed = false
        if (stream != null) open(stream!!, if (pendingError != null) position else 0) else load()
    }
    private fun playbackMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("重新播放").setOnMenuItemClickListener { restartPlayback(); true }
            show()
        }
    }
    private fun renderDetails() {
        val scrollPosition = details.scrollY
        val content = column(18)
        content.addView(label(video.title, 22f, bold = true))
        val metadata = listOf(video.duration, video.views, video.rating).filter { it.isNotBlank() }.joinToString(" · ")
        if (metadata.isNotBlank()) content.addView(label(metadata, 13f, Palette.secondary))
        (status.parent as? ViewGroup)?.removeView(status); content.addView(status)
        retryButton = action("重试", accent = true) {
            if (pendingError != null) restartPlayback() else load()
        }.apply { visibility = if (failureVisible) View.VISIBLE else View.GONE }
        content.addView(retryButton)
        if (stream != null || detail != null) {
            content.space(8)
            content.addView(FlowLayout(this).apply {
                addView(choiceAction("${stream?.label ?: "清晰度"}") { quality() }.apply { isEnabled = detail?.streams?.isNotEmpty() == true })
                addView(choiceAction("${userRate}×") { speed() })
                val more = quietAction("更多 ⋯") {}
                more.contentDescription = "更多播放操作"
                more.setOnClickListener { playbackMenu(more) }
                addView(more)
            })
            content.space(10)
            content.addView(FlowLayout(this).apply {
                addView(outlineAction(if (store.isFavorite("video", video.id)) "✓ 已收藏" else "＋ 收藏") {
                    store.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb, video.duration)); renderDetails()
                })
                if (!offline) addView(outlineAction("↓ 下载") { download() })
            })
        }
        detail?.let { data ->
            if (data.description.isNotBlank()) addExpandable(content, "description", "简介", initiallyExpanded = true) {
                addView(label(data.description, 14f, Palette.secondary).apply { setLineSpacing(dp(3).toFloat(), 1f) })
            }
            for ((name, links) in data.groups) {
                if (links.isEmpty()) continue
                addExpandable(content, "group:$name", "$name · ${links.size}") {
                    val chips = FlowLayout(this@PlayerActivity)
                    for (link in links) {
                        val translated = if (store.bool("translate_tags", true)) TagNames.lookup(this@PlayerActivity, link.slug) else null
                        chips.addView(chip(translated ?: link.label) {
                            startActivity(Intent(this@PlayerActivity, MainActivity::class.java).putExtra("link", link.url))
                        }.apply {
                            setOnLongClickListener {
                                val kind = when (name) { "角色" -> "character"; "作者" -> "model"; else -> "tag" }
                                val choices = if (name == "标签") arrayOf("收藏 / 取消收藏", "屏蔽 / 取消屏蔽") else arrayOf("收藏 / 取消收藏")
                                AlertDialog.Builder(this@PlayerActivity).setTitle(link.label).setItems(choices) { _, which ->
                                    if (which == 1) { store.toggleBlocked(link.slug); toast("已更新屏蔽词") }
                                    else toast(if (store.toggleFavorite(Favorite(kind, link.slug, link.label, link.url, link.thumb))) "已收藏" else "已取消收藏")
                                }.show(); true
                            }
                        })
                    }
                    addView(chips)
                }
            }
            if (data.comments.isNotEmpty()) addExpandable(content, "comments", "评论 · ${data.comments.size}") {
                data.comments.forEachIndexed { index, comment ->
                    if (index > 0) space(12)
                    addView(label("${comment.author}  ${comment.date}", 13f, bold = true))
                    addView(label(comment.text, 14f, Palette.secondary).apply { setLineSpacing(dp(3).toFloat(), 1f) })
                }
            }
            val relatedVideos = data.related.filter(store::allowed).take(24)
            if (relatedVideos.isNotEmpty()) {
                content.space(22); content.addView(label("相关推荐", 18f, bold = true)); content.space(6)
                relatedVideos.chunked(2).forEach { pair ->
                    content.addView(row().apply {
                        gravity = Gravity.TOP
                        pair.forEachIndexed { index, related ->
                            addView(videoTile(related) {
                                startActivity(Intent(this@PlayerActivity, PlayerActivity::class.java).putExtra("video", store.gson.toJson(related)))
                            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(6) else marginStart = dp(6) })
                        }
                        if (pair.size == 1) addView(Space(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
                    })
                }
            }
        }
        details.removeAllViews(); details.addView(content)
        details.post { details.scrollTo(0, scrollPosition) }
    }
    private fun addExpandable(content: LinearLayout, key: String, title: String, initiallyExpanded: Boolean = false, body: LinearLayout.() -> Unit) {
        content.space(16)
        val expanded = expandedSections[key] ?: initiallyExpanded
        val bodyContent = column().apply { body(); visibility = if (expanded) View.VISIBLE else View.GONE }
        val indicator = iconAction(R.drawable.ic_chevron_down, title) {}.apply { rotation = if (expanded) 180f else 0f; contentDescription = "${if (expanded) "收起" else "展开"}$title" }
        fun toggle() {
            val show = bodyContent.visibility != View.VISIBLE
            expandedSections[key] = show; bodyContent.visibility = if (show) View.VISIBLE else View.GONE
            indicator.contentDescription = "${if (show) "收起" else "展开"}$title"
            indicator.animate().cancel()
            val angle = if (show) 180f else 0f
            if (runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(false)) {
                indicator.animate().rotation(angle).setDuration(180).start()
            } else indicator.rotation = angle
        }
        content.addView(row().apply {
            minimumHeight = dp(48)
            addView(label(title, 18f, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(indicator)
            setOnClickListener { toggle() }
        })
        indicator.setOnClickListener { toggle() }
        content.addView(bodyContent)
    }
    private fun quality() {
        val options = detail?.streams ?: return
        AlertDialog.Builder(this).setTitle("清晰度").setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(stream)) { dialog, which ->
            val start = player?.currentPosition ?: position; val playing = player?.playWhenReady ?: true
            stream = options[which]; store.set("quality", stream!!.label); refreshed = false
            open(stream!!, start, playing); renderDetails(); dialog.dismiss()
        }.show()
    }
    private fun speed() {
        val rates = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f)
        AlertDialog.Builder(this).setTitle("播放速度").setItems(rates.map { "${it}×" }.toTypedArray()) { _, which ->
            userRate = rates[which]; store.set("native_speed", userRate.toString()); player?.setPlaybackSpeed(userRate); renderDetails()
        }.show()
    }
    private fun download() {
        val options = detail?.streams ?: return
        AlertDialog.Builder(this).setTitle("选择缓存清晰度").setItems(options.map { it.label }.toTypedArray()) { _, which ->
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 40)
            runCatching { app.playback.add(video, options[which]) }.onSuccess { toast("已加入后台下载，可在片库查看") }.onFailure(::showError)
        }.show()
    }
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        details.visibility = if (fullscreen) View.GONE else View.VISIBLE
        playerFrame.layoutParams = LinearLayout.LayoutParams(-1, if (fullscreen) -1 else inlineHeight())
        val bars = WindowCompat.getInsetsController(window, root)
        if (fullscreen) {
            val size = player?.videoSize
            requestedOrientation = if (size != null && size.height > size.width) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; bars.show(WindowInsetsCompat.Type.systemBars()) }
        ViewCompat.requestApplyInsets(root)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (!fullscreen) playerFrame.layoutParams = LinearLayout.LayoutParams(-1, inlineHeight()) }
    private fun savePosition() {
        val exo = player ?: return
        position = exo.currentPosition.coerceAtLeast(0)
        val duration = exo.duration.takeIf { it > 0 } ?: (durationSeconds(video.duration) * 1000)
        store.saveWatch(Watch(video.id, video.title, video.thumb, video.url, position / 1000, duration / 1000, quality = stream?.label.orEmpty()))
    }
    override fun onSaveInstanceState(outState: Bundle) { savePosition(); outState.putLong("position", position); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        if (::playerView.isInitialized) playerView.post {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && player == null && stream != null) open(stream!!, position)
        }
    }
    override fun onPause() { savePosition(); player?.pause(); super.onPause() }
    override fun onStop() { savePosition(); if (::playerView.isInitialized) playerView.player = null; player?.release(); player = null; super.onStop() }

    @Suppress("ClickableViewAccessibility")
    private fun installGestures() {
        val audio = getSystemService(AudioManager::class.java)
        var startPosition = 0L; var downX = 0f; var downY = 0f; var mode = 0; var seekTo = 0L; var hold = false
        var brightness = .5f; var volume = 0
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { downX = e.x; downY = e.y; startPosition = player?.currentPosition ?: 0; mode = 0; hold = false; brightness = window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f; volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = playerView.width
                when { e.x < width * .4f -> { player?.seekTo((startPosition - 10000).coerceAtLeast(0)); gestureHint("后退 10 秒") }
                    e.x > width * .6f -> { player?.seekTo((startPosition + 10000).coerceAtMost(player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)); gestureHint("前进 10 秒") }
                    else -> toggleFullscreen() }
                return true
            }
            override fun onLongPress(e: MotionEvent) { hold = true; player?.setPlaybackSpeed(2f); gestureHint("2× 临时倍速") }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val dx = e2.x - downX; val dy = e2.y - downY
                if (mode == 0 && (kotlin.math.abs(dx) > dp(12) || kotlin.math.abs(dy) > dp(12))) mode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1 else if (downX < playerView.width / 2) 2 else 3
                when (mode) {
                    1 -> { val duration = player?.duration?.takeIf { it > 0 } ?: return true; seekTo = (startPosition + dx / playerView.width * 120000).toLong().coerceIn(0, duration); gestureHint(timeText(seekTo)) }
                    2 -> { val value = (brightness - dy / playerView.height).coerceIn(.02f, 1f); window.attributes = window.attributes.apply { screenBrightness = value }; gestureHint("亮度 ${(value * 100).toInt()}%") }
                    3 -> { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val value = (volume - dy / playerView.height * max).toInt().coerceIn(0, max); audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); gestureHint("音量 ${value * 100 / max.coerceAtLeast(1)}%") }
                }; return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == 1 && event.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seekTo)
                if (hold) { player?.setPlaybackSpeed(userRate); hold = false }
                hint.postDelayed({ hint.visibility = View.GONE }, 700)
            }
            true
        }
    }
    private fun gestureHint(text: String) { hint.text = text; hint.visibility = View.VISIBLE }
}
