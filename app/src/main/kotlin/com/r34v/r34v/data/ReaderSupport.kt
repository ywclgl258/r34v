package com.r34v.r34v.data

fun readerSheets(count: Int, spread: Int, combineCover: Boolean): List<List<Int>> {
    require(count > 0 && spread in 1..2)
    if (spread == 1) return (1..count).map(::listOf)
    if (combineCover) return (1..count).chunked(2)
    return buildList {
        add(listOf(1))
        (2..count).chunked(2).forEach(::add)
    }
}
