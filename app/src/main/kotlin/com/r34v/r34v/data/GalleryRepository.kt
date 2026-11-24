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
import java.io.RandomAccessFile
import java.security.MessageDigest
import com.r34v.r34v.downloads.GalleryStorageException

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
    internal data class SavedPage(val image: GalleryImage, val file: File, val encodedBytes: Long?) {
        fun valid(): Boolean = (encodedBytes == null || encodedBytes == file.length()) && isCompleteGalleryImage(file)
    }
    internal class DiskBook internal constructor(
        private val folder: File, val detail: GalleryDetail, private val links: Map<Int, String>,
        val selection: List<Int>, val hasCompleteMarker: Boolean, private val gson: Gson,
    ) {
        fun page(index: Int): SavedPage? = links[index]?.let { savedPage(folder, it, index, gson) }
        fun isComplete(): Boolean = hasCompleteMarker && (1..detail.pageCount).all { page(it)?.valid() == true }
        fun hasSelection(indices: Collection<Int> = selection): Boolean = indices.isNotEmpty() &&
            (hasCompleteMarker || selection.toSet().containsAll(indices)) &&
            indices.all { it in 1..detail.pageCount && page(it)?.valid() == true }
        fun audit(checkActive: () -> Unit = {}): GalleryAudit {
            val valid = mutableListOf<Int>()
            val missing = mutableListOf<Int>()
            val corrupt = mutableListOf<Int>()
            var bytes = 0L
            for (index in 1..detail.pageCount) {
                checkActive()
                val page = page(index)
                when {
                    links[index].isNullOrBlank() -> missing += index
                    page == null || !page.valid() -> corrupt += index
                    else -> { valid += index; bytes += page.file.length() }
                }
            }
            return GalleryAudit(detail.pageCount, valid, missing, corrupt, bytes)
        }
    }
    private val gson = Gson()
    private fun directory(site: SiteContext) = File(context.filesDir, "gallery-cache/${site.id}").apply { mkdirs() }
    val downloadsAllowed get() = storage.canWriteDownloads
    fun checkDownloadsAllowed() = storage.checkDownloadsAllowed()
    fun downloadDirectory(entry: Video, sourceRoot: String = ""): File = bookDirectory(entry, sourceRoot)

    internal fun duplicateBooks(shouldStop: () -> Boolean = { false }): List<DuplicateBook> = buildList {
        for (layout in storage.readableLayouts()) {
            if (!directChild(layout.root, layout.comics)) continue
            for (siteFolder in layout.comics.listFiles().orEmpty()) {
                if (shouldStop()) return emptyList()
                val site = SiteRegistry.byId(siteFolder.name) ?: continue
                if (!siteFolder.isDirectory || !directChild(layout.comics, siteFolder)) continue
                for (folder in siteFolder.listFiles().orEmpty()) {
                    if (shouldStop()) return emptyList()
                    if (!folder.isDirectory || !directChild(siteFolder, folder)) continue
                    val book = readDiskBook(folder, gson, expectedSite = site.id) ?: continue
                    if (layout.mode == StorageRoot.Mode.SHARED) storage.noteSharedGallery("${site.id}/${folder.name}")
                    if (!book.hasCompleteMarker) continue
                    // Old whole-book downloads may retain an obsolete selection; keep their full page set.
                    if (!book.isComplete() && File(folder, "selection.json").exists()) continue
                    val pages = (1..book.detail.pageCount).mapNotNull { page ->
                        if (shouldStop()) return emptyList()
                        book.page(page)
                    }
                    if (pages.size != book.detail.pageCount) continue
                    val entry = book.detail.entry
                    val files = pages.associateBy { it.file }
                    if (files.size != pages.size) continue
                    add(DuplicateBook("download", galleryDownloadKey(entry), entry.title, entry.site,
                        pages.map { it.file }, { files[it]?.valid() == true }, layout.root.absolutePath, entry))
                }
            }
        }
    }

    private fun bookDirectory(entry: Video, sourceRoot: String = ""): File =
        bookFolder(entry, if (sourceRoot.isBlank()) readingLayout(entry) else storage.sourceLayout(sourceRoot))

    private fun bookFolder(entry: Video, layout: StorageRoot.Layout): File {
        val site = SiteRegistry.byId(entry.site) ?: error("未知图集站点")
        require(entry.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
        val parent = File(layout.comics, site.id)
        val book = File(parent, key(entry.id))
        check(directChild(layout.root, layout.comics) && directChild(layout.comics, parent) && directChild(parent, book)) {
            "下载目录不在允许范围内"
        }
        return book
    }

    internal fun downloadLayout(): StorageRoot.Layout = storage.layout()

    private fun readingLayout(entry: Video): StorageRoot.Layout {
        val active = storage.layout()
        val candidates = storage.readableLayouts().sortedBy { it != active }.mapNotNull { layout ->
            val folder = runCatching { bookFolder(entry, layout) }.getOrNull() ?: return@mapNotNull null
            readDiskBook(folder, gson, expectedEntry = entry)?.also {
                if (layout.mode == StorageRoot.Mode.SHARED) storage.noteSharedGallery(relativeFolder(entry))
            }?.let { layout to it }
        }
        return candidates.firstOrNull { it.second.isComplete() }?.first
            ?: candidates.firstOrNull { it.second.hasSelection() }?.first
            ?: candidates.firstOrNull()?.first
            ?: active
    }

    /** Resolve the book once when opening; page reads then use this explicit location. */
    fun readingRoot(entry: Video): String = readingLayout(entry).root.absolutePath
    private fun writePageMetadata(file: File, image: GalleryImage, encodedBytes: Int) {
        val fields = gson.toJsonTree(image).asJsonObject
        fields.addProperty("encodedBytes", encodedBytes)
        write(file, gson.toJson(fields).toByteArray())
    }

    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.part")
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建下载目录" }
            temporary.writeBytes(bytes)
            check(temporary.renameTo(file)) { "无法保存图页缓存" }
        } catch (error: Exception) {
            throw GalleryStorageException(error.message ?: "无法保存下载文件", error)
        } finally { temporary.delete() }
    }

    suspend fun detail(entry: Video, offlineOnly: Boolean = false, sourceRoot: String = ""): GalleryDetail = withContext(Dispatchers.IO) {
        val folder = bookDirectory(entry, sourceRoot)
        val downloaded = readDiskBook(folder, gson, expectedEntry = entry)
        if (offlineOnly) {
            check(downloaded != null) { "图集下载信息缺失，请重新下载" }
            val job = currentCoroutineContext()
            val audit = downloaded.audit { job.ensureActive() }
            check(audit.validPages.isNotEmpty()) { "没有可离线读取的完整图页，请在下载中心检查并修复" }
            val requested = if (downloaded.hasCompleteMarker && audit.complete) audit.validPages
                else downloaded.selection.ifEmpty { (1..audit.total).toList() }
            return@withContext downloaded.detail.copy(cached = true, downloadedPages = audit.validPages, requestedPages = requested)
        }
        val site = SiteRegistry.byIdOrDefault(entry.site)
        val file = File(directory(site), "${key(entry.url)}.gallery.json")
        val previous = readJson(file, GalleryDetail::class.java, gson, MAX_BOOK_JSON_BYTES)?.let { normalizeDetail(it, entry) }
        try {
            AdapterRegistry.adapter(site, network).gallery(entry.url).also {
                // Reading can continue when only the dispensable cache write failed.
                runCatching { write(file, gson.toJson(it).toByteArray()) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is GalleryLoginRequiredException) throw error
            val saved = downloaded?.detail ?: previous
            if (saved != null && (error is IOException || error is GalleryAccessException)) saved.copy(cached = true) else throw error
        }
    }

    fun cachedPageUrl(entry: Video, page: Int, sourceRoot: String = ""): String? =
        cachedPageUrl(entry, page, bookDirectory(entry, sourceRoot))

    internal fun downloadedPageUrl(entry: Video, page: Int, sourceRoot: String): String? =
        readLinks(bookDirectory(entry, sourceRoot), gson)[page]?.takeIf { it.isNotBlank() }

    internal fun cachedPageUrl(entry: Video, page: Int, layout: StorageRoot.Layout): String? =
        cachedPageUrl(entry, page, bookFolder(entry, layout))

    private fun cachedPageUrl(entry: Video, page: Int, folder: File): String? =
        readLinks(folder, gson)[page]?.takeIf { it.isNotBlank() } ?: readJson(
            File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json"),
            KnownPages::class.java, gson, MAX_BOOK_JSON_BYTES
        )?.links?.get(page)?.takeIf { it.isNotBlank() }

    @Synchronized fun forgetCachedPageLink(entry: Video, page: Int, staleUrl: String) {
        val file = File(directory(SiteRegistry.byIdOrDefault(entry.site)), "${key(entry.url)}.links.json")
        val links = readJson(file, KnownPages::class.java, gson, MAX_BOOK_JSON_BYTES)?.links.orEmpty()
        // Other pages can be read concurrently. The persistent download index belongs to the download worker.
        if (links[page] == staleUrl) write(file, gson.toJson(KnownPages(links - page)).toByteArray())
    }

    @Synchronized private fun rememberDownloadPage(folder: File, image: GalleryImage) {
        val file = File(folder, "links.json")
        val links = readJson(file, KnownPages::class.java, gson, MAX_BOOK_JSON_BYTES)?.links.orEmpty()
        if (links[image.index] != image.pageUrl) write(file, gson.toJson(KnownPages(links + (image.index to image.pageUrl))).toByteArray())
    }

    fun isDownloaded(entry: Video, sourceRoot: String = ""): Boolean =
        readLayouts(sourceRoot).any { layout ->
            readDiskBook(bookFolder(entry, layout), gson, expectedEntry = entry)?.isComplete() == true
        }

    internal fun isSelectionDownloaded(entry: Video, pages: Collection<Int>, sourceRoot: String = ""): Boolean =
        readLayouts(sourceRoot).any { layout ->
            readDiskBook(bookFolder(entry, layout), gson, expectedEntry = entry)?.hasSelection(pages) == true
        }

    internal fun savedPageCount(entry: Video, sourceRoot: String = ""): Int? =
        readLayouts(sourceRoot).firstNotNullOfOrNull { layout ->
            runCatching { readDiskBook(bookFolder(entry, layout), gson, expectedEntry = entry)?.detail?.pageCount }.getOrNull()
        }

    private fun readLayouts(sourceRoot: String): List<StorageRoot.Layout> = if (sourceRoot.isBlank()) storage.readableLayouts()
        else listOf(storage.sourceLayout(sourceRoot))

    suspend fun prepareDownload(entry: Video): GalleryDetail {
        checkDownloadsAllowed()
        return prepareDownload(entry, downloadLayout())
    }

    internal suspend fun prepareDownload(entry: Video, layout: StorageRoot.Layout): GalleryDetail = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, layout)
        folder.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val file = File(folder, "book.json")
        val saved = readDiskBook(folder, gson, expectedEntry = entry)?.detail
        val gallery = saved ?: checkNotNull(normalizeDetail(detail(entry), entry)) { "图集信息无效，请刷新后重试" }
        if (saved == null) write(file, gson.toJson(gallery).toByteArray())
        if (layout.mode == StorageRoot.Mode.SHARED) storage.noteSharedGallery(relativeFolder(entry))
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
        val stored = savedPage(folder, pageUrl, index, gson)
        if (stored?.valid() == true) {
            rememberDownloadPage(folder, stored.image)
            return@withContext stored.image to stored.file.length()
        }
        val cachedFolder = directory(site)
        val cached = savedPage(cachedFolder, pageUrl, index, gson)
        val cachedMeta = cached?.image
        val cachedBytes = cachedMeta?.let {
            cached.takeIf(SavedPage::valid)?.let { page -> readImageBytes(page.file) }
                ?: cachedStrips(File(cachedFolder, "$id.strips"), it)?.let { bytes ->
                    runCatching { renderGalleryStrips(bytes, it.imageStripCount).takeIf(::validImage) }.getOrNull()
                }
        }
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
        writePageMetadata(metadataFile, normalized, bytes.size)
        rememberDownloadPage(folder, normalized)
        normalized to bytes.size.toLong()
    }

    suspend fun finishDownload(entry: Video, count: Int) = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, storage.layout())
        val audit = auditBook(folder, entry)
        check(audit.complete && audit.total == count) { "图集下载尚未完整保存" }
        writeComplete(folder, count)
    }

    private fun writeComplete(folder: File, count: Int) {
        write(File(folder, "readme.txt"), RECOVERY_NOTE.toByteArray())
        val selection = File(folder, "selection.json")
        if (selection.exists() && !selection.delete()) throw GalleryStorageException("无法移除旧选页记录")
        write(File(folder, "complete"), count.toString().toByteArray())
    }

    private suspend fun auditBook(folder: File, entry: Video): GalleryAudit {
        val book = readDiskBook(folder, gson, expectedEntry = entry) ?: error("图集下载信息缺失，无法检查")
        val job = currentCoroutineContext()
        return book.audit { job.ensureActive() }
    }

    suspend fun auditDownload(entry: Video, sourceRoot: String = ""): GalleryAudit = withContext(Dispatchers.IO) {
        auditBook(bookDirectory(entry, sourceRoot), entry)
    }

    internal suspend fun auditWriteTarget(entry: Video, layout: StorageRoot.Layout): GalleryAudit? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        readDiskBook(bookFolder(entry, layout), gson, expectedEntry = entry)?.audit { job.ensureActive() }
    }

    internal suspend fun commitDownload(entry: Video, layout: StorageRoot.Layout, requested: Set<Int>): GalleryAudit = withContext(Dispatchers.IO) {
        val folder = bookFolder(entry, layout)
        val audit = auditBook(folder, entry)
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

    suspend fun removeDownload(entry: Video, sourceRoot: String = "", knownRoot: String = "") = withContext(Dispatchers.IO) {
        val relative = relativeFolder(entry)
        val layouts = storage.galleryRemovalLayouts(sourceRoot, knownRoot, relative)
        for (layout in layouts) {
            val folder = bookFolder(entry, layout)
            if (layout.mode == StorageRoot.Mode.SHARED) storage.galleryRemovalLayouts(layout.root.absolutePath, relativeFolder = relative)
            check(!folder.exists() || folder.deleteRecursively()) { "部分下载图页无法删除，请重试" }
            // A revoked permission can make File.exists() report false for a still-present book.
            if (layout.mode == StorageRoot.Mode.SHARED) {
                storage.galleryRemovalLayouts(layout.root.absolutePath, relativeFolder = relative)
                storage.forgetSharedGallery(relative)
            }
        }
    }

    internal fun hasOtherCopy(entry: Video, sourceRoot: String): Boolean {
        val removed = storage.sourceLayout(sourceRoot)
        return storage.readableLayouts().any { it != removed && File(bookFolder(entry, it), "book.json").isFile }
    }

    @Synchronized private fun rememberPage(site: SiteContext, galleryUrl: String?, image: GalleryImage) {
        if (galleryUrl == null) return
        val file = File(directory(site), "${key(galleryUrl)}.links.json")
        val links = readJson(file, KnownPages::class.java, gson, MAX_BOOK_JSON_BYTES)?.links.orEmpty()
        if (links[image.index] != image.pageUrl) write(file, gson.toJson(KnownPages(links + (image.index to image.pageUrl))).toByteArray())
    }

    /**
     * 功能 F34：站点开启“原图优先”且图源给出原图地址时，返回指向原图的视图。
     * 元数据仍保留显示图字段（originalUrl 一并落盘），已缓存/已下载文件不追溯变化。
     */
    private fun originalView(site: SiteContext, image: GalleryImage): GalleryImage {
        val original = image.originalUrl
        return if (originalImages(site) && !original.isNullOrBlank()) image.copy(imageUrl = original) else image
    }

    suspend fun page(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null, entry: Video? = null,
        offlineOnly: Boolean = false, sourceRoot: String = "", expectedPage: Int? = null): RenderedPage = withContext(Dispatchers.IO) {
        val original = originalPage(site, pageUrl, force, galleryUrl, entry, offlineOnly, sourceRoot, expectedPage)
        RenderedPage(original.image, decode(original.bytes), original.cached)
    }
    suspend fun originalPage(site: SiteContext, pageUrl: String, force: Boolean = false, galleryUrl: String? = null, entry: Video? = null,
        offlineOnly: Boolean = false, sourceRoot: String = "", expectedPage: Int? = null): OriginalPage = withContext(Dispatchers.IO) {
        val loaded = loadPageContent(site, pageUrl, force, galleryUrl, entry, offlineOnly, sourceRoot, expectedPage,
            readFile = ::readImageBytes, readBytes = { it })
        OriginalPage(loaded.image, loaded.content, loaded.cached)
    }

    internal suspend fun thumbnail(site: SiteContext, pageUrl: String, sizePx: Int, force: Boolean = false,
        entry: Video, offlineOnly: Boolean, sourceRoot: String, expectedPage: Int? = null): RenderedPage = withContext(Dispatchers.IO) {
        val loaded = loadPageContent(site, pageUrl, force, entry.url, entry, offlineOnly, sourceRoot, expectedPage,
            readFile = { decodeGalleryThumbnail(it, sizePx) },
            readBytes = { bytes ->
                decodeGalleryThumbnail(sizePx) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                    ?: error("图片解码失败，请重试此页")
            },
            readStrips = { bytes, strips -> decodeStripThumbnail(bytes, strips, sizePx) })
        RenderedPage(loaded.image, loaded.content, loaded.cached)
    }

    private suspend fun <T : Any> loadPageContent(site: SiteContext, pageUrl: String, force: Boolean, galleryUrl: String?,
        entry: Video?, offlineOnly: Boolean, sourceRoot: String, expectedPage: Int?, readFile: (File) -> T?, readBytes: (ByteArray) -> T,
        readStrips: ((ByteArray, Int) -> T)? = null): PageContent<T> {
        val id = key(pageUrl.substringBefore('?'))
        if (entry != null && (offlineOnly || !force)) {
            require(entry.site == site.id)
            val folder = bookDirectory(entry, sourceRoot)
            val saved = savedPage(folder, pageUrl, expectedPage, gson)
            if (saved != null && readLinks(folder, gson)[saved.image.index] == pageUrl) {
                val content = saved.takeIf(SavedPage::valid)?.let { runCatching { readFile(it.file) }.getOrNull() }
                if (content != null) return PageContent(saved.image, content, true)
                check(!offlineOnly) { "下载图页已损坏，请在下载中心检查并修复" }
            }
        }
        check(!offlineOnly) { "下载图页缺失，请在下载中心重新下载；离线阅读不会连接网络" }
        val folder = directory(site)
        val metadataFile = File(folder, "$id.page.json")
        val imageFile = File(folder, "$id.image")
        val stripsFile = File(folder, "$id.strips")
        val cached = savedPage(folder, pageUrl, expectedPage, gson)
        val previous = cached?.image
        if (!force && previous != null) {
            if (cached.valid()) runCatching { readFile(imageFile) }.getOrNull()?.let {
                imageFile.setLastModified(System.currentTimeMillis())
                runCatching { rememberPage(site, galleryUrl, previous) }
                return PageContent(previous, it, true)
            }
            cachedStrips(stripsFile, previous)?.let { raw ->
                val content = if (readStrips != null) {
                    runCatching { readStrips(raw, previous.imageStripCount) }.getOrNull()
                        ?.also { stripsFile.setLastModified(System.currentTimeMillis()) }
                } else {
                    runCatching { renderGalleryStrips(raw, previous.imageStripCount).takeIf(::validImage) }.getOrNull()?.let { bytes ->
                        runCatching {
                            write(imageFile, bytes)
                            writePageMetadata(metadataFile, previous, bytes.size)
                            stripsFile.delete()
                            trim(folder)
                        }
                        readBytes(bytes)
                    }
                }
                if (content != null) {
                    runCatching { rememberPage(site, galleryUrl, previous) }
                    return PageContent(previous, content, true)
                }
            }
        }
        val requestUrl = if (force) previous?.reloadUrl ?: pageUrl else pageUrl
        val image = AdapterRegistry.adapter(site, network).galleryImage(requestUrl)
        check(image.index > 0 && (expectedPage == null || image.index == expectedPage)) { "原站返回了错误的图页编号" }
        val normalized = image.copy(pageUrl = pageUrl)
        val source = originalView(site, image)
        if (readStrips != null && image.imageStripCount != 0) {
            // Assemble the preview at thumbnail size and keep the encoded source for a later full decode.
            val raw = fetchImageBytes(site, source)
            check(validImage(raw)) { "图源未返回可识别的图片，请重试此页" }
            val content = readStrips(raw, image.imageStripCount)
            runCatching {
                write(stripsFile, raw)
                imageFile.delete()
                writePageMetadata(metadataFile, normalized, raw.size)
                rememberPage(site, galleryUrl, normalized)
                trim(folder)
            }
            return PageContent(normalized, content, false)
        }
        val bytes = imageBytes(site, source)
        check(validImage(bytes)) { "图源未返回可识别的图片，请重试此页" }
        runCatching {
            write(imageFile, bytes)
            writePageMetadata(metadataFile, normalized, bytes.size)
            stripsFile.delete()
            rememberPage(site, galleryUrl, normalized)
            trim(folder)
        }
        return PageContent(normalized, readBytes(bytes), false)
    }
    private suspend fun imageBytes(site: SiteContext, image: GalleryImage): ByteArray {
        val bytes = fetchImageBytes(site, image)
        if (image.imageStripCount != 0) check(validImage(bytes)) { "分条图页已损坏，请重试" }
        return renderGalleryStrips(bytes, image.imageStripCount)
    }

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
    private fun validImage(bytes: ByteArray): Boolean {
        if (bytes.size !in 1..MAX_GALLERY_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        return ImageStructure(bytes.size.toLong()) { offset, buffer, count ->
            bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
        }.complete(galleryContainerMime(bytes) ?: bounds.outMimeType)
    }

    private fun readImageBytes(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val count = file.length()
            if (count !in 1L..MAX_GALLERY_IMAGE_BYTES.toLong()) return null
            val bytes = ByteArray(count.toInt())
            java.io.DataInputStream(input).readFully(bytes)
            bytes.takeIf { input.read() == -1 }
        }
    }.getOrNull()
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
            for (bounds in galleryStripBounds(height, strips)) {
                val destTop = (bounds.destinationTop.toLong() * targetHeight / height).toInt()
                val destBottom = ((bounds.destinationTop + bounds.height).toLong() * targetHeight / height).toInt()
                if (destBottom > destTop) {
                    val strip = decoder.decodeRegion(Rect(0, bounds.sourceTop, width, bounds.sourceTop + bounds.height), sample)
                        ?: error("无法解码分条图页")
                    try {
                        canvas.drawBitmap(strip, null, Rect(0, destTop, targetWidth, destBottom), paint)
                    } finally { strip.recycle() }
                }
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
        return file.takeIf(::isCompleteGalleryImage)?.let(::readImageBytes)
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

        internal fun relativeFolder(entry: Video): String {
            val site = SiteRegistry.byId(entry.site) ?: error("未知图集站点")
            require(entry.mediaKind == MediaKind.GALLERY && entry.id.isNotBlank())
            return "${site.id}/${key(entry.id)}"
        }

        private const val MAX_PAGE_NUMBER = 100_000
        private const val MAX_BOOK_JSON_BYTES = 16L * 1024 * 1024
        private const val MAX_PAGE_JSON_BYTES = 128L * 1024

        internal fun directChild(parent: File, file: File): Boolean = runCatching {
            file.canonicalFile == File(parent.canonicalFile, file.name) && file.absoluteFile.parentFile == parent.absoluteFile
        }.getOrDefault(false)

        private fun <T> readJson(file: File, type: Class<T>, gson: Gson, limit: Long): T? = runCatching {
            if (!file.isFile || file.length() !in 1..limit || !directChild(file.parentFile ?: return null, file)) return null
            file.bufferedReader().use { gson.fromJson(it, type) }
        }.getOrNull()

        private fun normalizeDetail(gallery: GalleryDetail, expectedEntry: Video? = null): GalleryDetail? = runCatching {
            val entry = gallery.entry
            if (gallery.pageCount !in 1..MAX_PAGE_NUMBER || entry.id.isNullOrBlank() || SiteRegistry.byId(entry.site) == null ||
                (expectedEntry != null && (entry.id != expectedEntry.id || entry.site != expectedEntry.site))) return null
            val links = gallery.pageLinks.orEmpty()
            if (links.any { it.index !in 1..gallery.pageCount || it.url.isNullOrBlank() }) return null
            gallery.copy(entry = entry.copy(title = entry.title.orEmpty(), url = entry.url.orEmpty(), thumb = entry.thumb.orEmpty(),
                duration = entry.duration.orEmpty(), views = entry.views.orEmpty(), rating = entry.rating.orEmpty(),
                type = entry.type.orEmpty(), preview = entry.preview.orEmpty(), mediaKind = MediaKind.GALLERY),
                pageLinks = links, alternativeTitle = gallery.alternativeTitle.orEmpty(), uploader = gallery.uploader.orEmpty(),
                language = gallery.language.orEmpty(), description = gallery.description.orEmpty(), groups = gallery.groups.orEmpty().mapValues { (_, value) ->
                    value?.filterNotNull().orEmpty()
                },
                downloadedPages = gallery.downloadedPages.orEmpty(), requestedPages = gallery.requestedPages.orEmpty())
        }.getOrNull()

        private fun readLinks(folder: File, gson: Gson): Map<Int, String> =
            readJson(File(folder, "links.json"), KnownPages::class.java, gson, MAX_BOOK_JSON_BYTES)?.links.orEmpty()

        private fun savedPage(folder: File, url: String, index: Int?, gson: Gson): SavedPage? = runCatching {
            if (url.isBlank()) return null
            val id = key(url.substringBefore('?'))
            val fields = readJson(File(folder, "$id.page.json"), com.google.gson.JsonObject::class.java, gson, MAX_PAGE_JSON_BYTES)
                ?: return null
            val metadata = gson.fromJson(fields, GalleryImage::class.java)
            if (metadata.pageUrl != url || metadata.index !in 1..MAX_PAGE_NUMBER ||
                (index != null && metadata.index != index) || metadata.imageUrl.isNullOrBlank() ||
                (metadata.imageStripCount != 0 && metadata.imageStripCount !in 2..20)) return null
            val expectedBytes = fields.get("encodedBytes")?.let { element ->
                element.asString.toLongOrNull()?.takeIf { it in 1L..MAX_GALLERY_IMAGE_BYTES.toLong() } ?: return null
            }
            val file = File(folder, "$id.image")
            if (!directChild(folder, file)) return null
            SavedPage(metadata, file, expectedBytes)
        }.getOrNull()

        internal fun readDiskBook(folder: File, gson: Gson, expectedEntry: Video? = null, expectedSite: String? = null): DiskBook? {
            val raw = readJson(File(folder, "book.json"), GalleryDetail::class.java, gson, MAX_BOOK_JSON_BYTES) ?: return null
            val detail = normalizeDetail(raw, expectedEntry) ?: return null
            if (expectedSite != null && (detail.entry.site != expectedSite || folder.name != key(detail.entry.id))) return null
            val selection = readJson(File(folder, "selection.json"), DownloadSelection::class.java, gson, MAX_BOOK_JSON_BYTES)
                ?.selected.orEmpty().filterNotNull().distinct().sorted()
            val complete = File(folder, "complete")
            val marker = runCatching {
                if (!complete.isFile || complete.length() !in 1L..32L || !directChild(folder, complete)) null
                else complete.readText().trim().toIntOrNull()
            }.getOrNull()
            return DiskBook(folder, detail, readLinks(folder, gson), selection, marker == detail.pageCount, gson)
        }

        fun isCompleteDownload(folder: File, gson: Gson = Gson()): Boolean = readDiskBook(folder, gson)?.isComplete() == true

        /**
         * Left beside every finished book so a user who copies the folder elsewhere knows what it
         * is without this app. Plain text on purpose: it must survive any file manager or cloud sync.
         */
        const val RECOVERY_NOTE = "本文件夹是一份已下载的图集（漫画或写真），由 R34V 保存。\n\n" +
            "book.json  详情与页数\nlinks.json 页码与图源的对应关系\n*.image    原始图页（从第 1 页起依次保存）\n" +
            "complete   下载完成标记\n\n把整个文件夹放回 R34V/comics/<站点>/ 之下，R34V 会重新识别并显示在下载中心。"
    }
}

