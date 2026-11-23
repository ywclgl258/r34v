#!/usr/bin/env python3
"""Run the production queue and disk index on a JVM with local Android stand-ins."""

import os
from pathlib import Path
import subprocess
import tempfile


STUBS = {
    "Context.kt": '''
        package android.content
        import android.content.pm.PackageManager
        open class Context {
            open val applicationContext: Context get() = this
            val packageName = "fixture"
            val packageManager = PackageManager()
            open fun startActivity(intent: Intent) = Unit
        }
        open class ContextWrapper(val baseContext: Context) : Context() {
            override val applicationContext get() = baseContext.applicationContext
            override fun startActivity(intent: Intent) = baseContext.startActivity(intent)
        }
        class Intent(context: Context, val target: Class<*>) {
            private val extras = mutableMapOf<String, Any?>()
            fun putExtra(key: String, value: String?) = apply { extras[key] = value }
            fun putExtra(key: String, value: Boolean) = apply { extras[key] = value }
            fun text(key: String) = extras[key] as? String
            fun enabled(key: String) = extras[key] == true
        }
    ''',
    "PackageManager.kt": '''
        package android.content.pm
        class PackageManager { fun getPackageInfo(name: String, flags: Int) = PackageInfo() }
        class PackageInfo { val longVersionCode = 1L; val versionCode = 1 }
    ''',
    "Build.kt": '''
        package android.os
        object Build { object VERSION { const val SDK_INT = 35 } }
    ''',
    "BitmapFactory.kt": '''
        package android.graphics
        import java.io.File
        object BitmapFactory {
            class Options { var inJustDecodeBounds = false; var outWidth = 0; var outHeight = 0 }
            fun decodeFile(path: String, options: Options) {
                val image = File(path)
                if (image.isFile && image.readText() == "image") { options.outWidth = 1; options.outHeight = 1 }
            }
        }
    ''',
    "Toast.kt": '''
        package android.widget
        import android.content.Context
        class Toast(private val message: String) {
            fun show() { messages += message }
            companion object {
                const val LENGTH_LONG = 1
                val messages = mutableListOf<String>()
                fun makeText(context: Context, message: String, length: Int) = Toast(message)
            }
        }
    ''',
    "Activity.kt": '''
        package android.app
        import android.content.Context
        import com.r34v.r34v.R34Application
        open class Activity(val application: R34Application) : Context() {
            override val applicationContext get() = application
            var finished = false
            fun finish() { finished = true }
        }
    ''',
    "ComponentActivity.kt": '''
        package androidx.activity
        import android.app.Activity
        import android.content.Intent
        import com.r34v.r34v.R34Application
        import kotlinx.coroutines.CoroutineScope
        open class ComponentActivity(app: R34Application, val scope: CoroutineScope) : Activity(app) {
            override fun startActivity(intent: Intent) = application.deliver(intent)
        }
    ''',
    "Lifecycle.kt": '''
        package androidx.lifecycle
        import androidx.activity.ComponentActivity
        val ComponentActivity.lifecycleScope get() = scope
    ''',
    "OptIn.kt": '''
        package androidx.annotation
        import kotlin.reflect.KClass
        annotation class OptIn(val markerClass: KClass<*>)
    ''',
    "UnstableApi.kt": "package androidx.media3.common.util; annotation class UnstableApi",
    "Download.kt": '''
        package androidx.media3.exoplayer.offline
        data class Request(val id: String)
        data class Download(val request: Request, val state: Int, val bytesDownloaded: Long = 1, val startTimeMs: Long = 0) {
            companion object { const val STATE_COMPLETED = 3; const val STATE_DOWNLOADING = 2 }
        }
    ''',
    "Data.kt": '''
        package com.r34v.r34v.data
        import com.google.gson.Gson
        import java.io.File

        enum class MediaKind { VIDEO, GALLERY }
        enum class GalleryDownloadState { COMPLETED }
        data class Video(val id: String = "", val title: String = id, val url: String = "", val thumb: String = "",
            val duration: String = "", val site: String = "r34v", val mediaKind: MediaKind = MediaKind.VIDEO)
        object SiteRegistry {
            data class Site(val id: String, val mediaKind: MediaKind)
            val LOCAL = Site("local", MediaKind.GALLERY)
            val IWARA = Site("iwara", MediaKind.VIDEO)
            private val sites = listOf(LOCAL, IWARA, Site("r34v", MediaKind.VIDEO), Site("exhentai", MediaKind.GALLERY))
            fun byId(id: String) = sites.firstOrNull { it.id == id }
            fun byIdOrDefault(id: String) = byId(id) ?: sites[2]
        }
        val Video.entryKey get() = if (site == SiteRegistry.IWARA.id && mediaKind == MediaKind.GALLERY) "$site:gallery:$id" else "$site:$id"
        data class ShelfEntry(val video: Video, val completed: Boolean = false)
        data class LocalVideo(val id: String, val path: String)
        data class LocalBook(val id: String, val kind: String, val files: List<String>, val bytes: Long)
        class LocalLibrary(private val root: File) {
            val books = mutableMapOf<String, LocalBook>()
            fun book(id: String) = books[id]?.takeIf { it.files.all { name -> File(File(root, id), name).isFile } }
            fun rootDirectory() = root
        }
        class Prefs {
            private val values = mutableMapOf<String, String>()
            fun getString(key: String, fallback: String?) = values[key] ?: fallback
            fun edit() = Editor()
            fun rewriteQueue(transform: (String) -> String) {
                val key = values.keys.single()
                values[key] = transform(values.getValue(key))
            }
            inner class Editor {
                private var change: () -> Unit = {}
                fun putString(key: String, value: String) = apply { change = { values[key] = value } }
                fun remove(key: String) = apply { change = { values.remove(key) } }
                fun commit(): Boolean { change(); return true }
            }
        }
        class PersonalStore {
            val completed = mutableListOf<Pair<String, String>>()
            fun complete(video: Video, shelf: String, completed: Boolean) { check(completed); this.completed += shelf to video.entryKey }
        }
        class LibraryStore(dataDir: File) {
            val gson = Gson()
            val prefs = Prefs()
            val personal = PersonalStore()
            val legacy = mutableMapOf<String, List<LocalVideo>>()
            private val root = dataDir.canonicalFile
            fun localVideos(siteId: String) = legacy[siteId].orEmpty().filter {
                File(it.path).canonicalFile.let { file -> file.path.startsWith(root.path + File.separator) && file.isFile }
            }
        }
        class StorageRoot(private val directory: File) {
            enum class Mode { PRIVATE, SHARED }
            data class Layout(val root: File) {
                val videoMeta = File(root, "video-meta")
                val comics = File(root, "comics")
            }
            fun granted() = true
            fun layoutOf(mode: Mode) = Layout(File(directory, mode.name))
            fun layout() = layoutOf(Mode.PRIVATE)
            fun ensure() = layout()
        }
        data class GalleryDetail(val entry: Video, val pageCount: Int)
        data class GalleryImage(val index: Int, val pageUrl: String, val imageUrl: String)
        class GalleryRepository {
            data class DownloadSelection(val selected: List<Int>)
            data class GalleryAudit(val complete: Boolean)
            val checked = mutableListOf<Pair<String, String>>()
            val unusable = mutableSetOf<String>()
            var afterAudit: (() -> Unit)? = null
            suspend fun auditDownload(video: Video, root: String): GalleryAudit {
                check(root.isNotBlank())
                checked += video.entryKey to root
                afterAudit?.invoke()
                return GalleryAudit(video.entryKey !in unusable)
            }
        }
    ''',
    "Application.kt": '''
        package com.r34v.r34v
        import android.content.Context
        import android.content.Intent
        import androidx.media3.exoplayer.offline.Download
        import com.r34v.r34v.data.*
        import java.io.File

        class GalleryActivity
        class PlayerActivity
        class GalleryDownloads {
            data class Task(val state: GalleryDownloadState)
            val completed = mutableSetOf<String>()
            fun task(video: Video) = if (video.entryKey in completed) Task(GalleryDownloadState.COMPLETED) else null
        }
        data class DownloadMeta(val video: Video, val quality: String, val site: String = video.site)
        class Playback {
            val tasks = mutableMapOf<Download, DownloadMeta>()
            val caches = mutableMapOf<Pair<String, String>, File>()
            fun downloads() = tasks.keys.toList()
            fun metadata(task: Download) = tasks[task]
            fun cached(key: String, root: String) = caches[key to root]?.let { it.isFile && it.readText() == "complete" } == true
        }
        class R34Application(root: File) : Context() {
            val library = LibraryStore(root)
            val queue = PlaybackQueueStore(library)
            val storage = StorageRoot(root)
            val index = LibraryIndex(this, storage, library.gson)
            val playback = Playback()
            val galleries = GalleryRepository()
            val galleryDownloads = GalleryDownloads()
            val localLibrary = LocalLibrary(File(root, "imports"))
            val opened = mutableListOf<Intent>()
            var connected = false
            var exHentaiSignedIn = false
            var launchFails = false
            fun deliver(intent: Intent) {
                check(!launchFails) { "could not launch activity" }
                if (intent.target == GalleryActivity::class.java) {
                    val video = library.gson.fromJson(intent.text("gallery"), Video::class.java)
                    if (!intent.enabled("offline")) {
                        check(video.site != "exhentai" || exHentaiSignedIn) { "login required" }
                        check(connected) { "online gallery detail" }
                    }
                } else {
                    check(intent.target == PlayerActivity::class.java)
                    check(listOf("local", "cached", "download").any { intent.text(it) != null } || connected) { "online video detail" }
                }
                opened += intent
            }
        }
    ''',
    "Fixture.kt": '''
        package com.r34v.r34v.data
        import android.content.Intent
        import android.widget.Toast
        import androidx.activity.ComponentActivity
        import androidx.media3.exoplayer.offline.Download
        import androidx.media3.exoplayer.offline.Request
        import com.google.gson.JsonParser
        import com.r34v.r34v.*
        import java.io.File
        import java.security.MessageDigest
        import kotlinx.coroutines.*

        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun write(parent: File, name: String, content: String): File = File(parent, name).apply {
            check(parentFile.isDirectory || parentFile.mkdirs()); writeText(content)
        }
        private fun R34Application.gallery(id: String, whole: Boolean, saved: Int): Pair<Video, File> {
            val entry = Video(id, site = "exhentai", mediaKind = MediaKind.GALLERY)
            val folder = File(storage.layoutOf(StorageRoot.Mode.SHARED).comics, "exhentai/${hash(id)}")
            val links = (1..2).associateWith { "https://exhentai.invalid/s/$id/$it" }
            write(folder, "book.json", library.gson.toJson(GalleryDetail(entry, 2)))
            write(folder, "links.json", library.gson.toJson(mapOf("links" to links)))
            if (whole) write(folder, "complete", "2")
            else write(folder, "selection.json", """{"selected":[1]}""")
            for ((page, url) in links.entries.take(saved)) {
                write(folder, "${hash(url)}.page.json", library.gson.toJson(GalleryImage(page, url, "$url/image")))
                write(folder, "${hash(url)}.image", "image")
            }
            return entry to File(folder, "${hash(links.getValue(1))}.image")
        }
        private fun R34Application.cache(video: Video, restored: Boolean, complete: Boolean, taskState: Int = Download.STATE_COMPLETED): File {
            val quality = "720p"
            val key = "${video.site}:${video.id}:$quality"
            val layout = storage.layoutOf(StorageRoot.Mode.SHARED)
            val file = write(layout.root, "${video.id}.cache", if (complete) "complete" else "partial")
            playback.caches[key to layout.root.absolutePath] = file
            if (restored) index.putVideo(video, quality, file.length(), layout = layout)
            else playback.tasks[Download(Request(key), taskState)] = DownloadMeta(video, quality)
            return file
        }
        private fun Intent.video(app: R34Application) = app.library.gson.fromJson(text("gallery") ?: text("video"), Video::class.java)

        fun main(args: Array<String>) = runBlocking {
            val app = R34Application(File(args.single()))
            val shelf = "fixture-shelf"
            fun page() = ComponentActivity(app, this)
            suspend fun awaitOpen() { coroutineContext.job.children.toList().joinAll() }
            suspend fun start(vararg entries: ShelfEntry): Pair<Int?, Throwable?> {
                var skipped: Int? = null
                var error: Throwable? = null
                check(MediaRouter.startShelf(page(), "Fixture", shelf, entries.toList(), offlineOnly = true,
                    opened = { skipped = it }, failed = { error = it }, cancelled = { error("cancelled") }))
                awaitOpen()
                return skipped to error
            }
            suspend fun next(video: Video, token: String): ComponentActivity = page().also {
                check(MediaRouter.next(it, video, token)); awaitOpen()
            }

            val (ex, exPage) = app.gallery("whole", whole = true, saved = 2)
            val (partial, _) = app.gallery("selected-pages", whole = false, saved = 1)
            val (damaged, _) = app.gallery("stale-marker", whole = true, saved = 1)
            val (unreadable, _) = app.gallery("bad-metadata", whole = true, saved = 2)
            app.galleries.unusable += unreadable.entryKey
            app.galleryDownloads.completed += partial.entryKey

            val old = Video("same-id", site = "kbj")
            val otherSite = old.copy(site = "r34v")
            val oldFile = write(File(args.single()), "old.mp4", "old-media")
            app.library.legacy[old.site] = listOf(LocalVideo(old.id, oldFile.absolutePath))
            val zero = Video("zero", site = "kbj")
            val empty = write(File(args.single()), "empty.mp4", "")
            app.library.legacy[old.site] = app.library.legacy.getValue(old.site) + LocalVideo(zero.id, empty.absolutePath)

            val imported = Video("imported-film", site = "local")
            val local = app.localLibrary.rootDirectory()
            val importedFile = write(File(local, imported.id), "video.mp4", "imported-video")
            app.localLibrary.books[imported.id] = LocalBook(imported.id, "video", listOf(importedFile.name), importedFile.length())
            app.library.legacy[imported.site] = listOf(LocalVideo(imported.id, importedFile.absolutePath))
            val comic = Video("imported-comic", site = "local", mediaKind = MediaKind.GALLERY)
            val comicPage = write(File(local, comic.id), "0.image", "image")
            app.localLibrary.books[comic.id] = LocalBook(comic.id, "comic", listOf(comicPage.name), comicPage.length())

            val restored = Video("restored")
            app.cache(restored, restored = true, complete = true)
            val olderMedia3 = Video("media3-without-sidecar")
            app.cache(olderMedia3, restored = false, complete = true)
            val missing = Video("media3-missing-bytes")
            app.cache(missing, restored = false, complete = false)
            val running = Video("in-progress")
            app.cache(running, restored = true, complete = true)
            app.cache(running, restored = false, complete = true, taskState = Download.STATE_DOWNLOADING)

            // The library can find a full ExHentai book while the network and account are absent.
            val expected = listOf(ex, old, imported, comic, restored, olderMedia3)
            val (skipped, failure) = start(ShelfEntry(restored, completed = true), ShelfEntry(partial), ShelfEntry(ex),
                ShelfEntry(old), ShelfEntry(otherSite), ShelfEntry(imported), ShelfEntry(unreadable), ShelfEntry(comic),
                ShelfEntry(damaged), ShelfEntry(restored), ShelfEntry(missing), ShelfEntry(zero), ShelfEntry(running), ShelfEntry(olderMedia3))
            check(failure == null) { failure.toString() }
            check(skipped == 7) { "skipped $skipped" }
            val saved = PlaybackQueueStore(app.library).snapshot()!!
            check(saved.offlineOnly && saved.entries == expected)
            check(app.opened.single().enabled("offline") && app.opened.single().enabled("read"))
            check(app.opened.single().text("root") == app.storage.layoutOf(StorageRoot.Mode.SHARED).root.absolutePath)
            check(app.opened.single().text("queue_session") == saved.id)
            expected.zipWithNext().forEach { (current, target) ->
                check(next(current, saved.id).finished)
                check(app.opened.last().video(app).entryKey == target.entryKey)
            }
            check(app.opened[1].text("local") == oldFile.absolutePath)
            check(app.opened[2].text("local") == importedFile.absolutePath)
            check(app.opened[3].enabled("offline"))
            check(app.opened[4].text("cached") == "r34v:restored:720p")
            check(app.opened[5].text("cached") == "r34v:media3-without-sidecar:720p")
            check(app.library.personal.completed == expected.dropLast(1).map { shelf to it.entryKey })
            println("PASS ordered offline routes, skipped entries, ExHentai offline without sign-in, and persisted mode")

            // Deletion during selection is caught before the first activity is launched.
            app.queue.end(saved.id)
            var truncate = true
            app.galleries.afterAudit = { if (truncate) { check(exPage.delete()); truncate = false } }
            val openedBefore = app.opened.size
            check(start(ShelfEntry(ex)).second?.message?.contains("已不可用") == true)
            check(app.queue.snapshot() == null && app.opened.size == openedBefore)
            app.galleries.afterAudit = null
            exPage.writeText("image")

            val completedBefore = app.library.personal.completed.toList()
            check(start(ShelfEntry(ex), ShelfEntry(old)).second == null)
            val targetGone = app.queue.snapshot()!!.id
            check(oldFile.delete())
            check(!next(ex, targetGone).finished)
            check(app.queue.snapshot() == null && app.library.personal.completed == completedBefore)
            check(Toast.messages.last().contains("已停止"))
            check(!MediaRouter.next(page(), ex, targetGone))
            var invalidToken: Throwable? = null
            MediaRouter.open(page(), old, targetGone, failed = { invalidToken = it })
            awaitOpen()
            check(invalidToken?.message == "片单已结束")
            oldFile.writeText("old-media")

            check(start(ShelfEntry(ex), ShelfEntry(old)).second == null)
            val currentGone = app.queue.snapshot()!!.id
            check(exPage.delete())
            check(!next(ex, currentGone).finished)
            check(app.queue.snapshot() == null && app.library.personal.completed == completedBefore)
            exPage.writeText("image")

            check(start(ShelfEntry(old), ShelfEntry(comic)).second == null)
            val comicGone = app.queue.snapshot()!!.id
            comicPage.writeText("")
            check(!next(old, comicGone).finished)
            check(app.queue.snapshot() == null && app.library.personal.completed == completedBefore)
            comicPage.writeText("image")

            check(start(ShelfEntry(old), ShelfEntry(ex)).second == null)
            val launchFailure = app.queue.snapshot()!!.id
            app.launchFails = true
            check(!next(old, launchFailure).finished)
            check(app.queue.snapshot() == null && app.library.personal.completed == completedBefore)
            check(start(ShelfEntry(old)).second != null && app.queue.snapshot() == null)
            app.launchFails = false

            check(start(ShelfEntry(old), ShelfEntry(ex)).second == null)
            val reopened = PlaybackQueueStore(app.library).snapshot()!!
            check(reopened.offlineOnly && oldFile.delete())
            var resumeError: Throwable? = null
            MediaRouter.open(page(), old, reopened.id, failed = { resumeError = it })
            awaitOpen()
            check(resumeError?.message?.contains("已停止") == true && app.queue.snapshot() == null)
            oldFile.writeText("old-media")
            println("PASS first-open and resume checks, deletion or truncation during queue, ended tokens, and launch failure")

            app.connected = true
            check(MediaRouter.startShelf(page(), "Ordinary fixture", shelf, listOf(ShelfEntry(otherSite), ShelfEntry(ex)),
                offlineOnly = false, opened = { check(it == 0) }, failed = { throw it }, cancelled = { error("cancelled") }))
            awaitOpen()
            val ordinary = app.queue.snapshot()!!
            check(!ordinary.offlineOnly && ordinary.entries == listOf(otherSite, ex))
            check(app.opened.last().text("queue_session") == ordinary.id)
            val normal = app.queue.start("Old fixture", shelf, listOf(otherSite, missing))
            app.library.prefs.rewriteQueue { raw -> JsonParser.parseString(raw).asJsonObject.apply { remove("offlineOnly") }.toString() }
            check(PlaybackQueueStore(app.library).session(normal.id, otherSite)?.offlineOnly == false)
            app.connected = true
            check(next(otherSite, normal.id).finished)
            check(app.opened.last().text("local") == null && app.opened.last().text("cached") == null)
            check(app.opened.last().text("queue_session") == normal.id)
            app.queue.end(normal.id)
            check(start(ShelfEntry(partial), ShelfEntry(damaged), ShelfEntry(old, completed = true)).second?.message?.contains("没有完整") == true)
            check(app.queue.snapshot() == null)
            println("PASS old queue JSON, ordinary online queue, and empty offline selection")
        }
    ''',
}


