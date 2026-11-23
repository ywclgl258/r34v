package com.r34v.r34v.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.scale
import com.google.gson.Gson
import com.r34v.r34v.data.adapter.GalleryAccessException
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Bounded reading cache and separate, persistent whole-book downloads. */
class GalleryRepository(private val context: Context, private val network: Network, private val storage: StorageRoot,
    /** 功能 F34：按站点询问“原图优先”；由 Application 接到阅读行为设置上，阅读/下载/导出共用。 */
    private val originalImages: (SiteContext) -> Boolean = { false }) {
    /**
     * Plain private-storage repository, for callers that own neither app settings nor a download
     * location (probes, adapter fixtures). The application always passes a real [StorageRoot].
     */
    constructor(context: Context, network: Network) : this(context, network, StorageRoot(context, LibraryStore(context)))

    data class RenderedPage(val image: GalleryImage, val bitmap: Bitmap, val cached: Boolean)
    data class OriginalPage(val image: GalleryImage, val bytes: ByteArray, val cached: Boolean)
    private data class PageContent<T>(val image: GalleryImage, val content: T, val cached: Boolean)
    data class DownloadSelection(val selected: List<Int> = emptyList(), val completed: List<Int> = emptyList())
    data class GalleryAudit(val total: Int, val validPages: List<Int>, val missingPages: List<Int>, val corruptPages: List<Int>, val bytes: Long) {
        val complete: Boolean get() = total > 0 && validPages.size == total
    }
    private data class KnownPages(val links: Map<Int, String> = emptyMap())
    private val gson = Gson()
    private fun directory(site: SiteContext) = File(context.filesDir, "gallery-cache/${site.id}").apply { mkdirs() }
    val downloadsAllowed get() = !storage.changing && !storage.pendingRestart
    fun checkDownloadsAllowed() = storage.checkDownloadsAllowed()
    /** 重复文件检查等只读工具用的整本下载目录；删除仍走 [removeDownload] 与下载中心事务。 */
    fun downloadDirectory(entry: Video, sourceRoot: String = ""): File = bookDirectory(entry, sourceRoot)

    private fun bookDirectory(entry: Video, sourceRoot: String = ""): File =
        bookFolder(entry, if (sourceRoot.isBlank()) readingLayout(entry) else storage.sourceLayout(sourceRoot))

    private fun bookFolder(entry: Video, layout: StorageRoot.Layout): File {
        val site = SiteRegistry.byId(entry.site) ?: error("未知图集站点")
        require(entry.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        return File(File(layout.comics, site.id), key(entry.id))
    }

    internal fun downloadLayout(): StorageRoot.Layout = storage.layout()

    private fun readingLayout(entry: Video): StorageRoot.Layout {
        val active = storage.layout()
        val candidates = storage.readableLayouts().sortedBy { it != active }.associateWith { bookFolder(entry, it) }
        return candidates.entries.firstOrNull { isCompleteDownload(it.value, gson) }?.key
            ?: candidates.entries.firstOrNull { intactSelection(it.value, entry) }?.key
            ?: candidates.entries.firstOrNull { readDownloadDetail(File(it.value, "book.json"), entry) != null }?.key
            ?: active
    }

    private fun intactSelection(folder: File, entry: Video): Boolean = runCatching {
        val selected = read(File(folder, "selection.json"), DownloadSelection::class.java)?.selected.orEmpty()
        if (selected.isEmpty()) return false
        val detail = readDownloadDetail(File(folder, "book.json"), entry) ?: return false
        val links = read(File(folder, "links.json"), KnownPages::class.java)?.links.orEmpty()
        selected.all { it in 1..detail.pageCount && savedPage(folder, it, links[it], gson) != null }
    }.getOrDefault(false)

    /** Resolve the book once when opening; page reads then use this explicit location. */
    fun readingRoot(entry: Video): String = readingLayout(entry).root.absolutePath
    private fun <T> read(file: File, type: Class<T>): T? = runCatching { gson.fromJson(file.readText(), type) }.getOrNull()
    private fun readPageMetadata(file: File, url: String): GalleryImage? =
        read(file, GalleryImage::class.java)?.takeIf { it.pageUrl == url && !it.imageUrl.isNullOrBlank() }

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

    private fun validDownloadDetail(entry: Video, gallery: GalleryDetail): Boolean =
        gallery.pageCount > 0 && gallery.entry.id == entry.id && gallery.entry.site == entry.site &&
            gallery.pageLinks.isNotEmpty() && gallery.pageLinks.all { it.index in 1..gallery.pageCount && !it.url.isNullOrBlank() }

    private fun readDownloadDetail(file: File, entry: Video): GalleryDetail? = runCatching {
        // Older or damaged JSON can contain null in a required property even after Gson succeeds.
        read(file, GalleryDetail::class.java)?.takeIf { validDownloadDetail(entry, it) }
    }.getOrNull()

    suspend fun detail(entry: Video, offlineOnly: Boolean = false, sourceRoot: String = ""): GalleryDetail = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry, sourceRoot)
        val downloaded = readDownloadDetail(File(folder, "book.json"), entry)
        if (offlineOnly) {
            check(downloaded != null) { "图集下载信息缺失，请重新下载" }
            val audit = auditBook(folder, downloaded)
            val selection = read(File(folder, "selection.json"), DownloadSelection::class.java)
            check(audit.validPages.isNotEmpty()) { "没有可离线读取的完整图页，请在下载中心检查并修复" }
            return@withContext downloaded.copy(cached = true, downloadedPages = audit.validPages,
                requestedPages = selection?.selected ?: (1..downloaded.pageCount).toList())
        }
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val file = File(directory(site), "${key(entry.url)}.gallery.json")
        val previous = readDownloadDetail(file, entry)
        try {
            AdapterRegistry.adapter(site, network).gallery(entry.url).also { write(file, gson.toJson(it).toByteArray()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is GalleryLoginRequiredException) throw error
            val saved = downloaded ?: previous
            if (saved != null && (error is IOException || error is GalleryAccessException)) saved.copy(cached = true) else throw error
        }
    }

    fun cachedPageUrl(entry: Video, page: Int, sourceRoot: String = ""): String? =
        cachedPageUrl(entry, page, bookDirectory(entry, sourceRoot))

    internal fun downloadedPageUrl(entry: Video, page: Int, sourceRoot: String): String? =
        read(File(bookDirectory(entry, sourceRoot), "links.json"), KnownPages::class.java)?.links?.get(page)

    internal fun cachedPageUrl(entry: Video, page: Int, layout: StorageRoot.Layout): String? =
        cachedPageUrl(entry, page, bookFolder(entry, layout))

    private fun cachedPageUrl(entry: Video, page: Int, folder: File): String? =
        read(File(folder, "links.json"), KnownPages::class.java)?.links?.get(page) ?: read(
            File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json"), KnownPages::class.java
        )?.links?.get(page)

    @Synchronized fun forgetCachedPageLink(entry: Video, page: Int, staleUrl: String) {
        val file = File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty()
        // Other pages can be read concurrently. The persistent download index belongs to the download worker.
        if (links[page] == staleUrl) write(file, gson.toJson(KnownPages(links - page)).toByteArray())
    }

    @Synchronized private fun rememberDownloadPage(folder: File, image: GalleryImage) {
        val file = File(folder, "links.json")
        val links = read(file, KnownPages::class.java)?.links.orEmpty() + (image.index to image.pageUrl)
        write(file, gson.toJson(KnownPages(links)).toByteArray())
    }

    fun isDownloaded(entry: Video, sourceRoot: String = ""): Boolean =
        if (sourceRoot.isBlank()) storage.readableLayouts().any { isCompleteDownload(bookFolder(entry, it), gson) }
        else isCompleteDownload(bookDirectory(entry, sourceRoot), gson)

    suspend fun prepareDownload(entry: Video): GalleryDetail {
        checkDownloadsAllowed()
        return prepareDownload(entry, downloadLayout())
    }

    internal suspend fun prepareDownload(entry: Video, layout: StorageRoot.Layout): GalleryDetail = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, layout)
        folder.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val file = File(folder, "book.json")
        val saved = readDownloadDetail(file, entry)
        val gallery = saved ?: detail(entry)
        check(validDownloadDetail(entry, gallery)) { "图集信息无效，请刷新后重试" }
        write(file, gson.toJson(gallery).toByteArray())
        gallery
    }

    /** Save raw image files; a batch download must not decode every page into a bitmap. */
    suspend fun downloadPage(entry: Video, index: Int, pageUrl: String,
                             layout: StorageRoot.Layout = storage.layout()): Pair<GalleryImage, Long> = withContext(Dispatchers.IO) {
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val folder = bookFolder(entry, layout)
        val id = key(pageUrl.substringBefore('?'))
        val imageFile = File(folder, "$id.image")
        val metadataFile = File(folder, "$id.page.json")
        val stored = readPageMetadata(metadataFile, pageUrl)
        if (stored?.index == index && hasImageHeader(imageFile)) {
            rememberDownloadPage(folder, stored)
            return@withContext stored to imageFile.length()
        }
        val cachedFolder = directory(site)
        val cachedImage = File(cachedFolder, "$id.image")
        val cachedMeta = readPageMetadata(File(cachedFolder, "$id.page.json"), pageUrl)
        val cachedBytes = if (cachedMeta != null && cachedMeta.index == index) {
            runCatching { cachedImage.readBytes().takeIf(::validImage) }.getOrNull()
                ?: cachedStrips(File(cachedFolder, "$id.strips"), cachedMeta)?.let { bytes ->
                    runCatching { renderGalleryStrips(bytes, cachedMeta.imageStripCount).takeIf(::validImage) }.getOrNull()
                }
        } else null
        val adapter = AdapterRegistry.adapter(site, network)
        var image = if (cachedBytes != null) cachedMeta!! else adapter.galleryImage(pageUrl)
        check(image.index == index) { "原站返回了错误的图页编号" }
        val bytes = cachedBytes ?: try { imageBytes(site, originalView(site, image)) } catch (error: GalleryResponseException) {
            val reload = image.reloadUrl?.takeIf { error.status == 403 && it.isNotBlank() } ?: throw error
            image = adapter.galleryImage(reload)
            check(image.index == index) { "刷新图源返回了错误的图页编号" }
            imageBytes(site, originalView(site, image))
        } catch (error: GalleryRedirectException) {
            val reload = image.reloadUrl?.takeIf { (site.id == SiteRegistry.EHENTAI.id || site.id == SiteRegistry.EXHENTAI.id) && it.isNotBlank() } ?: throw error
            image = adapter.galleryImage(reload)
            check(image.index == index) { "刷新图源返回了错误的图页编号" }
            imageBytes(site, originalView(site, image))
        } catch (error: IOException) {
            if ((site.id != SiteRegistry.EHENTAI.id && site.id != SiteRegistry.EXHENTAI.id) ||
                !com.r34v.r34v.downloads.DownloadRecovery.isTlsConnectionInterruption(error)) throw error
            val reload = image.reloadUrl?.takeIf(String::isNotBlank) ?: throw error
            image = adapter.galleryImage(reload)
            check(image.index == index) { "刷新图源返回了错误的图页编号" }
            imageBytes(site, originalView(site, image))
        }
        check(validImage(bytes)) { "图源未返回可识别的图片，请重试下载" }
        val normalized = image.copy(pageUrl = pageUrl)
        write(imageFile, bytes)
        write(metadataFile, gson.toJson(normalized).toByteArray())
        rememberDownloadPage(folder, normalized)
        normalized to bytes.size.toLong()
    }

    suspend fun finishDownload(entry: Video, count: Int) = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, storage.layout())
        val audit = auditBook(folder, downloadDetail(folder, entry))
        check(audit.complete && audit.total == count) { "图集下载尚未完整保存" }
        writeComplete(folder, count)
    }

    private fun writeComplete(folder: File, count: Int) {
        write(File(folder, "readme.txt"), RECOVERY_NOTE.toByteArray())
        write(File(folder, "complete"), count.toString().toByteArray())
        File(folder, "selection.json").delete()
    }

    private fun downloadDetail(folder: File, entry: Video): GalleryDetail =
        readDownloadDetail(File(folder, "book.json"), entry) ?: error("图集下载信息缺失，无法检查")

    suspend fun auditDownload(entry: Video, sourceRoot: String = ""): GalleryAudit = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry, sourceRoot)
        auditBook(folder, downloadDetail(folder, entry))
    }

    internal suspend fun auditWriteTarget(entry: Video, layout: StorageRoot.Layout): GalleryAudit? = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, layout)
        readDownloadDetail(File(folder, "book.json"), entry)?.let { auditBook(folder, it) }
    }

    private fun auditBook(folder: File, detail: GalleryDetail): GalleryAudit {
        val links = read(File(folder, "links.json"), KnownPages::class.java)?.links.orEmpty()
        val valid = mutableListOf<Int>(); val missing = mutableListOf<Int>(); val corrupt = mutableListOf<Int>()
        var bytes = 0L
        for (page in 1..detail.pageCount) {
            val url = links[page]
            if (url == null) { missing += page; continue }
            val image = savedPage(folder, page, url, gson)
            if (image == null) corrupt += page else { valid += page; bytes += image.length() }
        }
        return GalleryAudit(detail.pageCount, valid, missing, corrupt, bytes)
    }

    internal suspend fun commitDownload(entry: Video, layout: StorageRoot.Layout, requested: Set<Int>): GalleryAudit = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, layout)
        val audit = auditBook(folder, downloadDetail(folder, entry))
        check(audit.validPages.toSet().containsAll(requested)) { "图集下载尚未完整保存" }
        currentCoroutineContext().ensureActive()
        if (audit.complete) writeComplete(folder, audit.total)
        else {
            write(File(folder, "selection.json"), gson.toJson(DownloadSelection(requested.sorted(), audit.validPages)).toByteArray())
            File(folder, "complete").delete()
            write(File(folder, "readme.txt"), ("此图集为部分保存，有效图页 ${audit.validPages.size} / ${audit.total}，不是整本下载。\n\n" + RECOVERY_NOTE).toByteArray())
        }
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

    /**
     * 功能 F34：站点开启“原图优先”且图源给出原图地址时，返回指向原图的视图。
     * 元数据仍保留显示图字段（originalUrl 一并落盘），已缓存/已下载文件不追溯变化。
     */
    private fun originalView(site: SiteContext, image: GalleryImage): GalleryImage {
        val original = image.originalUrl
        return if (originalImages(site) && !original.isNullOrBlank()) image.copy(imageUrl = original) else image
    }

    suspend fun page(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null, entry: Video? = null, offlineOnly: Boolean = false, sourceRoot: String = ""): RenderedPage = withContext(Dispatchers.IO) {
        val original = originalPage(site, pageUrl, force, galleryUrl, entry, offlineOnly, sourceRoot)
        RenderedPage(original.image, decode(original.bytes), original.cached)
    }
    suspend fun originalPage(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null, entry: Video? = null, offlineOnly: Boolean = false, sourceRoot: String = ""): OriginalPage = withContext(Dispatchers.IO) {
        val loaded = loadPageContent(site, pageUrl, force, galleryUrl, entry, offlineOnly, sourceRoot,
            readFile = { it.readBytes().takeIf(::validImage) }, readBytes = { it })
        OriginalPage(loaded.image, loaded.content, loaded.cached)
    }

    internal suspend fun thumbnail(site: SiteContext, pageUrl: String, sizePx: Int, force: Boolean = false,
        entry: Video, offlineOnly: Boolean, sourceRoot: String): RenderedPage = withContext(Dispatchers.IO) {
        val loaded = loadPageContent(site, pageUrl, force, entry.url, entry, offlineOnly, sourceRoot,
            readFile = { file ->
                if (file.length() in 1L..MAX_GALLERY_IMAGE_BYTES.toLong()) decodeGalleryThumbnail(file, sizePx) else null
            },
            readBytes = { bytes ->
                decodeGalleryThumbnail(sizePx) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                    ?: error("图片解码失败，请重试此页")
            },
            readStrips = { bytes, strips -> decodeStripThumbnail(bytes, strips, sizePx) })
        RenderedPage(loaded.image, loaded.content, loaded.cached)
    }

    private suspend fun <T : Any> loadPageContent(site: SiteContext, pageUrl: String, force: Boolean, galleryUrl: String?,
        entry: Video?, offlineOnly: Boolean, sourceRoot: String, readFile: (File) -> T?, readBytes: (ByteArray) -> T,
        readStrips: ((ByteArray, Int) -> T)? = null): PageContent<T> {
        val id = key(pageUrl.substringBefore('?'))
        if (entry != null) {
            require(entry.site == site.id)
            val folder = bookDirectory(entry, sourceRoot)
            val image = readPageMetadata(File(folder, "$id.page.json"), pageUrl)
            val file = File(folder, "$id.image")
            if ((offlineOnly || !force) && image != null && file.isFile) {
                val content = readFile(file) ?: error("下载图页已损坏，请在下载中心检查并修复")
                return PageContent(image, content, true)
            }
        }
        check(!offlineOnly) { "下载图页缺失，请在下载中心重新下载；离线阅读不会连接网络" }
        val folder = directory(site)
        val metadataFile = File(folder, "$id.page.json")
        val imageFile = File(folder, "$id.image")
        val stripsFile = File(folder, "$id.strips")
        val previous = read(metadataFile, GalleryImage::class.java)
        if (!force && previous != null) {
            if (imageFile.isFile) runCatching { readFile(imageFile) }.getOrNull()?.let {
                imageFile.setLastModified(System.currentTimeMillis())
                rememberPage(site, galleryUrl, previous)
                return PageContent(previous, it, true)
            }
            if (previous.pageUrl == pageUrl) cachedStrips(stripsFile, previous)?.let { raw ->
                val content = if (readStrips != null) {
                    runCatching { readStrips(raw, previous.imageStripCount) }.getOrNull()
                        ?.also { stripsFile.setLastModified(System.currentTimeMillis()) }
                } else {
                    runCatching { renderGalleryStrips(raw, previous.imageStripCount).takeIf(::validImage) }.getOrNull()?.let { bytes ->
                        write(imageFile, bytes)
                        stripsFile.delete()
                        trim(folder)
                        readBytes(bytes)
                    }
                }
                if (content != null) {
                    rememberPage(site, galleryUrl, previous)
                    return PageContent(previous, content, true)
                }
            }
        }
        val requestUrl = if (force) previous?.reloadUrl ?: pageUrl else pageUrl
        val image = AdapterRegistry.adapter(site, network).galleryImage(requestUrl)
        val normalized = image.copy(pageUrl = pageUrl)
        val source = originalView(site, image)
        if (readStrips != null && image.imageStripCount != 0) {
            // Assemble the preview at thumbnail size and keep the encoded source for a later full decode.
            val raw = fetchImageBytes(site, source)
            val content = readStrips(raw, image.imageStripCount)
            write(stripsFile, raw)
            write(metadataFile, gson.toJson(normalized).toByteArray())
            rememberPage(site, galleryUrl, normalized)
            trim(folder)
            return PageContent(normalized, content, false)
        }
        write(metadataFile, gson.toJson(normalized).toByteArray())
        val bytes = imageBytes(site, source)
        check(validImage(bytes)) { "图源未返回可识别的图片，请重试此页" }
        write(imageFile, bytes)
        stripsFile.delete()
        rememberPage(site, galleryUrl, normalized)
        trim(folder)
        return PageContent(normalized, readBytes(bytes), false)
    }
    private suspend fun imageBytes(site: SiteContext, image: GalleryImage): ByteArray =
        renderGalleryStrips(fetchImageBytes(site, image), image.imageStripCount)

    private suspend fun fetchImageBytes(site: SiteContext, image: GalleryImage): ByteArray {
        val request = Request.Builder().url(image.imageUrl).tag(SiteContext::class.java, site)
            .header("Referer", image.imageReferer ?: image.pageUrl.substringBefore('#'))
            .header("Cache-Control", "no-store").build()
        // 用图页客户端：保留站点 Cookie/形态/节流，但不继承页面请求的 25 秒总时限，
        // 否则慢链路上“一直在传”的单页会被中途掐断并反复从头重试。
        return network.imageClient.readResponse(request) { response ->
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
                    check(output.size() + count <= MAX_GALLERY_IMAGE_BYTES) { "此页图片过大，暂时无法显示" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
    private fun validImage(bytes: ByteArray): Boolean = BitmapFactory.Options().apply {
        inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
    }.let { bytes.size <= MAX_GALLERY_IMAGE_BYTES && it.outWidth > 0 && it.outHeight > 0 }
    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图源未返回可识别的图片，请重试此页" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 6_000_000) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片解码失败，请重试此页")
    }
    @Suppress("DEPRECATION")
    private fun decodeStripThumbnail(bytes: ByteArray, strips: Int, sizePx: Int): Bitmap {
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: error("无法解码分条图页")
        try {
            val width = decoder.width
            val height = decoder.height
            check(strips in 2..minOf(height, 20) && width.toLong() * height <= 12_000_000) { "图页尺寸或分条参数无效" }
            val targetWidth = thumbnailEdge(width, maxOf(width, height), sizePx)
            val targetHeight = thumbnailEdge(height, maxOf(width, height), sizePx)
            val sample = BitmapFactory.Options().apply { inSampleSize = thumbnailSampleSize(maxOf(width, height), sizePx) }
            val rendered = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(rendered)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val stripHeight = height / strips
                val remainder = height % strips
                var top = 0
                repeat(strips) { index ->
                    val start = height - (index + 1) * stripHeight - remainder
                    val length = stripHeight + if (index == 0) remainder else 0
                    val bottom = top + length
                    val destTop = (top.toLong() * targetHeight / height).toInt()
                    val destBottom = (bottom.toLong() * targetHeight / height).toInt()
                    if (destBottom > destTop) {
                        val strip = decoder.decodeRegion(Rect(0, start, width, start + length), sample)
                            ?: error("无法解码分条图页")
                        try {
                            canvas.drawBitmap(strip, null, Rect(0, destTop, targetWidth, destBottom), paint)
                        } finally { strip.recycle() }
                    }
                    top = bottom
                }
                return rendered
            } catch (e: Throwable) {
                rendered.recycle()
                throw e
            }
        } finally { decoder.recycle() }
    }

    private fun cachedStrips(file: File, image: GalleryImage): ByteArray? {
        if (image.imageStripCount == 0 || file.length() !in 1L..MAX_GALLERY_IMAGE_BYTES.toLong()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun cachedImages(folder: File) = folder.listFiles().orEmpty().filter { it.extension == "image" || it.extension == "strips" }

    private fun trim(folder: File) {
        val files = cachedImages(folder).sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 256L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }
    fun cacheInfo(site: SiteContext): Pair<Int, Long> = cachedImages(directory(site)).let { files ->
        files.distinctBy { it.nameWithoutExtension }.size to files.sumOf(File::length)
    }
    fun clear(site: SiteContext) { directory(site).listFiles().orEmpty().filter { it.isFile }.forEach { check(it.delete()) { "部分图页缓存无法删除，请重试" } } }

    companion object {
        private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun hasImageHeader(image: File): Boolean = runCatching {
            if (!image.isFile || image.length() !in 1L..MAX_GALLERY_IMAGE_BYTES.toLong()) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)

        private fun savedPage(folder: File, page: Int, url: String?, gson: Gson): File? = runCatching {
            if (url.isNullOrBlank()) return null
            val id = key(url.substringBefore('?'))
            val metadata = gson.fromJson(File(folder, "$id.page.json").readText(), GalleryImage::class.java)
            if (metadata.index != page || metadata.pageUrl != url || metadata.imageUrl.isNullOrBlank()) return null
            File(folder, "$id.image").takeIf(::hasImageHeader)
        }.getOrNull()

        fun isCompleteDownload(folder: File, gson: Gson = Gson()): Boolean = runCatching {
            val marker = File(folder, "complete").readText().trim().toIntOrNull() ?: return false
            val detail = gson.fromJson(File(folder, "book.json").readText(), GalleryDetail::class.java)
            if (marker <= 0 || detail.pageCount != marker) return false
            val links = gson.fromJson(File(folder, "links.json").readText(), KnownPages::class.java).links
            (1..marker).all { page -> savedPage(folder, page, links[page], gson) != null }
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

/** Decode at most about twice the requested long edge, then round down to the exact pixel size. */
internal fun decodeGalleryThumbnail(file: File, sizePx: Int, onSampled: ((Bitmap) -> Unit)? = null): Bitmap? =
    decodeGalleryThumbnail(sizePx, onSampled) { options -> BitmapFactory.decodeFile(file.path, options) }

private fun decodeGalleryThumbnail(sizePx: Int, onSampled: ((Bitmap) -> Unit)? = null,
    decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    require(sizePx > 0)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(options)
    val width = options.outWidth
    val height = options.outHeight
    if (width <= 0 || height <= 0) return null
    val longEdge = maxOf(width, height)
    options.inJustDecodeBounds = false
    options.inSampleSize = thumbnailSampleSize(longEdge, sizePx)
    val sampled = decode(options) ?: return null
    val targetWidth = thumbnailEdge(width, longEdge, sizePx)
    val targetHeight = thumbnailEdge(height, longEdge, sizePx)
    if (sampled.width == targetWidth && sampled.height == targetHeight) {
        onSampled?.invoke(sampled)
        return sampled
    }
    try {
        onSampled?.invoke(sampled)
        return sampled.scale(targetWidth, targetHeight, true)
    } finally { sampled.recycle() }
}

private fun thumbnailSampleSize(longEdge: Int, sizePx: Int): Int {
    var sample = 1
    while (longEdge / sample / 2 >= sizePx) sample *= 2
    return sample
}

private fun thumbnailEdge(edge: Int, longEdge: Int, sizePx: Int): Int =
    (edge.toLong() * minOf(longEdge, sizePx) / longEdge).toInt().coerceAtLeast(1)
