@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.StorageRoot
import com.r34v.r34v.playback.DownloadCaches
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadCacheTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: LibraryStore
    private lateinit var storage: StorageRoot
    private val owners = mutableListOf<DownloadCaches>()
    private val databases = mutableListOf<SQLiteDatabase>()
    private val bytes = ByteArray(1536) { (it % 251).toByte() }

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "download-caches-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
        }
        store = LibraryStore(context, root.name)
        storage = StorageRoot(context, store, File(root, "shared"))
    }

    @After fun cleanUp() {
        owners.forEach { it.close() }
        databases.forEach { it.close() }
        store.prefs.edit().clear().commit()
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    private fun database(): DatabaseProvider {
        val db = SQLiteDatabase.openOrCreateDatabase(File(root, "database-${databases.size}.db"), null)
        databases += db
        return object : DatabaseProvider {
            override fun getReadableDatabase() = db
            override fun getWritableDatabase() = db
        }
    }

    private fun owner(database: DatabaseProvider = database()) = DownloadCaches(context, storage, database).also { owners += it }
    private fun spec(key: String, position: Long = 0, length: Long = bytes.size.toLong()) =
        DataSpec.Builder().setUri("https://example.invalid/neutral.bin").setKey(key).setPosition(position).setLength(length).build()

    private fun write(cache: SimpleCache, key: String, position: Long = 0, length: Long = bytes.size.toLong()) {
        val factory = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(bytes) })
        CacheWriter(factory.createDataSource(), spec(key, position, length), null, null).cache()
        cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
    }

    private fun read(cache: SimpleCache, key: String): ByteArray {
        val source = CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(null).createDataSource()
        val output = ByteArrayOutputStream()
        try {
            source.open(spec(key))
            val buffer = ByteArray(257)
            while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
        } finally { source.close() }
        return output.toByteArray()
    }

    @Test fun cacheLocalIndexSurvivesAFreshPrivateDatabase() {
        val shared = storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath
        val first = owner()
        write(first.cache(shared), "shared:reinstall:720p")
        first.close()
        val reinstalled = owner()
        assertTrue(reinstalled.cached("shared:reinstall:720p", shared))
        assertArrayEquals(bytes, read(reinstalled.readCache("shared:reinstall:720p", shared), "shared:reinstall:720p"))
    }

    @Test fun aNeutralVideoDecodesAndSeeksOfflineAfterAFreshDatabase() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val clip = ins.context.assets.open("test-pattern.mp4").use { it.readBytes() }
        val shared = storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath
        val key = "neutral:reinstall:720p"
        val first = owner()
        val before = first.cache(shared)
        val spec = DataSpec.Builder().setUri("https://example.invalid/sample.mp4").setKey(key).setLength(clip.size.toLong()).build()
        val writer = CacheDataSource.Factory().setCache(before)
            .setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(clip) })
        CacheWriter(writer.createDataSource(), spec, null, null).cache()
        before.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), clip.size.toLong()))
        first.close()

        val reinstalled = owner()
        assertTrue(reinstalled.cached(key, shared))
        val offline = CacheDataSource.Factory().setCache(reinstalled.readCache(key, shared))
            .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(null)
        var player: ExoPlayer? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        fun awaitReady(minimumPosition: Long = 0) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                ins.runOnMainSync {
                    val engine = requireNotNull(player)
                    assertNull("Offline playback failed", engine.playerError)
                    ready = engine.playbackState == Player.STATE_READY && engine.videoSize.width > 0 && engine.currentPosition >= minimumPosition
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Offline decoder did not become ready after a fresh database", ready)
        }
        try {
            ins.runOnMainSync {
                texture = SurfaceTexture(0)
                surface = Surface(requireNotNull(texture))
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(offline)).build().apply {
                    setVideoSurface(surface)
                    setMediaItem(MediaItem.Builder().setUri("https://example.invalid/sample.mp4").setCustomCacheKey(key).build())
                    prepare()
                }
            }
            awaitReady()
            var target = 0L
            ins.runOnMainSync {
                val engine = requireNotNull(player)
                assertTrue(engine.videoSize.width > 0)
                assertTrue(engine.duration > 0)
                target = minOf(2000L, engine.duration / 2)
                engine.seekTo(target)
            }
            awaitReady(target)
        } finally {
            ins.runOnMainSync { player?.release(); surface?.release(); texture?.release() }
        }
    }

    @Test fun anOlderDatabaseIndexIsConvertedBeforeThatDatabaseIsLost() {
        val db = database()
        val layout = storage.ensure(StorageRoot.Mode.SHARED)
        val old = SimpleCache(layout.videoCache, NoOpCacheEvictor(), db)
        try { write(old, "old:video:720p") } finally { old.release() }
        val converted = owner(db)
        assertTrue(converted.cached("old:video:720p", layout.root.absolutePath))
        converted.close()
        val reinstalled = owner()
        assertArrayEquals(bytes, read(reinstalled.readCache("old:video:720p", layout.root.absolutePath), "old:video:720p"))
    }

    @Test fun aMissingMiddleSpanIsNotACompleteOfflineVideo() {
        val current = owner()
        val cache = current.cache()
        write(cache, "partial:video:720p", 0, 512)
        write(cache, "partial:video:720p", 512, 512)
        write(cache, "partial:video:720p", 1024, 512)
        assertTrue(current.cached("partial:video:720p"))
        val middle = cache.getCachedSpans("partial:video:720p").single { it.position == 512L }
        assertTrue(middle.file!!.delete())
        assertFalse(current.cached("partial:video:720p"))
    }

    @Test fun readsFindPrivateAndSharedKeysWithoutChangingTheChosenLocation() {
        storage.choose(StorageRoot.Mode.PRIVATE)
        val current = owner()
        val shared = storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath
        write(current.cache(), "private:video:720p")
        write(current.cache(shared), "shared:video:720p")
        assertArrayEquals(bytes, read(current.readCache("private:video:720p"), "private:video:720p"))
        assertArrayEquals(bytes, read(current.readCache("shared:video:720p"), "shared:video:720p"))
        assertEquals(StorageRoot.Mode.PRIVATE, storage.effective())
    }

    @Test fun lookupsDoNotCreateAnAlternateCacheOrAcceptAnUnapprovedRoot() {
        val current = owner()
        assertFalse(current.cached("missing:video:720p"))
        assertFalse(storage.layoutOf(StorageRoot.Mode.SHARED).videoCache.exists())
        val unrelated = File(root, "unapproved")
        assertTrue(runCatching { current.cache(unrelated.absolutePath) }.isFailure)
        assertFalse(unrelated.exists())
    }

    @Test fun migrationMergesIntoAnExistingSharedCacheAndKeepsItsKey() {
        storage.choose(StorageRoot.Mode.PRIVATE)
        val current = owner()
        val shared = storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath
        write(current.cache(), "private:new:720p")
        write(current.cache(shared), "shared:existing:1080p")
        current.copyToShared()
        assertTrue(current.cached("private:new:720p", shared))
        assertTrue(current.cached("shared:existing:1080p", shared))
        current.close()
        val reinstalled = owner()
        assertArrayEquals(bytes, read(reinstalled.readCache("private:new:720p", shared), "private:new:720p"))
        assertArrayEquals(bytes, read(reinstalled.readCache("shared:existing:1080p", shared), "shared:existing:1080p"))
    }

    @Test fun removingARestoredKeyDeletesBothCopiesAndKeepsOtherQualities() {
        val current = owner()
        val shared = storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath
        write(current.cache(), "same:video:720p")
        write(current.cache(shared), "same:video:720p")
        write(current.cache(shared), "same:video:1080p")
        current.remove("same:video:720p")
        assertFalse(current.cached("same:video:720p", storage.layout().root.absolutePath))
        assertFalse(current.cached("same:video:720p", shared))
        assertTrue(current.cached("same:video:1080p", shared))
    }
}
