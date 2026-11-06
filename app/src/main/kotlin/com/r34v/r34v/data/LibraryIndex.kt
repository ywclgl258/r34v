package com.r34v.r34v.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest

/**
 * One downloadable item described by a plain file on disk.
 *
 * This exists so the download centre can rebuild itself from the storage tree alone. The
 * Media3 database and the prefs record list are both inside the app sandbox and both die with
 * an uninstall; a sidecar next to the media does not.
 */
data class LibraryRecord(
    @SerializedName("schema") val schema: Int = VERSION,
    @SerializedName("kind") val kind: String = KIND_VIDEO,
    @SerializedName("site") val site: String = "",
    @SerializedName("id") val id: String = "",
    @SerializedName("quality") val quality: String = "",
    @SerializedName("cacheKey") val cacheKey: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("thumb") val thumb: String = "",
    @SerializedName("duration") val duration: String = "",
    @SerializedName("bytes") val bytes: Long = 0,
    @SerializedName("at") val at: Long = 0,
    /** COMPLETED, PARTIAL or MISSING: decided by what is still on disk, not by what we wish. */
    @SerializedName("state") val state: String = STATE_COMPLETED,
    @SerializedName("detail") val detail: String = "",
    @SerializedName("versionCode") val versionCode: Int = 0,
    /** Set from the scanned directory, never trusted from a sidecar supplied on disk. */
    @SerializedName("sourceRoot") val sourceRoot: String = "",
) {
    val video get() = Video(id = id, title = title, url = url, thumb = thumb, duration = duration, site = site, mediaKind = if (isComic) MediaKind.GALLERY else MediaKind.VIDEO)
    val isComic get() = kind == KIND_COMIC

    companion object {
        const val VERSION = 1
        const val KIND_VIDEO = "video"
        const val KIND_COMIC = "comic"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_PARTIAL = "PARTIAL"
        const val STATE_MISSING = "MISSING"
    }
}

/**
 * Reads and writes the on-disk library: one sidecar per video, `book.json` per comic.
 *
 * Nothing here is authoritative by itself. Every record is re-validated against the actual
 * media (cache hit for video, page files plus `complete` marker for comic) so a hand-deleted
 * folder, a half-copied card or a file manager rename degrades to PARTIAL instead of breaking
 * the screen.
 */
class LibraryIndex(private val context: android.content.Context, private val storage: StorageRoot, private val gson: Gson = Gson()) {
    val revision = MutableStateFlow(0L)
    fun invalidate() { revision.update { it + 1 } }
    private data class KnownPages(val links: Map<Int, String> = emptyMap())
    private fun safe(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(64)
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        return "$cleaned-$digest"
    }

    /**
     * Every location a book or video can legitimately live in.
     *
     * Shared comes first when it is granted, so the newest copy wins a duplicate; private is
     * always included so an upgrade from an older build keeps showing what it already had, and
     * so nothing disappears if the all-files grant is later revoked.
     */
    private fun layouts(): List<StorageRoot.Layout> = buildList {
        if (storage.granted()) runCatching { add(storage.layoutOf(StorageRoot.Mode.SHARED)) }
        add(storage.layoutOf(StorageRoot.Mode.PRIVATE))
    }

    /** Where a fresh sidecar should be written: the location actually in use. */
    private fun writeLayout(): StorageRoot.Layout = storage.ensure()

    /**
     * A record's sidecars, in every location that could hold one.
     *
     * Removal and lookup must not assume a single home: a book adopted from shared storage keeps
     * its sidecar there, while one downloaded before the switch still has its own in private.
     */
    fun videoSidecars(record: LibraryRecord): List<File> = layouts().map {
        File(it.videoMeta, "${safe("${record.site}|${record.id}|${record.quality}")}.json")
    }

    fun videoSidecar(record: LibraryRecord, layout: StorageRoot.Layout = writeLayout()): File =
        File(layout.videoMeta, "${safe("${record.site}|${record.id}|${record.quality}")}.json")

    private fun <T> read(file: File, type: Class<T>): T? = runCatching { gson.fromJson(file.readText(), type) }.getOrNull()

    private fun write(file: File, text: String) {
        if (runCatching { file.readText() == text }.getOrDefault(false)) return
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建索引目录" }
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.part")
        try {
            temporary.writeText(text)
            if (file.exists()) file.delete()
            check(temporary.renameTo(file)) { "无法写入下载索引" }
            invalidate()
        } finally { temporary.delete() }
    }

