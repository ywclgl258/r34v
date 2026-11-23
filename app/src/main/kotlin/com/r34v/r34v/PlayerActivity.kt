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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.r34v.r34v.data.*
import com.r34v.r34v.playback.BufferPlan
import com.r34v.r34v.playback.HlsDownloads
import com.r34v.r34v.playback.HlsSeekSession
import com.r34v.r34v.playback.LocalSeekPreview
import com.r34v.r34v.playback.MediaProbe
import com.r34v.r34v.playback.StallPolicy
import com.r34v.r34v.playback.StreamSelection
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import java.io.File

class PlayerActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private val store get() = app.library
    private var video by mutableStateOf(Video())
    private var detail by mutableStateOf<Detail?>(null)
    private var stream by mutableStateOf<Stream?>(null)
    private var player: ExoPlayer? = null
    private var hlsSeekSession: HlsSeekSession? = null
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
    private var seekPreviewFile by mutableStateOf<File?>(null)
    private var offlineId: String? = null
    private var cachedKey: String? = null
    private var sourceRoot = ""
    private val offline get() = local != null || offlineId != null || cachedKey != null
    private var refreshed = false
    private var refreshJob: Job? = null
    private var opening = false
    private var presented = false
    private var autoFullscreenApplied = false
    private var resumePlay = true
    private var showingCover by mutableStateOf(false)
    private var pendingError: Throwable? = null
    private var hintJob: Job? = null
    private var locked by mutableStateOf(false)
    private var pipMode by mutableStateOf(false)
    private var enteringPip = false
    private var pauseAt = 0L
    private var timerRemaining by mutableLongStateOf(0)
    private var loop by mutableStateOf(VideoLoopRange())
    private val dialogs = ComposeDialogs()

    /** 起播档位与自动降档的状态；见 [StreamSelection] 与 [StallPolicy]。 */
    private var bufferingSince = 0L
    private var stallWindowStart = 0L
    private var stallsInWindow = 0
    private var autoDowngrades = 0
    private var lastDowngradeAt = 0L
    private var appliedPlan: String? = null
    /** 等首帧之后才入队的自动下载；见 [startPendingAutoDownload]。 */
    private var pendingAutoDownload: Stream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = runCatching { store.gson.fromJson(intent.getStringExtra("video"), Video::class.java) }.getOrNull()?.takeIf { it.id.isNotBlank() } ?: run { finish(); return }
        local = intent.getStringExtra("local"); offlineId = intent.getStringExtra("download"); cachedKey = intent.getStringExtra("cached")
        sourceRoot = intent.getStringExtra("root").orEmpty()
        position = savedInstanceState?.getLong("position")
            ?: store.watch(video.id, video.site)?.takeIf { it.started && !it.finished }?.position?.times(1000)
            ?: 0
        val globalRate = store.string("native_speed", "1.0").toFloatOrNull()?.coerceIn(.25f, 3f) ?: 1f
        playbackPreferences = store.personal.playback(video.site).let { prefs ->
            if (store.personal.snapshot().playbackPreferences.containsKey(video.site)) prefs else prefs.copy(defaultRate = globalRate)
        }
        userRate = playbackPreferences.defaultRate
        fullscreen = savedInstanceState?.getBoolean("fullscreen") ?: false
        autoFullscreenApplied = savedInstanceState?.getBoolean("autoFullscreenApplied") ?: false
        showingCover = savedInstanceState?.getBoolean("showingCover") ?: !intent.getBooleanExtra("autoplay", true)
        resumePlay = savedInstanceState?.getBoolean("resumePlay") ?: !showingCover
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
            maybeDowngrade()
            delay(if (loop.active) 100 else 500)
        } }
    }
    private fun load() {
        if (opening) return
        opening = true; failureVisible = false
        phase = when { local != null -> "正在打开本地视频"; offline -> "正在打开离线片源"; else -> "正在连接放映厅" }
        pendingError = null
        autoDowngrades = 0; stallsInWindow = 0; stallWindowStart = 0; bufferingSince = 0; lastDowngradeAt = 0
        lifecycleScope.launch { try {
            stream = when {
                local != null -> {
                    val record = checkNotNull(store.localVideos(video.site).firstOrNull { it.id == video.id && it.path == local }) {
                        "离线文件不存在"
                    }
                    val file = File(record.path)
                    if (video.site == SiteRegistry.LOCAL.id && offlineId == null && cachedKey == null) seekPreviewFile = file
                    Stream("本地", Uri.fromFile(file).toString())
                }
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
                    // 起播档位永远从“默认清晰度”出发。以前这里优先读本片上次播放的档位，
                    // 而那次档位可能是弱网自动降下来的——于是一部片被降过一次 480p，之后
                    // 网络再好也永远从 480p 开始。自动降档只影响本次播放，不能写回偏好。
                    val preferred = store.string("quality", "720p")
                    chosenStream(result.streams, preferred)
                }
            }
            open(stream!!, position, resumePlay)
            // 自动下载改到首帧之后入队：以前在取到地址的同一刻就开始下载，正好把起播要用的
            // 那条连接和带宽对半分，弱网下等于自己给自己制造缓冲。
            if (!offline && local == null && store.bool("auto_download")) pendingAutoDownload = stream
        } catch (e: Exception) { if (e is CancellationException) throw e; playbackError(e) } finally { opening = false } }
    }
    /**
     * 起播档位。
     *
     * 单档片源可以直接起播。有多个档位时才需要用带宽估计选档。
     *
     * 以前是“标签精确匹配，匹配不到就取 ≤720 的最后一档”，于是站点默认给 720p、链路又喂不动时，
     * 播放器只会永远转圈。现在先看链路实测：已知速度就按预算选，未知就先测 1 MB 真实媒体再选，
     * 测速本身失败（断网、被拦）时退回用户设置，行为和以前一致。
     */
    private suspend fun chosenStream(streams: List<Stream>, preferred: String): Stream {
        if (streams.isEmpty()) error("详情页没有播放地址，可能需要登录或站点已改版")
        streams.singleOrNull()?.let { return it }
        val link = app.linkSpeed
        val local = link.estimateKbps()
        if (local > 0) return noteChoice(StreamSelection.pick(streams, preferred, local), preferred, local, measured = false)
        val sample = StreamSelection.preferred(streams, preferred) ?: streams.first()
        if (HlsDownloads.mime(sample.url) != null) return StreamSelection.pick(streams, preferred, 0)
        phase = "正在测试链路速度"
        val epoch = link.routeEpoch()
        val result: MediaProbe.Result? = try {
            app.mediaProbe.measureStartup(SiteRegistry.byIdOrDefault(video.site), sample)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val measured = result?.trustedKbps?.takeIf { link.seed(it, epoch) }
        val estimate = link.estimateKbps()
        return noteChoice(StreamSelection.pick(streams, preferred, estimate), preferred, estimate, measured = measured == estimate)
    }

    /** 选了比用户设置更低的档位时说明原因，避免用户以为设置没生效。 */
    private fun noteChoice(choice: Stream, preferred: String, kbps: Int, measured: Boolean): Stream {
        val wanted = StreamSelection.preferred(detail?.streams.orEmpty().ifEmpty { listOf(choice) }, preferred) ?: choice
        if (choice.label != wanted.label && kbps > 0) {
            gestureHint(if (measured) "链路约 ${kbps / 1000} Mbps，已用 ${choice.label}" else "当前网速下自动使用 ${choice.label}")
        }
        return choice
    }

    /** 当前档位的缓冲窗口；档位或链路换了就重建引擎，因为 LoadControl 建好后不能改。 */
    private fun bufferPlanFor(value: Stream) = StreamSelection.bufferPlan(
        StreamSelection.requiredKbps(value), app.linkSpeed.estimateKbps())

    /**
     * 取（必要时重建）播放引擎。
     *
     * Media3 的 `LoadControl` 建好之后不能改，所以缓冲窗口变化只能重建引擎。旧引擎在新引擎
     * 就位后才释放：降档路径可能正好在旧的 `Player.Listener` 回调里执行，先建后放不会把正在
     * 上报状态的引擎在回调中抽走。
     */
    private fun ensureEngine(plan: BufferPlan): ExoPlayer {
        val previous = player
        if (previous != null && appliedPlan == plan.signature) return previous
        val created = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(plan.minMs, plan.maxMs, plan.forPlaybackMs, plan.afterRebufferMs)
                .setPrioritizeTimeOverSizeThresholds(true).build())
            // 同一个带宽表用于选档：真实播放的载荷统计是下一次判断最可信的依据。
            .setBandwidthMeter(app.linkSpeed.meter)
            .build()
        appliedPlan = plan.signature
        player = created; engineRevision++; playerView?.player = created
        previous?.let { old -> playerView?.player = created; runCatching { old.stop(); old.release() } }
        created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        created.setHandleAudioBecomingNoisy(true)
        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (created !== player) return
                status = when (state) { Player.STATE_BUFFERING -> "正在缓冲…"; Player.STATE_READY -> "${stream?.label} · ${if (offline) "离线播放" else if (created.isPlaying) "正在播放" else "已暂停"}"; Player.STATE_ENDED -> "播放完毕"; else -> "准备播放" }
                playerView?.keepScreenOn = created.isPlaying || state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> {
                        phase = if (presented) "正在缓冲" else "正在校准画面"
                        if (bufferingSince == 0L) { bufferingSince = SystemClock.elapsedRealtime(); countStall() }
                    }
                    Player.STATE_READY -> { phase = null; presented = true; bufferingSince = 0
                        if (created.duration > 0 && !loop.validFor(created.duration)) { loop = VideoLoopRange(); gestureHint("片源时长已变更，循环区间已清除") }
                        if (!showingCover && !autoFullscreenApplied && store.bool("auto_fullscreen")) changeFullscreen(true)
                        startPendingAutoDownload() }
                    Player.STATE_ENDED -> { phase = null; bufferingSince = 0
                        if (loop.active && created.playWhenReady) { created.seekTo(loop.startMs); created.play() }
                        else { controls = true; locked = false; savePosition() } }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { playerView?.keepScreenOn = playing; revision++; if (created.playbackState == Player.STATE_READY) status = "${stream?.label} · ${if (playing) { if (offline) "离线播放" else "正在播放" } else "已暂停"}" }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (created === player) updateFullscreenOrientation(videoSize)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !loop.contains(newPosition.positionMs)) {
                    loop = VideoLoopRange(); gestureHint("已跳出循环区间，循环已清除")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (created !== player) return
                savePosition()
                val failed = stream
                if (offline || refreshed || failed == null) {
                    playbackError(error)
                    return
                }
                refreshed = true
                phase = "正在刷新播放地址"
                refreshJob = lifecycleScope.launch {
                    try {
                        val fresh = app.repository.detail(video.url)
                        val recovered = StreamSelection.afterRefresh(fresh.streams, failed, app.linkSpeed.estimateKbps())
                        detail = fresh
                        if (pendingAutoDownload != null) pendingAutoDownload = recovered
                        if (recovered.label != failed.label) gestureHint("原清晰度已不可用，已切换到 ${recovered.label}")
                        val autoplay = if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            player?.playWhenReady ?: resumePlay
                        } else resumePlay
                        open(recovered, position, autoplay)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        playbackError(e)
                    }
                }
            }
        })
        return created
    }

    private fun open(value: Stream, start: Long, autoplay: Boolean = true) {
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        stream = value; position = start
        pendingError = null; failureVisible = false; phase = if (presented) "正在缓冲" else "正在校准画面"
        bufferingSince = 0
        val engine = ensureEngine(bufferPlanFor(value))
        val item = if (local != null) MediaItem.fromUri(value.url)
            else app.playback.item(video, value)
        val hlsKey = HlsDownloads.mime(value.url)?.let { app.playback.key(video, value.label) }
        val streamingCache = store.bool("stream_cache")
        val seekEnabled = local == null && !offline && offlineId == null && cachedKey == null && store.bool("seek_cache", true)
        val sessionKey = if (seekEnabled && hlsKey == null) app.playback.key(video, value.label) else null
        val sources = app.playback.sourceFactory(offline && local == null, video.site, sourceRoot, hlsKey, streamingCache, sessionKey)
        hlsSeekSession?.close()
        val hlsSeek = if (seekEnabled && hlsKey != null && !streamingCache) app.playback.openHlsSeekSession() else null
        hlsSeekSession = hlsSeek
        val mediaSource = if (hlsSeek != null) {
            val chunks = app.playback.sourceFactory(siteId = video.site, sourceRoot = sourceRoot, hlsKey = hlsKey, hlsSeek = hlsSeek)
            HlsMediaSource.Factory(HlsDataSourceFactory { type -> (if (type == C.DATA_TYPE_MEDIA) chunks else sources).createDataSource() })
                .setPlaylistParserFactory(hlsSeek.playlistParsers).createMediaSource(item)
        } else DefaultMediaSourceFactory(sources).createMediaSource(item)
        engine.setMediaSource(mediaSource, start.coerceAtLeast(0)); engine.setPlaybackSpeed(userRate); engine.prepare(); engine.playWhenReady = autoplay
    }
    private fun playbackError(error: Throwable) {
        bufferingSince = 0
        pendingError = error; phase = null; status = "播放失败：${error.message}"; failureVisible = true; controls = true; locked = false
    }
    private fun restart() {
        refreshJob?.cancel()
        refreshed = false; autoDowngrades = 0; stallsInWindow = 0
        val current = stream
        if (current != null) open(current, if (pendingError != null) position else 0) else load()
    }

    /**
     * 首帧就绪后才真正入队自动下载。
     *
     * 以前在拿到播放地址的同一刻就 `add()`，Downloads 立刻和播放抢带宽（并行 2 个任务），在
     * 250 KB/s 的链路上等于把起播时间翻倍。现在先让画面出来，再开始写盘；只做一次。
     */
    private fun startPendingAutoDownload() {
        val target = pendingAutoDownload ?: return
        pendingAutoDownload = null
        runCatching { app.playback.add(video, target) }.onFailure { toast("自动下载未开始：${it.message}") }
    }

    /** 记一次卡顿；窗口过期就重新计数，避免十几次轻微抖动累加成一个“持续卡顿”。 */
    private fun countStall() {
        if (offline) return
        val now = SystemClock.elapsedRealtime()
        if (stallWindowStart == 0L || now - stallWindowStart > StallPolicy.WINDOW_MS) { stallWindowStart = now; stallsInWindow = 0 }
        stallsInWindow++
    }

    /**
     * 卡顿就降一档。
     *
     * 以前没有任何降档路径：链路喂不动 720p 时引擎只会一直 `STATE_BUFFERING`，用户面对的是
     * 永远转圈的缓冲画面。现在三种信号（起播太久 / 单次卡太久 / 短时间反复卡）任一成立就退到
     * 下一档，并保留播放位置和播放状态；用户手动选的档位也照降，只是会明说“网络跟不上，已降到 xxx”。
     */
    private fun maybeDowngrade() {
        if (offline || player == null || bufferingSince == 0L || refreshJob?.isActive == true) return
        if (autoDowngrades >= MAX_AUTO_DOWNGRADES) return
        val now = SystemClock.elapsedRealtime()
        if (lastDowngradeAt != 0L && now - lastDowngradeAt < DOWNGRADE_COOLDOWN_MS) return
        val engine = player ?: return
        val buffered = now - bufferingSince
        if (!StallPolicy.shouldDowngrade(buffered, engine.currentPosition, stallsInWindow)) return
        val current = stream ?: return
        val lower = StreamSelection.lower(detail?.streams.orEmpty(), current) ?: return
        autoDowngrades++; lastDowngradeAt = now; bufferingSince = 0; stallsInWindow = 0; stallWindowStart = now
        gestureHint("网络跟不上 ${current.label}，已自动降到 ${lower.label}")
        open(lower, engine.currentPosition, engine.playWhenReady)
    }
    @Composable private fun Content() {
        // 普通详情交给系统返回，保留跨 Activity 的预测性返回动画。
        BackHandler(enabled = fullscreen || locked, onBack = ::leavePlayerLayer)
        LaunchedEffect(Unit) { applyFullscreen() }
        val engine = remember(engineRevision) { player }
        @Suppress("UNUSED_VARIABLE") val changed = revision
        val palette = rememberArtworkPalette(SiteRegistry.byIdOrDefault(video.site), video.thumb, SiteCategory.VIDEO,
            cachedOnly = offline || !showingCover)
        val coverVisible = showingCover && !fullscreen && !pipMode
        ArtworkColors(palette) {
        Column(Modifier.fillMaxSize().then(if (fullscreen || pipMode) Modifier.background(Color.Black) else Modifier.artworkBackground(palette))
            .then(when {
                pipMode -> Modifier
                fullscreen -> Modifier.displayCutoutPadding()
                coverVisible -> Modifier.navigationBarsPadding().displayCutoutPadding()
                else -> Modifier.safeDrawingPadding()
            })) {
            if (!coverVisible) Box((if (fullscreen || pipMode) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(minOf(windowSizeDp().width * 9f / 16f, windowSizeDp().height * .55f).dp))
                .background(Color.Black).testTag("player-surface")) {
                AndroidView(factory = { context -> PlayerView(context).also { view ->
                    playerView = view; view.useController = false; view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); installGestures(view); view.player = engine
                } }, modifier = Modifier.fillMaxSize(), update = { it.player = engine }, onRelease = { it.player = null; playerView = null })
                phase?.let { loading ->
                    Box(Modifier.fillMaxSize().background(palette.bottom.copy(alpha = .84f)), contentAlignment = Alignment.Center) {
                        Loading(title = loading)
                    }
                }
                if (hint.isNotBlank()) Text(hint, Modifier.align(Alignment.Center).background(palette.bottom, ArtworkPill)
                    .padding(horizontal = 14.dp, vertical = 8.dp), color = palette.ink)
                if (!pipMode) {
                    if (locked) ArtworkAction("解锁画面", Modifier.align(Alignment.CenterStart).padding(12.dp), primary = false) { locked = false; controls = true }
                    else if (controls || failureVisible) VideoControls(engine, palette)
                    if (loop.startMs >= 0 && !controls && !locked) TextAction(::loopOptions,
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).background(palette.bottom, ArtworkPill)) {
                        Text(loopLabel(), Modifier.testTag("video-loop-range"))
                    }
                }
            }
            if (!fullscreen && !pipMode) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (coverVisible) PosterDetails(palette)
                Details(showTitle = !coverVisible)
            }
        }
        dialogs.Host()
        }
    }

    @Composable private fun PosterDetails(palette: ArtworkPalette) {
        val source = SiteRegistry.byIdOrDefault(video.site)
        val height = (windowSizeDp().height * .42f).dp.coerceIn(220.dp, 390.dp)
        Box(Modifier.fillMaxWidth().heightIn(min = height).artworkReveal(video.entryKey).testTag("video-poster-detail")) {
            SiteImage(video.thumb, source, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to palette.bottom.copy(alpha = .48f), .30f to Color.Transparent, .53f to Color.Transparent, 1f to palette.bottom)))
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 9.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ finish() }) {
                    Box(Modifier.size(38.dp).background(palette.bottom.copy(alpha = .87f), CircleShape), contentAlignment = Alignment.Center) {
                        Glyph(R.drawable.ic_back, "返回", Modifier.size(19.dp))
                    }
                }
                val favorite = store.isFavorite("video", video.id, video.site)
                IconButton(::toggleVideoFavorite, Modifier.semantics { selected = favorite }) {
                    Box(Modifier.size(38.dp).background(palette.bottom.copy(alpha = .87f), CircleShape), contentAlignment = Alignment.Center) {
                        Glyph(if (favorite) R.drawable.ic_check else R.drawable.ic_bookmark, if (favorite) "已收藏" else "收藏", Modifier.size(19.dp))
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.title, Modifier.semantics { heading() }, color = palette.ink,
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                val metadata = listOf(video.duration, video.type).filter(String::isNotBlank).joinToString(" · ")
                if (metadata.isNotBlank()) Copy(metadata)
                val external = detail?.takeIf { it.streams.isEmpty() }?.externalPlayers?.firstOrNull()
                ArtworkAction(if (external == null) "立即播放" else external.label, Modifier.fillMaxWidth(), R.drawable.ic_play) {
                    if (external != null) openExternalPlayer(external)
                    else startPosterPlayback()
                }
            }
        }
    }

    private fun startPosterPlayback() {
        showingCover = false
        resumePlay = true
        if (player?.playbackState == Player.STATE_READY && !autoFullscreenApplied && store.bool("auto_fullscreen")) {
            changeFullscreen(true)
        }
        if (failureVisible) restart()
        else player?.play()
    }

    private fun openExternalPlayer(link: Link) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri())) }
            .onFailure { toast("没有可用的浏览器") }
    }

    private fun toggleVideoFavorite() {
        store.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb, video.duration, site = video.site), video.site)
        revision++
    }
    @Composable private fun BoxScope.VideoControls(engine: ExoPlayer?, palette: ArtworkPalette) {
        var current by remember(engine) { mutableLongStateOf(position) }
        var duration by remember(engine) { mutableLongStateOf(0) }
        var scrubbing by remember(engine) { mutableStateOf(false) }
        var touch by remember(engine) { mutableStateOf(SeekTouch.IDLE) }
        val previewFile = seekPreviewFile?.takeIf { engine != null }
        val previewTarget by remember(engine, previewFile) {
            derivedStateOf {
                if (previewFile != null && scrubbing && touch == SeekTouch.DOWN && duration > 0) {
                    LocalSeekPreview.framePosition(current, duration)
                } else null
            }
        }
        var previewFrame by remember(engine, previewFile) { mutableStateOf<Pair<Long, android.graphics.Bitmap>?>(null) }
        if (previewFile != null) LaunchedEffect(engine, previewFile) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val preview = LocalSeekPreview(previewFile)
                try {
                    // One native decode at a time. Keep only the latest request while that call finishes.
                    snapshotFlow { previewTarget }.buffer(Channel.CONFLATED).collect { requested ->
                        if (requested == previewTarget) {
                            val frame = requested?.let { preview.frameAt(it) }
                            if (requested == previewTarget) previewFrame = if (frame != null && requested != null) requested to frame else null
                        }
                    }
                } finally {
                    previewFrame = null
                    preview.close()
                }
            }
        }
        LaunchedEffect(engine) { while (isActive) { if (!scrubbing) current = engine?.currentPosition?.coerceAtLeast(0) ?: position; duration = engine?.duration?.coerceAtLeast(0) ?: 0; delay(500) } }
        var focused by remember { mutableStateOf(false) }
        val menuOpen = dialogs.content != null
        LaunchedEffect(controls, status, scrubbing, menuOpen, focused) {
            if (controls && engine?.isPlaying == true && !failureVisible && !scrubbing && !menuOpen && !focused) {
                delay(4500)
                controls = false
            }
        }
        val opaque = LocalReduceTransparency.current
        val plate = Modifier.clip(ArtworkPill)
            .background(Brush.verticalGradient(listOf(palette.surface, palette.bottom).map { it.copy(alpha = if (opaque) 1f else .94f) }))
            .border(1.dp, palette.edge, ArtworkPill)
        Box(Modifier.fillMaxSize().padding(8.dp).artworkReveal(Unit).onFocusChanged { focused = it.hasFocus }.focusGroup()
            .testTag("artwork-player-controls")) {
            Row(Modifier.align(Alignment.TopStart).then(plate)) {
                IconAction(R.drawable.ic_back, "返回", click = ::leavePlayerLayer)
            }
            Row(Modifier.align(Alignment.TopEnd).then(plate), verticalAlignment = Alignment.CenterVertically) {
                if (windowSizeDp().width >= 340 && LocalConfiguration.current.fontScale <= 1.3f) {
                    PlaybackSetting(stream?.label ?: "清晰度", "清晰度，当前 ${stream?.label ?: "尚未加载"}",
                        detail?.streams?.isNotEmpty() == true, ::quality)
                    PlaybackSetting("${userRate}×", "播放速度，当前 ${userRate} 倍", true, ::speed)
                }
                IconAction(R.drawable.ic_more, "播放器菜单", click = ::playbackOptions)
                IconAction(R.drawable.ic_fullscreen, if (fullscreen) "退出全屏" else "全屏") { changeFullscreen(!fullscreen) }
            }
            if (failureVisible) ArtworkAction("重试", modifier = Modifier.align(Alignment.Center), click = ::restart)
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 760.dp).fillMaxWidth()) {
                previewTarget?.let { target ->
                    Column(
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)
                            .clip(RoundedCornerShape(8.dp)).background(palette.bottom).padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        previewFrame?.takeIf { it.first == target }?.let { (_, frame) ->
                            Image(frame.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.size(160.dp, 90.dp).testTag("local-seek-preview-frame"))
                        }
                        Text(timeText(current), Modifier.testTag("local-seek-preview-time"),
                            color = palette.ink, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                if (loop.startMs >= 0) TextAction(::loopOptions, Modifier.align(Alignment.End).padding(horizontal = 8.dp)) {
                    Text(loopLabel(), Modifier.testTag("video-loop-range"))
                }
                Row(Modifier.fillMaxWidth().then(plate).padding(start = 3.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val playing = engine?.isPlaying == true
                    IconButton(onClick = {
                        engine?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); if (it.isPlaying) it.pause() else it.play() }
                    }, enabled = engine != null, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.size(36.dp).background(if (engine == null) palette.edge else palette.accent, CircleShape),
                            contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides if (engine == null) palette.ink else palette.onAccent) {
                                Glyph(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "暂停" else "播放", Modifier.size(17.dp))
                            }
                        }
                    }
                    Text(timeText(current), color = palette.ink, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    val canSeek = duration > 0
                    Slider(
                        value = current.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = {
                            if (touch == SeekTouch.CANCELLED) touch = SeekTouch.IDLE
                            scrubbing = true
                            current = it.toLong()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "播放进度" }
                            .then(if (canSeek) Modifier.pointerInput(engine) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    touch = SeekTouch.DOWN
                                    var event = awaitPointerEvent(PointerEventPass.Initial)
                                    while (event.changes.any { it.pressed }) {
                                        event = awaitPointerEvent(PointerEventPass.Initial)
                                    }
                                    // Compose delivers cancellation as a consumed up. Slider also reports that as finished.
                                    touch = if (event.changes.any { it.changedToUp() }) SeekTouch.RELEASED else SeekTouch.CANCELLED
                                    if (touch == SeekTouch.CANCELLED) scrubbing = false
                                }
                            } else Modifier),
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = canSeek,
                        onValueChangeFinished = {
                            if (scrubbing && (touch == SeekTouch.IDLE || touch == SeekTouch.RELEASED) && engine === player) {
                                engine?.seekTo(current)
                            }
                            if (touch == SeekTouch.RELEASED) touch = SeekTouch.IDLE
                            scrubbing = false
                        },
                        thumb = { ArtworkSeekThumb(canSeek) },
                        track = { state -> ArtworkSeekTrack(if (canSeek) state.value / duration else 0f) },
                    )
                    Text(timeText(duration), color = palette.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
    @Composable private fun PlaybackSetting(value: String, description: String, enabled: Boolean, click: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        TextButton(onClick = click, enabled = enabled, shape = ArtworkPill,
            modifier = Modifier.widthIn(max = 88.dp).heightIn(min = 48.dp).semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface, disabledContentColor = colors.onSurfaceVariant)) {
            Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (enabled) Glyph(R.drawable.ic_chevron_down, null, Modifier.size(12.dp))
        }
    }
    @Composable private fun CompactTool(icon: Int, label: String, active: Boolean? = null, click: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val ink = if (active == true) colors.onPrimary else colors.onSurface
        Row(Modifier.heightIn(min = 48.dp).clip(ArtworkPill)
            .clickable(role = Role.Button, onClick = click).semantics { if (active != null) selected = active }
            .padding(vertical = 4.dp).border(1.dp, if (active == true) colors.primary else colors.outline, ArtworkPill)
            .background(if (active == true) colors.primary else colors.surface, ArtworkPill).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CompositionLocalProvider(LocalContentColor provides ink) { Glyph(icon, null, Modifier.size(16.dp)) }
            Text(label, color = ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    private fun shelfOptions() { dialogs.choose("稍后看与合集", listOf("加入 / 移出稍后看", "加入合集")) { index ->
        val personal = app.library.personal
        when (index) {
            0 -> if (personal.contains(video)) personal.remove(video) else personal.add(video)
            else -> dialogs.addToShelf(personal, video)
        }
    } }

    @Composable private fun Details(showTitle: Boolean) {
        @Suppress("UNUSED_VARIABLE") val changed = revision
        val personalRevision by app.library.personal.changes.collectAsState()
        val blockedRevision by store.blockedTagChanges.collectAsState()
        val waiting = remember(personalRevision, video.entryKey) { app.library.personal.contains(video) }
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showTitle) {
                    Text(video.title, Modifier.semantics { heading() }, color = colors.onSurface,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    val metadata = listOf(video.duration, video.views, video.rating).filter(String::isNotBlank).joinToString(" · ")
                    if (metadata.isNotBlank()) Copy(metadata)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val indicator = if (failureVisible) Color(0xffffb0b9) else colors.onSurfaceVariant
                    Spacer(Modifier.size(5.dp).background(if (failureVisible) indicator else colors.primary, CircleShape))
                    Text(status, color = indicator, style = MaterialTheme.typography.labelMedium)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                if (showTitle) {
                    val favorite = store.isFavorite("video", video.id, video.site)
                    CompactTool(R.drawable.ic_bookmark, if (favorite) "已收藏" else "收藏", active = favorite, click = ::toggleVideoFavorite)
                }
                CompactTool(R.drawable.ic_history, "稍后看 / 合集", active = waiting, click = ::shelfOptions)
                if (!offline && detail?.streams?.isNotEmpty() == true) CompactTool(R.drawable.ic_download, "下载") { download() }
                IconButton(onClick = ::playbackOptions, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(39.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                        Glyph(R.drawable.ic_more, "更多播放操作", Modifier.size(18.dp))
                    }
                }
            }
            val players = detail?.externalPlayers.orEmpty()
            val extraPlayers = if (!showTitle && detail?.streams?.isEmpty() == true) players.drop(1) else players
            if (extraPlayers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                extraPlayers.forEach { link -> ArtworkAction(link.label) { openExternalPlayer(link) } }
            }
            QueueNextAction(this@PlayerActivity, video, intent.getStringExtra("queue_session"), dialogs)
            if (timerRemaining > 0) TextAction(::sleepTimer) { Text("将在 ${timeText(timerRemaining)} 后暂停") }
            detail?.let { data ->
                val mediaDuration = (player?.duration?.takeIf { it > 0 } ?: durationSeconds(video.duration) * 1000)
                if (data.description.isNotBlank()) Expandable("简介", true) { TimestampText(data.description, mediaDuration, ::seekTimestamp) }
                data.groups.filterValues { it.isNotEmpty() }.forEach { (name, links) -> Expandable("$name · ${links.size}") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { links.forEach { link ->
                    val translated = if (store.bool("translate_tags", true)) TagNames.lookup(this@PlayerActivity, link.slug) else null
                    RentalTag(translated ?: link.label, longClickLabel = "管理标签", onClick = { startActivity(Intent(this@PlayerActivity, MainActivity::class.java).putExtra("link", link.url)) }, onLongClick = {
                        val kind = when (name) { "角色" -> "character"; "作者" -> "model"; else -> "tag" }
                        dialogs.choose(link.label, if (name == "标签") listOf("收藏 / 取消收藏", "屏蔽 / 取消屏蔽") else listOf("收藏 / 取消收藏")) { i ->
                            // 跨站搜索打开的视频不属于当前浏览站点：收藏与屏蔽都必须写在视频自己的站点上。
                            if (i == 1) store.toggleBlocked(link.slug, video.site)
                            else store.toggleFavorite(Favorite(kind, link.slug, link.label, link.url, link.thumb, site = video.site), video.site)
                            revision++
                        }
                    })
                } } } }
                if (data.comments.isNotEmpty()) Expandable("评论 · ${data.comments.size}") {
                    data.comments.forEachIndexed { index, comment ->
                        if (index > 0) HorizontalDivider(color = colors.outline.copy(alpha = .5f))
                        Text(listOf(comment.author, comment.date).filter(String::isNotBlank).joinToString(" · "),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        TimestampText(comment.text, mediaDuration, ::seekTimestamp)
                    }
                }
                val relatedVideos = remember(data.related, blockedRevision) {
                    store.filterAllowed(data.related) { it }.take(24)
                }
                if (relatedVideos.isNotEmpty()) { Title("相关推荐"); relatedVideos.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { related -> MediaCard(related, Modifier.weight(1f)) {
                        startActivity(Intent(this@PlayerActivity, PlayerActivity::class.java)
                            .putExtra("video", store.gson.toJson(related)).putExtra("autoplay", false))
                    } }; if (pair.size == 1) Spacer(Modifier.weight(1f))
                } } }
            }
        }
    }
    private fun quality() {
        val options = detail?.streams ?: return
        dialogs.choose("清晰度", options.map { it.label }, options.indexOf(stream)) { index ->
            val selected = options[index]
            val engine = player
            store.set("quality", selected.label)
            refreshJob?.cancel()
            refreshed = false
            if (selected == stream && engine?.isPlaying == true && pendingError == null) return@choose
            open(selected, engine?.currentPosition ?: position, engine?.playWhenReady ?: true)
        }
    }
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
    private fun leavePlayerLayer() {
        when {
            locked -> { locked = false; controls = true }
            fullscreen -> changeFullscreen(false)
            else -> finish()
        }
    }

    private fun changeFullscreen(value: Boolean) {
        fullscreen = value
        // 用户在片源就绪前进出全屏后，迟到的 READY 回调也不会再次进入全屏。
        if (value) autoFullscreenApplied = true
        applyFullscreen()
    }

    private fun applyFullscreen() {
        if (pipMode || enteringPip) return
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            player?.videoSize?.let(::updateFullscreenOrientation)
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun updateFullscreenOrientation(size: VideoSize) {
        if (!fullscreen || pipMode || enteringPip || size.width <= 0 || size.height <= 0) return
        // 有效尺寸到达前保留当前方向，避免竖片在起播或重建时先转成横屏。
        val orientation = if (size.width * size.pixelWidthHeightRatio < size.height) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }
    private fun savePosition() {
        val engine = player ?: return
        val ended = engine.playbackState == Player.STATE_ENDED
        val duration = engine.duration.takeIf { it > 0 } ?: durationSeconds(video.duration) * 1000
        position = if (ended && duration > 0) duration else engine.currentPosition.coerceAtLeast(0)
        store.saveWatch(Watch(video.id, video.title, video.thumb, video.url, position / 1000, duration / 1000,
            quality = stream?.label.orEmpty(), site = video.site, ended = ended))
    }
    override fun onSaveInstanceState(outState: Bundle) { savePosition(); outState.putLong("position", position); outState.putBoolean("fullscreen", fullscreen); outState.putBoolean("autoFullscreenApplied", autoFullscreenApplied); outState.putBoolean("resumePlay", player?.playWhenReady ?: resumePlay); outState.putBoolean("showingCover", showingCover); outState.putBoolean("locked", locked); outState.putLong("pauseAt", pauseAt); outState.putLong("loopA", loop.startMs); outState.putLong("loopB", loop.endMs); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        window.decorView.post {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || stream == null ||
                refreshJob?.isActive == true || pendingError != null) return@post
            val engine = player
            // 引擎存在说明只经历了暂停/恢复（例如上面盖了半透明界面而未 onStop）：
            // 此时 onPause 已经把播放停掉了，必须显式续播，否则画面一直停着。
            if (engine == null) open(stream!!, position, resumePlay)
            else if (resumePlay && !pipMode && !engine.isPlaying && engine.playbackState != Player.STATE_ENDED) engine.play()
        }
    }
    override fun onPause() { resumePlay = player?.playWhenReady ?: resumePlay; savePosition(); if (!pipMode && !enteringPip) player?.pause(); super.onPause() }
    override fun onStop() {
        savePosition()
        playerView?.player = null
        player?.release()
        player = null
        hlsSeekSession?.close()
        hlsSeekSession = null
        appliedPlan = null
        engineRevision++
        super.onStop()
    }
    private fun gestureHint(value: String) { hintJob?.cancel(); hint = value; hintJob = lifecycleScope.launch { delay(1000); hint = "" } }
    @Suppress("ClickableViewAccessibility") private fun installGestures(view: PlayerView) {
        val audio = getSystemService(AudioManager::class.java); var start = 0L; var x = 0f; var y = 0f; var mode = 0; var seek = 0L; var hold = false; var brightness = .5f; var volume = 0
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { x = e.x; y = e.y; start = player?.currentPosition ?: 0; mode = 0; hold = false; brightness = window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f; volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { controls = !controls; return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { when { e.x < view.width * .4f -> { player?.seekTo((start - playbackPreferences.seekStepMs).coerceAtLeast(0)); gestureHint("后退 ${playbackPreferences.seekStepMs / 1000} 秒") }; e.x > view.width * .6f -> { player?.seekTo((start + playbackPreferences.seekStepMs).coerceAtMost(player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)); gestureHint("前进 ${playbackPreferences.seekStepMs / 1000} 秒") }; else -> changeFullscreen(!fullscreen) }; return true }
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

    private enum class SeekTouch { IDLE, DOWN, RELEASED, CANCELLED }

    private companion object {
        /** 一部片子最多自动降两档：降到最低档还卡就是链路问题，再降只会更糊。 */
        const val MAX_AUTO_DOWNGRADES = 2
        /** 两次自动降档的最短间隔，避免一次长缓冲连降到底。 */
        const val DOWNGRADE_COOLDOWN_MS = 20_000L
    }
}
