package com.r34v.r34v.data

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SiteBackup(val site: String, val favorites: List<Favorite> = emptyList(), val watches: List<Watch> = emptyList(),
    val readings: List<ReadingProgress> = emptyList(), val subscriptions: List<Subscription> = emptyList(), val blocked: List<String> = emptyList(),
    val searches: List<String> = emptyList(), val language: GalleryLanguage = GalleryLanguage.ALL, val order: SubscriptionOrder = SubscriptionOrder.SUBSCRIBED)
data class LibraryBackup(val schema: Int = 1, val at: Long = System.currentTimeMillis(), val sites: List<SiteBackup> = emptyList(),
    val personal: PersonalLibrary = PersonalLibrary(), val settings: Map<String, String> = emptyMap()) {
    fun summary() = "收藏 ${sites.sumOf { it.favorites.size }} 项 · 订阅 ${sites.sumOf { it.subscriptions.size }} 项\n" +
        "观看 / 阅读记录 ${sites.sumOf { it.watches.size + it.readings.size }} 项\n" +
        "片单 ${personal.shelves.size} 个 · 书签 ${personal.bookmarks.size} 个"
}

object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val magic = "R34VBK01".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 36
    private val random = SecureRandom()
    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        // This KDF is also available on the app's Android 7 minimum platform.
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun encode(backup: LibraryBackup, password: CharArray): ByteArray {
        require(password.size >= 8) { "备份密码至少需要 8 个字符" }
        validateBackup(backup)
        val plain = Gson().toJson(backup).toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES) { "备份数据超过 16 MiB" }
        val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + iv; val derived = key(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(header)
            header + cipher.doFinal(plain)
        } finally { derived.fill(0); plain.fill(0) }
    }
    fun decode(bytes: ByteArray, password: CharArray): LibraryBackup {
        require(bytes.size in (HEADER + 16)..(MAX_BYTES + HEADER + 16) && bytes.copyOfRange(0, 8).contentEquals(magic)) { "不是受支持的 R34V 备份文件" }
        val derived = key(password, bytes.copyOfRange(8, 24))
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, bytes.copyOfRange(24, HEADER)))
            cipher.updateAAD(bytes.copyOfRange(0, HEADER)); cipher.doFinal(bytes, HEADER, bytes.size - HEADER)
        } catch (e: java.security.GeneralSecurityException) { throw IllegalArgumentException("密码不正确或备份文件已损坏", e) }
        finally { derived.fill(0) }
        return try { Gson().fromJson(String(plain, Charsets.UTF_8), LibraryBackup::class.java).also(::validateBackup) }
        finally { plain.fill(0) }
    }
}

internal val backupBooleans = setOf("translate_tags", "auto_fullscreen", "auto_download", "auto_update", "download_unmetered", "reduce_transparency")
internal val backupStrings = setOf("quality", "native_speed", "cross_search_sites")