    /** Records a video download. Written after the cache entry exists, so a scan can trust it. */
    @Synchronized fun putVideo(video: Video, quality: String, bytes: Long, state: String = LibraryRecord.STATE_COMPLETED,
                               at: Long = System.currentTimeMillis(), layout: StorageRoot.Layout? = null) {
        val site = SiteRegistry.byIdOrDefault(video.site).id
        val target = layout ?: writeLayout()
        val record = LibraryRecord(kind = LibraryRecord.KIND_VIDEO, site = site, id = video.id, quality = quality,
            cacheKey = "$site:${video.id}:$quality", title = video.title, url = video.url, thumb = video.thumb,
            duration = video.duration, bytes = bytes, at = at, state = state, versionCode = versionCode,
            sourceRoot = target.root.absolutePath)
        write(videoSidecar(record, target), gson.toJson(record))
    }

    @Synchronized fun removeVideo(site: String, id: String, quality: String) {
        videoSidecars(LibraryRecord(site = site, id = id, quality = quality)).forEach { it.delete() }
        invalidate()
    }

    /**
     * Comics already carry `book.json`; this only refreshes its size/timestamp sidecar.
     *
     * [layout] defaults to the folder the book was actually found in, so a rescan of shared
     * storage writes its sidecar next to that book instead of into whatever location is current.
     */
    @Synchronized fun putComic(entry: Video, bytes: Long, at: Long = System.currentTimeMillis(), layout: StorageRoot.Layout? = null) {
        val site = SiteRegistry.byIdOrDefault(entry.site).id
        val record = LibraryRecord(kind = LibraryRecord.KIND_COMIC, site = site, id = entry.id, title = entry.title,
            url = entry.url, thumb = entry.thumb, duration = entry.duration, bytes = bytes, at = at, versionCode = versionCode)
        val target = layout ?: comicLocation(entry) ?: writeLayout()
        write(comicSidecar(site, entry.id, target), gson.toJson(record))
    }

    /** The location whose `comics/<site>/<hash>` folder actually holds this book, if any. */
    private fun comicLocation(entry: Video): StorageRoot.Layout? {
        val site = SiteRegistry.byIdOrDefault(entry.site).id
        val folderName = MessageDigest.getInstance("SHA-256").digest(entry.id.toByteArray()).joinToString("") { "%02x".format(it) }
        return layouts().firstOrNull { File(File(it.comics, site), folderName).isDirectory }
    }

    /**
     * A comic's sidecar sits outside the book folder on purpose: the book folder stays a
     * self-contained payload, and a sidecar cannot collide with the page index.
     */
    fun comicSidecar(site: String, id: String, layout: StorageRoot.Layout = writeLayout()): File =
        File(layout.comics, "${safe("$site|$id")}.json")

    /** Every location that could hold this book's sidecar; removal and checks must not guess. */
    fun comicSidecars(site: String, id: String): List<File> = layouts().map { File(it.comics, "${safe("$site|$id")}.json") }

