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

/**
 * Local storage.
 *
 * Browsing data (history, favourites, searches, blocklist, downloads, cookies)
 * is namespaced per site so that switching sites never mixes two catalogues. User
 * preferences (quality, speed, update checks) stay global because they are not
 * about any particular catalogue.
 */
class LibraryStore(context: Context, name: String = "r34v_native", legacyName: String = "FlutterSharedPreferences") {
    companion object { const val SITE_SWITCH_TUTORIAL_SEEN = "site_switch_tutorial_seen" }
    val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val gson = Gson()
    private val dataDir = File(context.applicationInfo.dataDir).canonicalFile

    /** The site whose data [history], [favorites] and friends currently resolve to. */
    @Volatile var siteId: String = SiteRegistry.R34V.id

    init {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        // Decide before migration writes its own flags: upgrades and legacy users need no first-use tip.
        if (!prefs.contains(SITE_SWITCH_TUTORIAL_SEEN)) {
            prefs.edit().putBoolean(SITE_SWITCH_TUTORIAL_SEEN, prefs.all.isNotEmpty() || legacy.all.isNotEmpty()).apply()
        }
        migrate(legacy)
        siteId = SiteRegistry.byIdOrDefault(prefs.getString("active_site", null) ?: SiteRegistry.R34V.id).id
        migrateToSiteNamespaced()
        retireNetworkOverrides()
    }

    val needsSiteSwitchTutorial get() = !prefs.getBoolean(SITE_SWITCH_TUTORIAL_SEEN, true)

    /** Claim once globally, before showing; dismissing, switching sites and restarting cannot replay it. */
    @Synchronized fun consumeSiteSwitchTutorial(): Boolean = needsSiteSwitchTutorial &&
        prefs.edit().putBoolean(SITE_SWITCH_TUTORIAL_SEEN, true).commit()

    private fun retireNetworkOverrides() {
        val retired = listOf("direct_ip", "doh", "cdn_direct", "player_proxy", "custom_site_ips").map { "settings_v1_$it" } +
            listOf("site_ip_pool_v1", "site_ip_pool_at_v1") + SiteRegistry.all.flatMap { listOf("${it.id}:ip_pool", "${it.id}:ip_pool_at") }
        if (retired.any(prefs::contains)) prefs.edit().apply { retired.forEach(::remove) }.apply()
    }

    private fun k(name: String) = "$siteId:$name"

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

    /**
     * Move single-site data onto the default site's namespace.
     *
     * Copies rather than moves: the original keys stay on disk so this is reversible, and a
     * crash mid-way simply re-runs on next launch.
     */
    private fun migrateToSiteNamespaced() {
        if (prefs.getBoolean("site_namespace_v1", false)) return
        val first = SiteRegistry.R34V.id
        val edit = prefs.edit()
        val carried = listOf(
            "watch_history_v1", "favorites_v1", "native_search_history",
            "video_cache_v1", "native_cookies", "auth_session_v1", "native_username",
        )
        for (key in carried) {
            val target = "$first:$key"
            if (prefs.contains(target)) continue
            val value = prefs.all[key] ?: continue
            when (value) {
                is String -> edit.putString(target, value)
                is Set<*> -> edit.putStringSet(target, value.filterIsInstance<String>().toSet())
                is Boolean -> edit.putBoolean(target, value)
                is Long -> edit.putLong(target, value)
                is Int -> edit.putInt(target, value)
            }
        }
        // The blocklist used to live under a global settings key.
        val legacyBlocked = prefs.getString("settings_v1_blocked_tags", null)
        if (legacyBlocked != null && !prefs.contains("$first:blocked_tags")) edit.putString("$first:blocked_tags", legacyBlocked)
        edit.remove("settings_v1_blocked_tags")
        edit.putBoolean("site_namespace_v1", true)
        check(edit.commit()) { "无法迁移站点数据，请检查设备存储空间" }
    }

    /** Switches the active namespace and remembers the choice across launches. */
    fun switchSite(id: String) {
        val site = SiteRegistry.byIdOrDefault(id)
        siteId = site.id
        SiteRegistry.current = site
        prefs.edit().putString("active_site", site.id).apply()
    }

    fun galleryLanguage(): GalleryLanguage = GalleryLanguage.entries.firstOrNull {
        it.name == prefs.getString(k("gallery_language"), null)
    } ?: GalleryLanguage.ALL

    fun setGalleryLanguage(value: GalleryLanguage) {
        prefs.edit().putString(k("gallery_language"), value.name).apply()
    }

    @Synchronized private fun <T> list(key: String, type: Class<T>): List<T> = runCatching {
        val array = JsonParser.parseString(prefs.getString(key, "[]")).asJsonArray
        array.mapNotNull { raw -> runCatching {
            if (type == Favorite::class.java && raw.isJsonObject && !raw.asJsonObject.has("t") && raw.asJsonObject.has("id")) raw.asJsonObject.add("t", raw.asJsonObject["id"])
            gson.fromJson(raw, type)
        }.getOrNull() }
    }.getOrDefault(emptyList())
    @Synchronized private fun write(key: String, value: Any) { prefs.edit().putString(key, gson.toJson(value)).apply() }

