@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.playback.HlsSeekSession
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HlsSeekSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val cacheDir = TemporaryFolder(context.cacheDir)

    @Test fun vodRangesAndResourceUrisStayIsolatedUntilTheSessionEnds() = withCache(1024) { cache ->
        val first = Uri.parse("https://media.invalid/a/segment.m4s?token=1")
        val second = first.buildUpon().encodedQuery("token=2").build()
        val other = Uri.parse("https://media.invalid/b/segment.m4s?token=1")
        var calls = 0
        var bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val upstream = DataSource.Factory {
            val data = ByteArrayDataSource(bytes)
            object : DataSource by data {
                override fun open(dataSpec: DataSpec) = data.open(dataSpec).also { calls++ }
            }
        }
        session(cache).use { session ->
            parsePlaylist(session, ended = true)
            val source = session.upstream(upstream)
            assertArrayEquals(byteArrayOf(1, 2, 3), read(source, first, 0, 3))
            assertArrayEquals(byteArrayOf(4, 5, 6), read(source, first, 3, 3))
            assertArrayEquals(bytes, read(source, first, length = 6))
            assertEquals(2, calls)

            bytes = byteArrayOf(11, 12, 13, 14, 15, 16)
            assertArrayEquals(bytes, read(source, second))
            bytes = byteArrayOf(21, 22, 23, 24, 25, 26)
            assertArrayEquals(bytes, read(source, other))
            session(cache).use { another ->
                parsePlaylist(another, ended = true)
                assertArrayEquals(bytes, read(another.upstream(upstream), first))
                assertEquals(4, cache.keys.size)
            }
            assertEquals(3, cache.keys.size)

            val cacheRead = DataSourceInputStream(source.createDataSource(), DataSpec(first))
            assertEquals(1, cacheRead.read())
            session.close()
            assertEquals(3, cache.keys.size)
            cacheRead.close()
            assertTrue(cache.keys.isEmpty())
        }
    }

    @Test fun onlyEndedPlaylistsUseCacheAndOlderSpansAreEvictedWithinTheCap() = withCache(3L * 1024 * 1024) { cache ->
        val uri = Uri.parse("https://media.invalid/one.ts")
        val other = Uri.parse("https://media.invalid/two.ts")
        val bytes = ByteArray(2_500_000) { 7 }
        val upstream = DataSource.Factory { ByteArrayDataSource(bytes) }
        session(cache).use { live ->
            val source = live.upstream(upstream)
            assertEquals(128, read(source, uri, length = 128).size)
            parsePlaylist(live, ended = false)
            assertEquals(128, read(source, uri, length = 128).size)
            parsePlaylist(live, ended = true)
            assertEquals(128, read(source, uri, length = 128).size)
            assertTrue(cache.keys.isEmpty())
        }
        session(cache).use { vod ->
            parsePlaylist(vod, ended = true)
            val source = vod.upstream(upstream)
            assertEquals(bytes.size, read(source, uri).size)
            assertEquals(bytes.size, read(source, other).size)
            assertTrue(cache.cacheSpace in bytes.size.toLong()..(3L * 1024 * 1024))
            val earlier = cache.keys.singleOrNull { it.endsWith(uri.toString()) }
            assertTrue(earlier == null || !cache.isCached(earlier, 0, bytes.size.toLong()))
        }
    }

    @Test fun writeAdmissionKeepsCachedReadsAndSkipsWritingOnAMiss() = withCache(1024) { cache ->
        val watched = Uri.parse("https://media.invalid/watched.ts")
        val ahead = Uri.parse("https://media.invalid/ahead.ts")
        val stored = byteArrayOf(1, 2, 3)
        val remote = byteArrayOf(4, 5, 6)
        var mayWrite = true
        var bytes = stored
        HlsSeekSession(lazyOf(cache), allowWrite = { mayWrite }) {}.use { session ->
            parsePlaylist(session, ended = true)
            val source = session.upstream { ByteArrayDataSource(bytes) }
            assertArrayEquals(stored, read(source, watched))
            mayWrite = false
            bytes = remote
            assertArrayEquals(stored, read(source, watched))
            assertArrayEquals(remote, read(source, ahead))
            assertEquals(stored.size.toLong(), cache.cacheSpace)
        }
    }

    @Test fun byteRangeAdmissionRecognizesRetriesWithoutConfusingAdjacentSegments() {
        val url = "https://media.invalid/shared.mp4".toHttpUrl()
        val init = ProbeSegment(url, -1, 0, 100)
        val early = ProbeSegment(url, 0, 100, 100)
        val later = ProbeSegment(url, 2_000, 200, 100)
        val playlist = ProbePlaylist("https://media.invalid/playlist.m3u8".toHttpUrl(), listOf(early, later), setOf(init))
        fun spec(offset: Long, length: Long) = DataSpec.Builder().setUri(url.toString()).setPosition(offset).setLength(length).build()

        assertTrue(playlist.isInitialization(spec(0, 100)))
        assertEquals(early, playlist.segmentFor(spec(100, 100)))
        assertEquals(later, playlist.segmentFor(spec(220, 80)))
        assertEquals(null, playlist.segmentFor(spec(150, 100)))
        assertEquals(null, playlist.segmentFor(spec(200, -1)))
        assertEquals(0L, early.overlap(200, 100))
        assertEquals(30L, later.overlap(170, 60))
        val ambiguous = ProbePlaylist(playlist.uri, listOf(early), setOf(init.copy(length = -1)))
        assertEquals(null, ambiguous.segmentFor(spec(100, 100)))
        assertTrue(!ambiguous.isInitialization(spec(100, 100)))
    }

    @Test fun playedQuotaEvictsPrefetchFirstAndStillEnforcesTotalCapacity() = withCache(10,
        evictor = PlayedQuotaEvictor(10, 6) { it.endsWith("/watched.ts") }) { cache ->
        val watched = Uri.parse("https://media.invalid/watched.ts")
        val ahead = Uri.parse("https://media.invalid/ahead.ts")
        val later = Uri.parse("https://media.invalid/later.ts")
        val bytes = byteArrayOf(1, 2, 3, 4)
        session(cache).use { session ->
            parsePlaylist(session, ended = true)
            val source = session.upstream { ByteArrayDataSource(bytes) }
            read(source, watched)
            read(source, ahead)
            read(source, later)
            assertEquals(8L, cache.cacheSpace)
            assertTrue(cache.keys.any { it.endsWith("/watched.ts") && cache.isCached(it, 0, 4) })
            assertTrue(cache.keys.none { it.endsWith("/ahead.ts") && cache.isCached(it, 0, 4) })
        }
    }

    private fun withCache(maxBytes: Long, evictor: CacheEvictor = LeastRecentlyUsedCacheEvictor(maxBytes), test: (SimpleCache) -> Unit) {
        val cache = SimpleCache(File(cacheDir.root, "spans"), evictor, StandaloneDatabaseProvider(context))
        try { test(cache) } finally { cache.release() }
    }

    private fun session(cache: SimpleCache) = HlsSeekSession(lazyOf(cache)) { prefix ->
        cache.keys.filter { it.startsWith(prefix) }.forEach(cache::removeResource)
    }

    private fun parsePlaylist(session: HlsSeekSession, ended: Boolean) {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\none.ts\n" + if (ended) "#EXT-X-ENDLIST\n" else ""
        session.playlistParsers.createPlaylistParser().parse(Uri.parse("https://media.invalid/index.m3u8"), playlist.byteInputStream())
    }

    private fun read(source: DataSource.Factory, uri: Uri, offset: Long = 0, length: Long = -1): ByteArray {
        val spec = DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build()
        return DataSourceInputStream(source.createDataSource(), spec).use { it.readBytes() }
    }
}
