@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.r34v.r34v

import androidx.core.net.toUri

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.r34v.r34v.data.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

class PlayerActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private var video by mutableStateOf(Video())
    private var detail by mutableStateOf<Detail?>(null)
    private var stream by mutableStateOf<Stream?>(null)
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var engineRevision by mutableIntStateOf(0)
    private var fullscreen by mutableStateOf(false)
    private var controls by mutableStateOf(true)
    private var status by mutableStateOf("正在获取播放地址…")
    private var phase by mutableStateOf<String?>("正在连接放映厅")
    private var hint by mutableStateOf("")
    private var userRate by mutableFloatStateOf(1f)
    private var playbackPreferences by mutableStateOf(PlaybackPreferences())
    private var failureVisible by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var position = 0L
    private var local: String? = null
    private var offlineId: String? = null
    private var cachedKey: String? = null
    private var sourceRoot = ""
    private val offline get() = local != null || offlineId != null || cachedKey != null
    private var refreshed = false
    private var opening = false
    private var presented = false
    private var autoFullscreenApplied = false
    private var resumePlay = true
    private var pendingError: Throwable? = null
    private var hintJob: Job? = null
    private var locked by mutableStateOf(false)
    private var pipMode by mutableStateOf(false)
    private var enteringPip = false
    private var pauseAt = 0L
    private var timerRemaining by mutableLongStateOf(0)
    private var loop by mutableStateOf(VideoLoopRange())
    private val dialogs = ComposeDialogs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = runCatching { store.gson.fromJson(intent.getStringExtra("video"), Video::class.java) }.getOrNull()?.takeIf { it.id.isNotBlank() } ?: run { finish(); return }
        local = intent.getStringExtra("local"); offlineId = intent.getStringExtra("download"); cachedKey = intent.getStringExtra("cached")
        sourceRoot = intent.getStringExtra("root").orEmpty()
        position = savedInstanceState?.getLong("position") ?: store.watch(video.id, video.site)?.takeUnless { it.finished }?.position?.times(1000) ?: 0
        val globalRate = store.string("native_speed", "1.0").toFloatOrNull()?.coerceIn(.25f, 3f) ?: 1f
        playbackPreferences = store.personal.playback(video.site).let { prefs ->
            if (store.personal.snapshot().playbackPreferences.containsKey(video.site)) prefs else prefs.copy(defaultRate = globalRate)
        }
        userRate = playbackPreferences.defaultRate
        fullscreen = savedInstanceState?.getBoolean("fullscreen") ?: false
        autoFullscreenApplied = savedInstanceState?.getBoolean("autoFullscreenApplied") ?: false
        resumePlay = savedInstanceState?.getBoolean("resumePlay") ?: true
        pipMode = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
        locked = savedInstanceState?.getBoolean("locked") ?: false
        pauseAt = savedInstanceState?.getLong("pauseAt") ?: 0L
        loop = VideoLoopRange(savedInstanceState?.getLong("loopA", -1) ?: -1, savedInstanceState?.getLong("loopB", -1) ?: -1)
        if (pauseAt > 0 && pauseAt <= SystemClock.elapsedRealtime()) { pauseAt = 0; resumePlay = false }
        store.personal.markRead(video.site, setOf(video.entryKey))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { R34Theme { Content() } }
        load()
        lifecycleScope.launch { while (isActive) { delay(5000); savePosition() } }
        lifecycleScope.launch { while (isActive) {
            timerRemaining = if (pauseAt > 0) (pauseAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0
            if (pauseAt > 0 && timerRemaining == 0L) { pauseAt = 0; resumePlay = false; player?.pause(); savePosition(); hint = "定时暂停已生效"; controls = true }
            player?.let { if (loop.shouldRepeat(it.currentPosition, it.isPlaying)) it.seekTo(loop.startMs) }
            delay(if (loop.active) 100 else 500)
        } }
    }
    private fun load() {
        if (opening) return
        opening = true; failureVisible = false; phase = if (offline) "正在打开离线片源" else "正在连接放映厅"; pendingError = null
        lifecycleScope.launch { try {
            stream = when {
                local != null -> { check(store.localVideos(video.site).any { it.id == video.id && it.path == local }) { "离线文件不存在" }; Stream("本地", Uri.fromFile(java.io.File(local!!)).toString()) }
                offlineId != null -> {
                    val download = withContext(Dispatchers.IO) { app.playback.manager.downloadIndex.getDownload(offlineId!!) } ?: error("下载记录不存在")
                    check(download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) { "下载尚未完成" }
                    check(app.playback.metadata(download)?.site == video.site) { "下载不属于当前站点" }
                    Stream(app.playback.metadata(download)?.quality ?: "下载", download.request.uri.toString())
                }
                cachedKey != null -> {
                    // Restored from storage: the Media3 record is gone, only the cache remains.
                    check(cachedKey!!.startsWith("${video.site}:${video.id}:")) { "下载身份不匹配" }
                    check(withContext(Dispatchers.IO) { app.playback.cached(cachedKey!!, sourceRoot) }) { "本地缓存不完整，请重新下载" }
                    withContext(Dispatchers.IO) { app.playback.restoredStream(cachedKey!!, sourceRoot, video.url) }
                }
                else -> {
                    val result = app.repository.detail(video.url); detail = result; video = result.video.copy(thumb = result.video.thumb.ifBlank { video.thumb })
                    if (result.streams.isEmpty() && result.externalPlayers.isNotEmpty()) {
                        phase = null; status = "请在浏览器播放"; controls = true
                        return@launch
                    }
                    val preferred = store.watch(video.id, video.site)?.quality?.takeIf(String::isNotBlank) ?: store.string("quality", "720p")
                    result.streams.firstOrNull { it.label == preferred } ?: result.streams.firstOrNull { it.height <= (Regex("\\d+").find(preferred)?.value?.toIntOrNull() ?: 720) } ?: result.streams.last()
                }
            }
            open(stream!!, position, resumePlay)
            if (!offline && store.bool("auto_download")) runCatching { app.playback.add(video, stream!!) }
                .onFailure { toast("自动下载未开始：${it.message}") }
        } catch (e: Exception) { if (e is CancellationException) throw e; playbackError(e) } finally { opening = false } }
    }
    private fun open(value: Stream, start: Long, autoplay: Boolean = true) {
        stream = value; position = start
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        pendingError = null; failureVisible = false; phase = if (presented) "正在缓冲" else "正在校准画面"
        val engine = player ?: ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8000, 50000, 400, 800).setPrioritizeTimeOverSizeThresholds(true).build())
            .build().also { exo ->
                player = exo; engineRevision++; playerView?.player = exo
                exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); exo.setHandleAudioBecomingNoisy(true)
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        status = when (state) { Player.STATE_BUFFERING -> "正在缓冲…"; Player.STATE_READY -> "${stream?.label} · ${if (offline) "离线播放" else if (exo.isPlaying) "正在播放" else "已暂停"}"; Player.STATE_ENDED -> "播放完毕"; else -> "准备播放" }
                        playerView?.keepScreenOn = exo.isPlaying || state == Player.STATE_BUFFERING
                        when (state) {
                            Player.STATE_IDLE -> Unit
                            Player.STATE_BUFFERING -> phase = if (presented) "正在缓冲" else "正在校准画面"
                            Player.STATE_READY -> { phase = null; presented = true
                                if (exo.duration > 0 && !loop.validFor(exo.duration)) { loop = VideoLoopRange(); gestureHint("片源时长已变更，循环区间已清除") }
                                if (!autoFullscreenApplied && store.bool("auto_fullscreen")) { autoFullscreenApplied = true; fullscreen = true } }
                            Player.STATE_ENDED -> { phase = null
                                if (loop.active && exo.playWhenReady) { exo.seekTo(loop.startMs); exo.play() }
                                else { controls = true; locked = false; savePosition() } }
                        }
                    }
                    override fun onIsPlayingChanged(playing: Boolean) { playerView?.keepScreenOn = playing; revision++; if (exo.playbackState == Player.STATE_READY) status = "${stream?.label} · ${if (playing) { if (offline) "离线播放" else "正在播放" } else "已暂停"}" }
                    override fun onVideoSizeChanged(videoSize: VideoSize) { if (fullscreen) applyFullscreen() }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && !loop.contains(newPosition.positionMs)) {
                            loop = VideoLoopRange(); gestureHint("已跳出循环区间，循环已清除")
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        savePosition()
                        if (!offline && !refreshed) { refreshed = true; phase = "正在刷新播放地址"; lifecycleScope.launch { try {
                            val fresh = app.repository.detail(video.url); detail = fresh
                            open(fresh.streams.firstOrNull { it.label == stream?.label } ?: fresh.streams.last(), position,
                                player?.playWhenReady ?: resumePlay)
                        } catch (e: Exception) { if (e is CancellationException) throw e; playbackError(e) } } } else playbackError(error)
                    }
                })
            }
        val item = if (local != null) MediaItem.fromUri(value.url)
            else app.playback.item(video, value)
        val hlsKey = com.r34v.r34v.playback.HlsDownloads.mime(value.url)?.let { app.playback.key(video, value.label) }
        val mediaSource = DefaultMediaSourceFactory(app.playback.sourceFactory(offline && local == null, video.site, sourceRoot, hlsKey)).createMediaSource(item)
        engine.setMediaSource(mediaSource, start.coerceAtLeast(0)); engine.setPlaybackSpeed(userRate); engine.prepare(); engine.playWhenReady = autoplay
    }
    private fun playbackError(error: Throwable) { pendingError = error; phase = null; status = "播放失败：${error.message}"; failureVisible = true; controls = true; locked = false }
    private fun restart() { refreshed = false; if (stream != null) open(stream!!, if (pendingError != null) position else 0) else load() }
    @Composable private fun Content() {
        BackHandler { if (locked) { locked = false; controls = true } else if (fullscreen) fullscreen = false else finish() }
        SideEffect { applyFullscreen() }
        val engine = remember(engineRevision) { player }
        @Suppress("UNUSED_VARIABLE") val changed = revision

        Column(Modifier.fillMaxSize().background(CanvasColor).then(if (pipMode) Modifier else if (fullscreen) Modifier.displayCutoutPadding() else Modifier.safeDrawingPadding())) {
            Box((if (fullscreen || pipMode) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(minOf(windowSizeDp().width * 9f / 16f, windowSizeDp().height * .55f).dp)).testTag("player-surface")) {
                AndroidView(factory = { context -> PlayerView(context).also { view ->
                    playerView = view; view.useController = false; view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); installGestures(view); view.player = engine
                } }, modifier = Modifier.fillMaxSize(), update = { it.player = engine }, onRelease = { it.player = null; playerView = null })
                if (phase != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .75f)), contentAlignment = Alignment.Center) { Loading(title = phase!!) }
                if (hint.isNotBlank()) Text(hint, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f)).padding(16.dp))
                if (!pipMode) {
                    if (locked) Action("解锁画面", modifier = Modifier.align(Alignment.CenterStart).padding(12.dp)) { locked = false; controls = true }
                    else if (controls || failureVisible) VideoControls(engine)
                    if (loop.startMs >= 0 && !controls && !locked) TextAction(::loopOptions,
                        Modifier.align(Alignment.BottomEnd).padding(12.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(12.dp))) {
                        Text(loopLabel(), Modifier.testTag("video-loop-range"))
                    }
                }
            }
            if (!fullscreen && !pipMode) Details()
        }
        dialogs.Host()
    }
    @Composable private fun BoxScope.VideoControls(engine: ExoPlayer?) {
        var current by remember(engine) { mutableLongStateOf(position) }; var duration by remember(engine) { mutableLongStateOf(0) }; var scrubbing by remember { mutableStateOf(false) }
        LaunchedEffect(engine) { while (isActive) { if (!scrubbing) current = engine?.currentPosition?.coerceAtLeast(0) ?: position; duration = engine?.duration?.coerceAtLeast(0) ?: 0; delay(500) } }
        LaunchedEffect(controls, status, scrubbing) { if (controls && engine?.isPlaying == true && !failureVisible && !scrubbing) { delay(4500); controls = false } }
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .65f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .65f)))), verticalArrangement = Arrangement.SpaceBetween) {
            GlassSurface(modifier = Modifier.fillMaxWidth().padding(8.dp), radius = 18) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_back, "返回") { if (fullscreen) fullscreen = false else finish() }; Spacer(Modifier.weight(1f))
                    if (windowSizeDp().width >= 400 && LocalConfiguration.current.fontScale <= 1.3f) {
                    Selector(stream?.label ?: "清晰度", enabled = detail?.streams?.isNotEmpty() == true, click = ::quality)
                    Spacer(Modifier.width(6.dp)); Selector("${userRate}×", click = ::speed)
                    }
                    IconAction(R.drawable.ic_more, "播放器菜单", click = ::playbackOptions)
                    IconAction(R.drawable.ic_fullscreen, if (fullscreen) "退出全屏" else "全屏") { fullscreen = !fullscreen }
                }
            }
            if (failureVisible) Action("重试", primary = true, modifier = Modifier.align(Alignment.CenterHorizontally), click = ::restart)
            GlassSurface(modifier = Modifier.fillMaxWidth().padding(8.dp), radius = 18) {
                Column {
                if (loop.startMs >= 0) TextAction(::loopOptions, Modifier.align(Alignment.End).padding(horizontal = 8.dp)) { Text(loopLabel(), Modifier.testTag("video-loop-range")) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(if (engine?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play, if (engine?.isPlaying == true) "暂停" else "播放") { engine?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); if (it.isPlaying) it.pause() else it.play() } }
                    Text(timeText(current), style = MaterialTheme.typography.labelSmall)
                    Slider(current.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)), { scrubbing = true; current = it.toLong() }, Modifier.weight(1f).semantics { contentDescription = "播放进度" },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0, onValueChangeFinished = { engine?.seekTo(current); scrubbing = false },
                        thumb = { Box(Modifier.size(12.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)) },
                        track = { state -> Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .17f))) {
                            Box(Modifier.fillMaxWidth(if (duration > 0) (state.value / duration.toFloat()).coerceIn(0f, 1f) else 0f).height(3.dp).background(BrandColor, CircleShape))
                        } })
                    Text(timeText(duration), style = MaterialTheme.typography.labelSmall)
                }
                }
            }
        }
    }
    @Composable private fun PlaybackStatusPill() {
        val playing = status.contains("正在播放") || status.contains("离线播放")
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .07f))
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).background(if (playing) MatchColor else BrandColor, CircleShape))
            Text(status, color = if (playing) MatchColor else MutedColor, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
    @Composable private fun Details() {
        @Suppress("UNUSED_VARIABLE") val changed = revision
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SurfaceColor.copy(alpha = .42f), CanvasColor)))
            .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(video.title, color = Color.White, fontSize = if (LocalUiDensity.current.compact) 17.sp else 20.sp,
                lineHeight = if (LocalUiDensity.current.compact) 24.sp else 27.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            val metadata = listOf(video.duration, video.views, video.rating).filter(String::isNotBlank).joinToString(" · ")
            if (metadata.isNotBlank()) Copy(metadata)
            PlaybackStatusPill()
            if (failureVisible) Action("重试", primary = true, click = ::restart)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Selector(stream?.label ?: "清晰度", enabled = detail?.streams?.isNotEmpty() == true, modifier = Modifier.semantics { contentDescription = "清晰度，当前 ${stream?.label ?: "尚未加载"}" }, click = ::quality)
                Selector("${userRate}×", modifier = Modifier.semantics { contentDescription = "播放速度，当前 ${userRate} 倍" }, click = ::speed)
                Spacer(Modifier.weight(1f))
                IconAction(R.drawable.ic_more, "更多播放操作", click = ::playbackOptions)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val favorite = store.isFavorite("video", video.id, video.site)
                Action(if (favorite) "已收藏" else "收藏", icon = R.drawable.ic_bookmark, selected = favorite) {
                    store.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb, video.duration, site = video.site), video.site); revision++
                }
                if (!offline && detail?.streams?.isNotEmpty() == true) Action("下载", icon = R.drawable.ic_download, click = ::download)
                detail?.externalPlayers?.forEach { link -> Action(link.label) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri())) }
                        .onFailure { toast("没有可用的浏览器") }
                } }
            }
            SaveForLater(video, app, dialogs)
            QueueNextAction(this@PlayerActivity, video, intent.getStringExtra("queue_session"), dialogs)
            if (timerRemaining > 0) TextAction(::sleepTimer) { Text("将在 ${timeText(timerRemaining)} 后暂停") }
            detail?.let { data ->
                val mediaDuration = (player?.duration?.takeIf { it > 0 } ?: durationSeconds(video.duration) * 1000)
                if (data.description.isNotBlank()) Expandable("简介", true) { TimestampText(data.description, mediaDuration, ::seekTimestamp) }
                data.groups.filterValues { it.isNotEmpty() }.forEach { (name, links) -> Expandable("$name · ${links.size}") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { links.forEach { link ->
                    val translated = if (store.bool("translate_tags", true)) TagNames.lookup(this@PlayerActivity, link.slug) else null
                    Box(Modifier.combinedClickable(onClick = { startActivity(Intent(this@PlayerActivity, MainActivity::class.java).putExtra("link", link.url)) }, onLongClick = {
                        val kind = when (name) { "角色" -> "character"; "作者" -> "model"; else -> "tag" }
                        dialogs.choose(link.label, if (name == "标签") listOf("收藏 / 取消收藏", "屏蔽 / 取消屏蔽") else listOf("收藏 / 取消收藏")) { i -> if (i == 1) store.toggleBlocked(link.slug) else store.toggleFavorite(Favorite(kind, link.slug, link.label, link.url, link.thumb, site = video.site), video.site); revision++ }
                    }).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .05f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(translated ?: link.label, fontSize = 12.sp)
                    }
                } } } }
                if (data.comments.isNotEmpty()) Expandable("评论 · ${data.comments.size}") { data.comments.forEach { Copy("${it.author}  ${it.date}"); TimestampText(it.text, mediaDuration, ::seekTimestamp) } }
                if (data.related.isNotEmpty()) { Title("相关推荐"); data.related.filter(store::allowed).take(24).chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { related -> MediaCard(related, Modifier.weight(1f)) { startActivity(Intent(this@PlayerActivity, PlayerActivity::class.java).putExtra("video", store.gson.toJson(related))) } }; if (pair.size == 1) Spacer(Modifier.weight(1f))
                } } }
            }
        }
    }
    private fun quality() { val options = detail?.streams ?: return; dialogs.choose("清晰度", options.map { it.label }, options.indexOf(stream)) { index ->
        val start = player?.currentPosition ?: position; val playing = player?.playWhenReady ?: true
        stream = options[index]; store.set("quality", stream!!.label); refreshed = false; open(stream!!, start, playing)
    } }
    private fun speed() { val rates = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f); dialogs.choose("播放速度", rates.map { "${it}×" }, rates.indexOf(userRate)) { userRate = rates[it]; store.set("native_speed", userRate.toString()); player?.setPlaybackSpeed(userRate) } }
    private fun playbackOptions() {
        val pip = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        val choices = listOf("重新播放", "锁定画面，防止误触", "定时暂停", "清晰度", "播放速度", "时间书签与片段", "播放偏好", "A–B 区间循环") + if (pip) listOf("进入画中画") else emptyList()
        dialogs.choose("播放操作", choices) {
            when (it) { 0 -> restart(); 1 -> { locked = true; controls = false }; 2 -> sleepTimer(); 3 -> quality(); 4 -> speed(); 5 -> timeBookmarks(); 6 -> playbackPreferenceOptions(); 7 -> loopOptions(); 8 -> pictureInPicture() }
        }
    }
    private fun loopLabel() = if (loop.active) "循环 ${timeText(loop.startMs)} – ${timeText(loop.endMs)}" else "A ${timeText(loop.startMs)} · 待设置 B"
    private fun loopOptions() {
        val engine = player ?: return
        if (engine.duration <= 0 || !engine.isCurrentMediaItemSeekable) { toast("等待可定位的片源加载完成"); return }
        dialogs.choose("A–B 区间循环", listOf("将当前位置设为 A 点", "将当前位置设为 B 点并循环", "清除循环区间")) { index ->
            when (index) {
                0 -> { loop = loop.start(engine.currentPosition); gestureHint(loopLabel()) }
                1 -> runCatching { loop.end(engine.currentPosition.coerceAtMost(engine.duration)) }
                    .onSuccess { loop = it; engine.seekTo(it.startMs); gestureHint(loopLabel()) }
                    .onFailure { toast(it.message.orEmpty()) }
                2 -> { loop = VideoLoopRange(); gestureHint("循环已清除") }
            }
        }
    }
    private fun seekTimestamp(target: Long) {
        val duration = player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
        position = target.coerceIn(0, duration); player?.seekTo(position); controls = true; gestureHint("已跳到 ${timeText(position)}")
    }
    private fun timeBookmarks() {
        dialogs.content = {
            val changed by store.personal.changes.collectAsState()
            val marks = remember(changed, video.entryKey) { store.personal.timeBookmarks(video) }
            AlertDialog(dialogs::dismiss, title = { Text("时间书签与片段") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextAction({ dialogs.dismiss(); addTimeBookmark() }) { Text("添加当前位置 · ${timeText(player?.currentPosition ?: position)}") }
                    if (marks.isEmpty()) Copy("为当前视频保存时间点、备注和分组；备份时会一并导出。")
                    marks.groupBy { it.group.ifBlank { "时间点" } }.forEach { (group, entries) ->
                        Title(group)
                        entries.forEach { mark ->
                            Column(Modifier.fillMaxWidth().contentPanel(12).padding(10.dp)) {
                                Text(timeText(mark.positionMs), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                if (mark.note.isNotBlank()) Copy(mark.note)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextAction({ dialogs.dismiss(); seekTimestamp(mark.positionMs) }) { Text("跳转") }
                                    TextAction({ store.personal.removeTimeBookmark(mark.id) }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextAction(dialogs::dismiss) { Text("关闭") } })
        }
    }
    private fun addTimeBookmark() {
        val target = (player?.currentPosition ?: position).coerceAtLeast(0)
        dialogs.content = {
            var note by remember { mutableStateOf("") }; var group by remember { mutableStateOf("时间点") }
            AlertDialog(dialogs::dismiss, title = { Text("添加 ${timeText(target)} 时间书签") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, colors = filledFieldColors())
                    TextField(group, { group = it }, Modifier.fillMaxWidth(), label = { Text("分组") }, singleLine = true, colors = filledFieldColors())
                }
            }, confirmButton = { TextAction({
                if (note.length <= 500 && group.trim().length <= 80) {
                    store.personal.saveTimeBookmark(TimeBookmark(video = video, positionMs = target, note = note.trim(), group = group.trim().ifBlank { "时间点" }))
                    dialogs.dismiss(); toast("已保存时间书签")
                }
            }) { Text("保存") } }, dismissButton = { TextAction(dialogs::dismiss) { Text("取消") } })
        }
    }
    private fun playbackPreferenceOptions() {
        fun save(value: PlaybackPreferences) { playbackPreferences = value.validated(); store.personal.setPlayback(video.site, playbackPreferences) }
        dialogs.content = {
            AlertDialog(dialogs::dismiss, title = { Text("${SiteRegistry.byIdOrDefault(video.site).name} · 播放偏好") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Selector("默认倍速 · ${playbackPreferences.defaultRate}×") {
                        val values = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f)
                        dialogs.choose("默认倍速", values.map { "${it}×" }, values.indexOf(playbackPreferences.defaultRate)) { selected ->
                            save(playbackPreferences.copy(defaultRate = values[selected])); userRate = values[selected]; player?.setPlaybackSpeed(userRate); playbackPreferenceOptions()
                        }
                    }
                    Selector("长按倍速 · ${playbackPreferences.holdRate}×") {
                        val values = listOf(1.5f, 2f, 2.5f, 3f, 4f)
                        dialogs.choose("长按倍速", values.map { "${it}×" }, values.indexOf(playbackPreferences.holdRate)) { save(playbackPreferences.copy(holdRate = values[it])); playbackPreferenceOptions() }
                    }
                    Selector("快进 / 快退 · ${playbackPreferences.seekStepMs / 1000} 秒") {
                        val values = listOf(5_000L, 10_000L, 15_000L, 30_000L, 60_000L)
                        dialogs.choose("快进步长", values.map { "${it / 1000} 秒" }, values.indexOf(playbackPreferences.seekStepMs)) { save(playbackPreferences.copy(seekStepMs = values[it])); playbackPreferenceOptions() }
                    }
                    Selector("整屏进度手势 · ${playbackPreferences.gestureRangeMs / 1000} 秒") {
                        val values = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
                        dialogs.choose("手势灵敏度", values.map { "整屏 ${it / 1000} 秒" }, values.indexOf(playbackPreferences.gestureRangeMs)) { save(playbackPreferences.copy(gestureRangeMs = values[it])); playbackPreferenceOptions() }
                    }
                    Copy("只覆盖当前来源，点选后立即保存并作用于当前播放。“取消”仅关闭选项，不撤销已保存值；其他来源和播放器音量逻辑保持不变。")
                }
            }, confirmButton = { TextAction(dialogs::dismiss) { Text("完成") } })
        }
    }
    private fun sleepTimer() {
        val minutes = listOf(0, 5, 15, 30, 60)
        dialogs.choose("定时暂停", listOf("关闭定时") + minutes.drop(1).map { "$it 分钟后暂停" } + "自定义分钟") { index ->
            fun schedule(value: Int) { pauseAt = if (value == 0) 0 else SystemClock.elapsedRealtime() + value * 60_000L; timerRemaining = value * 60_000L }
            if (index < minutes.size) schedule(minutes[index])
            else dialogs.edit("定时暂停", "15", "1–180 分钟", keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
                validate = { if (it.toIntOrNull() in 1..180) null else "请输入 1–180 分钟" }) { schedule(it.toInt()) }
        }
    }
    private fun pictureInPicture() {
        if (Build.VERSION.SDK_INT < 26 || player == null) return
        val size = player?.videoSize
        val aspect = if (size != null && size.width > 0 && size.height > 0) (size.width.toFloat() / size.height).coerceIn(.42f, 2.38f) else 16f / 9f
        enteringPip = true; controls = false; locked = false; dialogs.dismiss()
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
        val bounds = android.graphics.Rect()
        if (playerView?.getGlobalVisibleRect(bounds) == true) params.setSourceRectHint(bounds)
        // PiP remains an explicit player action; leaving the app keeps the existing pause behavior.
        if (Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(false)
        runCatching { enterPictureInPictureMode(params.build()) }
            .onSuccess { if (!it) { enteringPip = false; controls = true; toast("系统未允许画中画") } }
            .onFailure { enteringPip = false; controls = true; toast("无法进入画中画：${it.message}") }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode; enteringPip = false
        if (pipMode) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else { controls = true; applyFullscreen() }
    }
    private fun download() { val options = detail?.streams ?: return; dialogs.choose("选择下载清晰度", options.map { it.label }) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 40)
        runCatching { app.playback.add(video, options[it]) }.onSuccess { toast("已加入下载，可在统一下载中心查看") }.onFailure(::showError)
    } }
    private fun applyFullscreen() {
        if (pipMode || enteringPip) return
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) { val size = player?.videoSize; requestedOrientation = if (size != null && size.height > size.width) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; bars.hide(WindowInsetsCompat.Type.systemBars()) }
        else { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    private fun savePosition() { val exo = player ?: return; position = exo.currentPosition.coerceAtLeast(0); val duration = exo.duration.takeIf { it > 0 } ?: durationSeconds(video.duration) * 1000
        store.saveWatch(Watch(video.id, video.title, video.thumb, video.url, position / 1000, duration / 1000, quality = stream?.label.orEmpty(), site = video.site))
    }
    override fun onSaveInstanceState(outState: Bundle) { savePosition(); outState.putLong("position", position); outState.putBoolean("fullscreen", fullscreen); outState.putBoolean("autoFullscreenApplied", autoFullscreenApplied); outState.putBoolean("resumePlay", player?.playWhenReady ?: resumePlay); outState.putBoolean("locked", locked); outState.putLong("pauseAt", pauseAt); outState.putLong("loopA", loop.startMs); outState.putLong("loopB", loop.endMs); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); window.decorView.post { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && player == null && stream != null) open(stream!!, position, resumePlay) } }
    override fun onPause() { resumePlay = player?.playWhenReady ?: resumePlay; savePosition(); if (!pipMode && !enteringPip) player?.pause(); super.onPause() }
    override fun onStop() { savePosition(); playerView?.player = null; player?.release(); player = null; engineRevision++; super.onStop() }
    private fun gestureHint(value: String) { hintJob?.cancel(); hint = value; hintJob = lifecycleScope.launch { delay(1000); hint = "" } }
    @Suppress("ClickableViewAccessibility") private fun installGestures(view: PlayerView) {
        val audio = getSystemService(AudioManager::class.java); var start = 0L; var x = 0f; var y = 0f; var mode = 0; var seek = 0L; var hold = false; var brightness = .5f; var volume = 0
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { x = e.x; y = e.y; start = player?.currentPosition ?: 0; mode = 0; hold = false; brightness = window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f; volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { controls = !controls; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { when { e.x < view.width * .4f -> { player?.seekTo((start - playbackPreferences.seekStepMs).coerceAtLeast(0)); gestureHint("后退 ${playbackPreferences.seekStepMs / 1000} 秒") }; e.x > view.width * .6f -> { player?.seekTo((start + playbackPreferences.seekStepMs).coerceAtMost(player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)); gestureHint("前进 ${playbackPreferences.seekStepMs / 1000} 秒") }; else -> fullscreen = !fullscreen }; return true }
            override fun onLongPress(e: MotionEvent) { hold = true; player?.setPlaybackSpeed(playbackPreferences.holdRate); gestureHint("${playbackPreferences.holdRate}× 临时倍速") }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val dx = e2.x - x; val dy = e2.y - y
                if (mode == 0 && (kotlin.math.abs(dx) > dp(12) || kotlin.math.abs(dy) > dp(12))) mode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1 else if (x < view.width / 2f) 2 else 3
                when (mode) {
                    1 -> { val duration = player?.duration?.takeIf { it > 0 } ?: return true; seek = (start + dx / view.width * playbackPreferences.gestureRangeMs).toLong().coerceIn(0, duration); gestureHint(timeText(seek)) }
                    2 -> { val value = (brightness - dy / view.height).coerceIn(.02f, 1f); window.attributes = window.attributes.apply { screenBrightness = value }; gestureHint("亮度 ${(value * 100).toInt()}%") }
                    3 -> { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val value = (volume - dy / view.height * max).toInt().coerceIn(0, max); audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); gestureHint("音量 ${value * 100 / max.coerceAtLeast(1)}%") }
                }; return true
            }
        })
        view.setOnTouchListener { _, event ->
            if (locked || pipMode) return@setOnTouchListener true
            detector.onTouchEvent(event); if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { if (mode == 1 && event.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seek); if (hold) { player?.setPlaybackSpeed(userRate); hold = false } }; true
        }
    }
}
