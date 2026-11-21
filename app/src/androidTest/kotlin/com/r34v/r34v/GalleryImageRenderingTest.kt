package com.r34v.r34v

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.r34v.r34v.data.renderGalleryStrips
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GalleryImageRenderingTest {
    @Test fun restoresStripOrderAndKeepsRemainderRows() {
        val input = Bitmap.createBitmap(2, 7, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE)
        for (y in colors.indices) for (x in 0 until input.width) input.setPixel(x, y, colors[y])
        val bytes = ByteArrayOutputStream().also { input.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        input.recycle()

        assertSame(bytes, renderGalleryStrips(bytes, 0))
        val restored = renderGalleryStrips(bytes, 3)
        val result = BitmapFactory.decodeByteArray(restored, 0, restored.size)
        try {
            assertEquals(7, result.height)
            val expected = listOf(4, 5, 6, 2, 3, 0, 1)
            assertEquals(expected.map { colors[it] }, (0 until result.height).map { result.getPixel(1, it) })
        } finally { result.recycle() }
    }
}
