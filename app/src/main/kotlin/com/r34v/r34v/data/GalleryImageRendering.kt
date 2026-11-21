package com.r34v.r34v.data

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

internal const val MAX_GALLERY_IMAGE_BYTES = 24 * 1024 * 1024

/** Render the bottom-to-top strips used by the public JMComic chapter reader. */
@Suppress("DEPRECATION")
internal fun renderGalleryStrips(bytes: ByteArray, strips: Int): ByteArray {
    if (strips == 0) return bytes
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        ?: error("无法解码分条图页")
    try {
        val width = decoder.width
        val height = decoder.height
        check(strips in 2..minOf(height, 20) && width.toLong() * height <= 12_000_000) { "图页尺寸或分条参数无效" }
        val rendered = createBitmap(width, height)
        try {
            val canvas = Canvas(rendered)
            val stripHeight = height / strips
            val remainder = height % strips
            var destination = 0
            repeat(strips) { index ->
                val start = height - (index + 1) * stripHeight - remainder
                val length = stripHeight + if (index == 0) remainder else 0
                val bitmap = decoder.decodeRegion(Rect(0, start, width, start + length), null)
                    ?: error("无法解码分条图页")
                try {
                    // Explicit pixel rectangles keep the result independent of device density.
                    canvas.drawBitmap(bitmap, null, Rect(0, destination, width, destination + length), null)
                    destination += length
                } finally { bitmap.recycle() }
            }
            val jpeg = bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
            val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val output = ByteArrayOutputStream()
            check(rendered.compress(format, 96, output) && output.size() <= MAX_GALLERY_IMAGE_BYTES) { "无法保存还原后的图页" }
            return output.toByteArray()
        } finally { rendered.recycle() }
    } finally { decoder.recycle() }
}