fun validateBackup(value: LibraryBackup) {
    require(value.schema == 1) { "备份版本不受支持，请使用兼容版本的应用" }
    require(value.sites.size <= SiteRegistry.librarySources.size && value.sites.map { it.site }.distinct().size == value.sites.size)
    fun source(site: String) = require(SiteRegistry.byId(site) != null) { "备份包含未知来源站点" }
    fun location(path: String, site: String) {
        require(path.length <= 16384)
        if (site == SiteRegistry.LOCAL.id) { require(path.isBlank() || Regex("r34local://[a-f0-9]{64}").matches(path)); return }
        if (path.isBlank() || path.startsWith('/')) return
        val url = path.toHttpUrlOrNull(); require(url != null && url.isHttps && SiteRegistry.byIdOrDefault(site).isSite(url.host)) { "备份作品地址不属于其来源站点" }
    }
    fun video(video: Video) { source(video.site); require(video.id.isNotBlank() && video.id.length <= 2048); location(video.url, video.site) }
    value.sites.forEach { site ->
        source(site.site)
        require(site.favorites.size <= 50000 && site.watches.size <= 500 && site.readings.size <= 500 && site.subscriptions.size <= 5000 && site.blocked.size <= 10000 && site.searches.size <= 30)
        site.favorites.forEach { require(it.site == site.site && it.target.isNotBlank() && it.kind in setOf("video", "gallery", "character", "model", "tag")); location(it.path, site.site) }
        site.watches.forEach { require(it.site == site.site && it.id.isNotBlank() && it.position >= 0 && it.duration >= 0); location(it.path, site.site) }
        site.readings.forEach { video(it.entry); require(it.entry.site == site.site && it.page in 1..100000 && it.pages >= it.page && it.offset in -10000000..10000000) }
        site.subscriptions.forEach { require(it.site == site.site && it.slug.isNotBlank()); location(it.url, site.site) }
        require(site.blocked.all { it.length in 1..2048 } && site.searches.all { it.length <= 2048 })
        require(site.language in GalleryLanguage.entries && site.order in SubscriptionOrder.entries)
    }
    val personal = value.personal
    require(personal.shelves.size in 1..1000 && personal.shelves.count { it.id == PersonalLibrary.WATCH_LATER } == 1 && personal.shelves.map { it.id }.distinct().size == personal.shelves.size)
    personal.shelves.forEach { shelf -> require(shelf.id.isNotBlank() && shelf.name.length in 1..80 && shelf.entries.size <= 50000); shelf.entries.forEach { video(it.video) } }
    require(personal.bookmarks.size <= 50000)
    personal.bookmarks.forEach { video(it.video); require(it.page in 1..100000 && it.offset in -10000000..10000000) }
    personal.readerModes.forEach { (site, mode) -> source(site); require(ReaderMode.entries.any { it.name == mode }) }
    personal.inboxes.forEach { (site, box) ->
        source(site); require(box.entries.size <= 1000 && box.known.size <= 5000)
        box.entries.forEach { video(it.video); require(it.video.site == site) }
        box.known.forEach { (slug, ids) -> require(slug.isNotBlank() && ids.size <= 1000 && ids.all { it.isNotBlank() }) }
    }
    fun validSearch(search: SavedSearch) {
        require(search.id.isNotBlank() && search.name.length in 1..80 && search.query.length <= 2048 && !search.condition.isEmpty)
        search.filter.validate()
        require(search.includeTags.size + search.excludeTags.size <= 200 && (search.includeTags + search.excludeTags).all { it.length in 1..200 })
        require(search.sources.isNotEmpty() && search.sources.distinct().size == search.sources.size && search.sources.all { id -> SiteRegistry.all.any { it.id == id } })
        require(search.category.isBlank() || SiteCategory.entries.any { it.name == search.category })
        search.languages.forEach { (id, lang) -> require(id in search.sources && GalleryLanguage.entries.any { it.name == lang }) }
    }
    require(personal.savedSearches.size <= 1000 && personal.smartShelves.size <= 1000)
    personal.savedSearches.forEach(::validSearch)
    personal.smartShelves.forEach { shelf -> require(shelf.id.isNotBlank() && shelf.name.length in 1..80 && shelf.rule in SmartRule.entries); if (shelf.source.isNotBlank()) source(shelf.source); require(shelf.category.isBlank() || SiteCategory.entries.any { it.name == shelf.category }) }
    require(personal.timeBookmarks.size <= 5000 && personal.timeBookmarks.map { it.id }.distinct().size == personal.timeBookmarks.size)
    personal.timeBookmarks.forEach { video(it.video); require(it.positionMs >= 0 && it.note.length <= 500 && it.group.length <= 80 && it.id.isNotBlank()) }
    personal.playbackPreferences.forEach { (site, prefs) -> source(site); require(prefs.validated() == prefs) }
    personal.readerPreferences.forEach { (key, prefs) ->
        require(key.contains(':') && SiteRegistry.byId(key.substringBefore(':')) != null)
        require(prefs.cropPercent in 0..20 && (prefs.mode.isBlank() || ReaderMode.entries.any { it.name == prefs.mode }))
    }
    personal.readingBehaviors.forEach { (site, behavior) -> source(site); require(behavior.autoPageSeconds in 0..300 && behavior.slideshowSeconds in 0..300) }
    require(personal.imageFavorites.size <= 20000 && personal.imageFavorites.map { it.key }.distinct().size == personal.imageFavorites.size)
    personal.imageFavorites.forEach { favorite ->
        video(favorite.video); require(favorite.page in 1..100000); location(favorite.pageUrl, favorite.video.site)
        require(favorite.imageUrl.length <= 16384 && (favorite.imageUrl.isBlank() || favorite.imageUrl.toHttpUrlOrNull()?.isHttps == true))
    }
    require(personal.channels.size <= 100 && personal.channels.map { it.id }.distinct().size == personal.channels.size)
    personal.channels.forEach { channel ->
        require(channel.id.isNotBlank() && channel.name.length in 1..80 && channel.kind in FeedKind.entries && channel.target.length <= 2048)
        if (channel.site.isNotBlank()) source(channel.site)
        channel.savedSearch?.let(::validSearch)
    }
    require(value.settings.keys.all { it in backupBooleans || it in backupStrings })
    value.settings.forEach { (key, setting) -> require(setting.length <= 2048); if (key in backupBooleans) require(setting in setOf("true", "false")) }
    value.settings["native_speed"]?.let { require(it.toFloatOrNull()?.let { rate -> rate in .25f..3f } == true) }
    value.settings["cross_search_sites"]?.split(',')?.filter { it.isNotBlank() }?.forEach(::source)
}

