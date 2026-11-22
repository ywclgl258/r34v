@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.IntState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.Stream
import com.r34v.r34v.playback.StreamSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayerEnginePerformanceProbe : ComposeUiCase() {
    private enum class Path(val hls: Boolean = false, val reselect: Boolean = false) {
        REPLAY_REUSE, REPLAY_REBUILD, HLS_REPLAY_REUSE(hls = true), HLS_REPLAY_REBUILD(hls = true),
        QUALITY_SAME(reselect = true), HLS_QUALITY_SAME(hls = true, reselect = true), QUALITY_LOWER,
        REFRESH_ADDRESS, AUTO_DOWNGRADE, LIFECYCLE_AND_CONTROLS,
    }

    private class Media(private val files: Map<String, ByteArray>) : Dispatcher() {
        enum class Fault { SLOW_HIGH, EXPIRED }

        data class Request(val path: String, val at: Long, val delayMs: Long)

        val requests = ConcurrentLinkedQueue<Request>()
        val detailCount = AtomicInteger()
        @Volatile var fault: Fault? = null
        @Volatile var weak = false
        @Volatile var starting = true
        private val faultUsed = AtomicBoolean()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = checkNotNull(request.requestUrl).encodedPath
            val clip = files[path] ?: return MockResponse().setResponseCode(404)
            val expired = path == "/high.mp4" && fault == Fault.EXPIRED
            val stalled = path == "/high.mp4" && fault == Fault.SLOW_HIGH && faultUsed.compareAndSet(false, true)
            val delayMs = when {
                stalled -> 12_000L
                expired -> 0L
                starting || weak -> 400L
                else -> 100L
            }
            requests.add(Request(path, now(), delayMs))
            if (expired) return MockResponse().setResponseCode(403)

            val range = request.getHeader("Range")
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = range?.substringAfter('-', "")?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            if (start !in 0..end) return MockResponse().setResponseCode(416)
            return MockResponse().setResponseCode(if (range == null) 200 else 206)
                .setHeader("Content-Type", when {
                    path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                    path.endsWith(".ts") -> "video/mp2t"
                    else -> "video/mp4"
                })
                .setHeader("Accept-Ranges", "bytes")
                .apply {
                    if (range != null) setHeader("Content-Range", "bytes $start-$end/${clip.size}")
                    if (weak) throttleBody(24 * 1024, 125, TimeUnit.MILLISECONDS)
                }
                .setHeadersDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBody(Buffer().write(clip, start, end - start + 1))
        }
    }

    private data class Visit(val revision: Int, val at: Long, val engine: ExoPlayer?, val plan: String?)
    private data class Ready(val at: Long, val engine: ExoPlayer)
    private data class Frame(val at: Long, val engine: ExoPlayer, val position: Long)
    private data class Window(val start: Long, val returned: Long? = null)
    private data class ReselectionStart(val at: Long, val position: Long, val requests: Int)

    private class Observation(private val activity: PlayerActivity) {
        private val player = field(activity, "player")
        private val revision = field(activity, "engineRevision\$delegate").get(activity) as IntState
        private val applied = field(activity, "appliedPlan")
        private var latest: Visit? = null
        val ready = mutableListOf<Ready>()
        val frames = mutableListOf<Frame>()
        val buffering = mutableListOf<Long>()

        fun scan(): Visit {
            latest?.takeIf { it.revision == revision.intValue }?.let { return it }
            val engine = player.get(activity) as ExoPlayer?
            val visit = Visit(revision.intValue, now(), engine, applied.get(activity) as String?)
            latest = visit
            engine?.addAnalyticsListener(object : AnalyticsListener {
                override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                    if (state == Player.STATE_READY) ready.add(Ready(now(), engine))
                    if (state == Player.STATE_BUFFERING) buffering.add(now())
                }
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    frames.add(Frame(renderTimeMs, engine, eventTime.currentPlaybackPositionMs))
                }
            })
            return visit
        }
    }

    @Test fun observeEngineRevisionsWithTheSameMedia() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("playerEnginePerf") == "true")
        val repeats = args.getString("playerEnginePerfRepeats")?.toInt()?.coerceIn(1, 10) ?: 3
        val selection = args.getString("playerEnginePerfPath")?.split(',')?.map(Path::valueOf)
        val paths = Path.entries.filter { selection == null || it in selection }
        val expectQualityReopen = args.getString("playerEnginePerfExpectQualityReopen") == "true"
        ComposeFixture().use { fixture ->
            val link = fixture.app.linkSpeed
            val sampled = field(link, "sampled")
            val seeded = field(link, "seeded")
            val original = main { sampled.getInt(link) to seeded.getInt(link) }
            fun setEstimate(kbps: Int) {
                sampled.setInt(link, 0)
                seeded.setInt(link, kbps)
            }
            val assets = fixture.ins.context.assets
            val clip = assets.open("test-pattern.mp4").use { it.readBytes() }
            val mp4 = listOf("/high.mp4", "/low.mp4", "/fresh.mp4").associateWith { clip }
            val hls = checkNotNull(assets.list("hls")).associate { name ->
                "/hls/$name" to assets.open("hls/$name").use { it.readBytes() }
            }
            try {
                repeat(repeats + 1) { round ->
                    val ordered = if (round % 2 == 0) paths else paths.reversed()
                    for (path in ordered) {
                        val links = if (path == Path.REPLAY_REBUILD || path == Path.REPLAY_REUSE) listOf(false, true) else listOf(false)
                        for (weak in if (round % 2 == 0) links else links.reversed()) {
                            val files = if (path.hls) hls else mp4
                            measure(fixture, Media(files), path, weak, round, expectQualityReopen, ::setEstimate)
                        }
                    }
                }
            } finally {
                main {
                    sampled.setInt(link, original.first)
                    seeded.setInt(link, original.second)
                }
            }
        }
    }

    private fun measure(fixture: ComposeFixture, media: Media, path: Path, weak: Boolean, round: Int,
                        expectQualityReopen: Boolean, setEstimate: (Int) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher = media
            server.start()
            val highPath = if (path.hls) "/hls/media.m3u8" else "/high.mp4"
            val high = Stream("720p", server.url(highPath).toString())
            val low = Stream("480p", server.url(if (path.hls) "$highPath?quality=480" else "/low.mp4").toString())
            val refreshed = high.copy(url = server.url("/fresh.mp4").toString())
            fixture.detailHook = {
                fixture.detailStreams = when {
                    path == Path.REFRESH_ADDRESS && media.detailCount.incrementAndGet() > 1 -> listOf(refreshed)
                    path.reselect || path == Path.QUALITY_LOWER || path == Path.AUTO_DOWNGRADE -> listOf(high, low)
                    else -> listOf(high)
                }
            }
            val initialKbps = if (path in setOf(Path.REPLAY_REBUILD, Path.HLS_REPLAY_REBUILD)) 1_600 else 20_000
            main {
                fixture.app.library.set("quality", high.label)
                fixture.app.library.set("auto_download", false)
                fixture.app.library.set("auto_fullscreen", false)
                fixture.app.library.set("native_speed", "1.0")
                fixture.app.library.set("stream_cache", false)
                fixture.app.library.set("seek_cache", false)
                setEstimate(initialKbps)
            }
            val video = fixture.video(index = round * 20 + path.ordinal * 2 + if (weak) 1 else 0)
            val intent = Intent(fixture.app, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("video", fixture.app.library.gson.toJson(video))
            val launched = main { now().also { fixture.app.startActivity(intent) } }
            val activity = await("resumed player activity for $path") { main {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlayerActivity>().singleOrNull()
            } }
            val observation = Observation(activity)
            try {
                val startup = await("first frame at startup for $path") { main {
                    observation.scan().engine?.playerError?.let { throw it }
                    observation.frames.firstOrNull()
                } }
                media.starting = false
                val before = main { observation.scan() }
                check(before.revision == 1 && before.engine === startup.engine)
                if (path == Path.LIFECYCLE_AND_CONTROLS) {
                    exerciseControls(activity, observation, before, media, round, setEstimate)
                    return
                }
                val action = when (path) {
                    Path.QUALITY_SAME, Path.HLS_QUALITY_SAME, Path.QUALITY_LOWER -> {
                        ui.onNodeWithContentDescription("清晰度，当前 720p").performClick()
                        clickAction(if (path == Path.QUALITY_LOWER) low.label else high.label, selectable = true)
                    }
                    else -> {
                        ui.onNodeWithContentDescription("播放器菜单").performClick()
                        clickAction("重新播放")
                    }
                }
                if (path.reselect) {
                    main { fixture.app.library.set("quality", low.label) }
                    measureReselection(server, observation, before, path, round, expectQualityReopen, action)
                    check(fixture.app.library.string("quality", "") == high.label)
                    return
                }
                val window = main {
                    media.weak = weak
                    media.fault = when (path) {
                        Path.AUTO_DOWNGRADE -> Media.Fault.SLOW_HIGH
                        Path.REFRESH_ADDRESS -> Media.Fault.EXPIRED
                        else -> null
                    }
                    setEstimate(20_000)
                    val start = now()
                    check(action())
                    Window(start, now()).also { observation.scan() }
                }
                val expected = when (path) {
                    Path.QUALITY_LOWER, Path.AUTO_DOWNGRADE -> "/low.mp4"
                    Path.REFRESH_ADDRESS -> "/fresh.mp4"
                    else -> highPath
                }
                val request = await("media request for $path") {
                    main { observation.scan() }
                    media.requests.firstOrNull { it.at >= window.start && it.path == expected }
                }
                val frame = await("restored frame for $path") { main {
                    val active = observation.scan().engine
                    observation.frames.firstOrNull { it.at >= request.at && it.engine === active }
                } }
                val after = main { observation.scan() }
                val rebuild = path in setOf(Path.REPLAY_REBUILD, Path.HLS_REPLAY_REBUILD, Path.QUALITY_LOWER, Path.AUTO_DOWNGRADE)
                check((after.engine !== before.engine) == rebuild && after.revision - before.revision == if (rebuild) 1 else 0)
                val expectedPlan = StreamSelection.bufferPlan(StreamSelection.requiredKbps(if (expected == "/low.mp4") low else high), 20_000)
                check(after.plan == expectedPlan.signature)
                check(before.plan == StreamSelection.bufferPlan(StreamSelection.requiredKbps(high), initialKbps).signature)
                if (path == Path.REFRESH_ADDRESS) check(media.detailCount.get() == 2)
                val ready = await("ready after reopening for $path") { main { observation.ready.firstOrNull {
                    it.at >= request.at && it.engine === after.engine
                } } }
                val segment = media.requests.firstOrNull { it.at >= request.at && it.path.endsWith(".ts") }
                report(round, path.name, if (weak) "limited" else "local", window, before, after, frame, request, ready,
                    "startupMs=${startup.at - launched} segmentRequestMs=${segment?.at?.minus(window.start)} " +
                        "served=${media.requests.count { it.at >= window.start && it.at <= frame.at }}")
            } finally {
                main { activity.finish() }
                await("player activity cleanup") { main { true.takeIf { activity.isDestroyed && observation.scan().engine == null } } }
            }
        }
    }

    private fun measureReselection(server: MockWebServer, observation: Observation, before: Visit, path: Path,
                                   round: Int, expectReopen: Boolean, action: () -> Boolean) {
        val engine = checkNotNull(before.engine)
        val start = await("playing with the complete fixture buffered for $path") { main {
            val duration = engine.duration
            val position = engine.currentPosition
            if (!engine.isPlaying || duration <= 0 || engine.bufferedPosition < duration) return@main null
            check(duration - position >= 1_200) { "Not enough playback remains for $path at $position of $duration" }
            val sample = ReselectionStart(now(), position, server.requestCount)
            check(action())
            sample
        } }
        await("one second of playback after reselecting $path") { true.takeIf { now() >= start.at + 1_000 } }
        main {
            val after = observation.scan()
            val requests = server.requestCount - start.requests
            val stalls = observation.buffering.count { it >= start.at }
            val progress = engine.currentPosition - start.position
            check(after.revision == before.revision && after.engine === engine)
            if (expectReopen) {
                check(requests > 0 && stalls > 0) { "$path must reproduce a request and rebuffer on the baseline" }
            } else {
                check(requests == 0 && stalls == 0) { "$path reopened media: requests=$requests buffering=$stalls" }
                check(engine.isPlaying && progress >= 750) { "$path interrupted playback: progress=$progress" }
            }
            if (round > 0) Log.i(TAG, "round=$round path=$path link=local baseline=$expectReopen " +
                "windowMs=${now() - start.at} requestsBefore=${start.requests} newRequests=$requests rebuffer=$stalls " +
                "oldRev=${before.revision} newRev=${after.revision} delta=0 progressMs=$progress")
        }
    }

    private fun exerciseControls(activity: PlayerActivity, observation: Observation, before: Visit, media: Media,
                                 round: Int, setEstimate: (Int) -> Unit) {
        val engine = checkNotNull(before.engine)
        val position = main { setEstimate(1_600); engine.currentPosition }
        await("playback scaling without reopening") { main { true.takeIf { engine.currentPosition >= position + 600L } } }
        check(main { observation.scan() } == before)
        if (round > 0) Log.i(TAG, "round=$round path=ESTIMATE_ONLY link=local delta=0")
        val seek = main {
            val at = now()
            engine.seekTo(0)
            Window(at, now())
        }
        val seekFrame = await("seek frame") { main { observation.frames.firstOrNull { it.at >= seek.start } } }
        val afterSeek = main { observation.scan() }
        check(afterSeek == before)
        report(round, "SEEK_AFTER_ESTIMATE_CHANGE", "local", seek, before, afterSeek, seekFrame, null)

        ui.onNodeWithContentDescription("全屏").performClick()
        ui.onNodeWithContentDescription("退出全屏").assertExists().performClick()
        check(main { observation.scan() } == before)
        if (round > 0) Log.i(TAG, "round=$round path=FULLSCREEN_ROUND_TRIP link=local delta=0")

        main { activity.startActivity(Intent(activity, MainActivity::class.java)) }
        val cover = await("stopped player under another activity") { main {
            val released = observation.scan()
            if (released.engine == null && released.revision == before.revision + 1) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
            } else null
        } }
        val released = main { observation.scan() }
        val resume = main { Window(now()).also { cover.finish() } }
        val frame = await("first frame on return to player") { main {
            val active = observation.scan().engine
            observation.frames.firstOrNull { it.at >= resume.start && it.engine === active }
        } }
        val resumed = main { observation.scan() }
        check(resumed.revision == released.revision + 1 && resumed.engine !== before.engine)
        val request = media.requests.firstOrNull { it.at >= resume.start && it.at <= frame.at }
        val ready = await("ready on return to player") { main { observation.ready.firstOrNull { it.engine === resumed.engine } } }
        report(round, "RETURN_FROM_STOP", "local", resume, released, resumed, frame, request, ready, "stopDelta=1")
    }

    private fun clickAction(text: String, selectable: Boolean = false): () -> Boolean {
        val match = if (selectable) hasText(text) and isSelectable() else hasText(text) and hasClickAction()
        return checkNotNull(ui.onNode(match).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }

    private fun report(round: Int, path: String, link: String, window: Window, before: Visit, after: Visit,
                       frame: Frame, request: Media.Request?, ready: Ready? = null, extra: String = "") {
        if (round == 0) return
        val requestMs = request?.let { it.at - window.start }
        val readyMs = ready?.at?.minus(window.start)
        Log.i(TAG, "round=$round path=$path link=$link delta=${after.revision - before.revision} " +
            "oldRev=${before.revision} newRev=${after.revision} oldPlan=${before.plan} newPlan=${after.plan} " +
            "mainMs=${window.returned?.minus(window.start)} engineObservedMs=${after.at.takeIf { it >= window.start }?.minus(window.start)} " +
            "requestMs=$requestMs headersDelayMs=${request?.delayMs} readyMs=$readyMs frameMs=${frame.at - window.start} " +
            "requestToFrameMs=${request?.let { frame.at - it.at }} positionMs=${frame.position} $extra")
    }

    private companion object {
        const val TAG = "R34VEnginePerf"
        fun now() = SystemClock.elapsedRealtime()
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun <T> main(action: () -> T): T = runBlocking(Dispatchers.Main) { action() }
        fun <T : Any> await(what: String, observation: () -> T?): T {
            val deadline = now() + 25_000
            while (now() < deadline) {
                observation()?.let { return it }
                SystemClock.sleep(12)
            }
            error("Timed out waiting for $what")
        }
    }
}
