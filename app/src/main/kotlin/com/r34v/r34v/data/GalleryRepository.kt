package com.r34v.r34v.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.r34v.r34v.data.adapter.GalleryAccessException
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Cache opened pages and the reader's bounded pre-read window; no whole-gallery downloads. */
class GalleryRepository(private val context: Context, private val network: Network) {
    data class RenderedPage(val image: GalleryImage, val bitmap: Bitmap, val cached: Boolean)
    private data class KnownPages(val links: Map<Int, String> = emptyMap())
    private val gson = Gson()
    private fun directory(site: SiteContext) = File(context.filesDir, "gallery-cache/${site.id}").apply { mkdirs() }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun <T> read(file: File, type: Class<T>): T? = runCatching { gson.fromJson(file.readText(), type) }.getOrNull()
    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.part")
        try {
            temporary.writeBytes(bytes)
            check(temporary.renameTo(file)) { "无法保存图页缓存" }
        } finally { temporary.delete() }
    }

    suspend fun detail(entry: Video): GalleryDetail = withContext(Dispatchers.IO) {
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val file = File(directory(site), "${key(entry.url)}.gallery.json")
        val previous = read(file, GalleryDetail::class.java)
        try {
            AdapterRegistry.adapter(site, network).gallery(entry.url).also { write(file, gson.toJson(it).toByteArray()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is GalleryLoginRequiredException) throw error
            if (previous != null && (error is IOException || error is GalleryAccessException)) previous.copy(cached = true) else throw error
        }
    }

    fun cachedPageUrl(entry: Video, page: Int): String? = read(
        File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json"), KnownPages::class.java
    )?.links?.get(page)

    @Synchronized private fun rememberPage(site: SiteContext, galleryUrl: String?, image: GalleryImage) {
        if (galleryUrl == null) return
        val file = File(directory(site), "${key(galleryUrl)}.links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty() + (image.index to image.pageUrl)
        write(file, gson.toJson(KnownPages(links)).toByteArray())
    }

    suspend fun page(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null): RenderedPage = withContext(Dispatchers.IO) {
        val folder = directory(site)
        val id = key(pageUrl.substringBefore('?'))
        val metadataFile = File(folder, "$id.page.json")
        val imageFile = File(folder, "$id.image")
        val previous = read(metadataFile, GalleryImage::class.java)
        if (!force && previous != null && imageFile.isFile) {
            runCatching { decode(imageFile.readBytes()) }.getOrNull()?.let {
                imageFile.setLastModified(System.currentTimeMillis())
                rememberPage(site, galleryUrl, previous)
                return@withContext RenderedPage(previous, it, true)
            }
        }
        val requestUrl = if (force) previous?.reloadUrl ?: pageUrl else pageUrl
        val image = AdapterRegistry.adapter(site, network).galleryImage(requestUrl)
        write(metadataFile, gson.toJson(image.copy(pageUrl = pageUrl)).toByteArray())
        val bytes = network.client.readResponse(Request.Builder().url(image.imageUrl).tag(SiteContext::class.java, site)
            .header("Referer", image.pageUrl.substringBefore('#')).header("Cache-Control", "no-store").build()) { response ->
            if (response.code == 509) throw GalleryAccessException("原站图片配额已用尽，请稍后重试")
            check(response.isSuccessful) { "图片读取失败：HTTP ${response.code}，可重试更换图源" }
            val body = response.body ?: error("图片响应为空")
            body.byteStream().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    check(output.size() + count <= 24 * 1024 * 1024) { "此页图片过大，暂时无法显示" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val bitmap = decode(bytes)
        write(imageFile, bytes)
        rememberPage(site, galleryUrl, image.copy(pageUrl = pageUrl))
        trim(folder)
        RenderedPage(image.copy(pageUrl = pageUrl), bitmap, false)
    }
    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图源未返回可识别的图片，请重试此页" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 6_000_000) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片解码失败，请重试此页")
    }
    private fun trim(folder: File) {
        val files = folder.listFiles().orEmpty().filter { it.extension == "image" }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 256L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }
    fun cacheInfo(site: SiteContext): Pair<Int, Long> = directory(site).listFiles().orEmpty().filter { it.extension == "image" }.let { it.size to it.sumOf(File::length) }
    fun clear(site: SiteContext) { directory(site).listFiles().orEmpty().filter { it.isFile }.forEach { check(it.delete()) { "部分图页缓存无法删除，请重试" } } }
}
