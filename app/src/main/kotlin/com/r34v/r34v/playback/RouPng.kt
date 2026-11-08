@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * rou.video 的媒体包装拆壳。
 *
 * 该站的清单和分片都不是普通响应：每个响应体都是一张合法 PNG，真正的数据放在自定义
 * `roUd` 分块（块类型 `0x726f5564`）里，首字节是标志位——`1` 表示其后是 zlib 压缩过的
 * 清单或 MPEG-TS，`0` 表示原样。站点自己的播放器靠 shaka 的响应过滤器解包，所以浏览器
 * 直接看是图片，Media3 拿到只会解析失败。
 *
 * 这里在数据源层按内容识别：PNG 签名 + `roUd` 分块才拆壳，其它响应原样放行，所以同一个
 * 站点上的封面图不会被误处理。
 */
object RouPng {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val PAYLOAD_TYPE = 0x726f5564
    private const val DEFLATED = 1
    private const val MAX_BYTES = 64 * 1024 * 1024

    fun isWrapped(prefix: ByteArray): Boolean =
        prefix.size >= signature.size && signature.indices.all { prefix[it] == signature[it] }

    /** 返回 PNG 里 `roUd` 分块的内层字节；没有这个分块就返回 null。 */
    fun unwrap(body: ByteArray): ByteArray? {
        if (!isWrapped(body)) return null
        var index = signature.size
        while (index + 8 <= body.size) {
            val length = readInt(body, index)
            val type = readInt(body, index + 4)
            val start = index + 8
            if (length < 0 || length > body.size - start) return null
            if (type == PAYLOAD_TYPE && length > 1) {
                val flag = body[start].toInt() and 0xFF
                val payload = body.copyOfRange(start + 1, start + length)
                return if (flag and DEFLATED != 0) inflate(payload) else payload
            }
            // 每个分块后面还有 4 字节 CRC。
            index = start + length + 4
        }
        return null
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        // 站点用的是 zlib 包装（不是裸 deflate），所以 nowrap 必须为 false。
        val inflater = java.util.zip.Inflater(false)
        return try {
            inflater.setInput(raw)
            val out = java.io.ByteArrayOutputStream(raw.size * 2)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) return null
                if (out.size() + read > MAX_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray().takeIf { it.isNotEmpty() && inflater.finished() }
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    /** 解开包装后按内容给出 MIME：清单是文本，分片是 MPEG-TS。 */
    fun contentType(inner: ByteArray): String {
        val head = String(inner, 0, minOf(16, inner.size), Charsets.US_ASCII)
        return when {
            head.startsWith("#EXTM3U") -> "application/vnd.apple.mpegurl"
            inner.size >= 189 && inner[0] == 0x47.toByte() -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }

    /**
     * 只在确定是 PNG 包装时替换响应体；否则原样透传，避免把直链 MP4 也读进内存。
     *
     * 先读到的前 8 个字节用于识别签名，识别失败时连同剩余内容一起回放给上层。
     */
    class UnwrappingDataSource(private val upstream: DataSource) : DataSource {
        private var body: ByteArray = ByteArray(0)
        private var position = 0
        private var buffered = false
        private var remaining = -1L
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            // Offsets refer to decoded bytes, so never send that offset into a compressed PNG.
            val opened = upstream.open(dataSpec.buildUpon().setPosition(0).setLength(-1).build())
            uri = upstream.uri ?: dataSpec.uri
            headers = upstream.responseHeaders
            val prefix = ByteArray(8)
            var count = 0
            while (count < prefix.size) {
                val n = upstream.read(prefix, count, prefix.size - count)
                if (n < 0) break
                count += n
            }
            buffered = isWrapped(prefix.copyOf(count))
            body = if (buffered) drain(prefix) else prefix.copyOf(count)
            if (buffered) unwrap(body)?.let {
                body = it
                headers = headers.filterKeys { key -> !key.equals("Content-Length", true) && !key.equals("Content-Type", true) } +
                    mapOf("Content-Type" to listOf(contentType(it)), "Content-Length" to listOf(it.size.toString()))
            }
            position = 0
            remaining = -1
            var skip = dataSpec.position
            val scratch = ByteArray(8192)
            while (skip > 0) {
                val n = read(scratch, 0, minOf(skip, scratch.size.toLong()).toInt())
                if (n < 0) throw androidx.media3.datasource.DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                skip -= n
            }
            val available = if (buffered) body.size.toLong() - position else if (opened >= 0) opened - dataSpec.position else -1L
            remaining = if (dataSpec.length >= 0) {
                if (available >= 0) minOf(dataSpec.length, available) else dataSpec.length
            } else available
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val requested = if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length
            val count = if (position < body.size) {
                minOf(requested, body.size - position).also {
                    System.arraycopy(body, position, buffer, offset, it); position += it
                }
            } else if (buffered) -1 else upstream.read(buffer, offset, requested)
            if (count > 0 && remaining >= 0) remaining -= count
            return count
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> = headers

        override fun close() {
            position = 0
            body = ByteArray(0)
            remaining = -1; buffered = false; uri = null; headers = emptyMap()
            upstream.close()
        }

        private fun drain(prefix: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(prefix)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (out.size() + read > MAX_BYTES) throw java.io.IOException("媒体分片超过大小限制")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
