package com.r34v.r34v.data

import androidx.core.net.toUri

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where downloaded media lives, and why that choice decides whether an uninstall loses it.
 *
 * PRIVATE is the app sandbox ([Context.getFilesDir]): always writable, deleted with the app.
 * SHARED is `<shared storage>/R34V`: survives uninstall, which is the point of this class, but it
 * needs all-files access on Android 11+ and WRITE_EXTERNAL_STORAGE before that.
 *
 * [requested] and [applied] are deliberately separate. Switching location writes a pending flag
 * and a restart applies it, because Media3's SimpleCache binds its index to one absolute path
 * while open and cannot be relocated underneath a running download manager.
 */
class StorageRoot(private val context: Context, private val store: LibraryStore, private val rootOverride: File? = null) {
    enum class Mode { PRIVATE, SHARED }

    data class Layout(val mode: Mode, val root: File) {
        /** Everything the user can see and copy out, at one place per mode. */
        val videos: File get() = if (mode == Mode.PRIVATE) root else File(root, "videos")

        /**
         * PRIVATE keeps the historical `files/downloads` path so existing installs and the
         * offline-file tests keep working; SHARED uses a folder a file manager can open.
         */
        val videoCache: File get() = if (mode == Mode.PRIVATE) File(root, "downloads") else File(videos, "cache")
        val videoMeta: File get() = if (mode == Mode.PRIVATE) File(root, "download-meta") else File(videos, "meta")

        /** Same reasoning: `files/gallery-downloads` is the pre-existing private location. */
        val comics: File get() = if (mode == Mode.PRIVATE) File(root, "gallery-downloads") else File(root, "comics")
        val manifest: File get() = File(root, "library.json")
    }

    data class Status(val applied: Mode, val requested: Mode, val granted: Boolean, val writable: Boolean,
                      val path: String, val pendingRestart: Boolean, val note: String)

    /**
     * The primary shared volume: `/storage/emulated/0` (or `/sdcard`).
     *
     * Deliberately not `Environment.getStorageDirectory()`, which is `/storage` — its parent is
     * `/`, so joining would put the folder at `/R34V` where nothing can be written.
     */
    @Suppress("DEPRECATION")
    private val volume: File? = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()

    /** `/storage/emulated/0/R34V`, or null when no shared volume is mounted at all. */
    val sharedRoot: File? = rootOverride ?: volume?.let { File(it, FOLDER) }

    /** Whether this device can offer shared storage at all; some form factors cannot. */
    fun supported(): Boolean = sharedRoot != null

    private fun key(name: String) = "download_root_$name"

    /**
     * Reads a location key straight from the preference file.
     *
     * Deliberately not [LibraryStore.string]: that helper prefixes `settings_v1_`, and mixing the
     * prefixed read with an unprefixed write silently restores the default after every switch.
     */
    private fun read(name: String): String? = store.prefs.getString(key(name), null)

    /** True once the user picked a location themselves; auto-detection must never override that. */
    private fun userChosen() = read("requested") != null

    @Volatile private var adopted = false
    private var boundMode: Mode? = null
    @Volatile var changing: Boolean = false
        private set

    @Synchronized fun beginChange() {
        check(!changing && !pendingRestart) { "下载位置正在切换，请重启应用后再操作" }
        changing = true
    }
    fun endChange() { changing = false }
    fun checkDownloadsAllowed() {
        check(!changing && !pendingRestart) { "下载位置已准备切换，请重启应用后继续下载" }
    }

    /**
     * Whether a previous install left a real library in shared storage.
     *
     * This is the whole point of the feature: after a reinstall the preference is gone but the
     * files are not, so an empty preference plus a populated `R34V` folder means "adopt it"
     * rather than "start empty". Requires the all-files grant, which a reinstall also drops —
     * the user re-grants once and the next call picks the library up.
     *
     * Emptiness is judged by content, never by directory presence: an empty folder left behind by
     * a deletion must not hijack every later download into a library the user never chose. Cache
     * internals alone never count either — Media3 leaves a `.uid` file behind even for nothing.
     */
    private fun sharedLibraryPresent(): Boolean = runCatching {
        if (!granted()) return false
        val root = sharedRoot ?: return false
        val books = File(root, "comics").walkTopDown().any { it.isFile && it.name == "book.json" }
        val videos = File(File(root, "videos"), "meta").listFiles().orEmpty().any { it.isFile && it.name.endsWith(".json") }
        books || videos
    }.getOrDefault(false)

