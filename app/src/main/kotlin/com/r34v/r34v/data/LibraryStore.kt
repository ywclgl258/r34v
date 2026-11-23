package com.r34v.r34v.data

import androidx.core.content.edit

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Local storage.
 *
 * Browsing data (history, favourites, searches, blocklist, downloads, cookies)
 * is namespaced per site so that switching sites never mixes two catalogues. User
 * preferences (quality, speed, update checks) stay global because they are not
 * about any particular catalogue.
 */
class LibraryStore(context: Context, name: String = "r34v_native", legacyName: String = "FlutterSharedPreferences") {
    companion object {
        const val SITE_SWITCH_TUTORIAL_SEEN = "site_switch_tutorial_seen"
        internal const val PINNED_SITE_IDS = "pinned_site_ids_v1"
    }
    val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val gson = Gson()
    val personal by lazy { PersonalStore(this) }
    private val dataDir = File(context.applicationInfo.dataDir).canonicalFile
    private val blockedTagRevision = MutableStateFlow(0)
    internal val blockedTagChanges = blockedTagRevision.asStateFlow()
    private val blockedTagListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.endsWith(":blocked_tags")) blockedTagRevision.update { it + 1 }
    }

    /** The site whose data [history], [favorites] and friends currently resolve to. */
    @Volatile var siteId: String = SiteRegistry.R34V.id

    init {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        // Decide before migration writes its own flags: upgrades and legacy users need no first-use tip.
        if (!prefs.contains(SITE_SWITCH_TUTORIAL_SEEN)) {
            prefs.edit { putBoolean(SITE_SWITCH_TUTORIAL_SEEN, prefs.all.isNotEmpty() || legacy.all.isNotEmpty()) }
        }
        migrate(legacy)
        siteId = SiteRegistry.byIdOrDefault(prefs.getString("active_site", null) ?: SiteRegistry.R34V.id).id
        migrateToSiteNamespaced()
        retireNetworkOverrides()
        prefs.registerOnSharedPreferenceChangeListener(blockedTagListener)
    }

    val needsSiteSwitchTutorial get() = !prefs.getBoolean(SITE_SWITCH_TUTORIAL_SEEN, true)

    /** Claim once globally, before showing; dismissing, switching sites and restarting cannot replay it. */
    @Synchronized fun consumeSiteSwitchTutorial(): Boolean = needsSiteSwitchTutorial &&
        prefs.edit().putBoolean(SITE_SWITCH_TUTORIAL_SEEN, true).commit()

    private fun retireNetworkOverrides() {
        val retired = listOf("direct_ip", "doh", "cdn_direct", "player_proxy", "custom_site_ips").map { "settings_v1_$it" } +
            listOf("site_ip_pool_v1", "site_ip_pool_at_v1") + SiteRegistry.all.flatMap { listOf("${it.id}:ip_pool", "${it.id}:ip_pool_at") }
        if (retired.any(prefs::contains)) prefs.edit { retired.forEach(::remove) }
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
        prefs.edit { putString("active_site", site.id) }
    }

    fun pinnedSiteIds(): List<String> {
        val available = SiteRegistry.all.map { it.id }.toSet()
        return list(PINNED_SITE_IDS, String::class.java).filter { it in available }.distinct()
    }

    @Synchronized fun togglePinnedSite(id: String): List<String> {
        val current = pinnedSiteIds()
        if (SiteRegistry.all.none { it.id == id }) return current
        val next = if (id in current) current - id else current + id
        write(PINNED_SITE_IDS, next)
        return next
    }

    fun galleryLanguage(siteId: String = this.siteId): GalleryLanguage = GalleryLanguage.entries.firstOrNull {
        it.name == prefs.getString("$siteId:gallery_language", null)
    } ?: GalleryLanguage.ALL

    fun setGalleryLanguage(value: GalleryLanguage, siteId: String = this.siteId) {
        prefs.edit { putString("$siteId:gallery_language", value.name) }
    }

    @Synchronized private fun <T> list(key: String, type: Class<T>): List<T> = runCatching {
        val array = JsonParser.parseString(prefs.getString(key, "[]")).asJsonArray
        array.mapNotNull { raw -> runCatching {
            if (raw.isJsonObject && !raw.asJsonObject.has("site") &&
                (type == Watch::class.java || type == Favorite::class.java || type == LocalVideo::class.java)) {
                key.substringBefore(':', "").takeIf { it.isNotBlank() }?.let { raw.asJsonObject.addProperty("site", it) }
            }
            if (type == Favorite::class.java && raw.isJsonObject && !raw.asJsonObject.has("t") && raw.asJsonObject.has("id")) raw.asJsonObject.add("t", raw.asJsonObject["id"])
            gson.fromJson(raw, type)
        }.getOrNull() }
    }.getOrDefault(emptyList())
    @Synchronized private fun write(key: String, value: Any) { prefs.edit { putString(key, gson.toJson(value)) } }

    fun history(siteId: String = this.siteId) = list("$siteId:watch_history_v1", Watch::class.java).filter { it.id.isNotBlank() }.sortedByDescending { it.at }
    fun watch(id: String, siteId: String = this.siteId) = history(siteId).firstOrNull { it.id == id }
    @Synchronized fun saveWatch(watch: Watch) =
        write("${watch.site}:watch_history_v1", (listOf(watch) + history(watch.site).filter { it.id != watch.id }).take(500))
    @Synchronized fun removeWatch(id: String) = write(k("watch_history_v1"), history().filter { it.id != id })
    fun clearHistory() = prefs.edit { remove(k("watch_history_v1")) }

    fun favorites(siteId: String = this.siteId) = list("$siteId:favorites_v1", Favorite::class.java).filter { it.target.isNotBlank() }.sortedByDescending { it.at }
    fun isFavorite(kind: String, target: String, siteId: String = this.siteId) = favorites(siteId).any { it.kind == kind && it.target == target }
    @Synchronized fun toggleFavorite(entry: Favorite, siteId: String = this.siteId): Boolean {
        val normalized = entry.copy(site = siteId)
        val all = favorites(siteId); val exists = all.any { it.key == normalized.key }
        write("$siteId:favorites_v1", if (exists) all.filter { it.key != normalized.key } else listOf(normalized) + all)
        return !exists
    }
    @Synchronized fun removeFavorite(entry: Favorite) {
        val all = favorites(entry.site)
        if (all.any { it.key == entry.key }) write("${entry.site}:favorites_v1", all.filterNot { it.key == entry.key })
    }

    /**
     * 阅读历史。
     *
     * 单条记录损坏（缺 `entry`、字段类型不符）只跳过该条：Gson 会把缺失的非空字段填成 null，
     * 一旦让异常冒到列表层，整站历史都读不出来——而保存新进度也要先读旧列表，等于后续进度也存不下。
     */
    @Synchronized fun readings(siteId: String = this.siteId): List<ReadingProgress> = runCatching {
        val array = JsonParser.parseString(prefs.getString("$siteId:reading_history_v1", "[]")).asJsonArray
        array.mapNotNull { raw -> runCatching { gson.fromJson(raw, ReadingProgress::class.java)?.takeIf { it.entry.id.isNotBlank() } }.getOrNull() }
            .sortedByDescending { it.at }
    }.getOrDefault(emptyList())
    fun reading(id: String, siteId: String = this.siteId) = readings(siteId).firstOrNull { it.entry.id == id }
    @Synchronized fun saveReading(progress: ReadingProgress) {
        val siteId = progress.entry.site
        require(SiteRegistry.byId(siteId) != null && progress.entry.mediaKind == MediaKind.GALLERY)
        check(prefs.edit().putString("$siteId:reading_history_v1", gson.toJson((listOf(progress) + readings(siteId).filter { it.entry.id != progress.entry.id }).take(500))).commit()) { "无法保存阅读进度" }
    }
    /** 读改写必须在同一把锁内完成，否则后台进度写入会被这次回写覆盖。 */
    @Synchronized fun removeReading(id: String, siteId: String = this.siteId) =
        write("$siteId:reading_history_v1", readings(siteId).filter { it.entry.id != id })
    fun clearReadings() = prefs.edit { remove(k("reading_history_v1")) }

    fun galleryCookies(site: SiteContext): Set<String>? = prefs.getString("${site.id}:encrypted_cookies", null)?.let { encoded ->
        runCatching { gson.fromJson(SessionVault.open(encoded), Array<String>::class.java).toSet() }.getOrNull()
    }
    fun saveGalleryCookies(site: SiteContext, cookies: Set<String>) {
        val encrypted = SessionVault.seal(gson.toJson(cookies))
        check(prefs.edit().putString("${site.id}:encrypted_cookies", encrypted).remove("${site.id}:cookies").commit()) { "无法保存会话，请检查存储空间" }
    }

    fun searches(): List<String> = list(k("native_search_history"), String::class.java)
    fun addSearch(query: String) = write(k("native_search_history"), (listOf(query) + searches().filter { it != query }).take(30))
    fun clearSearches() = prefs.edit { remove(k("native_search_history")) }

    fun blockedTags(siteId: String = this.siteId) = list("$siteId:blocked_tags", String::class.java)
    @Synchronized fun toggleBlocked(slug: String, siteId: String = this.siteId) {
        val tags = blockedTags(siteId)
        write("$siteId:blocked_tags", if (slug in tags) tags - slug else tags + slug)
    }

    fun allowed(video: Video) = allowed(video, video.site)
    fun allowed(video: Video, siteId: String) = allowed(video, blockedTags(siteId))

    private fun allowed(video: Video, blocked: List<String>) = blocked.none { slug ->
        video.tagSlugs.orEmpty().any { it.equals(slug, ignoreCase = true) } ||
            video.title.contains(slug.replace('-', ' '), ignoreCase = true) ||
            video.url.contains(slug, ignoreCase = true)
    }

    internal fun <T> filterAllowed(entries: List<T>, videoOf: (T) -> Video): List<T> {
        val bySite = mutableMapOf<String, List<String>>()
        return entries.filter { entry ->
            val video = videoOf(entry)
            allowed(video, bySite.getOrPut(video.site) { blockedTags(video.site) })
        }
    }

    internal fun subscriptionPage(feeds: List<TagFeed>, order: SubscriptionOrder): SubscriptionPage {
        val visible = feeds.map { it.copy(videos = filterAllowed(it.videos) { video -> video }) }
        return SubscriptionPage(visible, SubscriptionMerge.sort(visible, order), order, visible.filter { it.error != null })
    }

    /**
     * Followed tags for one site, in the order the user arranged them.
     *
     * The list order *is* the ranking: `SubscriptionOrder.SUBSCRIBED` shows groups in this order,
     * so moving an entry here is the custom sort. Per site on purpose — a slug only means
     * something inside one catalogue.
     */
    fun subscriptions(siteId: String = this.siteId): List<Subscription> =
        list("$siteId:subscriptions_v1", Subscription::class.java).filter { it.slug.isNotBlank() }.map { it.copy(site = siteId) }

    fun isSubscribed(slug: String, siteId: String = this.siteId) = subscriptions(siteId).any { it.slug == slug }

    /** Adds, or removes when already followed. Returns true when the tag is now followed. */
    @Synchronized fun toggleSubscription(entry: Subscription, siteId: String = this.siteId): Boolean {
        val all = subscriptions(siteId)
        val exists = all.any { it.slug == entry.slug }
        val normalized = entry.copy(site = siteId, label = entry.label.ifBlank { entry.slug },
            at = all.firstOrNull { it.slug == entry.slug }?.at ?: System.currentTimeMillis())
        write("$siteId:subscriptions_v1", if (exists) all.filter { it.slug != entry.slug } else all + normalized)
        return !exists
    }

    @Synchronized fun removeSubscription(slug: String, siteId: String = this.siteId) =
        write("$siteId:subscriptions_v1", subscriptions(siteId).filter { it.slug != slug })

    /** Moves one followed tag to [to]; the stored order is what the subscription screen renders. */
    @Synchronized fun moveSubscription(from: Int, to: Int, siteId: String = this.siteId): Boolean {
        val all = subscriptions(siteId).toMutableList()
        if (from !in all.indices || to !in all.indices || from == to) return false
        all.add(to, all.removeAt(from))
        write("$siteId:subscriptions_v1", all)
        return true
    }

    /** Renames a followed tag locally; the slug stays the fetch key. */
    @Synchronized fun renameSubscription(slug: String, label: String, siteId: String = this.siteId) =
        write("$siteId:subscriptions_v1", subscriptions(siteId).map { if (it.slug == slug) it.copy(label = label.ifBlank { it.slug }) else it })

    fun clearSubscriptions(siteId: String = this.siteId) = prefs.edit { remove("$siteId:subscriptions_v1") }

    /** Merge order for the subscription screen; stored per site like the list itself. */
    fun subscriptionOrder(siteId: String = this.siteId): SubscriptionOrder =
        SubscriptionOrder.entries.firstOrNull { it.name == prefs.getString("$siteId:subscription_order_v1", null) } ?: SubscriptionOrder.SUBSCRIBED

    fun setSubscriptionOrder(value: SubscriptionOrder, siteId: String = this.siteId) =
        prefs.edit { putString("$siteId:subscription_order_v1", value.name) }

    // Global preferences: shared by every site on purpose.
    fun string(name: String, default: String = "") = prefs.getString("settings_v1_$name", default) ?: default
    fun bool(name: String, default: Boolean = false) = prefs.getBoolean("settings_v1_$name", default)
    fun set(name: String, value: String) = prefs.edit { putString("settings_v1_$name", value) }
    fun set(name: String, value: Boolean) = prefs.edit { putBoolean("settings_v1_$name", value) }

    fun localVideos(siteId: String = this.siteId) = list("$siteId:video_cache_v1", LocalVideo::class.java).filter { item ->
        runCatching { File(item.path).canonicalFile.let { it.path.startsWith(dataDir.path + File.separator) && it.isFile } }.getOrDefault(false)
    }
    // JWT 类站点会话（Iwara 等）：只落密封后的密文，明文只存在于内存缓存。
    fun authToken(siteId: String): String? = prefs.getString("$siteId:auth_token", null)
    fun saveAuthToken(siteId: String, sealed: String) { prefs.edit { putString("$siteId:auth_token", sealed) } }
    fun clearAuthToken(siteId: String) { prefs.edit { remove("$siteId:auth_token") } }

    /** 功能 F37：登记本地导入的视频；同 id 重新导入时替换旧记录。 */
    fun addLocalVideo(item: LocalVideo, siteId: String = this.siteId) {
        write("$siteId:video_cache_v1", localVideos(siteId).filterNot { it.id == item.id } + item)
    }
    fun removeLocal(id: String, siteId: String = this.siteId) {
        localVideos(siteId).firstOrNull { it.id == id }?.let { File(it.path).delete() }
        write("$siteId:video_cache_v1", localVideos(siteId).filter { it.id != id })
    }

    var username: String
        get() = username(siteId)
        set(value) = setUsername(value, siteId)

    /** 指定站点的已登录用户名；登录常发生在非当前浏览站点，因此不能只读 [siteId]。 */
    fun username(siteId: String): String = prefs.getString("$siteId:native_username", null) ?: runCatching {
        JsonParser.parseString(prefs.getString("$siteId:auth_session_v1", "{}")).asJsonObject["u"]?.asString.orEmpty()
    }.getOrDefault("")

    fun setUsername(value: String, siteId: String = this.siteId) {
        prefs.edit { putString("$siteId:native_username", value) }
    }

    fun legacyCookies(siteId: String = this.siteId): Map<String, String> = runCatching {
        JsonParser.parseString(prefs.getString("$siteId:auth_session_v1", "{}")).asJsonObject["c"].asJsonObject
            .entrySet().associate { it.key to it.value.asString }
    }.getOrDefault(emptyMap())

    /** Signs out one site without touching any other site's session. */
    fun logout(id: String = siteId) {
        prefs.edit { remove("$id:native_cookies"); remove("$id:auth_session_v1"); putString("$id:native_username", "") }
    }
}