/** Check encoded size, decoder recognition and container boundaries without allocating a bitmap. */
internal fun isCompleteGalleryImage(file: File, maxBytes: Long = MAX_GALLERY_IMAGE_BYTES.toLong()): Boolean = runCatching {
    if (!file.isFile || file.length() !in 1..maxBytes) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
    RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(minOf(input.length(), 4096L).toInt())
        input.readFully(header)
        val mime = galleryContainerMime(header)
        val length = input.length()
        length in 1..maxBytes && ImageStructure(length) { offset, buffer, count ->
            input.seek(offset)
            input.readFully(buffer, 0, count)
        }.complete(mime ?: bounds.outMimeType)
    }
}.getOrDefault(false)

/** AVIF and HEIF are reported under the same MIME type by some Android decoders. */
internal fun galleryContainerMime(bytes: ByteArray): String? = ImageStructure(bytes.size.toLong()) { offset, buffer, count ->
    bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
}.heifMime()

private class ImageStructure(val length: Long, val readAt: (Long, ByteArray, Int) -> Unit) {
    private val buffer = ByteArray(4096)
    private var bufferStart = -buffer.size.toLong()
    private var bufferEnd = 0L

    private fun byte(offset: Long): Int {
        if (offset !in 0 until length) return -1
        if (offset !in bufferStart until bufferEnd) {
            bufferStart = offset / buffer.size * buffer.size
            val count = minOf(length - bufferStart, buffer.size.toLong()).toInt()
            readAt(bufferStart, buffer, count)
            bufferEnd = bufferStart + count
        }
        return buffer[(offset - bufferStart).toInt()].toInt().and(0xff)
    }

