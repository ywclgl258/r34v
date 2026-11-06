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
class GalleryRepository(private val context: Context, private val network: Network, private val storage: StorageRoot) {
    /**
     * Plain private-storage repository, for callers that own neither app settings nor a download
     * location (probes, adapter fixtures). The application always passes a real [StorageRoot].
     */
    constructor(context: Context, network: Network) : this(context, network, StorageRoot(context, LibraryStore(context)))

    data class RenderedPage(val image: GalleryImage, val bitmap: Bitmap, val cached: Boolean)
    data class DownloadSelection(val selected: List<Int> = emptyList(), val completed: List<Int> = emptyList())
    data class GalleryAudit(val total: Int, val validPages: List<Int>, val missingPages: List<Int>, val corruptPages: List<Int>, val bytes: Long) {
        val complete: Boolean get() = total > 0 && validPages.size == total
    }
    private data class KnownPages(val links: Map<Int, String> = emptyMap())
    private val gson = Gson()
    private fun directory(site: SiteContext) = File(context.filesDir, "gallery-cache/${site.id}").apply { mkdirs() }
    val downloadsAllowed get() = !storage.changing && !storage.pendingRestart
    fun checkDownloadsAllowed() = storage.checkDownloadsAllowed()
    private fun bookDirectory(entry: Video, sourceRoot: String = "", write: Boolean = false): File {
        val site = SiteRegistry.byId(entry.site) ?: error("未知图集站点")
        require(entry.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        fun folder(layout: StorageRoot.Layout) = File(File(layout.comics, site.id), key(entry.id))
        if (sourceRoot.isNotBlank()) return folder(storage.sourceLayout(sourceRoot))
        if (write) return folder(storage.ensure())
        val candidates = storage.readableLayouts().sortedBy { it.root != storage.layout().root }.map(::folder)
        return candidates.firstOrNull(::completeBook) ?: candidates.firstOrNull { File(it, "book.json").isFile }
            ?: folder(storage.layout())
    }
    private fun completeBook(folder: File) = isCompleteDownload(folder, gson)
    /** Resolve/validate the book once when opening; page reads then use this explicit location. */
    fun readingRoot(entry: Video): String {
        val folder = bookDirectory(entry)
        return storage.readableLayouts().first { it.comics == folder.parentFile?.parentFile }.root.absolutePath
    }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun <T> read(file: File, type: Class<T>): T? = runCatching { gson.fromJson(file.readText(), type) }.getOrNull()
    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.part")
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建下载目录" }
            temporary.writeBytes(bytes)
            check(temporary.renameTo(file)) { "无法保存图页缓存" }
        } catch (error: Exception) {
            throw com.r34v.r34v.downloads.GalleryStorageException(error.message ?: "无法保存下载文件", error)
        } finally { temporary.delete() }
    }

    suspend fun detail(entry: Video, offlineOnly: Boolean = false, sourceRoot: String = ""): GalleryDetail = withContext(Dispatchers.IO) {
        val downloaded = read(File(bookDirectory(entry, sourceRoot), "book.json"), GalleryDetail::class.java)
        if (offlineOnly) {
            check(downloaded != null) { "图集下载信息缺失，请重新下载" }
            val audit = auditDownload(entry, sourceRoot)
            val selection = read(File(bookDirectory(entry, sourceRoot), "selection.json"), DownloadSelection::class.java)
            check(audit.validPages.isNotEmpty()) { "没有可离线读取的完整图页，请在下载中心检查并修复" }
            return@withContext downloaded.copy(cached = true, downloadedPages = audit.validPages,
                requestedPages = selection?.selected ?: (1..downloaded.pageCount).toList())
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

    fun cachedPageUrl(entry: Video, page: Int, sourceRoot: String = ""): String? = read(File(bookDirectory(entry, sourceRoot), "links.json"), KnownPages::class.java)?.links?.get(page) ?: read(
        File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json"), KnownPages::class.java
    )?.links?.get(page)

    @Synchronized private fun rememberDownloadPage(entry: Video, image: GalleryImage) {
        val file = File(bookDirectory(entry, write = true), "links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty() + (image.index to image.pageUrl)
        write(file, gson.toJson(KnownPages(links)).toByteArray())
    }

    fun isDownloaded(entry: Video, sourceRoot: String = "") = completeBook(bookDirectory(entry, sourceRoot))

    suspend fun prepareDownload(entry: Video): GalleryDetail = withContext(Dispatchers.IO) {
        checkDownloadsAllowed()
        val folder = bookDirectory(entry, write = true)
        folder.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val file = File(folder, "book.json")
        val gallery = read(file, GalleryDetail::class.java) ?: detail(entry)
        check(gallery.pageCount > 0) { "这条内容没有可下载的图页" }
        check(gallery.entry.id == entry.id && gallery.entry.site == entry.site) { "图集身份不匹配" }
        write(file, gson.toJson(gallery).toByteArray())
        gallery
    }

    /** Save raw image files; a batch download must not decode every page into a bitmap. */
    suspend fun downloadPage(entry: Video, index: Int, pageUrl: String): Pair<GalleryImage, Long> = withContext(Dispatchers.IO) {
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val folder = bookDirectory(entry, write = true)
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
        val adapter = AdapterRegistry.adapter(site, network)
        var image = if (cachedBytes != null) cachedMeta!! else adapter.galleryImage(pageUrl)
        check(image.index == index) { "原站返回了错误的图页编号" }
        val bytes = cachedBytes ?: try { imageBytes(site, image) } catch (error: GalleryResponseException) {
            val reload = image.reloadUrl?.takeIf { error.status == 403 && it.isNotBlank() } ?: throw error
            image = adapter.galleryImage(reload)
            check(image.index == index) { "刷新图源返回了错误的图页编号" }
            imageBytes(site, image)
        } catch (error: GalleryRedirectException) {
            val reload = image.reloadUrl?.takeIf { (site.id == SiteRegistry.EHENTAI.id || site.id == SiteRegistry.EXHENTAI.id) && it.isNotBlank() } ?: throw error
            image = adapter.galleryImage(reload)
            check(image.index == index) { "刷新图源返回了错误的图页编号" }
            imageBytes(site, image)
        } catch (error: IOException) {
            if ((site.id != SiteRegistry.EHENTAI.id && site.id != SiteRegistry.EXHENTAI.id) ||
                !com.r34v.r34v.downloads.DownloadRecovery.isTlsConnectionInterruption(error)) throw error
            val reload = image.reloadUrl?.takeIf(String::isNotBlank) ?: throw error
            image = adapter.galleryImage(reload)
            check(image.index == index) { "刷新图源返回了错误的图页编号" }
            imageBytes(site, image)
        }
        check(validImage(bytes)) { "图源未返回可识别的图片，请重试下载" }
        val normalized = image.copy(pageUrl = pageUrl)
        write(imageFile, bytes)
        write(metadataFile, gson.toJson(normalized).toByteArray())
        rememberDownloadPage(entry, normalized)
        normalized to bytes.size.toLong()
    }

    suspend fun finishDownload(entry: Video, count: Int) = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry, write = true)
        val links = read(File(folder, "links.json"), KnownPages::class.java)?.links.orEmpty()
        check((1..count).all { page -> links[page]?.let { File(folder, "${key(it.substringBefore('?'))}.image").isFile } == true }) { "图集下载尚未完整保存" }
        File(folder, "selection.json").delete()
        write(File(folder, "complete"), count.toString().toByteArray())
        write(File(folder, "readme.txt"), RECOVERY_NOTE.toByteArray())
    }

    suspend fun auditDownload(entry: Video, sourceRoot: String = ""): GalleryAudit = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry, sourceRoot)
        val detail = read(File(folder, "book.json"), GalleryDetail::class.java) ?: error("图集下载信息缺失，无法检查")
        val links = read(File(folder, "links.json"), KnownPages::class.java)?.links.orEmpty()
        val valid = mutableListOf<Int>(); val missing = mutableListOf<Int>(); val corrupt = mutableListOf<Int>()
        var bytes = 0L
        for (page in 1..detail.pageCount) {
            val url = links[page]
            if (url == null) { missing += page; continue }
            val id = key(url.substringBefore('?'))
            val metadata = read(File(folder, "$id.page.json"), GalleryImage::class.java)
            val image = File(folder, "$id.image")
            val validPage = metadata?.index == page && metadata.pageUrl == url && image.isFile && runCatching { validImage(image.readBytes()) }.getOrDefault(false)
            if (!validPage) corrupt += page else { valid += page; bytes += image.length() }
        }
        GalleryAudit(detail.pageCount, valid, missing, corrupt, bytes)
    }

    suspend fun finishPartialDownload(entry: Video, selected: Collection<Int>): GalleryAudit = withContext(Dispatchers.IO) {
        val audit = auditDownload(entry)
        val folder = bookDirectory(entry, write = true)
        write(File(folder, "selection.json"), gson.toJson(DownloadSelection(selected.distinct().sorted(), audit.validPages)).toByteArray())
        File(folder, "complete").delete()
        write(File(folder, "readme.txt"), ("此图集为部分保存，有效图页 ${audit.validPages.size} / ${audit.total}，不是整本下载。\n\n" + RECOVERY_NOTE).toByteArray())
        audit
    }

    suspend fun removeDownload(entry: Video) = withContext(Dispatchers.IO) {
        for (layout in storage.readableLayouts()) {
            val folder = bookDirectory(entry, layout.root.absolutePath)
            val root = layout.comics.canonicalFile
            check(folder.canonicalPath.startsWith(root.path + File.separator)) { "下载目录不在允许范围内" }
            check(!folder.exists() || folder.deleteRecursively()) { "部分下载图页无法删除，请重试" }
        }
    }

    @Synchronized private fun rememberPage(site: SiteContext, galleryUrl: String?, image: GalleryImage) {
        if (galleryUrl == null) return
        val file = File(directory(site), "${key(galleryUrl)}.links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty() + (image.index to image.pageUrl)
        write(file, gson.toJson(KnownPages(links)).toByteArray())
    }

    suspend fun page(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null, entry: Video? = null, offlineOnly: Boolean = false, sourceRoot: String = ""): RenderedPage = withContext(Dispatchers.IO) {
        if (entry != null) {
            require(entry.site == site.id)
            val folder = bookDirectory(entry, sourceRoot)
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
    private suspend fun imageBytes(site: SiteContext, image: GalleryImage): ByteArray {
        val request = Request.Builder().url(image.imageUrl).tag(SiteContext::class.java, site)
            .header("Referer", image.pageUrl.substringBefore('#')).header("Cache-Control", "no-store").build()
        return network.client.readResponse(request) { response ->
            if ((site.id == SiteRegistry.EHENTAI.id || site.id == SiteRegistry.EXHENTAI.id) && response.request.url != request.url)
                throw GalleryRedirectException("图片链接发生异常跳转，请刷新图源后重试")
            if (!response.isSuccessful) throw GalleryResponseException(response.code, response.header("Retry-After"),
                if (response.code == 509) "原站图片配额已用尽，请稍后重试" else "图片读取失败：HTTP ${response.code}")
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

    companion object {
        fun isCompleteDownload(folder: File, gson: Gson = Gson()): Boolean = runCatching {
            val detail = gson.fromJson(File(folder, "book.json").readText(), GalleryDetail::class.java)
            val count = detail.pageCount
            if (count <= 0 || File(folder, "complete").readText().trim().toIntOrNull() != count) return false
            val links = gson.fromJson(File(folder, "links.json").readText(), KnownPages::class.java).links
            (1..count).all { page ->
                val url = links[page] ?: return@all false
                val id = MessageDigest.getInstance("SHA-256").digest(url.substringBefore('?').toByteArray()).joinToString("") { "%02x".format(it) }
                val image = File(folder, "$id.image")
                val metadata = gson.fromJson(File(folder, "$id.page.json").readText(), GalleryImage::class.java)
                image.isFile && image.length() > 0 && metadata.index == page && metadata.pageUrl == url
            }
        }.getOrDefault(false)

        /**
         * Left beside every finished book so a user who copies the folder elsewhere knows what it
         * is without this app. Plain text on purpose: it must survive any file manager or cloud sync.
         */
        const val RECOVERY_NOTE = "本文件夹是一份已下载的图集（漫画或写真），由 R34V 保存。\n\n" +
            "book.json  详情与页数\nlinks.json 页码与图源的对应关系\n*.image    原始图页（从第 1 页起依次保存）\n" +
            "complete   下载完成标记\n\n把整个文件夹放回 R34V/comics/<站点>/ 之下，R34V 会重新识别并显示在下载中心。"
    }
}
