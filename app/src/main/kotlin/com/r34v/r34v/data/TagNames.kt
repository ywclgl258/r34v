package com.r34v.r34v.data

import android.content.Context

object TagNames {
    private var names: Map<String, String>? = null
    @Synchronized fun lookup(context: Context, slug: String): String? {
        if (names == null) names = runCatching {
            context.assets.open("tag-names.json").bufferedReader().use { reader ->
                com.google.gson.JsonParser.parseReader(reader).asJsonObject.entrySet().associate { it.key to it.value.asString }
            }
        }.getOrDefault(emptyMap())
        return names?.get(slug)
    }
    @Synchronized fun suggestions(context: Context, raw: String, limit: Int = 8): List<Pair<String, String>> {
        if (names == null) lookup(context, raw)
        return match(raw, names.orEmpty(), limit)
    }

    /**
     * 纯匹配逻辑，词典由调用方给定（assets 词典或测试夹具）。
     * needle 去掉 "-" 排除前缀与命名空间；前缀命中优先，其次更短的 slug，再按字典序。
     */
    internal fun match(raw: String, dictionary: Map<String, String>, limit: Int = 8): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        val needle = raw.trim().removePrefix("-").substringAfter(':').lowercase()
        if (needle.isBlank()) return emptyList()
        return dictionary.entries.filter { it.key.contains(needle, true) || it.value.contains(needle, true) }
            .sortedWith(compareBy({ !it.key.startsWith(needle, true) }, { it.key.length }, { it.key }))
            .take(limit).map { it.key to it.value }
    }
}
