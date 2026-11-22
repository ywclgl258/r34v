@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.adapter.KvsAdapter
import com.r34v.r34v.data.adapter.SiteAdapter
import com.r34v.r34v.playback.PlaybackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.atomic.AtomicReference

/** Opt-in controlled comparison. Sample preparation and execution are in docs/dev/playback-performance.md. */
class PlaybackPerformanceProbe {
    private enum class Exercise { SEEK, STARTUP, REBUFFER, HLS_CACHE, CDN, SHORT_RANGES }

    private data class Profile(
        val hls: Boolean,
        val knownSpeed: Boolean,
        val seekCache: Boolean = true,
        val streamCache: Boolean = false,
        val exercise: Exercise = Exercise.SEEK,
        val multipleQualities: Boolean = false,
        val weakCdn: Boolean = false,
        val cdnPolicy: CdnSeekPolicy? = null,
    ) {
        val media = when {
            exercise == Exercise.SHORT_RANGES -> "ranges.m3u8"
            hls -> "index.m3u8"
            else -> "source.mp4"
        }
        val name = if (exercise == Exercise.CDN) {
            "cdn-${if (weakCdn) "weak" else "natural"}-${cdnPolicy?.label ?: if (seekCache) "on" else "off"}"
        } else if (exercise == Exercise.SHORT_RANGES) "hls-range-${checkNotNull(cdnPolicy).label}"
        else "${if (hls) "hls" else "mp4"}-${if (knownSpeed) "known" else "unknown"}" +
            when {
                streamCache -> "-stream-cache"
                !seekCache -> "-no-seek-cache"
                multipleQualities -> "-multiple"
                exercise == Exercise.STARTUP -> "-startup"
                exercise == Exercise.REBUFFER -> "-rebuffer"
                else -> ""
            }
        val hlsSession get() = hls && seekCache && !streamCache
    }

    private data class Request(val path: String, val range: String?, val start: Long, val length: Int) {
        val probe get() = range == "bytes=0-1048575"
        val detail get() = path.endsWith("/detail")

        fun reads(segment: ProbeSegment): Boolean {
            if (!path.endsWith(segment.uri.encodedPath)) return false
            if (segment.length == C.LENGTH_UNSET.toLong()) return true
            val offset = range?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            return segment.overlap(offset, length.toLong()) > 0
        }
    }

    private data class Frame(val at: Long, val position: Long)
    private data class CacheEvent(val span: CacheSpan, val at: Long)
    private data class CdnTrial(val id: String, val weak: Boolean)

    private fun parsePlaylist(url: HttpUrl, input: InputStream): ProbePlaylist {
        val playlist = HlsPlaylistParser().parse(Uri.parse(url.toString()), input) as HlsMediaPlaylist
        check(playlist.hasEndTag)
        fun resource(segment: HlsMediaPlaylist.Segment) = ProbeSegment(
            checkNotNull(url.resolve(segment.url)), segment.relativeStartTimeUs / 1_000, segment.byteRangeOffset, segment.byteRangeLength,
        )
        return ProbePlaylist(url, playlist.segments.map(::resource), playlist.segments.mapNotNull { it.initializationSegment }
            .map(::resource).toSet())
    }