    /** Recorded on each sidecar so a future format change can recognise old files. */
    @Suppress("DEPRECATION")
    private val versionCode get() = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    /**
     * Everything currently downloadable, rebuilt from disk.
     *
     * [isCached] answers whether a video cache key still resolves to playable bytes; the caller
     * owns Media3's cache so the index does not need to open a second one. [known] supplies the
     * in-app records so an item whose sidecar is missing (an upgrade from an older build) still
     * shows up and gets its sidecar written on the next scan.
     */
    suspend fun scan(isCached: (String, String) -> Boolean, known: List<LibraryRecord> = emptyList()): List<LibraryRecord> = withContext(Dispatchers.IO) {
        val all = runCatching { layouts() }.getOrDefault(emptyList())
        if (all.isEmpty()) return@withContext known
        val found = LinkedHashMap<String, LibraryRecord>()
        val livePartial = mutableSetOf<String>()
        fun key(record: LibraryRecord) = "${record.site}:${record.id}:${record.quality}"
        fun videoRecord(record: LibraryRecord, sourceRoot: String): LibraryRecord? = runCatching {
            if (record.kind != LibraryRecord.KIND_VIDEO || record.schema != LibraryRecord.VERSION ||
                SiteRegistry.byId(record.site) == null || record.id.isBlank() || record.quality.isBlank() ||
                record.cacheKey != key(record)) return@runCatching null
            val cached = runCatching { isCached(record.cacheKey, sourceRoot) }.getOrDefault(false)
            record.copy(title = record.title.orEmpty(), url = record.url.orEmpty(), thumb = record.thumb.orEmpty(),
                duration = record.duration.orEmpty(), detail = record.detail.orEmpty(), sourceRoot = sourceRoot,
                state = if (cached) LibraryRecord.STATE_COMPLETED else LibraryRecord.STATE_MISSING)
        }.getOrNull()
        for (record in known) {
            val source = all.firstOrNull { runCatching { isCached(record.cacheKey, it.root.absolutePath) }.getOrDefault(false) }
                ?: storage.layout()
            val checked = videoRecord(record, source.root.absolutePath) ?: continue
            found[key(checked)] = if (record.state == LibraryRecord.STATE_COMPLETED) checked
                else checked.copy(state = LibraryRecord.STATE_PARTIAL).also { livePartial += key(it) }
            if (checked.state == LibraryRecord.STATE_COMPLETED && record.state == LibraryRecord.STATE_COMPLETED && !videoSidecar(checked, source).isFile) {
                runCatching { putVideo(checked.video, checked.quality, checked.bytes, at = checked.at, layout = source) }
            }
        }

        for (layout in all) {
            // Videos: sidecars are the list, the cache decides whether it is still playable.
            layout.videoMeta.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
                val raw = read(file, LibraryRecord::class.java) ?: return@forEach
                val record = videoRecord(raw, layout.root.absolutePath) ?: return@forEach
                if (found[key(record)]?.state != LibraryRecord.STATE_COMPLETED) found[key(record)] =
                    if (key(record) in livePartial) record.copy(state = LibraryRecord.STATE_PARTIAL) else record
            }

            // Comics: book folder is the payload, `complete` plus every page is the proof.
            layout.comics.listFiles().orEmpty().filter { it.isDirectory }.forEach { siteFolder ->
                val site = SiteRegistry.byId(siteFolder.name) ?: return@forEach
                siteFolder.listFiles().orEmpty().filter { it.isDirectory }.forEach bookLoop@ { book -> runCatching {
                    val bookJson = read(File(book, "book.json"), GalleryDetail::class.java) ?: return@bookLoop
                    val entry = bookJson.entry
                    if (entry.id.isBlank() || entry.site != site.id || book.name != hash(entry.id) || bookJson.pageCount <= 0) return@bookLoop
                    val recordKey = "${site.id}:${entry.id}:"
                    if (found[recordKey]?.state == LibraryRecord.STATE_COMPLETED) return@bookLoop
                    val sidecar = read(comicSidecar(site.id, entry.id, layout), LibraryRecord::class.java)
                    val links = read(File(book, "links.json"), KnownPages::class.java)?.links.orEmpty()
                    val pages = (1..bookJson.pageCount).mapNotNull { page ->
                        val url = links[page]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val image = File(book, "${hash(url.substringBefore('?'))}.image")
                        val metadata = read(File(book, "${hash(url.substringBefore('?'))}.page.json"), GalleryImage::class.java)
                        image.takeIf { it.isFile && it.length() > 0 && metadata?.index == page && metadata.pageUrl == url }
                    }
                    val bytes = pages.sumOf { it.length() }
                    val marker = runCatching { File(book, "complete").readText().trim().toInt() }.getOrNull()
                    val whole = marker == bookJson.pageCount && pages.size == bookJson.pageCount
                    val at = sidecar?.at?.takeIf { it > 0 } ?: book.listFiles().orEmpty().minOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
                    val record = LibraryRecord(kind = LibraryRecord.KIND_COMIC, site = site.id, id = entry.id, title = entry.title.orEmpty(),
                        url = entry.url.orEmpty(), thumb = entry.thumb.orEmpty().ifBlank { sidecar?.thumb.orEmpty() }, duration = entry.duration.orEmpty(),
                        bytes = bytes, at = at, state = if (whole) LibraryRecord.STATE_COMPLETED else LibraryRecord.STATE_PARTIAL,
                        detail = if (whole) "" else "已保存 ${pages.size} / ${bookJson.pageCount} 页",
                        versionCode = sidecar?.versionCode ?: versionCode, sourceRoot = layout.root.absolutePath)
                    if (sidecar == null) runCatching { putComic(entry, bytes, at, layout) }
                    found[recordKey] = record
                } }
            }
        }
        found.values.sortedByDescending { it.at }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** One-line summary for settings: where things are and how much of it. */
    fun summary(records: List<LibraryRecord>): String {
        val done = records.count { it.state == LibraryRecord.STATE_COMPLETED }
        val partial = records.count { it.state != LibraryRecord.STATE_COMPLETED }
        val bytes = records.sumOf { it.bytes }
        val size = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / 1073741824.0) else "${bytes / (1024 * 1024)} MB"
        return "$done 项可用" + (if (partial > 0) " · $partial 项不完整" else "") + " · $size"
    }

    /** Deletes the sidecar for a comic the user removed, so a rescan does not resurrect it. */
    @Synchronized fun forgetComic(site: String, id: String) {
        comicSidecars(site, id).forEach { it.delete() }
        invalidate()
    }

    /** Drops unreadable sidecars and aborted temporary files in every location, never just one. */
    fun dropCorruptSidecars(): Int = runCatching {
        var removed = 0
        for (layout in layouts()) {
            layout.videoMeta.listFiles().orEmpty().forEach { file ->
                if (!file.isFile) return@forEach
                if (file.extension == "json" && read(file, LibraryRecord::class.java) == null) {
                    if (file.delete()) removed++
                } else if (file.name.endsWith(".part")) {
                    file.delete()
                }
            }
        }
        if (removed > 0) invalidate()
        removed
    }.getOrDefault(0)
}
