package com.r34v.r34v.data

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

internal const val MAX_GALLERY_IMAGE_BYTES = 24 * 1024 * 1024

internal data class GalleryStripBounds(val sourceTop: Int, val destinationTop: Int, val height: Int)

internal fun galleryStripBounds(height: Int, strips: Int): List<GalleryStripBounds> {
    val stripHeight = height / strips
    val remainder = height % strips
    var destinationTop = 0
    return List(strips) { index ->
        val sourceTop = height - (index + 1) * stripHeight - remainder
        val length = stripHeight + if (index == 0) remainder else 0
        GalleryStripBounds(sourceTop, destinationTop, length).also { destinationTop += length }
    }
}

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
            for ((sourceTop, destinationTop, length) in galleryStripBounds(height, strips)) {
                val bitmap = decoder.decodeRegion(Rect(0, sourceTop, width, sourceTop + length), null)
                    ?: error("无法解码分条图页")
                try {
                    // Explicit pixel rectangles keep the result independent of device density.
                    canvas.drawBitmap(bitmap, null, Rect(0, destinationTop, width, destinationTop + length), null)
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
