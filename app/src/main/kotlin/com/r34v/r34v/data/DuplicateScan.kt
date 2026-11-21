package com.r34v.r34v.data

import java.io.File
import java.security.MessageDigest

/** 一本参与比对的整本图集：下载任务目录或本地导入目录，由调用方给出安全删除身份。 */
data class DuplicateBook(val source: String, val id: String, val title: String, val site: String, val dir: File)

/** 一本确认参与比对的副本：扫描后携带真实页数与字节量。 */
data class DuplicateCopy(val source: String, val id: String, val title: String, val site: String, val pages: Int, val sizeBytes: Long)

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
    private val IGNORE = setOf("complete", "book.json")

    private data class Measured(val book: DuplicateBook, val pages: Int, val sizeBytes: Long, val fingerprint: String)

    /**
     * [shouldStop] 由调用方提供（通常是协程取消检查）：大库扫描可能持续很久，
     * 对话框关闭后必须能停下来，而不是继续读完整个磁盘再往已销毁的界面状态写结果。
     */
    fun scan(books: List<DuplicateBook>, shouldStop: () -> Boolean = { false }): List<DuplicateGroup> =
        books.mapNotNull { book ->
            if (shouldStop()) return@mapNotNull null
            val files = pageFiles(book.dir)
            if (files.isEmpty()) return@mapNotNull null
            val fingerprint = fingerprint(files, shouldStop).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Measured(book, files.size, files.sumOf { it.length() }, fingerprint)
        }
        .also { if (shouldStop()) return emptyList() }
        .groupBy { it.pages to it.sizeBytes }
        .values
        .filter { it.size > 1 }
        .flatMap { candidates ->
            candidates.groupBy { it.fingerprint }
                .filter { it.value.size > 1 }
                .map { (fingerprint, group) -> DuplicateGroup(fingerprint,
                    group.map { m -> DuplicateCopy(m.book.source, m.book.id, m.book.title, m.book.site, m.pages, m.sizeBytes) }
                        .sortedWith(compareBy<DuplicateCopy> { it.site }.thenBy { it.title })) }
        }
        .sortedByDescending { it.wastedBytes }

    /** 指纹 = 自然页序逐页内容摘要串联再摘要；目录缺页或读不到内容时返回空串。 */
    internal fun fingerprint(files: List<File>, shouldStop: () -> Boolean = { false }): String {
        if (files.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            if (shouldStop()) return ""
            val page = MessageDigest.getInstance("SHA-256")
            // 文件可能在扫描期间被删除/移除，读失败只代表“这本算不同”，不能让整次扫描崩掉。
            runCatching {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val read = input.read(buffer); if (read < 0) break; page.update(buffer, 0, read) }
                }
            }.onFailure { return "" }
            digest.update(page.digest())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 页面文件：排除完成标记、元数据与临时文件，按自然页序排列。 */
    private fun pageFiles(dir: File): List<File> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name !in IGNORE && !it.name.endsWith(".part") }
        .sortedWith(compareBy(LocalLibrary.naturalOrder) { it.name })
}

/** 与下载中心一致的体积文案。 */
internal fun humanBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / 1073741824.0)
else if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} KB"