    /**
     * Whether this install already has a private library of its own.
     *
     * Adopting shared storage is only safe when the private side is empty. An existing user who
     * upgrades must keep writing where their downloads already are; otherwise new files would
     * scatter to a second location while the old ones sat unread next to them.
     */
    private fun privateLibraryPresent(): Boolean = runCatching {
        val layout = layoutOf(Mode.PRIVATE)
        val books = layout.comics.walkTopDown().any { it.isFile && it.name == "book.json" }
        val sides = layout.videoMeta.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".json") }
        val cached = layout.videoCache.walkTopDown().any { it.isFile && it.extension == "exo" }
        books || sides || cached
    }.getOrDefault(false)

    /** Adopt an existing shared library exactly once per process, unless the user has chosen. */
    private fun adoptIfPresent() {
        if (adopted) return
        adopted = true
        if (userChosen() || applied == Mode.SHARED) return
        if (privateLibraryPresent()) return
        if (sharedLibraryPresent()) runCatching { markApplied(Mode.SHARED) }
    }

    val applied: Mode get() = if (read("applied") == SHARED_ID) Mode.SHARED else Mode.PRIVATE

    val requested: Mode get() = if (read("requested") == SHARED_ID) Mode.SHARED else Mode.PRIVATE

    val pendingRestart: Boolean get() = read("pending") == "1"

    /** True when the user really holds the permission this mode needs right now. */
    fun granted(): Boolean = when {
        sharedRoot == null -> false
        // A test-injected root lives inside the sandbox, which needs no all-files grant.
        rootOverride != null -> true
        Build.VERSION.SDK_INT >= 30 -> runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        else -> ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** The mode actually used for I/O. A revoked permission degrades instead of failing every write. */
    @Synchronized fun effective(): Mode {
        boundMode?.let { return it }
        adoptIfPresent()
        return when {
            applied != Mode.SHARED -> Mode.PRIVATE
            !granted() -> Mode.PRIVATE
            !writable() -> Mode.PRIVATE
            else -> Mode.SHARED
        }.also { boundMode = it }
    }


    fun layout(): Layout = layoutOf(effective())

    /**
     * PRIVATE points at [Context.getFilesDir] verbatim — the same string an older build stored —
     * so nothing has to be re-resolved through symlinks before comparing paths.
     */
    fun layoutOf(mode: Mode): Layout = when (mode) {
        Mode.PRIVATE -> Layout(Mode.PRIVATE, context.filesDir)
        Mode.SHARED -> Layout(Mode.SHARED, sharedRoot ?: context.filesDir)
    }

    /** Only these roots may be read from an imported sidecar or an activity extra. */
    fun readableLayouts(): List<Layout> = buildList {
        if (granted()) add(layoutOf(Mode.SHARED))
        add(layoutOf(Mode.PRIVATE))
    }

    fun sourceLayout(path: String): Layout = if (path.isBlank()) layout() else
        readableLayouts().firstOrNull { it.root.canonicalPath == File(path).canonicalPath }
            ?: error("下载来源目录不可用，请检查存储权限")

    /** Call before anything binds a cache to a directory. */
    fun applyPendingOnStart() {
        if (pendingRestart && (requested == Mode.PRIVATE || (granted() && writable()))) markApplied(requested)
    }

    /** Creates the folders for [mode] and drops a `.nomedia` so art never lands in the gallery. */
    fun ensure(mode: Mode = effective()): Layout {
        val layout = layoutOf(mode)
        if (mode == Mode.SHARED) {
            listOf(layout.root, layout.videos, layout.videoCache, layout.videoMeta, layout.comics).forEach {
                check(it.isDirectory || it.mkdirs()) { "无法创建下载目录：${it.path}" }
            }
            val marker = File(layout.root, ".nomedia")
            if (!marker.isFile) runCatching { marker.writeText("") }
        } else {
            listOf(layout.videoCache, layout.videoMeta, layout.comics).forEach { check(it.isDirectory || it.mkdirs()) { "无法创建下载目录：${it.path}" } }
        }
        return layout
    }

    private fun writable(): Boolean = runCatching {
        val root = sharedRoot?.takeIf { it.isDirectory || it.mkdirs() } ?: return false
        val probe = File(root, ".write-probe")
        probe.writeText("1"); probe.delete()
        true
    }.getOrDefault(false)

    fun describe(): String = when {
        pendingRestart -> "${if (requested == Mode.SHARED) "公共目录" else "应用内存储"}（重启后生效）"
        applied == Mode.SHARED && !granted() -> "公共目录权限已撤销，请重新授权或重启应用"
        applied == Mode.SHARED && effective() == Mode.SHARED -> "公共目录 ${sharedRoot?.path}"
        applied == Mode.SHARED -> "公共目录不可用，暂存应用内"
        requested == Mode.SHARED -> "公共目录（等待迁移并重启）"
        else -> "应用内存储（卸载会删除）"
    }

    fun status(): Status = Status(
        applied = applied, requested = requested, granted = granted(), writable = writable(),
        path = layout().root.path, pendingRestart = pendingRestart,
        note = when {
            sharedRoot == null -> "未检测到可写的外部存储卷"
            applied == Mode.SHARED && !granted() -> "所有文件访问权限已被撤销，请重新授权或重启后使用应用内存储"
            applied == Mode.SHARED && !writable() -> "公共目录不可写，可能已被删除或被安全软件拦截"
            else -> ""
        })

    /** The system screen that unlocks `<shared>/R34V`. Null before Android 11: use a runtime prompt. */
    fun permissionIntent(): Intent? = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    } else null

    fun choose(mode: Mode) = write(key("requested"), if (mode == Mode.SHARED) SHARED_ID else PRIVATE_ID)

    fun markPending() = write(key("pending"), "1")

    fun requestOnRestart(mode: Mode) {
        check(store.prefs.edit().putString(key("requested"), if (mode == Mode.SHARED) SHARED_ID else PRIVATE_ID)
            .putString(key("pending"), "1").commit()) { "无法保存下载位置设置" }
    }

    fun markApplied(mode: Mode) {
        // Synchronous on purpose: this decides where downloads are written, so a read right after
        // the switch must never observe the previous location.
        check(store.prefs.edit()
            .putString(key("applied"), if (mode == Mode.SHARED) SHARED_ID else PRIVATE_ID)
            .remove(key("pending"))
            .commit()) { "无法保存下载位置设置" }
    }

    private fun write(name: String, value: String) = check(store.prefs.edit().putString(name, value).commit()) { "无法保存下载位置设置" }

    /**
     * Copies the current private library into the shared folder.
     *
     * Copy, never move: the originals stay as a rollback until the user deletes them. In-flight
     * downloads are paused first; a torn page is re-fetched on resume, and the index scan marks an
     * incomplete book as partial rather than pretending it is whole.
     */
    suspend fun migrate(copyVideos: suspend () -> Unit, report: (done: Long, total: Long, label: String) -> Unit = { _, _, _ -> }): String = withContext(Dispatchers.IO) {
        check(granted()) { "请先授予存储权限" }
        val destination = layoutOf(Mode.SHARED)
        val source = layoutOf(Mode.PRIVATE)
        val migrationContext = currentCoroutineContext()
        fun confined(root: File, file: File) {
            val base = root.canonicalPath
            val path = file.canonicalPath
            check(path == base || path.startsWith(base + File.separator)) { "迁移路径不在允许目录内：${file.path}" }
        }
        check(source.root.canonicalPath != destination.root.canonicalPath) { "迁移来源和目标目录相同" }
        for (layout in listOf(source, destination)) {
            listOf(layout.videoMeta, layout.videoCache, layout.comics).forEach { confined(layout.root, it) }
        }
        ensure(Mode.SHARED)
        fun files(root: File, allowedRoot: File): List<File> {
            if (!root.exists()) return emptyList()
            confined(allowedRoot, root)
            return root.walkTopDown().onEnter { confined(allowedRoot, it); true }.filter { file ->
                confined(allowedRoot, file); file.isFile
            }.toList()
        }
        fun digest(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    migrationContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }
        fun same(first: File, second: File) = second.isFile && first.length() == second.length() && digest(first).contentEquals(digest(second))
        val comicFiles = files(source.comics, source.root)
        val bookFolders = comicFiles.mapNotNull { file ->
            val parts = file.relativeTo(source.comics).path.split(File.separatorChar)
            if (parts.size < 3) null else File(File(source.comics, parts[0]), parts[1])
        }.distinctBy { it.path }
        val preservedBooks = mutableSetOf<String>()
        val completeCopies = mutableListOf<Pair<File, File>>()
        // Preflight whole books before touching a destination snapshot or copying video data.
        for (from in bookFolders) {
            val relative = from.relativeTo(source.comics).path
            val to = File(destination.comics, relative)
            confined(destination.root, to)
            val destinationFiles = files(to, destination.root)
            val sourceFiles = files(from, source.root)
            val sourceComplete = GalleryRepository.isCompleteDownload(from, store.gson)
            when {
                GalleryRepository.isCompleteDownload(to, store.gson) -> preservedBooks += relative
                sourceComplete -> completeCopies.add(Pair(from, to))
                destinationFiles.isNotEmpty() -> {
                    val sourceNames = sourceFiles.map { it.relativeTo(from).path }.toSet()
                    val destinationNames = destinationFiles.map { it.relativeTo(to).path }.toSet()
                    check(sourceNames == destinationNames && sourceFiles.all { same(it, File(to, it.relativeTo(from).path)) }) {
                        "同一图集有不同的未完成副本，已停止迁移并保留两边文件：$relative"
                    }
                }
            }
        }
        fun preserve(file: File): Boolean {
            val relative = file.relativeTo(source.comics).path
            if (preservedBooks.any { relative.startsWith(it + File.separator) }) return true
            // Size/timestamp sidecars live beside the book folders; keep the complete copy's too.
            if (file.parentFile == source.comics && file.extension == "json") {
                val record = runCatching { store.gson.fromJson(file.readText(), LibraryRecord::class.java) }.getOrNull()
                if (record != null && record.isComic) {
                    val id = MessageDigest.getInstance("SHA-256").digest(record.id.toByteArray()).joinToString("") { "%02x".format(it) }
                    return "${record.site}${File.separator}$id" in preservedBooks
                }
            }
            return false
        }
        val plan = listOf(
            source.videoMeta to destination.videoMeta,
            source.comics to destination.comics)
        val copies = plan.flatMap { (from, to) -> files(from, source.root).filter { from != source.comics || !preserve(it) }
            .map { it to File(to, it.relativeTo(from).path) } }
        copies.forEach { (from, to) -> confined(source.root, from); confined(destination.root, to) }
        // SimpleCache folders merge by key through their owner, never by numeric span filenames.
        copyVideos()
        for ((_, to) in completeCopies) {
            val marker = File(to, "complete")
            check(!marker.exists() || marker.delete()) { "无法清理旧图集完成标记" }
        }
        val total = copies.sumOf { it.first.length() }
        var done = 0L
        var copied = 0
        var skipped = 0
        // Completion markers are committed only after every page and its metadata are in place.
        for ((file, out) in copies.sortedBy { if (it.first.name == "complete") 1 else 0 }) {
                currentCoroutineContext().ensureActive()
                confined(source.root, file); confined(destination.root, out)
                done += file.length()
                report(done, total, file.name)
                if (same(file, out)) { skipped++; continue }
                val temporary = File(out.parentFile, "${out.name}.importing")
                confined(destination.root, temporary)
                try {
                    val parent = out.parentFile ?: continue
                    check(parent.isDirectory || parent.mkdirs()) { "无法创建 ${parent.path}" }
                    file.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
                    check(same(file, temporary)) { "${file.name} 复制不完整" }
                    check(temporary.renameTo(out)) { "无法写入 ${out.path}" }
                    copied++
                } catch (e: IOException) {
                    throw IOException("${file.name} 复制失败，下载位置未切换：${e.message}", e)
                } finally { temporary.delete() }
        }
        completeCopies.forEach { (_, to) -> check(GalleryRepository.isCompleteDownload(to, store.gson)) { "迁移后的图集不完整，下载位置未切换" } }
        currentCoroutineContext().ensureActive()
        requestOnRestart(Mode.SHARED)
        "已复制 $copied 个文件（跳过 $skipped 个），共 ${done / (1024 * 1024)} MB；重启后生效，应用内原文件保留"
    }

    companion object {
        const val FOLDER = "R34V"
        private const val PRIVATE_ID = "private"
        private const val SHARED_ID = "shared"
    }
}