fun mergeBackups(local: LibraryBackup, incoming: LibraryBackup): LibraryBackup {
    validateBackup(incoming)
    val sites = (local.sites.map { it.site } + incoming.sites.map { it.site }).distinct().map { id ->
        val a = local.sites.firstOrNull { it.site == id } ?: SiteBackup(id)
        val b = incoming.sites.firstOrNull { it.site == id } ?: return@map a
        b.copy(favorites = (a.favorites + b.favorites).sortedByDescending { it.at }.distinctBy { it.key },
            watches = (a.watches + b.watches).sortedByDescending { it.at }.distinctBy { it.id }.take(500),
            readings = (a.readings + b.readings).sortedByDescending { it.at }.distinctBy { it.entry.entryKey }.take(500),
            subscriptions = (a.subscriptions + b.subscriptions).distinctBy { it.slug },
            blocked = (a.blocked + b.blocked).distinct(), searches = (a.searches + b.searches).distinct().take(30))
    }
    val a = local.personal; val b = incoming.personal
    val shelves = (a.shelves.map { it.id } + b.shelves.map { it.id }).distinct().map { id ->
        val first = a.shelves.firstOrNull { it.id == id }; val second = b.shelves.firstOrNull { it.id == id }
        val all = first?.entries.orEmpty() + second?.entries.orEmpty()
        val newest = all.sortedByDescending { it.at }.distinctBy { it.video.entryKey }.associateBy { it.video.entryKey }
        val earliest = all.groupBy { it.video.entryKey }.mapValues { (_, entries) -> entries.minOf { it.addedAt.takeIf { time -> time > 0 } ?: it.at } }
        (first ?: second!!).copy(entries = all.distinctBy { it.video.entryKey }.map { entry ->
            newest.getValue(entry.video.entryKey).copy(addedAt = earliest.getValue(entry.video.entryKey))
        })
    }
    val boxes = (a.inboxes.keys + b.inboxes.keys).associateWith { id ->
        val first = a.inboxes[id] ?: SubscriptionInbox(); val second = b.inboxes[id] ?: SubscriptionInbox()
        SubscriptionInbox((first.known.keys + second.known.keys).associateWith { (first.known[it].orEmpty() + second.known[it].orEmpty()).distinct().take(1000) },
            (first.entries + second.entries).groupBy { it.video.entryKey }.values.map { entries -> entries.maxBy { it.at }.copy(read = entries.any { it.read }) }.sortedByDescending { it.at }.take(1000))
    }
    return LibraryBackup(sites = sites, personal = PersonalLibrary(
        shelves = shelves,
        bookmarks = (a.bookmarks + b.bookmarks).sortedByDescending { it.at }.distinctBy { it.key },
        readerModes = a.readerModes + b.readerModes,
        inboxes = boxes,
        savedSearches = (b.savedSearches + a.savedSearches).distinctBy { it.id },
        smartShelves = (b.smartShelves + a.smartShelves).distinctBy { it.id },
        timeBookmarks = (b.timeBookmarks + a.timeBookmarks).sortedByDescending { it.at }.distinctBy { it.id },
        playbackPreferences = a.playbackPreferences + b.playbackPreferences,
        readerPreferences = a.readerPreferences + b.readerPreferences,
        readingBehaviors = a.readingBehaviors + b.readingBehaviors,
        imageFavorites = (b.imageFavorites + a.imageFavorites).sortedByDescending { it.at }.distinctBy { it.key },
        channels = (b.channels + a.channels).distinctBy { it.id },
    ), settings = local.settings + incoming.settings)
}