def main():
    repo = Path(__file__).resolve().parents[2]
    source = repo / "app/src/main/kotlin/com/r34v/r34v/data"
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"

    def jar(group, name, version):
        artifact = next((cache / group / name / version).glob(f"*/{name}-{version}.jar"), None)
        if artifact is None:
            raise SystemExit(f"Missing local dependency {group}:{name}:{version} in {cache}")
        return str(artifact.resolve())

    kotlin = "2.2.10"
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
    reflect = jar("org.jetbrains.kotlin", "kotlin-reflect", kotlin)
    annotations = jar("org.jetbrains", "annotations", "23.0.0")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0")
    gson = jar("com.google.code.gson", "gson", "2.11.0")
    compiler = jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin)
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    runtime = [stdlib, annotations, coroutines, gson]

    with tempfile.TemporaryDirectory(prefix="r34v-offline-queue-") as temp:
        temp = Path(temp)
        sources = []
        for name, content in STUBS.items():
            file = temp / name
            file.write_text(content, encoding="utf-8")
            sources.append(str(file))
        compiled = str(temp / "classes")
        subprocess.run([java, "-cp", os.pathsep.join([compiler, reflect, *runtime]),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                        "-jvm-target", "17", "-classpath", os.pathsep.join(runtime), "-d", compiled,
                        str(source / "PlaybackQueue.kt"), str(source / "LibraryIndex.kt"), *sources], check=True, cwd=temp)
        subprocess.run([java, "-cp", os.pathsep.join([compiled, *runtime]), "com.r34v.r34v.data.FixtureKt",
                        str(temp / "media")], check=True, cwd=temp)


if __name__ == "__main__":
    main()
