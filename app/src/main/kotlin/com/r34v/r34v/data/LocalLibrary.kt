package com.r34v.r34v.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class LocalBook(val id: String = "", val title: String = "", val files: List<String> = emptyList(), val bytes: Long = 0, val at: Long = System.currentTimeMillis())

/** Copies selected documents into an app-owned library. Never extracts archive paths. */
class LocalLibrary(private val context: Context) {
    companion object {
        const val MAX_PAGES = 2000
        const val MAX_IMAGE = 50L * 1024 * 1024
        const val MAX_BOOK = 1024L * 1024 * 1024
        fun imageName(name: String) = name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        private val digits = Regex("\\d+|\\D+")
        val naturalOrder = Comparator<String> { a, b ->
            val left = digits.findAll(a.lowercase()).map { it.value }.toList(); val right = digits.findAll(b.lowercase()).map { it.value }.toList()
            var result = 0
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]; val y = right[i]
                result = if (x.first().isDigit() && y.first().isDigit()) {
                    val nx = x.trimStart('0').ifBlank { "0" }; val ny = y.trimStart('0').ifBlank { "0" }
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else x.compareTo(y)
                if (result != 0) break
            }
            if (result != 0) result else left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
    private val root get() = File(context.filesDir, "local-library").apply { mkdirs() }
    private val gson = com.google.gson.Gson()
    val changes = MutableStateFlow(0)
    private fun folder(id: String): File { require(Regex("[a-f0-9]{64}").matches(id)); return File(root, id) }
    fun book(id: String): LocalBook? = runCatching {
        val value = gson.fromJson(File(folder(id), "book.json").readText(), LocalBook::class.java)
        value.takeIf { it.id == id && it.files.size in 1..MAX_PAGES && it.files.all { f -> Regex("\\d+\\.image").matches(f) && File(folder(id), f).isFile } }
    }.getOrNull()
    fun books(): List<LocalBook> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("incoming-") }.mapNotNull { book(it.name) }.sortedByDescending { it.at }
    fun video(book: LocalBook) = Video(book.id, book.title, "r34local://${book.id}", Uri.fromFile(File(folder(book.id), book.files.first())).toString(), "${book.files.size} 页", site = SiteRegistry.LOCAL.id, mediaKind = MediaKind.GALLERY)
    fun detail(video: Video): GalleryDetail {
        val book = book(video.id) ?: error("本地文件不存在，请重新导入同一文件或目录")
        return GalleryDetail(video(book), book.files.size, emptyList(), book.files.size, description = "本地导入 · ${book.files.size} 页", cached = true)
    }
    suspend fun page(id: String, index: Int): GalleryRepository.RenderedPage = withContext(Dispatchers.IO) {
        val book = book(id) ?: error("本地文件不存在，请重新导入")
        require(index in 1..book.files.size)
        val file = File(folder(id), book.files[index - 1])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 4_000_000) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片无法解码")
        GalleryRepository.RenderedPage(GalleryImage(index, "r34local://$id/$index", Uri.fromFile(file).toString(), null, null), bitmap, true)
    }
    private fun safeDelete(dir: File) {
        check(dir.canonicalPath.startsWith(root.canonicalPath + File.separator))
        check(!dir.exists() || dir.deleteRecursively()) { "本地文件无法移除，请重试" }
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { safeDelete(folder(id)); changes.update { it + 1 } }
    private fun name(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().ifBlank { "本地漫画" }
    suspend fun importArchive(uri: Uri, report: (String) -> Unit = {}): Video = withContext(Dispatchers.IO) {
        val title = name(uri).substringBeforeLast('.').take(160)
        build(title, report) { add ->
            context.contentResolver.openInputStream(uri)?.use { raw -> ZipInputStream(raw.buffered()).use { zip ->
                var visited = 0
                var unpacked = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    check(++visited <= 10000) { "压缩包条目过多" }
                    var entryBytes = 0L
                    val bounded = object : java.io.FilterInputStream(zip) {
                        private fun count(n: Int): Int { if (n > 0) { entryBytes += n; unpacked += n; check(entryBytes <= MAX_IMAGE && unpacked <= MAX_BOOK) { "压缩包解压大小超过上限" } }; return n }
                        override fun read() = super.read().also { if (it >= 0) count(1) }
                        override fun read(bytes: ByteArray, offset: Int, length: Int) = count(zip.read(bytes, offset, length))
                    }
                    if (!entry.isDirectory && imageName(entry.name) && !entry.name.startsWith("__MACOSX/") && !entry.name.substringAfterLast('/').startsWith('.')) add(entry.name, bounded)
                    else { val buffer = ByteArray(65536); while (bounded.read(buffer) >= 0) currentCoroutineContext().ensureActive() }
                    zip.closeEntry()
                }
            } } ?: error("无法打开所选压缩包")
        }
    }
    suspend fun importDirectory(tree: Uri, report: (String) -> Unit = {}): Video = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val files = mutableListOf<Pair<String, Uri>>()
        var visited = 0
        suspend fun walk(id: String, prefix: String, depth: Int) {
            check(depth <= 8) { "目录层级过深" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    check(++visited <= 10000) { "目录条目过多" }
                    val child = cursor.getString(0); val label = cursor.getString(1).orEmpty()
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) walk(child, "$prefix$label/", depth + 1)
                    else if (imageName(label)) { check(files.size < MAX_PAGES) { "单本最多导入 $MAX_PAGES 页" }; files += "$prefix$label" to DocumentsContract.buildDocumentUriUsingTree(tree, child) }
                }
            } ?: error("无法读取所选目录")
        }
        walk(rootId, "", 0)
        build(name(rootDocument).take(160), report) { add -> files.forEach { (label, uri) -> resolver.openInputStream(uri)?.use { add(label, it) } ?: error("无法读取 $label") } }
    }
    private suspend fun build(title: String, report: (String) -> Unit, source: suspend (suspend (String, InputStream) -> Unit) -> Unit): Video {
        val staging = File(root, "incoming-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val pages = mutableListOf<Pair<String, File>>()
        var total = 0L
        try {
            source { label, stream ->
                check(pages.size < MAX_PAGES) { "单本最多导入 $MAX_PAGES 页" }
                val file = File(staging, "${pages.size}.image")
                var bytes = 0L; val buffer = ByteArray(64 * 1024)
                file.outputStream().buffered().use { output -> while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    bytes += count; total += count
                    check(bytes <= MAX_IMAGE && total <= MAX_BOOK && context.filesDir.usableSpace > 16L * 1024 * 1024) { "导入空间不足或文件超过上限（单页 50 MiB，整本 1 GiB）" }
                    output.write(buffer, 0, count)
                } }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "$label 不是有效图片" }
                pages += label to file; report("已导入 ${pages.size} 页")
            }
            check(pages.isNotEmpty()) { "没有找到支持的图片（JPG、PNG、WebP、GIF、BMP）" }
            val sorted = pages.sortedWith { a, b -> naturalOrder.compare(a.first, b.first) }
            val digest = MessageDigest.getInstance("SHA-256")
            sorted.forEach { (_, file) -> digest.update(java.nio.ByteBuffer.allocate(8).putLong(file.length()).array()); file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } } }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            book(id)?.let { return video(it) }
            // Rename in two passes so source archive ordering cannot overwrite another page.
            sorted.forEachIndexed { i, pair -> check(pair.second.renameTo(File(staging, "$i.sorted"))) }
            val names = sorted.indices.map { "$it.image" }
            names.forEachIndexed { i, filename -> check(File(staging, "$i.sorted").renameTo(File(staging, filename))) }
            val book = LocalBook(id, title.ifBlank { "本地漫画" }, names, total)
            File(staging, "book.json").writeText(gson.toJson(book))
            val target = folder(id)
            check(!target.exists()) { "同名本地内容不完整，请先在本地导入中移除后重试" }
            check(staging.renameTo(target)) { "无法保存本地导入" }
            changes.update { it + 1 }; return video(book)
        } finally { if (staging.exists()) safeDelete(staging) }
    }
}