    fun history() = list(k("watch_history_v1"), Watch::class.java).filter { it.id.isNotBlank() }.sortedByDescending { it.at }
    fun watch(id: String) = history().firstOrNull { it.id == id }
    @Synchronized fun saveWatch(watch: Watch) =
        write(k("watch_history_v1"), (listOf(watch.copy(site = siteId)) + history().filter { it.id != watch.id }).take(500))
    @Synchronized fun removeWatch(id: String) = write(k("watch_history_v1"), history().filter { it.id != id })
    fun clearHistory() = prefs.edit().remove(k("watch_history_v1")).apply()

    fun favorites(siteId: String = this.siteId) = list("$siteId:favorites_v1", Favorite::class.java).filter { it.target.isNotBlank() }.sortedByDescending { it.at }
    fun isFavorite(kind: String, target: String, siteId: String = this.siteId) = favorites(siteId).any { it.kind == kind && it.target == target }
    @Synchronized fun toggleFavorite(entry: Favorite, siteId: String = this.siteId): Boolean {
        val normalized = entry.copy(site = siteId)
        val all = favorites(siteId); val exists = all.any { it.key == normalized.key }
        write("$siteId:favorites_v1", if (exists) all.filter { it.key != normalized.key } else listOf(normalized) + all)
        return !exists
    }

    fun readings(siteId: String = this.siteId) = list("$siteId:reading_history_v1", ReadingProgress::class.java).filter { it.entry.id.isNotBlank() }.sortedByDescending { it.at }
    fun reading(id: String, siteId: String = this.siteId) = readings(siteId).firstOrNull { it.entry.id == id }
    @Synchronized fun saveReading(progress: ReadingProgress) {
        val siteId = progress.entry.site
        require(SiteRegistry.byId(siteId)?.mediaKind == MediaKind.GALLERY)
        write("$siteId:reading_history_v1", (listOf(progress) + readings(siteId).filter { it.entry.id != progress.entry.id }).take(500))
    }
    fun removeReading(id: String) = write(k("reading_history_v1"), readings().filter { it.entry.id != id })
    fun clearReadings() = prefs.edit().remove(k("reading_history_v1")).apply()

    fun galleryCookies(site: SiteContext): Set<String>? = prefs.getString("${site.id}:encrypted_cookies", null)?.let { encoded ->
        runCatching { gson.fromJson(SessionVault.open(encoded), Array<String>::class.java).toSet() }.getOrNull()
    }
    fun saveGalleryCookies(site: SiteContext, cookies: Set<String>) {
        val encrypted = SessionVault.seal(gson.toJson(cookies))
        check(prefs.edit().putString("${site.id}:encrypted_cookies", encrypted).remove("${site.id}:cookies").commit()) { "无法保存会话，请检查存储空间" }
    }

    fun searches(): List<String> = list(k("native_search_history"), String::class.java)
    fun addSearch(query: String) = write(k("native_search_history"), (listOf(query) + searches().filter { it != query }).take(30))
    fun clearSearches() = prefs.edit().remove(k("native_search_history")).apply()

    fun blockedTags() = list(k("blocked_tags"), String::class.java)
    fun toggleBlocked(slug: String) { val tags = blockedTags(); write(k("blocked_tags"), if (slug in tags) tags - slug else tags + slug) }
    fun allowed(video: Video) = blockedTags().none { slug ->
        val needle = slug.replace('-', ' ')
        video.title.contains(needle, ignoreCase = true) || video.url.contains(slug, ignoreCase = true)
    }

    // Global preferences: shared by every site on purpose.
    fun string(name: String, default: String = "") = prefs.getString("settings_v1_$name", default) ?: default
    fun bool(name: String, default: Boolean = false) = prefs.getBoolean("settings_v1_$name", default)
    fun set(name: String, value: String) = prefs.edit().putString("settings_v1_$name", value).apply()
    fun set(name: String, value: Boolean) = prefs.edit().putBoolean("settings_v1_$name", value).apply()

    fun localVideos() = list(k("video_cache_v1"), LocalVideo::class.java).filter { item ->
        runCatching { File(item.path).canonicalFile.let { it.path.startsWith(dataDir.path + File.separator) && it.isFile } }.getOrDefault(false)
    }
    fun removeLocal(id: String) {
        localVideos().firstOrNull { it.id == id }?.let { File(it.path).delete() }
        write(k("video_cache_v1"), localVideos().filter { it.id != id })
    }

    var username: String
        get() = prefs.getString("$siteId:native_username", null) ?: runCatching {
            JsonParser.parseString(prefs.getString("$siteId:auth_session_v1", "{}")).asJsonObject["u"]?.asString.orEmpty()
        }.getOrDefault("")
        set(value) { prefs.edit().putString("$siteId:native_username", value).apply() }

    fun legacyCookies(): Map<String, String> = runCatching {
        JsonParser.parseString(prefs.getString("$siteId:auth_session_v1", "{}")).asJsonObject["c"].asJsonObject
            .entrySet().associate { it.key to it.value.asString }
    }.getOrDefault(emptyMap())

    /** Signs out one site without touching any other site's session. */
    fun logout(id: String = siteId) {
        prefs.edit().remove("$id:native_cookies").remove("$id:auth_session_v1")
            .putString("$id:native_username", "").apply()
    }
}
