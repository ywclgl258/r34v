@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.adapter.KvsAdapter
import com.r34v.r34v.data.adapter.SiteAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Opt-in controlled comparison. Sample preparation and execution are in docs/dev/playback-performance.md. */
class PlaybackPerformanceProbe {
    private data class Profile(val hls: Boolean, val knownSpeed: Boolean, val seekCache: Boolean = true, val streamCache: Boolean = false) {
        val media = if (hls) "index.m3u8" else "source.mp4"
        val name = "${if (hls) "hls" else "mp4"}-${if (knownSpeed) "known" else "unknown"}" +
            when { streamCache -> "-stream-cache"; !seekCache -> "-no-seek-cache"; else -> "" }
    }

    private data class Request(val path: String, val range: String?, val start: Long, val length: Int) {
        val probe get() = range == "bytes=0-1048575"
        val detail get() = path.endsWith("/detail")
    }

    private data class Frame(val at: Long, val position: Long)
    private data class CacheRead(val span: CacheSpan, val at: Long)

    @Test fun compareProgressiveAndHls() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("playbackPerf") == "true")
        val repeats = args.getString("playbackPerfRepeats")?.toInt() ?: 3
        require(repeats > 0)
        ComposeFixture().use { fixture ->
            val directory = File(fixture.app.filesDir, "playback_probe")
            val media = directory.listFiles().orEmpty().filter(File::isFile).associate { it.name to it.readBytes() }
            val names = media.keys.filter { it.matches(Regex("segment\\d+\\.ts")) }.sorted()
            check(media.keys.containsAll(listOf("source.mp4", "index.m3u8")) && names.isNotEmpty()) { "Missing playback_probe fixtures" }
            val requests = ConcurrentLinkedQueue<Request>()
            val completions = ConcurrentLinkedQueue<Pair<String, Long>>()
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        val name = path.substringAfterLast('/')
                        val id = path.substringBeforeLast('/').substringAfterLast('/')
                        val bytes = if (name == "detail") {
                            val mediaName = if (id.contains("-hls-")) "index.m3u8" else "source.mp4"
                            val url = server.url("/playback-probe/$id/$mediaName")
                            "<h1>Playback probe</h1><script>var flashvars={video_id:'$id',video_url:'$url',video_url_text:'360p'}</script>".toByteArray()
                        } else media[name] ?: return MockResponse().setResponseCode(404)
                        val range = request.getHeader("Range")
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val end = (range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                        if (start !in 0..end) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                        val length = end - start + 1
                        requests.add(Request(path, range, now(), length))
                        return MockResponse().setHeadersDelay(120, MILLISECONDS).throttleBody(16384, 80, MILLISECONDS)
                            .setHeader("Content-Type", when (name) {
                                "detail" -> "text/html; charset=utf-8"
                                "index.m3u8" -> "application/vnd.apple.mpegurl"
                                "source.mp4" -> "video/mp4"
                                else -> "video/mp2t"
                            }).setHeader("Accept-Ranges", "bytes")
                            .setBody(Buffer().write(bytes, start, length)).apply {
                                if (range != null) setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                            }
                    }
                }
                server.start()
                val app = fixture.app
                val site = SiteRegistry.R34V
                val adapterField = field(AdapterRegistry, "cache")
                @Suppress("UNCHECKED_CAST")
                val adapters = adapterField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
                adapters[site.id] = KvsAdapter(site, app.network)
                val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
                val client = app.network.client.newBuilder().cache(null)
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val path = request.url.encodedPath
                        if (!path.startsWith("/playback-probe/")) throw IOException("Only local playback fixtures are available")
                        chain.proceed(request.newBuilder().url(server.url(path)).build())
                    }
                    .eventListener(object : EventListener() {
                        override fun callEnd(call: Call) {
                            completions.add(call.request().url.encodedPath to now())
                        }
                    }).build()
                clientField.set(app.network, client)
                // The bandwidth meter posts an initial network type update on the main looper.
                // Drain that update before resetting the estimate for the first trial.
                main { app.linkSpeed }
                fixture.ins.waitForIdleSync()
                val profiles = listOf(Profile(false, false), Profile(true, false), Profile(false, true), Profile(true, true),
                    Profile(false, true, seekCache = false), Profile(true, true, streamCache = true))
                fun runTrial(round: Int, profile: Profile) {
                    val id = "${fixture.nonce}-$round-${profile.name}"
                    val path = "/playback-probe/$id/"
                    measure(fixture, profile, id, names.map { server.url(path + it).toString() }, {
                        requests.filter { it.path.startsWith(path) }
                    }, { name -> completions.firstOrNull { it.first == path + name }?.second }, report = round > 0)
                }
                try {
                    // Warm the UI and decoder for both formats before collecting timings.
                    runTrial(0, Profile(false, true))
                    runTrial(0, Profile(true, true))
                    repeat(repeats) { round ->
                        val order = if (round % 2 == 0) profiles else profiles.reversed()
                        order.forEach { runTrial(round + 1, it) }
                    }
                } finally {
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    private fun measure(fixture: ComposeFixture, profile: Profile, id: String, segments: List<String>,
                        requests: () -> List<Request>, completedAt: (String) -> Long?, report: Boolean) {
        val app = fixture.app
        val site = SiteRegistry.R34V
        val video = Video(id, "Playback probe", "${site.base}/playback-probe/$id/detail", site = site.id)
        val key = app.playback.key(video, "360p")
        val frames = ConcurrentLinkedQueue<Frame>()
        val cacheReads = ConcurrentLinkedQueue<CacheRead>()
        val cacheName = if (profile.hls) "streamCache" else "seekCache"
        val cache = (field(app.playback, "${cacheName}\$delegate").get(app.playback) as Lazy<*>).value as Cache
        val cacheKeys = if (profile.hls) segments.map { "$key|hls|$it" } else listOf(key)
        val cacheListener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) = Unit
            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
                cacheReads.add(CacheRead(newSpan, now()))
            }
        }
        cacheKeys.forEach { check(cache.addListener(it, cacheListener).isEmpty()) }
        val firstFrame = object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                frames.add(Frame(renderTimeMs, eventTime.currentPlaybackPositionMs))
            }
        }
        main {
            app.library.set("quality", "360p")
            app.library.set("native_speed", "1.0")
            app.library.set("auto_download", false)
            app.library.set("seek_cache", profile.seekCache)
            app.library.set("stream_cache", profile.streamCache)
            field(app.linkSpeed, "sampled").setInt(app.linkSpeed, 0)
            field(app.linkSpeed, "seeded").setInt(app.linkSpeed, if (profile.knownSpeed) 1600 else 0)
        }
        val intent = Intent(app, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("video", app.library.gson.toJson(video))
        val launched = main { now().also { app.startActivity(intent) } }
        val activity = await("activity for $id") {
            main {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlayerActivity>().singleOrNull()
            }
        }
        val playerField = field(activity, "player")
        try {
            val player = await("engine for $id") {
                main {
                    (playerField.get(activity) as ExoPlayer?)?.also {
                        check(it.playbackState != Player.STATE_READY) { "Missed first-frame listener for $id" }
                        it.addAnalyticsListener(firstFrame)
                    }
                }
            }
            val startup = await("first frame for $id") { frames.peek() }
            val detailEnd = checkNotNull(completedAt("detail"))
            val probes = requests().filter { it.probe }
            check(probes.size == if (profile.knownSpeed) 0 else 1) { "Unexpected startup probe count for $id: ${requests()}" }
            val probe = probes.singleOrNull()
            val probeEnd = probe?.let { checkNotNull(completedAt(profile.media)) }
            val firstMedia = requests().first { !it.detail && !it.probe }.start
            val firstSegment = if (profile.hls) requests().first { it.path.endsWith("/segment00.ts") }.start else firstMedia

            await("initial playback for $id") { main { true.takeIf { player.currentPosition >= 2700 } } }
            val forward = main {
                check(player.bufferedPosition < 22_000) { "Forward jump is already buffered for $id" }
                now().also { player.seekTo(22_000) }
            }
            await("forward frame for $id") { frames.firstOrNull { it.at > forward && it.position in 22_000..22_750 } }
            await("forward playback for $id") { main { true.takeIf { player.currentPosition >= 22_500 } } }
            val back = main { now().also { player.seekTo(1200) } }
            val restored = await("backward frame for $id") { frames.firstOrNull { it.at >= back && it.position in 1200..1950 } }
            val samePlayer = main { playerField.get(activity) === player }
            val hits = cacheReads.filter { it.at in back..restored.at }.map { it.span }
                .distinctBy { it.key to it.position }
            val fetches = requests().filter { it.start in back..restored.at }
            if (report) Log.i("R34VPlaybackPerf", "$id first=${startup.at - launched} detail=${detailEnd - launched} " +
                "probe=${if (probe != null) probeEnd!! - probe.start else 0} probeBytes=${probe?.length ?: 0} " +
                "preMedia=${firstMedia - detailEnd} mediaToFrame=${startup.at - firstMedia} " +
                "playlistToSegment=${firstSegment - firstMedia} back=${restored.at - back} " +
                "cacheSpansRead=${hits.size} backHttp=${fetches.size} sameEngine=$samePlayer " +
                "cacheSpans=${hits.joinToString { it.key.substringAfterLast('/') + ":" + it.position + "+" + it.length }} " +
                "backRequests=${fetches.joinToString { it.path.substringAfterLast('/') + ":" + it.range.orEmpty() }}")
        } finally {
            main { activity.finish() }
            await("close for $id") { main { true.takeIf { activity.isDestroyed && playerField.get(activity) == null } } }
            cacheKeys.forEach {
                cache.removeListener(it, cacheListener)
                cache.removeResource(it)
            }
            if (profile.hls) cache.removeResource("$key|hls|${segments.first().substringBeforeLast('/')}/index.m3u8")
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun <T> main(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private fun <T : Any> await(description: String, observe: () -> T?): T {
        val deadline = now() + 20_000
        while (now() < deadline) {
            observe()?.let { return it }
            SystemClock.sleep(12)
        }
        error("Timed out waiting for $description")
    }
}
