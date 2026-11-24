package com.r34v.r34v.data

fun readerSheets(count: Int, spread: Int, combineCover: Boolean): List<List<Int>> {
    require(count > 0)
    return readerSheets((1..count).toList(), spread, combineCover)
}

private fun readerSheets(pages: List<Int>, spread: Int, combineCover: Boolean): List<List<Int>> {
    require(pages.isNotEmpty() && spread in 1..2)
    if (spread == 1 || combineCover || pages.first() != 1) return pages.chunked(spread)
    return listOf(listOf(1)) + pages.drop(1).chunked(spread)
}

internal data class ReaderNavigation(val pages: List<Int>, val spread: Int, val combineCover: Boolean) {
    val sheets = readerSheets(pages, spread, combineCover)

    fun sheetIndex(page: Int): Int = sheets.indexOfFirst { page in it }.coerceAtLeast(0)

    fun adjacent(page: Int, forward: Boolean): Int? =
        sheets.getOrNull(sheetIndex(page) + if (forward) 1 else -1)?.first()

    fun boundary(forward: Boolean): Int = if (forward) pages.last() else pages.first()
}
