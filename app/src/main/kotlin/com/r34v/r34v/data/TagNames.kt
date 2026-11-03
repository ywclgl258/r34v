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
}
