package com.r34v.r34v.data

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.r34v.r34v.R34Application
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportMode { SAVE_IMAGES, SHARE_IMAGES, CBZ }
data class ExportFile(val file: File, val mime: String)
data class PreparedExport(val folder: File, val files: List<ExportFile>, val mode: ExportMode)

/** Each job owns one temporary directory. Only complete original files reach the system picker. */
class GalleryExporter(private val root: File) {
    suspend fun prepare(title: String, pages: List<Int>, mode: ExportMode, load: suspend (Int) -> ByteArray,
        report: (String) -> Unit = {}): PreparedExport = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty() && pages.size <= LocalLibrary.MAX_PAGES && pages.all { it > 0 }) { "最多导出 2000 页" }
        require(mode != ExportMode.SHARE_IMAGES || pages.size <= 100) { "单次最多分享 100 张图片，请分批选择" }
        root.mkdirs()
        // Shared recipients may still be reading yesterday's files; never delete an active job here.
        root.listFiles().orEmpty().filter { it.isDirectory && it.lastModified() < System.currentTimeMillis() - 2 * 86_400_000L }
            .forEach { remove(it) }
        val folder = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) { "无法创建导出目录" } }
        try {
            var bytesTotal = 0L
            val outputs = mutableListOf<ExportFile>()
            val archive = if (mode == ExportMode.CBZ) File(folder, "${safeName(title)}.cbz") else null
            val zip = archive?.let { ZipOutputStream(it.outputStream().buffered()) }
            zip.use {
                pages.distinct().sorted().forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    report("正在准备 ${index + 1} / ${pages.size} · 第 $page 页")
                    val bytes = load(page)
                    require(bytes.size.toLong() <= LocalLibrary.MAX_IMAGE) { "单张图片超过 50 MiB" }
                    bytesTotal += bytes.size
                    check(bytesTotal <= LocalLibrary.MAX_BOOK) { "本次导出超过 1 GiB，请分批导出" }
                    val mime = imageMime(bytes)
                    val name = "%06d.%s".format(java.util.Locale.ROOT, page, extension(mime))
                    if (zip != null) {
                        zip.putNextEntry(ZipEntry(name)); write(bytes, zip); zip.closeEntry()
                    } else {
                        val file = File(folder, "${safeName(title)}-$name")
                        file.outputStream().use { write(bytes, it) }; outputs += ExportFile(file, mime)
                    }
                }
            }
            if (archive != null) outputs += ExportFile(archive, "application/vnd.comicbook+zip")
            currentCoroutineContext().ensureActive()
            PreparedExport(folder, outputs, mode)
        } catch (error: Throwable) { remove(folder); throw error }
    }
    fun remove(folder: File) {
        check(folder.canonicalFile.parentFile == root.canonicalFile) { "无效的导出目录" }
        folder.deleteRecursively()
    }
    companion object {
        fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80).ifBlank { "R34V" }
        fun imageMime(bytes: ByteArray): String {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType != null) { "图页不是有效图片" }
            return bounds.outMimeType
        }
        fun extension(mime: String) = when (mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/avif" -> "avif"; "image/heif", "image/heic" -> "heic"; else -> error("暂不支持此图片格式：$mime")
        }
        private suspend fun write(bytes: ByteArray, output: OutputStream) {
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(65536, bytes.size - offset); output.write(bytes, offset, count); offset += count
            }
        }
        suspend fun copy(file: File, output: OutputStream) {
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}

class GalleryExportModel(application: Application) : AndroidViewModel(application) {
    private val app = application as R34Application
    private val exporter = GalleryExporter(File(app.cacheDir, "exports"))
    var prepared by mutableStateOf<PreparedExport?>(null); private set
    var delivering by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var message by mutableStateOf(""); private set
    private var job: Job? = null
    fun dismissMessage() { message = "" }
    fun start(entry: Video, gallery: GalleryDetail, pages: List<Int>, mode: ExportMode, offline: Boolean, sourceRoot: String) {
        if (busy || prepared != null) return
        require(pages.all { it in 1..gallery.pageCount })
        busy = true; message = ""; progress = "正在准备导出…"
        job = viewModelScope.launch {
            try {
                val site = SiteRegistry.byIdOrDefault(entry.site)
                prepared = exporter.prepare(entry.title, pages, mode, { page ->
                    if (entry.site == SiteRegistry.LOCAL.id) {
                        val uri = app.localLibrary.imageUri(entry.id, page) ?: error("本地图片已移除，请重新导入")
                        check(app.localLibrary.ownsImageUri(uri))
                        val file = File(java.net.URI(uri))
                        check(file.length() <= LocalLibrary.MAX_IMAGE) { "图片过大" }; file.readBytes()
                    } else {
                        val url = app.galleries.cachedPageUrl(entry, page, sourceRoot)
                            ?: if (offline) error("第 $page 页尚未下载，请先下载或选择已有页")
                            else AdapterRegistry.adapter(site, app.network).galleryPageLink(gallery, page)
                        app.galleries.originalPage(site, url, galleryUrl = entry.url, entry = entry, offlineOnly = offline, sourceRoot = sourceRoot)
                            .also { check(it.image.index == page) { "图源返回了错误页码" } }.bytes
                    }
                }) { progress = it }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "导出失败：${error.message}"
            } finally { busy = false }
        }
    }
    fun beginDelivery() { delivering = true }
    fun shared() { prepared = null; delivering = false; message = "已打开系统分享" }
    fun cancel() {
        job?.cancel()
        if (!busy) { prepared?.let { exporter.remove(it.folder) }; prepared = null }
        delivering = false
    }
    fun save(uri: Uri?, tree: Boolean) {
        delivering = false
        if (uri == null) { cancel(); return }
        val batch = prepared
        busy = true; progress = "正在保存…"
        job = viewModelScope.launch {
            val created = mutableListOf<Uri>()
            try {
                withContext(Dispatchers.IO) {
                    if (!tree) created += uri
                    check(batch != null) { "导出任务已中断，请重新导出" }
                    val resolver = app.contentResolver
                    val parent = if (tree) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else null
                    batch.files.forEachIndexed { index, item ->
                        currentCoroutineContext().ensureActive()
                        progress = "正在保存 ${index + 1} / ${batch.files.size}"
                        val target = if (parent == null) uri else DocumentsContract.createDocument(resolver, parent, item.mime, item.file.name)
                            ?.also { created += it } ?: error("无法创建目标文件")
                        resolver.openOutputStream(target, "wt")?.use { GalleryExporter.copy(item.file, it) } ?: error("目标位置无法写入")
                    }
                }
                message = "已保存 ${batch!!.files.size} 个文件"
            } catch (error: Exception) {
                var remaining = 0
                withContext(NonCancellable + Dispatchers.IO) {
                    created.forEach { if (!runCatching { DocumentsContract.deleteDocument(app.contentResolver, it) }.getOrDefault(false)) remaining++ }
                }
                val cleanup = if (remaining > 0) "；$remaining 个未完成文件无法移除，请在目标位置删除" else ""
                message = if (error is CancellationException) "已取消保存$cleanup" else "保存失败：${error.message}，请重新选择位置$cleanup"
                if (error is CancellationException) throw error
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { batch?.let { exporter.remove(it.folder) } }
                prepared = null; busy = false
            }
        }
    }
}