fun LibraryStore.backupSnapshot(): LibraryBackup = synchronized(this) {
    LibraryBackup(sites = SiteRegistry.librarySources.map { owner ->
        val id = owner.id
        SiteBackup(id, favorites(id), history(id), readings(id), subscriptions(id), blockedTags(id),
            runCatching { gson.fromJson(prefs.getString("$id:native_search_history", "[]"), Array<String>::class.java).toList() }.getOrDefault(emptyList()),
            galleryLanguage(id), subscriptionOrder(id))
    }, personal = personal.snapshot(), settings = buildMap {
        backupBooleans.forEach { put(it, bool(it, it in setOf("translate_tags", "auto_update")).toString()) }
        backupStrings.forEach { key -> put(key, string(key, when (key) { "quality" -> "720p"; "native_speed" -> "1.0"; else -> "" })) }
    })
}

fun LibraryStore.restoreBackup(incoming: LibraryBackup) = synchronized(this) {
    validateBackup(incoming)
    val merged = mergeBackups(backupSnapshot(), incoming)
    validateBackup(merged)
    val editor = prefs.edit()
    val previous = prefs.all
    val keys = mutableSetOf<String>()
    fun text(key: String, value: Any) { keys += key; editor.putString(key, if (value is String) value else gson.toJson(value)) }
    merged.sites.forEach { site ->
        val id = site.site
        text("$id:favorites_v1", site.favorites); text("$id:watch_history_v1", site.watches); text("$id:reading_history_v1", site.readings)
        text("$id:subscriptions_v1", site.subscriptions); text("$id:blocked_tags", site.blocked); text("$id:native_search_history", site.searches)
        text("$id:gallery_language", site.language.name); text("$id:subscription_order_v1", site.order.name)
    }
    text(PersonalStore.KEY, merged.personal)
    merged.settings.forEach { (key, setting) ->
        val raw = "settings_v1_$key"; keys += raw
        if (key in backupBooleans) editor.putBoolean(raw, setting.toBooleanStrict()) else editor.putString(raw, setting)
    }
    if (!editor.commit()) {
        val rollback = prefs.edit()
        keys.forEach { key -> when (val value = previous[key]) { is String -> rollback.putString(key, value); is Boolean -> rollback.putBoolean(key, value); else -> rollback.remove(key) } }
        rollback.commit()
        throw IOException("恢复保存失败，请保留备份文件并重试")
    }
}
