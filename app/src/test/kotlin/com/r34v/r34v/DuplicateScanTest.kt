package com.r34v.r34v

import com.r34v.r34v.data.DuplicateBook
import com.r34v.r34v.data.DuplicateScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DuplicateScanTest {
    @get:Rule val temp = TemporaryFolder()

    private fun page(dir: File, name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun book(dir: File, pages: List<Pair<String, ByteArray>>, extras: List<String> = emptyList()) {
        pages.forEach { (name, bytes) -> page(dir, name, bytes) }
        extras.forEach { File(dir, it).writeText("x") }
    }

    private fun same(bytes: Int = 3) = ByteArray(bytes) { 7 }

    @Test fun identicalBooksAreGroupedRegardlessOfNames() {
        val a = temp.newFolder("a"); val b = temp.newFolder("b")
        // 同一内容、不同来源的页名习惯：1.image 对 001.jpg，字节相同才算同一本。
        book(a, listOf("1.image" to same(), "2.image" to same(4)))
        book(b, listOf("001.jpg" to same(), "002.jpg" to same(4)))
        val groups = DuplicateScan.scan(listOf(
            DuplicateBook("local", "a", "画集 A", "本地", a),
            DuplicateBook("download", "b", "画集 B", "ehentai", b)))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].copies.size)
        assertEquals(7L, groups[0].unitBytes)   // 整本总字节 = 3 + 4
        assertEquals(7L, groups[0].wastedBytes)
        // 副本按站点再按标题排序：download 在 local 之前，展示顺序稳定。
        assertEquals("画集 B", groups[0].copies[0].title)
    }

    @Test fun differingPageBytesOrCountsNeverGroup() {
        val a = temp.newFolder("a"); val b = temp.newFolder("b"); val c = temp.newFolder("c")
        book(a, listOf("1.image" to same(), "2.image" to same()))
        book(b, listOf("1.image" to same(), "2.image" to byteArrayOf(1, 2)))   // 字节不同
        book(c, listOf("1.image" to same()))                                    // 页数不同
        val groups = DuplicateScan.scan(listOf(
            DuplicateBook("local", "a", "A", "本地", a),
            DuplicateBook("local", "b", "B", "本地", b),
            DuplicateBook("local", "c", "C", "本地", c)))
        assertTrue(groups.isEmpty())
    }

    @Test fun naturalPageOrderAlignsBeforeFingerprinting() {
        val a = temp.newFolder("a"); val b = temp.newFolder("b")
        // 自然页序必须对齐：字典序会把 10 排在 2 前，按页序哈希后同一内容会误判不同。
        val pages = (1..12).map { "%02d 页".format(it) to byteArrayOf(it.toByte()) }
        book(a, pages.map { (n, b) -> "$n.image" to b })
        book(b, pages.map { (n, b) -> "$n.jpg" to b })
        val groups = DuplicateScan.scan(listOf(
            DuplicateBook("local", "a", "A", "本地", a),
            DuplicateBook("local", "b", "B", "本地", b)))
        assertEquals(1, groups.size)
    }

    @Test fun markersAndTempFilesAreIgnored() {
        val a = temp.newFolder("a"); val b = temp.newFolder("b")
        val pages = listOf("1.image" to same(), "2.image" to same())
        book(a, pages)
        book(b, pages, extras = listOf("complete", "book.json", "3.image.part"))
        val groups = DuplicateScan.scan(listOf(
            DuplicateBook("download", "a", "A", "ehentai", a),
            DuplicateBook("download", "b", "B", "ehentai", b)))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].copies[0].pages)
    }

    @Test fun groupsAreSortedByWastedBytesAndEmptyFoldersAreSkipped() {
        val big = temp.newFolder("big"); val big2 = temp.newFolder("big2")
        val small = temp.newFolder("small"); val small2 = temp.newFolder("small2")
        val large = ByteArray(64 * 1024) { 1 }
        book(big, listOf("1.image" to large, "2.image" to large))
        book(big2, listOf("1.image" to large, "2.image" to large))
        book(small, listOf("1.image" to same()))
        book(small2, listOf("1.image" to same()))
        val empty = temp.newFolder("empty")
        val groups = DuplicateScan.scan(listOf(
            DuplicateBook("local", "big", "大书", "本地", big),
            DuplicateBook("local", "big2", "大书二", "本地", big2),
            DuplicateBook("local", "small", "小书", "本地", small),
            DuplicateBook("local", "small2", "小书二", "本地", small2),
            DuplicateBook("local", "empty", "空", "本地", empty)))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].copies.size)
        assertEquals(128L * 1024, groups[0].wastedBytes)
        assertEquals(2, groups[1].copies.size)
        assertEquals(3L, groups[1].wastedBytes)
    }
}
