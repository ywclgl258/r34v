@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.Stream
import com.r34v.r34v.playback.StallPolicy
import com.r34v.r34v.playback.StreamSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PlayerDowngradePerformanceProbe {
    private enum class Fault(val segment: Int, val silenceMs: Long, val throughMs: Long, val slowForMs: Long = 0) {
        STARTUP_SILENCE(0, 12_000, 8_000),
        PLAYBACK_SILENCE(5, 12_000, 18_000),
        BRIEF_JITTER(5, 1_200, 18_000),
        RECOVERABLE_JITTER(5, 4_700, 18_000),
        STARTUP_RECOVERABLE(0, 4_000, 8_000),
        STARTUP_SLOW(0, 0, 8_000, Long.MAX_VALUE),
        PLAYBACK_SLOW(5, 0, 18_000, Long.MAX_VALUE),
        RECOVERABLE_SLOW(5, 0, 18_000, 4_700);

        val atStartup get() = segment == 0
        val path get() = "/high/segment${segment.toString().padStart(3, '0')}.ts"
        val slow get() = slowForMs > 0
    }

    private class Media(private val files: Map<String, ByteArray>, private val fault: Fault) : Dispatcher() {
        val heldRequest = AtomicLong()
        val lowerManifest = AtomicLong()
        val playbackBuffering = CountDownLatch(1)
        val highBytes = AtomicLong()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = checkNotNull(request.requestUrl).encodedPath
            val bytes = files[path] ?: return MockResponse().setResponseCode(404)
            if (path == "/low/movie.m3u8") lowerManifest.compareAndSet(0, now())
            val hold = path == fault.path && heldRequest.compareAndSet(0, now())
            if (hold && !fault.atStartup && !playbackBuffering.await(20, SECONDS)) {
                return MockResponse().setResponseCode(503)
            }
            val range = request.getHeader("Range")
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = (range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
            if (start !in 0..end) return MockResponse().setResponseCode(416)
            return MockResponse().setResponseCode(if (range == null) 200 else 206)
                .setHeader("Content-Type", if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t")
                .setHeader("Accept-Ranges", "bytes")
                .setHeadersDelay(120 + if (hold) fault.silenceMs else 0, MILLISECONDS)
                .setBody(Buffer().write(bytes, start, end - start + 1)).apply {
                    if (range != null) setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    if (path.endsWith(".ts")) throttleBody(32 * 1024, 80, MILLISECONDS)
                }
        }

        fun observeHighBody(chain: Interceptor.Chain, port: Int): okhttp3.Response {
            val response = chain.proceed(chain.request())
            val url = chain.request().url
            val path = url.encodedPath
            val body = response.body ?: return response
            if (url.port != port || !response.isSuccessful || !path.startsWith("/high/") || !path.endsWith(".ts")) return response
            val paced = fault.slow && if (fault.slowForMs == Long.MAX_VALUE) path >= fault.path else path == fault.path
            return response.newBuilder().body(object : ResponseBody() {
                private val source = object : ForwardingSource(body.source()) {
                    private var startedNs = 0L
                    private var pacedBytes = 0L

                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (startedNs == 0L) startedNs = System.nanoTime()
                        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                        val slowNow = paced && elapsedMs < fault.slowForMs
                        val read = super.read(sink, if (slowNow) minOf(byteCount, 8_192L) else byteCount)
                        if (read > 0) {
                            if (slowNow) {
                                pacedBytes += read
                                val waitNs = startedNs + pacedBytes * 1_000_000_000L / (32 * 1024) - System.nanoTime()
                                try {
                                    if (waitNs > 0) NANOSECONDS.sleep(waitNs)
                                } catch (interrupted: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    throw IOException("Paced HLS read interrupted", interrupted)
                                }
                            }
                            highBytes.addAndGet(read)
                        }
                        return read
                    }
                }.buffer()

                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }
    }

    private data class PlaybackEvent(val at: Long, val player: ExoPlayer, val position: Long)
    private data class FrameTime(val callbackAt: Long, val renderAt: Long, val player: ExoPlayer)

    private class Observation(private val activity: PlayerActivity, private val experiment: DowngradeThroughputExperiment?) {
        private val playerField = field(activity, "player")
        private var attached: ExoPlayer? = null
        val buffering = mutableListOf<PlaybackEvent>()
        val playing = mutableListOf<PlaybackEvent>()
        val frames = ConcurrentLinkedQueue<FrameTime>()

        fun scan(): ExoPlayer? {
            val current = playerField.get(activity) as ExoPlayer? ?: return null
            experiment?.observe(current)
            val engine = playerField.get(activity) as ExoPlayer? ?: return null
            if (engine === attached) return engine
            attached = engine
            engine.setVideoFrameMetadataListener { _, releaseNs, _, _ ->
                val callbackAt = now()
                val waitMs = ((releaseNs - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                frames.add(FrameTime(callbackAt, callbackAt + waitMs, engine))
            }
            engine.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) buffering.add(PlaybackEvent(now(), engine, engine.currentPosition))
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playing.add(PlaybackEvent(now(), engine, engine.currentPosition))
                }
            })
            if (engine.isPlaying) playing.add(PlaybackEvent(now(), engine, engine.currentPosition))
            return engine
        }

        fun rebufferDurations(through: Long): List<Long> {
            val firstPlaying = playing.first().at
            return buffering.filter { it.at > firstPlaying && it.at <= through }
                .groupBy { started -> playing.first { it.at > started.at }.at }
                .map { (resumed, starts) -> resumed - starts.first().at }
        }
    }

    @Test fun compareAutomaticDowngradeThresholds() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("playerDowngradePerf") == "true")
        val compareSignals = args.getString("playerDowngradeCompareSignals") == "true"
        val repeats = args.getString("playerDowngradeRepeats")?.toInt()?.coerceIn(1, 6) ?: 3
        val selection = args.getString("playerDowngradeFault")?.let(Fault::valueOf)
        val faults = Fault.entries.filter { (compareSignals || !it.slow) && (selection == null || it == selection) }
        require(faults.isNotEmpty())
        val startupThreshold = field(StallPolicy, "STARTUP_MS").getLong(null)
        val playbackThreshold = field(StallPolicy, "PLAYBACK_MS").getLong(null)
        val thresholds = "$startupThreshold/$playbackThreshold"
        check(thresholds == args.getString("playerDowngradeExpectedThresholds")) { "Unexpected installed APK thresholds: $thresholds" }
        ComposeFixture().use { fixture ->
            val high = File(fixture.app.filesDir, "playback_long_replay")
            val low = File(fixture.app.filesDir, "playback_downgrade_360p")
            val files = listOf("high" to high, "low" to low).flatMap { (rendition, directory) ->
                directory.listFiles().orEmpty().filter(File::isFile).map { "/$rendition/${it.name}" to it.readBytes() }
            }.toMap()
            for (rendition in listOf("high", "low")) {
                check(files.containsKey("/$rendition/movie.m3u8") &&
                    (0..89).all { files.containsKey("/$rendition/segment${it.toString().padStart(3, '0')}.ts") }) {
                    "Missing 180-second $rendition rendition"
                }
            }
            val link = fixture.app.linkSpeed
            val sampled = field(link, "sampled")
            val seeded = field(link, "seeded")
            val original = main { sampled.getInt(link) to seeded.getInt(link) }
            try {
                main {
                    fixture.app.library.set("quality", "720p")
                    fixture.app.library.set("auto_download", false)
                    fixture.app.library.set("auto_fullscreen", false)
                    fixture.app.library.set("native_speed", "1.0")
                    fixture.app.library.set("stream_cache", false)
                    fixture.app.library.set("seek_cache", false)
                }
                fixture.detailHook = { withContext(Dispatchers.Main) {
                    sampled.setInt(link, 0)
                    link.seed(6_000)
                } }
                measure(fixture, Media(files, Fault.BRIEF_JITTER), Fault.BRIEF_JITTER, thresholds, 0, false)
                for (round in 1..repeats) {
                    for (fault in if (round % 2 == 0) faults.reversed() else faults) {
                        val arms = when {
                            !compareSignals -> listOf(false)
                            round % 2 == 0 -> listOf(true, false)
                            else -> listOf(false, true)
                        }
                        for (signals in arms) measure(fixture, Media(files, fault), fault, thresholds, round, signals)
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

    private fun measure(fixture: ComposeFixture, media: Media, fault: Fault, thresholds: String, round: Int, signals: Boolean) {
        MockWebServer().use { server ->
            server.dispatcher = media
            server.start()
            val high = Stream("720p", server.url("/high/movie.m3u8").toString())
            fixture.detailStreams = listOf(
                high,
                Stream("360p", server.url("/low/movie.m3u8").toString()),
            )
            val video = fixture.video(index = (round * Fault.entries.size + fault.ordinal) * 2 + if (signals) 1 else 0).copy(duration = "3:00")
            val intent = Intent(fixture.app, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("video", fixture.app.library.gson.toJson(video))
            val network = fixture.app.network
            val clientField = field(network, "current")
            val previous = clientField.get(network) as OkHttpClient
            val observedClient = previous.newBuilder().addInterceptor { media.observeHighBody(it, server.port) }.build()
            clientField.set(network, observedClient)
            try {
                val launched = main { now().also { fixture.app.startActivity(intent) } }
                val activity = await("player activity") { main {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<PlayerActivity>().singleOrNull()
                } }
                val experiment = if (signals) DowngradeThroughputExperiment(activity, media.highBytes, StreamSelection.requiredKbps(high)) else null
                val observation = Observation(activity, experiment)
                try {
                    observeRecovery(activity, observation, experiment, media, fault, thresholds, launched, round)
                } finally {
                    media.playbackBuffering.countDown()
                    main { activity.finish() }
                    await("player cleanup") { main { true.takeIf { activity.isDestroyed && observation.scan() == null } } }
                }
            } finally {
                clientField.set(network, previous)
            }
        }
    }

    private fun observeRecovery(activity: PlayerActivity, observation: Observation, experiment: DowngradeThroughputExperiment?,
                                media: Media, fault: Fault, thresholds: String, launched: Long, round: Int) {
        val start = if (fault.atStartup) launched else {
            val initiallyPlaying = await("initial 720p playback") { main {
                observation.scan()
                observation.playing.firstOrNull()?.takeIf { it.player.videoSize.height == 720 && it.player.duration == 180_000L }
            } }
            val stall = await("rebuffer from the held segment for $fault") { main {
                observation.scan()?.playerError?.let { throw it }
                val requestedAt = media.heldRequest.get()
                observation.buffering.firstOrNull {
                    requestedAt > 0 && it.player === initiallyPlaying.player && it.at > initiallyPlaying.at && it.at >= requestedAt
                }
            } }
            check(stall.position in 9_500..10_500) { "Expected a stall at the 10-second boundary, got ${stall.position} ms" }
            media.playbackBuffering.countDown()
            stall.at
        }
        val resumed = await("playback after $fault") { main {
            observation.scan()?.playerError?.let { throw it }
            observation.playing.firstOrNull { it.at > start }
        } }
        val frame = await("video output after $fault") {
            observation.frames.firstOrNull { it.player === resumed.player && it.callbackAt >= resumed.at }
        }
        val end = await("playback through ${fault.throughMs} ms", pollMs = 60) { main {
            val engine = observation.scan() ?: return@main null
            engine.playerError?.let { throw it }
            now().takeIf { engine.isPlaying && engine.currentPosition >= fault.throughMs }
        } }
        val (drops, stalls) = main {
            check(observation.scan() === resumed.player) { "Player changed again after recovery" }
            val drops = field(activity, "autoDowngrades").getInt(activity)
            check(resumed.player.duration == 180_000L)
            check(resumed.player.videoSize.height == if (drops > 0) 360 else 720)
            drops to observation.rebufferDurations(end)
        }
        val lowerRequest = media.lowerManifest.get()
        check(media.heldRequest.get() in launched..end && (lowerRequest > 0) == (drops > 0))
        val decision = main { experiment?.decision }
        check(decision == null || drops > 0)
        Log.i(TAG, "round=$round fault=$fault thresholds=$thresholds signals=${experiment != null} recoveryMs=${frame.renderAt - start} " +
            "playingMs=${resumed.at - start} lowerRequestMs=${if (lowerRequest > 0) lowerRequest - start else -1} " +
            "rebufferCount=${stalls.size} rebufferMs=${stalls.sum()} drops=$drops positionAtResumeMs=${resumed.position} " +
            "observedThroughMs=${fault.throughMs} earlyMs=${decision?.bufferingMs ?: -1} " +
            "signalKbps=${decision?.ratesKbps?.joinToString(",") ?: "-"} refillMs=${decision?.refillMs ?: -1}")
    }

    private companion object {
        const val TAG = "R34VDowngradePerf"
        fun now() = SystemClock.elapsedRealtime()
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun <T> main(action: () -> T): T = runBlocking(Dispatchers.Main) { action() }
        fun <T : Any> await(what: String, pollMs: Long = 12, observe: () -> T?): T {
            val deadline = now() + 30_000
            while (now() < deadline) {
                observe()?.let { return it }
                SystemClock.sleep(pollMs)
            }
            error("Timed out waiting for $what")
        }
    }
}
