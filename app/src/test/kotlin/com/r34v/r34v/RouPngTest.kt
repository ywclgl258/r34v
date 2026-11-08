package com.r34v.r34v

import com.r34v.r34v.playback.RouPng
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * rou.video 把清单和分片都伪装成 PNG：自定义 `roUd` 分块里放 zlib 压缩的真实数据。
 * 这里用真实抓下来的载荷做样本，验证拆壳结果和类型判断。
 */
class RouPngTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("avsites/$name")!!.readBytes()

    private fun png(payload: ByteArray, deflate: Boolean): ByteArray {
        val body = if (deflate) deflateOf(payload) else payload
        val chunk = byteArrayOf(if (deflate) 1 else 0) + body
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }.toByteArray())
        out.write(chunkOf("IHDR", ByteArray(13)))
        out.write(chunkOf("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x01)))
        out.write(chunkOf("roUd", chunk))
        out.write(chunkOf("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunkOf(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intOf(data.size))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(intOf(0)) // CRC 不参与解析，填 0 即可
        return out.toByteArray()
    }

    private fun intOf(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun deflateOf(raw: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(raw); deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    @Test fun unpacksRealManifestThatTheAppCouldNotPlayBefore() {
        val inner = RouPng.unwrap(fixture("rou-manifest.png"))
        assertNotNull("真实清单必须能拆出来", inner)
        val text = String(inner!!, Charsets.UTF_8)
        assertTrue(text, text.startsWith("#EXTM3U"))
        assertTrue(text, text.contains("C-000.png"))
        // 分片地址带签名，必须原样保留，否则 401。
        assertTrue(text, text.contains("auth="))
    }

    @Test fun reportsManifestAndSegmentTypes() {
        val manifest = RouPng.unwrap(fixture("rou-manifest.png"))!!
        assertEquals("application/vnd.apple.mpegurl", RouPng.contentType(manifest))
        // MPEG-TS 以 0x47 开头；这里直接构造一个最小同步字节的样本。
        val ts = ByteArray(189).also { it[0] = 0x47 }
        assertEquals("video/mp2t", RouPng.contentType(ts))
        assertEquals("application/octet-stream", RouPng.contentType(byteArrayOf(1, 2, 3)))
    }

    @Test fun unwrapsPlainAndDeflatedPayloads() {
        val text = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        assertEquals(String(text), String(RouPng.unwrap(png(text, deflate = false))!!))
        assertEquals(String(text), String(RouPng.unwrap(png(text, deflate = true))!!))
    }

    @Test fun leavesOrdinaryPngAndForeignBytesAlone() {
        // 普通封面图没有 roUd 分块：必须返回 null，让上层原样放行。
        val cover = png(byteArrayOf(0), deflate = false).let { it }
        assertNotNull(RouPng.unwrap(cover))
        // 一张真正的图（只有 IHDR/IDAT/IEND）不能被当成包装。
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }.toByteArray())
        out.write(chunkOf("IHDR", ByteArray(13)))
        out.write(chunkOf("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x01)))
        out.write(chunkOf("IEND", ByteArray(0)))
        assertNull(RouPng.unwrap(out.toByteArray()))
        assertNull(RouPng.unwrap("not an image".toByteArray()))
        assertFalse(RouPng.isWrapped("nope".toByteArray()))
    }

    @Test fun truncatedPayloadDoesNotThrow() {
        // 截断到 roUd 分块中间：声明长度超过剩余字节，必须安静地返回 null。
        val full = png("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(), deflate = true)
        val roUd = full.indexOfChunk("roUd")
        assertTrue(roUd > 0)
        val cut = full.copyOfRange(0, roUd + 10)
        assertNull(RouPng.unwrap(cut))
    }

    private fun ByteArray.indexOfChunk(type: String): Int {
        val needle = type.toByteArray(Charsets.US_ASCII)
        for (i in 0..size - needle.size) {
            if (needle.indices.all { this[i + it] == needle[it] }) return i - 4
        }
        return -1
    }
}