    private fun number(offset: Long, count: Int, littleEndian: Boolean = false): Long = (0 until count).fold(0L) { result, index ->
        result shl 8 or byte(offset + if (littleEndian) count - index - 1 else index).toLong()
    }

    private fun tag(offset: Long, vararg values: String): Boolean = values.any { value ->
        value.indices.all { byte(offset + it) == value[it].code }
    }

    private fun tag(offset: Long, vararg bytes: Int): Boolean = bytes.indices.all { byte(offset + it) == bytes[it] }

    fun heifMime(): String? {
        if (length < 16 || !tag(4, "ftyp")) return null
        val end = minOf(number(0, 4), length, 4096L)
        if (end < 16) return null
        val brands = listOf(8L) + (16L..end - 4 step 4).toList()
        return when {
            brands.any { tag(it, "avif", "avis") } -> "image/avif"
            brands.any { tag(it, "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs") } -> "image/heic"
            brands.any { tag(it, "mif1", "msf1") } -> "image/heif"
            else -> null
        }
    }

    fun complete(mime: String?): Boolean = when {
        byte(0) == 0xff && byte(1) == 0xd8 -> jpeg()
        tag(0, 137, 80, 78, 71, 13, 10, 26, 10) -> png()
        tag(0, "GIF87a", "GIF89a") -> gif()
        tag(0, "RIFF") && tag(8, "WEBP") -> webp()
        tag(0, "BM") -> bmp()
        mime == "image/heic" && heifMime() == null -> false
        mime in setOf("image/heif", "image/heic", "image/avif") || heifMime() != null -> isoBoxes()
        // Preserve decoder-supported legacy formats for which we do not have a container check.
        else -> mime != null
    }

