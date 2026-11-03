package com.r34v.r34v.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import android.util.Base64

class LibraryStore(context: Context, name: String = "r34v_native", legacyName: String = "FlutterSharedPreferences") {
    val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val gson = Gson()
    private val dataDir = File(context.applicationInfo.dataDir).canonicalFile
    init { migrate(context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)) }

    /** Copy once; leave the original preferences and media files available for rollback. */
    private fun migrate(old: SharedPreferences) {
        if (prefs.getBoolean("kotlin_migration_v1", false)) return
        val edit = prefs.edit()
        for ((rawKey, value) in old.all) {
            val key = rawKey.removePrefix("flutter.")
            if (prefs.contains(key)) continue
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        val searches = old.getString("flutter.search_history_v1", null)
        if (!prefs.contains("native_search_history") && searches != null) runCatching {
            val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
            val values = if (searches.startsWith("$prefix!")) JsonParser.parseString(searches.removePrefix("$prefix!")).asJsonArray.map { it.asString }
            else if (searches.startsWith(prefix)) {
                // Flutter's old string-list format is a Java ArrayList. Restrict decoding to that class.
                val bytes = Base64.decode(searches.removePrefix(prefix), Base64.DEFAULT)
                require(bytes.size <= 128 * 1024)
                object : ObjectInputStream(ByteArrayInputStream(bytes)) {
                    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
                        require(desc.name in listOf("java.util.ArrayList", "[Ljava.lang.String;")) { "Unsupported legacy search data" }
                        return super.resolveClass(desc)
                    }
                }.use { (it.readObject() as? List<*>)?.filterIsInstance<String>().orEmpty() }
            } else emptyList()
            edit.putString("native_search_history", gson.toJson(values.take(30)))
        }
        val speed = old.getString("flutter.settings_v1_speed", null)?.removePrefix("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu")?.toFloatOrNull()
        if (!prefs.contains("settings_v1_native_speed") && speed != null) edit.putString("settings_v1_native_speed", speed.coerceIn(.25f, 3f).toString())
        check(edit.putBoolean("kotlin_migration_v1", true).commit()) { "无法迁移旧版本数据，请检查设备存储空间" }
    }
    @Synchronized private fun <T> list(key: String, type: Class<T>): List<T> = runCatching {
        val array = JsonParser.parseString(prefs.getString(key, "[]")).asJsonArray
        array.mapNotNull { raw -> runCatching {
            if (type == Favorite::class.java && raw.isJsonObject && !raw.asJsonObject.has("t") && raw.asJsonObject.has("id")) raw.asJsonObject.add("t", raw.asJsonObject["id"])
            gson.fromJson(raw, type)
        }.getOrNull() }
    }.getOrDefault(emptyList())
    @Synchronized private fun write(key: String, value: Any) { prefs.edit().putString(key, gson.toJson(value)).apply() }
    fun history() = list("watch_history_v1", Watch::class.java).filter { it.id.isNotBlank() }.sortedByDescending { it.at }
    fun watch(id: String) = history().firstOrNull { it.id == id }
    @Synchronized fun saveWatch(watch: Watch) = write("watch_history_v1", (listOf(watch) + history().filter { it.id != watch.id }).take(500))
    @Synchronized fun removeWatch(id: String) = write("watch_history_v1", history().filter { it.id != id })
    fun clearHistory() = prefs.edit().remove("watch_history_v1").apply()
    fun favorites() = list("favorites_v1", Favorite::class.java).filter { it.target.isNotBlank() }.sortedByDescending { it.at }
    fun isFavorite(kind: String, target: String) = favorites().any { it.kind == kind && it.target == target }
    @Synchronized fun toggleFavorite(entry: Favorite): Boolean {
        val all = favorites(); val exists = all.any { it.key == entry.key }
        write("favorites_v1", if (exists) all.filter { it.key != entry.key } else listOf(entry) + all)
        return !exists
    }
    fun searches(): List<String> = list("native_search_history", String::class.java)
    fun addSearch(query: String) = write("native_search_history", (listOf(query) + searches().filter { it != query }).take(30))
    fun clearSearches() = prefs.edit().remove("native_search_history").apply()
    fun blockedTags() = list("settings_v1_blocked_tags", String::class.java)
    fun toggleBlocked(slug: String) { val tags = blockedTags(); write("settings_v1_blocked_tags", if (slug in tags) tags - slug else tags + slug) }
    fun allowed(video: Video) = blockedTags().none { slug ->
        val needle = slug.replace('-', ' ')
        video.title.contains(needle, ignoreCase = true) || video.url.contains(slug, ignoreCase = true)
    }
    fun string(name: String, default: String = "") = prefs.getString("settings_v1_$name", default) ?: default
    fun bool(name: String, default: Boolean = false) = prefs.getBoolean("settings_v1_$name", default)
    fun set(name: String, value: String) = prefs.edit().putString("settings_v1_$name", value).apply()
    fun set(name: String, value: Boolean) = prefs.edit().putBoolean("settings_v1_$name", value).apply()
    fun localVideos() = list("video_cache_v1", LocalVideo::class.java).filter { item ->
        runCatching { File(item.path).canonicalFile.let { it.path.startsWith(dataDir.path + File.separator) && it.isFile } }.getOrDefault(false)
    }
    fun removeLocal(id: String) {
        localVideos().firstOrNull { it.id == id }?.let { File(it.path).delete() }
        write("video_cache_v1", localVideos().filter { it.id != id })
    }
    var username: String
        get() = prefs.getString("native_username", null) ?: runCatching {
            JsonParser.parseString(prefs.getString("auth_session_v1", "{}")).asJsonObject["u"]?.asString.orEmpty()
        }.getOrDefault("")
        set(value) { prefs.edit().putString("native_username", value).apply() }
    fun legacyCookies(): Map<String, String> = runCatching {
        JsonParser.parseString(prefs.getString("auth_session_v1", "{}")).asJsonObject["c"].asJsonObject.entrySet().associate { it.key to it.value.asString }
    }.getOrDefault(emptyMap())
    fun logout() { prefs.edit().remove("native_cookies").remove("auth_session_v1").putString("native_username", "").apply() }
}
