@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.net.Uri
import androidx.media3.datasource.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.playback.RouPng
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream

internal object TestPng {
    fun wrap(data: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(data) } }.toByteArray()
        return ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            it.writeInt(compressed.size + 1); it.writeBytes("roUd"); it.writeByte(1); it.write(compressed); it.writeInt(0)
        } }.toByteArray()
    }
}

@RunWith(AndroidJUnit4::class)
class RouDataSourceTest {
    @Test fun decodedRangesAndPlainRangesHaveTheSameMeaning() {
        val bytes = "0123456789".toByteArray()
        for (wrapped in listOf(false, true)) for (length in listOf(-1L, 3L)) {
            val source = RouPng.UnwrappingDataSource(ByteArrayDataSource(if (wrapped) TestPng.wrap(bytes) else bytes))
            try {
                assertEquals(if (length < 0) 7L else length, source.open(DataSpec.Builder().setUri("https://example.invalid/hls/data").setPosition(3).setLength(length).build()))
                val output = ByteArrayOutputStream(); val buffer = ByteArray(2)
                while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; output.write(buffer, 0, n) }
                assertEquals(if (length < 0) "3456789" else "345", output.toString("UTF-8"))
                assertEquals(0, source.read(buffer, 0, 0))
            } finally { source.close() }
        }
    }

    @Test fun redirectsAreVisibleAndPlainBodiesAreStreamed() {
        val bytes = ByteArray(100000)
        val delegate = ByteArrayDataSource(bytes)
        var read = 0
        val redirected = Uri.parse("https://cdn.invalid/folder/master.m3u8")
        val source = RouPng.UnwrappingDataSource(object : DataSource by delegate {
            override fun getUri() = redirected
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length).also { if (it > 0) read += it }
        })
        try {
            source.open(DataSpec.Builder().setUri("https://example.invalid/api/hls/demo").build())
            assertEquals(redirected, source.uri)
            assertEquals(8, read)
        } finally { source.close() }
    }
}
