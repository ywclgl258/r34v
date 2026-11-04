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

/** Bounded reading cache and separate, persistent whole-book downloads. */
class GalleryRepository(private val context: Context, private val network: Network) {
    data class RenderedPage(val image: GalleryImage, val bitmap: Bitmap, val cached: Boolean)
    private data class KnownPages(val links: Map<Int, String> = emptyMap())
    private val gson = Gson()
    private fun directory(site: SiteContext) = File(context.filesDir, "gallery-cache/${site.id}").apply { mkdirs() }
    private fun bookDirectory(entry: Video): File {
        val site = SiteRegistry.byId(entry.site) ?: error("未知漫画站点")
        require(site.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        return File(context.filesDir, "gallery-downloads/${site.id}/${key(entry.id)}")
    }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun <T> read(file: File, type: Class<T>): T? = runCatching { gson.fromJson(file.readText(), type) }.getOrNull()
    private fun write(file: File, bytes: ByteArray) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建下载目录" }
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.part")
        try {
            temporary.writeBytes(bytes)
            check(temporary.renameTo(file)) { "无法保存图页缓存" }
        } finally { temporary.delete() }
    }

    suspend fun detail(entry: Video, offlineOnly: Boolean = false): GalleryDetail = withContext(Dispatchers.IO) {
        val downloaded = read(File(bookDirectory(entry), "book.json"), GalleryDetail::class.java)
        if (offlineOnly) {
            check(isDownloaded(entry) && downloaded != null) { "漫画下载不完整，请在下载中心继续下载" }
            return@withContext downloaded.copy(cached = true)
        }
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val file = File(directory(site), "${key(entry.url)}.gallery.json")
        val previous = read(file, GalleryDetail::class.java)
        try {
            AdapterRegistry.adapter(site, network).gallery(entry.url).also { write(file, gson.toJson(it).toByteArray()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is GalleryLoginRequiredException) throw error
            val saved = downloaded ?: previous
            if (saved != null && (error is IOException || error is GalleryAccessException)) saved.copy(cached = true) else throw error
        }
    }

    fun cachedPageUrl(entry: Video, page: Int): String? = read(File(bookDirectory(entry), "links.json"), KnownPages::class.java)?.links?.get(page) ?: read(
        File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json"), KnownPages::class.java
    )?.links?.get(page)

    @Synchronized private fun rememberDownloadPage(entry: Video, image: GalleryImage) {
        val file = File(bookDirectory(entry), "links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty() + (image.index to image.pageUrl)
        write(file, gson.toJson(KnownPages(links)).toByteArray())
    }

    fun isDownloaded(entry: Video) = File(bookDirectory(entry), "complete").isFile

    suspend fun prepareDownload(entry: Video): GalleryDetail = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry)
        folder.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val file = File(folder, "book.json")
        val gallery = read(file, GalleryDetail::class.java) ?: detail(entry)
        check(gallery.pageCount > 0) { "漫画没有可下载的图页" }
        check(gallery.entry.id == entry.id && gallery.entry.site == entry.site) { "画廊身份不匹配" }
        write(file, gson.toJson(gallery).toByteArray())
        gallery
    }

    /** Save raw image files; a batch download must not decode every page into a bitmap. */
    suspend fun downloadPage(entry: Video, index: Int, pageUrl: String): Pair<GalleryImage, Long> = withContext(Dispatchers.IO) {
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val folder = bookDirectory(entry)
        val id = key(pageUrl.substringBefore('?'))
        val imageFile = File(folder, "$id.image")
        val metadataFile = File(folder, "$id.page.json")
        val stored = read(metadataFile, GalleryImage::class.java)
        if (stored?.index == index && imageFile.isFile && validImage(imageFile.readBytes())) {
            rememberDownloadPage(entry, stored)
            return@withContext stored to imageFile.length()
        }
        val cachedFolder = directory(site)
        val cachedImage = File(cachedFolder, "$id.image")
        val cachedMeta = read(File(cachedFolder, "$id.page.json"), GalleryImage::class.java)
        val cachedBytes = if (cachedMeta?.index == index && cachedImage.isFile) cachedImage.readBytes().takeIf(::validImage) else null
        val image = if (cachedBytes != null) cachedMeta!! else AdapterRegistry.adapter(site, network).galleryImage(pageUrl)
        check(image.index == index) { "原站返回了错误的图页编号" }
        val bytes = cachedBytes ?: imageBytes(site, image)
        check(validImage(bytes)) { "图源未返回可识别的图片，请重试下载" }
        val normalized = image.copy(pageUrl = pageUrl)
        write(imageFile, bytes)
        write(metadataFile, gson.toJson(normalized).toByteArray())
        rememberDownloadPage(entry, normalized)
        normalized to bytes.size.toLong()
    }

    suspend fun finishDownload(entry: Video, count: Int) = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry)
        val links = read(File(folder, "links.json"), KnownPages::class.java)?.links.orEmpty()
        check((1..count).all { page -> links[page]?.let { File(folder, "${key(it.substringBefore('?'))}.image").isFile } == true }) { "漫画下载尚未完整保存" }
        write(File(folder, "complete"), count.toString().toByteArray())
    }

    suspend fun removeDownload(entry: Video) = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry)
        val root = File(context.filesDir, "gallery-downloads").canonicalFile
        check(folder.canonicalPath.startsWith(root.path + File.separator))
        check(!folder.exists() || folder.deleteRecursively()) { "部分下载图页无法删除，请重试" }
    }

    @Synchronized private fun rememberPage(site: SiteContext, galleryUrl: String?, image: GalleryImage) {
        if (galleryUrl == null) return
        val file = File(directory(site), "${key(galleryUrl)}.links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty() + (image.index to image.pageUrl)
        write(file, gson.toJson(KnownPages(links)).toByteArray())
    }

    suspend fun page(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null, entry: Video? = null, offlineOnly: Boolean = false): RenderedPage = withContext(Dispatchers.IO) {
        if (entry != null) {
            require(entry.site == site.id)
            val folder = bookDirectory(entry)
            val id = key(pageUrl.substringBefore('?'))
            val image = read(File(folder, "$id.page.json"), GalleryImage::class.java)
            val file = File(folder, "$id.image")
            if ((offlineOnly || !force) && image != null && file.isFile) {
                return@withContext RenderedPage(image, decode(file.readBytes()), true)
            }
        }
        check(!offlineOnly) { "下载图页缺失，请在下载中心重新下载；离线阅读不会连接网络" }
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
        val bytes = imageBytes(site, image)
        val bitmap = decode(bytes)
        write(imageFile, bytes)
        rememberPage(site, galleryUrl, image.copy(pageUrl = pageUrl))
        trim(folder)
        RenderedPage(image.copy(pageUrl = pageUrl), bitmap, false)
    }
    private suspend fun imageBytes(site: SiteContext, image: GalleryImage): ByteArray =
        network.client.readResponse(Request.Builder().url(image.imageUrl).tag(SiteContext::class.java, site)
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
    private fun validImage(bytes: ByteArray): Boolean = BitmapFactory.Options().apply {
        inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
    }.let { bytes.size <= 24 * 1024 * 1024 && it.outWidth > 0 && it.outHeight > 0 }
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