    private fun cdnClip(client: OkHttpClient): ProbePlaylist {
        val url = "https://test-streams.mux.dev/x36xhzz/url_8/193039199_mp4_h264_aac_fhd_7.m3u8".toHttpUrl()
        return client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful && response.request.url == url) { "CDN playlist HTTP ${response.code}" }
            val playlist = parsePlaylist(url, checkNotNull(response.body).byteStream())
            check(playlist.segments.last().startMs >= 80_000 && playlist.segments.all { it.uri.isHttps && it.uri.host == url.host })
            Log.i("R34VCdn", "playlist host=${url.host} edge=${response.header("X-Served-By")} " +
                "protocol=${response.protocol} segments=${playlist.segments.size}")
            playlist
        }
    }

    /** Paced delivery to Media3. Requests still use the device's real HTTPS connection to the CDN. */
    private fun cdnBody(body: ResponseBody, paced: Boolean, firstByte: (() -> Unit)?): ResponseBody = object : ResponseBody() {
        private val pacedSource = object : ForwardingSource(body.source()) {
            private var started = 0L
            private var delivered = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (started == 0L) started = System.nanoTime()
                val size = super.read(sink, if (paced) minOf(byteCount, 16_384) else byteCount)
                if (size > 0) {
                    val first = delivered == 0L
                    delivered += size
                    val wait = if (paced) started + delivered * 2_000L - System.nanoTime() else 0L // 500,000 bytes/s
                    if (wait > 0) {
                        try { NANOSECONDS.sleep(wait) }
                        catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("Paced CDN read interrupted", interrupted)
                        }
                    }
                    if (first) firstByte?.invoke()
                }
                return size
            }
        }.buffer()

        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source() = pacedSource
    }

    // Accessed only on the player's application looper.
    private class RebufferObserver : Player.Listener {
        private var playingSince: Long? = null
        private var bufferingSince: Long? = null
        private val stalls = mutableListOf<Long>()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && playingSince == null) playingSince = SystemClock.elapsedRealtime()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playingSince == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> if (bufferingSince == null) bufferingSince = SystemClock.elapsedRealtime()
                Player.STATE_READY -> bufferingSince?.let {
                    stalls += SystemClock.elapsedRealtime() - it
                    bufferingSince = null
                }
            }
        }

        fun result(): String {
            check(bufferingSince == null) { "Playback reached the observation endpoint while buffering" }
            val elapsed = SystemClock.elapsedRealtime() - checkNotNull(playingSince)
            return "playElapsed=$elapsed stalls=${stalls.size} stallMs=${stalls.sum()} longestMs=${stalls.maxOrNull() ?: 0}"
        }
    }

    @Test fun compareProgressiveAndHls() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("playbackPerf") == "true")
        val repeats = args.getString("playbackPerfRepeats")?.toInt() ?: 3
        require(repeats > 0)
        val startupComparison = args.getString("playbackPerfScenario") == "startup"
        val hlsCacheComparison = args.getString("playbackPerfScenario") == "hls-cache"
        val scenario = args.getString("playbackPerfScenario")
        val cdnLater = scenario == "cdn-later"
        val shortRanges = scenario == "short-ranges"
        val cdnComparison = scenario == "cdn-high" || scenario == "cdn-policy" || cdnLater
        val policyComparison = scenario == "cdn-policy" || cdnLater || shortRanges
        val policies = if (cdnLater || shortRanges) listOf(CdnSeekPolicy.CURRENT, CdnSeekPolicy.LIMIT_PREFETCH) else CdnSeekPolicy.entries
        val expectSingleProbe = args.getString("playbackPerfExpectSingleProbe") == "true"
        ComposeFixture().use { fixture ->
            val directory = File(fixture.app.filesDir, "playback_probe")
            val media = if (cdnComparison) emptyMap() else directory.listFiles().orEmpty().filter(File::isFile).associate { it.name to it.readBytes() }
            val names = media.keys.filter { it.matches(Regex("segment\\d+\\.ts")) }.sorted()
            if (shortRanges) check(media.keys.containsAll(listOf("ranges.m3u8", "ranges.mp4"))) { "Missing short byte-range fixtures" }
            else if (!cdnComparison) check(media.keys.containsAll(listOf("source.mp4", "index.m3u8")) && names.isNotEmpty()) { "Missing playback_probe fixtures" }
            val cdnClient = fixture.app.network.client.newBuilder().cache(null).followRedirects(false).build()
            val cdn = if (cdnComparison) cdnClip(cdnClient) else null
            val activeCdnTrial = AtomicReference<CdnTrial?>()
            val cdnPaths = cdn?.let { it.segments.map { segment -> segment.uri }.toSet() + it.uri }.orEmpty()
            val requests = ConcurrentLinkedQueue<Request>()
            val completions = ConcurrentLinkedQueue<Pair<String, Long>>()
            val firstCdnBytes = ConcurrentLinkedQueue<Pair<String, Long>>()
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        val name = path.substringAfterLast('/')
                        val id = path.substringBeforeLast('/').substringAfterLast('/')
                        val bytes = if (name == "detail") {
                            val mediaName = when {
                                id.contains("-hls-range-") -> "ranges.m3u8"
                                id.contains("-hls-") -> "index.m3u8"
                                else -> "source.mp4"
                            }
                            val url = cdn?.uri ?: server.url("/playback-probe/$id/$mediaName")
                            val label = if (cdn != null) "1080p" else "360p"
                            val alternate = if (id.endsWith("-multiple")) ",video_alt_url:'$url?quality=720',video_alt_url_text:'720p'" else ""
                            "<h1>Playback probe</h1><script>var flashvars={video_id:'$id',video_url:'$url',video_url_text:'$label'$alternate}</script>".toByteArray()
                        } else media[name] ?: return MockResponse().setResponseCode(404)
                        val range = request.getHeader("Range")
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val end = (range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                        if (start !in 0..end) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                        val length = end - start + 1
                        requests.add(Request(path, range, now(), length))
                        val throttlePeriod = when {
                            id.contains("-hls-range-") -> 25L
                            id.endsWith("-rebuffer") -> 200L
                            else -> 80L
                        }
                        return MockResponse().setHeadersDelay(120, MILLISECONDS).throttleBody(16384, throttlePeriod, MILLISECONDS)
                            .setHeader("Content-Type", when (name) {
                                "detail" -> "text/html; charset=utf-8"
                                "index.m3u8", "ranges.m3u8" -> "application/vnd.apple.mpegurl"
                                "source.mp4", "ranges.mp4" -> "video/mp4"
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
                val client = cdnClient.newBuilder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val path = request.url.encodedPath
                        if (cdn != null && request.url in cdnPaths) {
                            val trial = activeCdnTrial.get() ?: throw IOException("No active CDN comparison")
                            requests.add(Request("/playback-probe/${trial.id}$path", request.header("Range"), now(), 0))
                            val response = chain.proceed(request)
                            val observeStart = request.url == cdn.segments.first().uri
                            if (observeStart || (cdnLater && request.url == cdn.at(11_200).uri)) {
                                Log.i("R34VCdn", "${trial.id} targetHttp=${response.code} edge=${response.header("X-Served-By")} " +
                                    "cache=${response.header("X-Cache")} length=${response.body?.contentLength()}")
                            }
                            val body = response.body
                            return@addInterceptor if ((trial.weak || observeStart) && request.url != cdn.uri && response.isSuccessful && body != null) {
                                response.newBuilder().body(cdnBody(body, trial.weak, if (observeStart) {
                                    { firstCdnBytes.add(trial.id to now()) }
                                } else null)).build()
                            } else response
                        }
                        if (!path.startsWith("/playback-probe/")) throw IOException("Only local playback fixtures are available")
                        chain.proceed(request.newBuilder().url(server.url(path)).build())
                    }
                    .eventListener(object : EventListener() {
                        override fun callEnd(call: Call) {
                            val url = call.request().url
                            val path = if (url in cdnPaths) {
                                val trial = activeCdnTrial.get() ?: return
                                "/playback-probe/${trial.id}${url.encodedPath}"
                            } else url.encodedPath
                            completions.add(path to now())
                        }
                    }).build()
                clientField.set(app.network, client)
                // The bandwidth meter posts an initial network type update on the main looper.
                // Drain that update before resetting the estimate for the first trial.
                main { app.linkSpeed }
                fixture.ins.waitForIdleSync()
                val profiles = if (policyComparison) {
                    val links = if (shortRanges) listOf(false) else listOf(false, true)
                    links.flatMap { weak -> policies.map { policy ->
                        Profile(true, false, exercise = if (shortRanges) Exercise.SHORT_RANGES else Exercise.CDN, weakCdn = weak, cdnPolicy = policy)
                    } }
                } else if (cdnComparison) {
                    listOf(
                        Profile(true, false, seekCache = false, exercise = Exercise.CDN),
                        Profile(true, false, exercise = Exercise.CDN),
                        Profile(true, false, seekCache = false, exercise = Exercise.CDN, weakCdn = true),
                        Profile(true, false, exercise = Exercise.CDN, weakCdn = true),
                    )
                } else if (hlsCacheComparison) {
                    listOf(
                        Profile(true, false, exercise = Exercise.HLS_CACHE, seekCache = false),
                        Profile(true, false, exercise = Exercise.HLS_CACHE),
                        Profile(true, false, exercise = Exercise.HLS_CACHE, streamCache = true),
                    )
                } else if (startupComparison) {
                    listOf(
                        Profile(false, false, exercise = Exercise.STARTUP), Profile(true, false, exercise = Exercise.STARTUP),
                        Profile(false, false, exercise = Exercise.REBUFFER), Profile(true, false, exercise = Exercise.REBUFFER),
                    )
                } else {
                    listOf(Profile(false, false), Profile(true, false), Profile(false, true), Profile(true, true),
                        Profile(false, true, seekCache = false), Profile(true, true, streamCache = true))
                }
                val defaultSession = if (policyComparison) main { app.playback.hlsSeekSessionFactory } else null
                val experiments = if (policyComparison) policies.filter { it != CdnSeekPolicy.CURRENT }
                    .associateWith { CdnSeekExperiment(app, it) } else emptyMap()
                fun runTrial(round: Int, profile: Profile) {
                    val id = "${fixture.nonce}-$round-${profile.name}"
                    val path = "/playback-probe/$id/"
                    val playlist = cdn ?: if (shortRanges) {
                        checkNotNull(media[profile.media]).inputStream().use { parsePlaylist(server.url(path + profile.media), it) }.also {
                            check(it.segments.size > 10 && it.segments.all { s -> s.uri == it.segments.first().uri && s.length > 0 })
                        }
                    } else null
                    val segments = playlist?.segments?.map { it.uri.toString() }?.distinct() ?: names.map { server.url(path + it).toString() }
                    val experiment = experiments[profile.cdnPolicy]
                    if (defaultSession != null) main {
                        app.playback.hlsSeekSessionFactory = if (experiment == null) defaultSession else {
                            { experiment.startSession(checkNotNull(playlist)) }
                        }
                    }
                    if (cdn != null) activeCdnTrial.set(CdnTrial(id, profile.weakCdn))
                    try {
                        measure(fixture, profile, id, segments, {
                            requests.filter { it.path.startsWith(path) }
                        }, { name -> completions.firstOrNull { it.first == if (name.startsWith('/')) name else path + name }?.second },
                            expectProbe = !profile.knownSpeed && (profile.multipleQualities || expectSingleProbe), report = round > 0,
                            experiment = experiment, firstCdnByteAt = { firstCdnBytes.firstOrNull { it.first == id }?.second },
                            playlist = playlist, backPosition = if (cdnLater || shortRanges) 11_200 else 1_200)
                    } finally { activeCdnTrial.set(null) }
                }
                try {
                    // Warm the UI and decoder for both formats before collecting timings.
                    if (policyComparison) {
                        experiments.values.forEach { check(it.cache.keys.isEmpty()) }
                        runTrial(0, profiles.first())
                    } else if (cdnComparison) {
                        profiles.take(2).forEach { runTrial(0, it) }
                    } else if (hlsCacheComparison) {
                        profiles.forEach { runTrial(0, it) }
                    } else {
                        val warmup = if (startupComparison) Exercise.STARTUP else Exercise.SEEK
                        runTrial(0, Profile(false, true, exercise = warmup))
                        runTrial(0, Profile(true, true, exercise = warmup))
                    }
                    repeat(repeats) { round ->
                        val order = if (policyComparison) {
                            val width = policies.size
                            val groups = profiles.chunked(width).let { if (round % 2 == 0) it else it.reversed() }
                            groups.flatMap { group -> group.drop(round % width) + group.take(round % width) }
                        } else if (round % 2 == 0) profiles else profiles.reversed()
                        order.forEach { runTrial(round + 1, it) }
                    }
                    if (startupComparison) {
                        runTrial(1, Profile(false, false, exercise = Exercise.STARTUP, multipleQualities = true))
                        runTrial(1, Profile(false, true, exercise = Exercise.STARTUP, multipleQualities = true))
                    }
                } finally {
                    if (defaultSession != null) main { app.playback.hlsSeekSessionFactory = defaultSession }
                    experiments.values.forEach(CdnSeekExperiment::close)
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    private fun measure(fixture: ComposeFixture, profile: Profile, id: String, segments: List<String>,
                        requests: () -> List<Request>, completedAt: (String) -> Long?, expectProbe: Boolean, report: Boolean,
                        experiment: CdnSeekExperiment?, firstCdnByteAt: () -> Long?, playlist: ProbePlaylist?, backPosition: Long) {
        val app = fixture.app
        val site = SiteRegistry.R34V
        val video = Video(id, "Playback probe", "${site.base}/playback-probe/$id/detail", site = site.id)
        val cdnRun = profile.exercise == Exercise.CDN
        val rangeRun = profile.exercise == Exercise.SHORT_RANGES
        val policyRun = cdnRun || rangeRun
        val label = if (cdnRun) "1080p" else "360p"
        val key = app.playback.key(video, label)
        val target = playlist?.at(backPosition)
        val targetPath = target?.uri?.encodedPath ?: if (profile.hls) segments.first().toHttpUrl().encodedPath else ""
        val frames = ConcurrentLinkedQueue<Frame>()
        val rebuffer = RebufferObserver()
        val cacheReads = ConcurrentLinkedQueue<CacheEvent>()
        val cacheWrites = ConcurrentLinkedQueue<CacheEvent>()
        val cacheRemovals = ConcurrentLinkedQueue<CacheEvent>()
        // The startup benchmark can also run against the historical app APK.
        val sessionCacheField = if (profile.hlsSession) runCatching { field(app.playback, "hlsSeekCache") }.getOrNull() else null
        val usesSession = sessionCacheField != null
        if (profile.exercise == Exercise.HLS_CACHE || policyRun) check(usesSession == profile.hlsSession)
        val cacheField = sessionCacheField ?: field(app.playback, if (profile.hls) "streamCache\$delegate" else "seekCache\$delegate")
        val cache = experiment?.cache ?: (cacheField.get(app.playback) as Lazy<*>).value as Cache
        var sessionPrefix: String? = null
        var cacheKeys = if (usesSession) emptyList() else if (profile.hls) {
            segments.map { "$key|hls|$it" } + "$key|hls|${segments.first().substringBeforeLast('/')}/index.m3u8"
        } else listOf(key)
        val cacheListener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) { cacheWrites.add(CacheEvent(span, now())) }
            override fun onSpanRemoved(cache: Cache, span: CacheSpan) { cacheRemovals.add(CacheEvent(span, now())) }
            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
                cacheReads.add(CacheEvent(newSpan, now()))
            }
        }
        cacheKeys.forEach { check(cache.addListener(it, cacheListener).isEmpty()) }
        val firstFrame = object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                frames.add(Frame(renderTimeMs, eventTime.currentPlaybackPositionMs))
            }
        }
        main {
            app.library.set("quality", label)
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
        var writeReport: String? = null
        var observation: Closeable? = null
        try {
            val player = await("engine for $id") {
                main {
                    (playerField.get(activity) as ExoPlayer?)?.also {
                        check(it.playbackState != Player.STATE_READY) { "Missed first-frame listener for $id" }
                        it.addAnalyticsListener(firstFrame)
                        if (profile.exercise == Exercise.REBUFFER || policyRun) it.addListener(rebuffer)
                        observation = experiment?.observe(it, checkNotNull(playlist))
                    }
                }
            }
            if (usesSession) {
                val session = main { checkNotNull(field(activity, "hlsSeekSession").get(activity)) }
                val prefix = field(session, "prefix").get(session) as String
                sessionPrefix = prefix
                cacheKeys = segments.map { prefix + it }
                cacheKeys.forEach { check(cache.addListener(it, cacheListener).isEmpty()) }
            }
            val startup = await("first frame for $id") { frames.peek() }
            val detailEnd = checkNotNull(completedAt("detail"))
            val probes = requests().filter { it.probe }
            check(probes.size == if (expectProbe) 1 else 0) { "Unexpected startup probe count for $id: ${requests()}" }
            val probe = probes.singleOrNull()
            val probeEnd = probe?.let { checkNotNull(completedAt(profile.media)) }
            val mediaRequest = requests().first { !it.detail && !it.probe }
            val firstMedia = mediaRequest.start
            val firstSegment = if (profile.hls) requests().first {
                if (playlist != null) it.reads(playlist.segments.first()) else it.path.endsWith(targetPath)
            }.start else firstMedia
            val startupStages = if (cdnRun) {
                val manifestEnd = checkNotNull(completedAt(mediaRequest.path)) { "CDN playlist completion missing for $id" }
                val firstByte = checkNotNull(firstCdnByteAt()) { "CDN first byte missing for $id" }
                check(launched <= detailEnd && detailEnd <= firstMedia && firstMedia <= manifestEnd &&
                    manifestEnd <= firstSegment && firstSegment <= firstByte && firstByte <= startup.at) { "Invalid CDN startup timeline for $id" }
                "detailMs=${detailEnd - launched} prepareMs=${firstMedia - detailEnd} playlistMs=${manifestEnd - firstMedia} " +
                    "playlistToSegmentMs=${firstSegment - manifestEnd} segmentToByteMs=${firstByte - firstSegment} " +
                    "byteToFrameMs=${startup.at - firstByte}"
            } else ""

            if (profile.exercise == Exercise.STARTUP || profile.exercise == Exercise.REBUFFER) {
                val uri = main { checkNotNull(player.currentMediaItem?.localConfiguration?.uri) }
                check(uri.lastPathSegment == profile.media && uri.query == null) { "Unexpected stream selected for $id: $uri" }
                val stallStats = if (profile.exercise == Exercise.REBUFFER) {
                    await("16 seconds of weak-link playback for $id", timeoutMs = 45_000) {
                        main { true.takeIf { player.currentPosition >= 16_000 && player.isPlaying } }
                    }
                    main { rebuffer.result() }
                } else ""
                check(main { playerField.get(activity) === player && player.playerError == null }) { "Playback restarted or failed for $id" }
                if (report) Log.i("R34VSingleStart", "$id first=${startup.at - launched} detail=${detailEnd - launched} " +
                    "probes=${probes.size} probeMs=${if (probe != null) probeEnd!! - probe.start else 0} " +
                    "mediaToFrame=${startup.at - firstMedia} $stallStats")
                return
            }

            val stallStats = if (policyRun) {
                check(main { player.videoSize.width == (if (cdnRun) 1920 else 640) && player.videoSize.height == (if (cdnRun) 1080 else 360) })
                await("16 seconds of playback for $id", timeoutMs = 75_000) {
                    main { if (player.currentPosition >= 16_000 && player.isPlaying) rebuffer.result() else null }
                }
            } else {
                await("initial playback for $id") { main { true.takeIf { player.currentPosition >= 2700 } } }
                ""
            }
            val forwardPosition = when {
                cdnRun -> 72_000L
                rangeRun -> 32_000L
                else -> 22_000L
            }
            val forward = main {
                check(player.bufferedPosition < forwardPosition) { "Forward jump is already buffered for $id" }
                experiment?.seekTo(forwardPosition)
                now().also { player.seekTo(forwardPosition) }
            }
            await("forward frame for $id", timeoutMs = 45_000) {
                frames.firstOrNull { it.at > forward && it.position in forwardPosition..(forwardPosition + 1000) }
            }
            await("forward playback for $id") { main { true.takeIf { player.currentPosition >= forwardPosition + 500 } } }
            val targetKey = sessionPrefix?.let { prefix -> target?.let { prefix + it.uri } }
            fun cachedTargetBytes() = if (targetKey != null && target != null) {
                cache.getCachedSpans(targetKey).sumOf { target.overlap(it.position, it.length) }
            } else 0L
            val targetBytes = cachedTargetBytes()
            val targetSkipped = target?.let { experiment?.skippedWritesFor(it) } ?: 0
            val retainedBytes = if (policyRun && usesSession) cache.cacheSpace else 0L
            val back = main {
                experiment?.seekTo(backPosition)
                now().also { player.seekTo(backPosition) }
            }
            val restored = await("backward frame for $id", timeoutMs = 45_000) {
                frames.firstOrNull { it.at >= back && it.position in backPosition..(backPosition + 750) }
            }
            val samePlayer = main { playerField.get(activity) === player }
            val hits = cacheReads.filter { it.at in back..restored.at }.map { it.span }
                .distinctBy { it.key to it.position }
            val fetches = requests().filter { it.start in back..restored.at }
            if (policyRun) {
                check(samePlayer)
                check(retainedBytes <= PlaybackStore.HLS_SEEK_CACHE_BYTES)
                checkNotNull(target)
                val targetHits = hits.count { it.key == targetKey && target.overlap(it.position, it.length) > 0 }
                val targetRequests = fetches.filter { it.reads(target) }
                val targetFetches = targetRequests.size
                check(targetHits > 0 || targetFetches > 0) { "The rewind reused buffered samples for $id" }
                if (!usesSession) check(targetHits == 0 && targetFetches > 0)
                await("playback after returning for $id", timeoutMs = 45_000) {
                    main { true.takeIf { player.currentPosition >= backPosition + 3800 && player.isPlaying } }
                }
                val targetAfter = cachedTargetBytes()
                if (rangeRun) {
                    if (profile.cdnPolicy == CdnSeekPolicy.LIMIT_PREFETCH) {
                        check(targetSkipped > 0 && targetBytes == 0L && targetHits == 0 && targetFetches > 0) { "Unexpected prefetched target state for $id" }
                        val request = targetRequests.single()
                        check(request.range == "bytes=${target.offset}-${target.offset + target.length - 1}" && request.length.toLong() == target.length) {
                            "Unexpected target HTTP range for $id: $request"
                        }
                        check(targetAfter == target.length) { "Revisited byte range was not stored for $id" }
                    } else check(targetBytes == target.length && targetHits > 0 && targetFetches == 0)
                }
                val removed = cacheRemovals.count { it.at < back }
                if (report) writeReport = "$id first=${startup.at - launched} $startupStages back=${restored.at - back} " +
                    "$stallStats targetHits=$targetHits targetHttp=$targetFetches targetBytes=$targetBytes " +
                    "backTo=$backPosition targetOffset=${target.offset} targetLength=${target.length} targetSkipped=$targetSkipped targetAfter=$targetAfter " +
                    "retainedBytes=$retainedBytes evictionsBeforeBack=$removed skippedWrites=${experiment?.skippedWrites ?: 0}"
            } else if (profile.exercise == Exercise.HLS_CACHE) {
                check(samePlayer)
                val targetHits = hits.count { it.key.endsWith("/segment00.ts") }
                val targetFetches = fetches.count { it.path.endsWith("/segment00.ts") }
                check((targetHits > 0) == (usesSession || profile.streamCache))
                check((targetFetches > 0) == (targetHits == 0))
                await("playback after returning for $id") { main { true.takeIf { player.currentPosition >= 5000 } } }
                val startBytes = cacheWrites.filter { it.at <= startup.at }.sumOf { it.span.length }
                if (report) writeReport = "$id first=${startup.at - launched} back=${restored.at - back} " +
                    "targetHits=$targetHits targetHttp=$targetFetches startupCommitted=$startBytes"
            } else if (report) Log.i("R34VPlaybackPerf", "$id first=${startup.at - launched} detail=${detailEnd - launched} " +
                "probe=${if (probe != null) probeEnd!! - probe.start else 0} probeBytes=${probe?.length ?: 0} " +
                "preMedia=${firstMedia - detailEnd} mediaToFrame=${startup.at - firstMedia} " +
                "playlistToSegment=${firstSegment - firstMedia} back=${restored.at - back} " +
                "cacheSpansRead=${hits.size} backHttp=${fetches.size} sameEngine=$samePlayer " +
                "cacheSpans=${hits.joinToString { it.key.substringAfterLast('/') + ":" + it.position + "+" + it.length }} " +
                "backRequests=${fetches.joinToString { it.path.substringAfterLast('/') + ":" + it.range.orEmpty() }}")
        } finally {
            main { observation?.close(); activity.finish() }
            await("close for $id") { main { true.takeIf { activity.isDestroyed && playerField.get(activity) == null } } }
            sessionPrefix?.let { prefix ->
                await("session cache cleanup for $id") { true.takeIf { cache.keys.none { it.startsWith(prefix) } } }
            }
            writeReport?.let {
                Log.i(when { cdnRun -> "R34VCdnPerf"; rangeRun -> "R34VRangePerf"; else -> "R34VHlsSeek" },
                    "$it bytesWritten=${cacheWrites.sumOf { event -> event.span.length }} commits=${cacheWrites.size}")
            }
            cacheKeys.forEach {
                cache.removeListener(it, cacheListener)
                if (!usesSession) cache.removeResource(it)
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun <T> main(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private fun <T : Any> await(description: String, timeoutMs: Long = 20_000, observe: () -> T?): T {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            observe()?.let { return it }
            SystemClock.sleep(12)
        }
        error("Timed out waiting for $description")
    }
}