    private fun jpeg(): Boolean {
        var offset = 2L
        while (offset + 3 < length && byte(offset++) == 0xff) {
            while (byte(offset) == 0xff) offset++
            val marker = byte(offset++)
            val size = number(offset, 2)
            if (size < 2 || size > length - offset) return false
            offset += size
            if (marker == 0xda) {
                // An EXIF thumbnail can also contain an EOI, so search only after the first scan starts.
                for (end in length - 2 downTo maxOf(offset, length - 64 * 1024L)) {
                    if (byte(end + 1) == 0xd9 && byte(end) == 0xff) return true
                }
                return false
            }
        }
        return false
    }

    private fun png(): Boolean {
        var offset = 8L
        var imageData = false
        while (offset + 12 <= length) {
            val bytes = number(offset, 4)
            if (bytes > length - offset - 12) return false
            if (tag(offset + 4, "IDAT")) imageData = true
            if (tag(offset + 4, "IEND")) return imageData && bytes == 0L && number(offset + 8, 4) == 0xae426082L
            offset += bytes + 12
        }
        return false
    }

    private fun gif(): Boolean {
        if (length < 14) return false
        fun tableBytes(flags: Int) = if (flags and 0x80 == 0) 0 else 3 shl ((flags and 7) + 1)
        fun skipBlocks(start: Long): Long {
            var position = start
            while (position < length) {
                val count = byte(position++)
                if (count == 0) return position
                if (count > length - position) return -1L
                position += count
            }
            return -1L
        }
        var offset = 13L + tableBytes(byte(10))
        var imageData = false
        while (offset < length) {
            when (byte(offset++)) {
                0x21 -> {
                    if (offset >= length) return false
                    offset = skipBlocks(offset + 1) // Extension label, then its length-prefixed blocks.
                    if (offset < 0) return false
                }
                0x2c -> {
                    if (offset + 9 >= length) return false
                    val start = offset + 9 + tableBytes(byte(offset + 8))
                    if (start + 1 >= length || byte(start) !in 2..12 || byte(start + 1) <= 0) return false
                    offset = skipBlocks(start + 1)
                    if (offset < 0) return false
                    imageData = true
                }
                0x3b -> return imageData
                else -> return false
            }
        }
        return false
    }

    private fun webp(): Boolean {
        val end = number(4, 4, littleEndian = true) + 8
        if (end !in 20..length) return false
        var offset = 12L
        while (offset + 8 <= end) {
            val size = number(offset + 4, 4, littleEndian = true)
            offset += 8 + size + (size and 1L)
        }
        return offset == end
    }

    private fun bmp(): Boolean {
        val end = number(2, 4, littleEndian = true)
        val pixels = number(10, 4, littleEndian = true)
        return pixels in 14 until length && (end == 0L || end in (pixels + 1)..length)
    }

    private fun isoBoxes(): Boolean {
        var offset = 0L
        var media = false
        while (offset + 8 <= length) {
            val size = number(offset, 4)
            val header = if (size == 1L) 16 else 8
            val total = when (size) {
                0L -> length - offset
                1L -> if (offset + 16 <= length) number(offset + 8, 8) else return false
                else -> size
            }
            if (total < header || total > length - offset) return false
            if (tag(offset + 4, "mdat", "meta") && total > header) media = true
            offset += total
        }
        return offset == length && media
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
