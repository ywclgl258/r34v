package com.r34v.r34v.data

import java.io.File
import java.security.MessageDigest

/** 页文件由各来源的清单解析，顺序与阅读器一致。 */
internal data class DuplicateBook(
    val source: String,
    val id: String,
    val title: String,
    val site: String,
    val pageFiles: List<File>,
    val validatePage: (File) -> Boolean,
    val sourceRoot: String = "",
    val entry: Video? = null,
)

/** 一本确认参与比对的副本：扫描后携带真实页数与字节量。 */
data class DuplicateCopy(
    val source: String, val id: String, val title: String, val site: String, val pages: Int, val sizeBytes: Long,
    val sourceRoot: String = "", val entry: Video? = null,
) {
    val key get() = Triple(source, id, sourceRoot)
}

/** 同一内容指纹的整本副本组；[wastedBytes] 是“多出的那一本”占用的空间。 */
data class DuplicateGroup(val fingerprint: String, val copies: List<DuplicateCopy>) {
    val unitBytes: Long get() = copies.first().sizeBytes
    val wastedBytes: Long get() = unitBytes * (copies.size - 1)
}

/**
 * 整本内容指纹比对（功能 F41）。
 *
 * 重复的单位是“整本书”：同一内容被重复导入/下载后出现两个文件夹。指纹 = 按自然页序
 * 逐页 SHA-256 串联再摘要——页数不同、字节不同或页序不同都必然不同；文件名与目录
 * 身份不参与，改名、换来源的同内容副本仍能对上。先按（页数，总字节）预筛，只有可能
 * 相同的书才真正读文件哈希，扫描成本有界。
 *
 * 报告只列出候选。删除必须由用户逐本决定，并走各来源既有的安全删除路径
 * （下载中心事务删除 / 本地库删除），这里绝不直接删任何文件。
 */
internal object DuplicateScan {
    private data class Measured(val book: DuplicateBook, val lengths: List<Long>) {
        val bytes = lengths.sum()
        val pages = lengths.size
        fun copy() = DuplicateCopy(book.source, book.id, book.title, book.site, pages, bytes, book.sourceRoot, book.entry)
    }

    fun scan(books: List<DuplicateBook>, shouldStop: () -> Boolean = { false }): List<DuplicateGroup> {
        val measured = books.mapNotNull { book ->
            if (shouldStop()) return emptyList()
            val lengths = book.pageFiles.map { file ->
                if (shouldStop()) return emptyList()
                if (file.isFile) file.length() else 0L
            }
            if (lengths.isEmpty() || lengths.any { it <= 0 }) null else Measured(book, lengths)
        }
        val groups = mutableListOf<DuplicateGroup>()
        for (candidates in measured.groupBy { it.pages to it.bytes }.values) {
            if (candidates.size < 2) continue
            val fingerprints = candidates.mapNotNull { candidate ->
                if (shouldStop()) return emptyList()
                fingerprint(candidate, shouldStop)?.let { it to candidate.copy() }
            }
            fingerprints.groupBy({ it.first }, { it.second }).forEach { (digest, copies) ->
                if (copies.size > 1) groups += DuplicateGroup(digest, copies.sortedWith(compareBy<DuplicateCopy> { it.site }.thenBy { it.title }))
            }
        }
        return if (shouldStop()) emptyList() else groups.sortedByDescending { it.wastedBytes }
    }

    private fun fingerprint(candidate: Measured, shouldStop: () -> Boolean): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        candidate.book.pageFiles.forEachIndexed { index, file ->
            if (shouldStop()) return null
            val page = MessageDigest.getInstance("SHA-256")
            val expected = candidate.lengths[index]
            try {
                val modified = file.lastModified()
                if (file.length() != expected || !candidate.book.validatePage(file)) return null
                file.inputStream().use { input ->
                    var remaining = expected
                    while (remaining > 0) {
                        if (shouldStop()) return null
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count <= 0) return null
                        page.update(buffer, 0, count)
                        remaining -= count
                    }
                    if (input.read() != -1) return null
                }
                if (file.length() != expected || file.lastModified() != modified) return null
            } catch (error: java.io.IOException) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                return null
            } catch (error: SecurityException) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                return null
            }
            digest.update(page.digest())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 与下载中心一致的体积文案。 */
internal fun humanBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / 1073741824.0)
else if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} KB"
