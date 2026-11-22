@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.r34v.r34v.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.Closeable
import java.util.UUID

/** One media source owns this namespace, even when the same work is opened twice. */
internal class HlsSeekSession(
    private val cache: Lazy<SimpleCache>,
    private val discard: (String) -> Unit,
) : Closeable {
    private val prefix = "${UUID.randomUUID()}|"
    private var vod: Boolean? = null
    private var closed = false
    private var leases = 0

    val playlistParsers: HlsPlaylistParserFactory = object : HlsPlaylistParserFactory {
        private val delegate = DefaultHlsPlaylistParserFactory()

        override fun createPlaylistParser() = observe(delegate.createPlaylistParser())

        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?) =
            observe(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))
    }

    private fun observe(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
        parser.parse(uri, input).also { if (it is HlsMediaPlaylist) updateVod(it.hasEndTag) }
    }

    @Synchronized private fun updateVod(ended: Boolean) {
        // Playlists that refresh can reuse segment URLs for new bytes.
        vod = vod != false && ended
    }

    @Synchronized private fun acquire(): Boolean {
        if (closed || vod != true) return false
        leases++
        return true
    }

    @Synchronized private fun release() {
        leases--
        if (closed && leases == 0) discard(prefix)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (leases == 0) discard(prefix)
    }

    /** Only used for media chunks, behind the download and global stream cache read paths. */
    fun upstream(network: DataSource.Factory): DataSource.Factory {
        val cached by lazy {
            val store = cache.value
            CacheDataSource.Factory().setCache(store)
                .setCacheKeyFactory { prefix + it.uri }
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(store).setFragmentSize(2L * 1024 * 1024))
                .setUpstreamDataSourceFactory(network)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        return DataSource.Factory {
            object : DataSource {
                private var source: DataSource? = null
                private var leased = false
                private val listeners = mutableListOf<TransferListener>()

                override fun addTransferListener(listener: TransferListener) {
                    listeners += listener
                    source?.addTransferListener(listener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    leased = acquire()
                    try {
                        val dataSource = (if (leased) cached else network).createDataSource()
                        source = dataSource
                        listeners.forEach(dataSource::addTransferListener)
                        val spec = if (leased) dataSpec.buildUpon()
                            .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build() else dataSpec
                        return dataSource.open(spec)
                    } catch (failure: Exception) {
                        runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(source).read(buffer, offset, length)
                override fun getUri() = source?.uri
                override fun getResponseHeaders() = source?.responseHeaders.orEmpty()

                override fun close() {
                    try {
                        source?.close()
                    } finally {
                        source = null
                        if (leased) {
                            leased = false
                            release()
                        }
                    }
                }
            }
        }
    }
}
