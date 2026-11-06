package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EhAdapterTest {
    private fun fixture(action: suspend (LibraryStore, Network, EhFixtures, Context) -> Unit) = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "eh-test-${System.nanoTime()}"
        val folder = File(target.filesDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = folder }
        val originalSite = SiteRegistry.current
        val store = LibraryStore(context, name, "$name-legacy")
        val network = Network(context, store)
        val source = EhFixtures()
        val client = network.client.newBuilder().addInterceptor { source.respond(it.request()) }.build()
        Network::class.java.getDeclaredField("current").apply { isAccessible = true }.set(network, client)
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val original = adapters.toMap()
        adapters["ehentai"] = EhAdapter(SiteRegistry.EHENTAI, network); adapters["exhentai"] = EhAdapter(SiteRegistry.EXHENTAI, network)
        try { action(store, network, source, context) }
        finally {
            adapters.clear(); adapters.putAll(original); SiteRegistry.current = originalSite
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-legacy")
            folder.deleteRecursively(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun realAdapterFollowsCursorsAndLoadsOnlyTheRequestedPreviewChunk() = fixture { _, network, source, _ ->
        val adapter = EhAdapter(SiteRegistry.EHENTAI, network)
        val first = adapter.feed(FeedRequest())
        assertEquals(1, first.current); assertTrue(first.hasNext)
        val second = adapter.feed(FeedRequest(page = 2, cursor = first.nextCursor!!))
        assertFalse(second.hasNext)
        val detail = adapter.gallery(first.videos.single().url)
        assertEquals("https://e-hentai.org/s/a5/8888-5", adapter.galleryPageLink(detail, 5))
        assertTrue(source.requests.any { it.url.queryParameter("p") == "2" })
        assertEquals(2, source.requests.count { it.url.encodedPath.startsWith("/g/") })
        assertTrue(source.requests.none { it.url.host == "sample.hath.network" })
    }
    @Test fun exhentaiRequiresAValidatedSessionAndPersistsItEncrypted() = fixture { store, network, source, _ ->
        val ex = EhAdapter(SiteRegistry.EXHENTAI, network)
        try { ex.feed(FeedRequest()); fail("ExHentai must require a session") } catch (_: GalleryAccessException) {}
        assertTrue(source.requests.isEmpty())
        ex.importSession(source.cookie)
        assertTrue(network.session.hasGallerySession(SiteRegistry.EXHENTAI))
        assertNotNull(store.prefs.getString("exhentai:encrypted_cookies", null))
        assertFalse(store.prefs.all.values.any { it.toString().contains("0123456789abcdef0123456789abcdef") })
        assertFalse(store.prefs.contains("exhentai:cookies"))
        val restored = SessionCookies(store)
        assertTrue(restored.hasGallerySession(SiteRegistry.EXHENTAI))
        assertTrue(restored.loadForRequest("https://e-hentai.org/".toHttpUrl()).none { it.name.startsWith("ipb_") })
        assertTrue(restored.loadForRequest("https://sample.hath.network/page1.png".toHttpUrl()).isEmpty())
        assertEquals("exhentai", ex.feed(FeedRequest()).videos.single().site)
        source.rejectEx = true
        val before = store.prefs.getString("exhentai:encrypted_cookies", null)
        try { ex.importSession(source.cookie.replace("12345", "54321")); fail("Rejected session was committed") } catch (_: GalleryAccessException) {}
        assertEquals(before, store.prefs.getString("exhentai:encrypted_cookies", null))
        restored.clear(SiteRegistry.EXHENTAI)
        assertFalse(SessionCookies(store).hasGallerySession(SiteRegistry.EXHENTAI))
    }
    @Test fun cachedReadPagesWorkOfflineAndUseTheOwningSiteReferer() = fixture { _, network, source, context ->
        val repository = GalleryRepository(context, network)
        val entry = EhAdapter(SiteRegistry.EHENTAI, network).feed(FeedRequest()).videos.single()
        val detail = repository.detail(entry)
        val first = repository.page(SiteRegistry.EHENTAI, detail.pageLinks.first().url)
        assertEquals(800, first.bitmap.width); first.bitmap.recycle()
        assertEquals(1, source.requests.count { it.url.host == "sample.hath.network" })
        val fifthUrl = EhAdapter(SiteRegistry.EHENTAI, network).galleryPageLink(detail, 5)
        repository.page(SiteRegistry.EHENTAI, fifthUrl, galleryUrl = entry.url).bitmap.recycle()
        source.offline = true
        assertTrue(repository.detail(entry).cached)
        val offline = repository.page(SiteRegistry.EHENTAI, detail.pageLinks.first().url)
        assertTrue(offline.cached); offline.bitmap.recycle()
        val afterRestart = GalleryRepository(context, network)
        assertEquals(fifthUrl, afterRestart.cachedPageUrl(entry, 5))
        assertNull(afterRestart.cachedPageUrl(entry.copy(site = "exhentai"), 5))
        afterRestart.page(SiteRegistry.EHENTAI, afterRestart.cachedPageUrl(entry, 5)!!).also {
            assertTrue(it.cached); it.bitmap.recycle()
        }
        assertEquals(2, source.requests.count { it.url.host == "sample.hath.network" })
        source.offline = false
        network.session.replace(SiteRegistry.EXHENTAI, EhProtocol.sessionCookies(SiteRegistry.EXHENTAI, source.cookie))
        val exPage = repository.page(SiteRegistry.EXHENTAI, "https://exhentai.org/s/a1/8888-1")
        exPage.bitmap.recycle()
        val request = source.requests.last { it.url.host == "sample.hath.network" }
        assertEquals("https://exhentai.org/s/a1/8888-1", request.header("Referer"))
        assertEquals(1, repository.cacheInfo(SiteRegistry.EXHENTAI).first)
        assertEquals(2, repository.cacheInfo(SiteRegistry.EHENTAI).first)
        repository.clear(SiteRegistry.EXHENTAI)
        assertEquals(2, repository.cacheInfo(SiteRegistry.EHENTAI).first)
    }
    @Test fun connectionFailureRefreshesButCertificateFailureDoesNotWriteOrReload() = fixture { _, network, source, context ->
        val repository = GalleryRepository(context, network)
        val adapter = EhAdapter(SiteRegistry.EHENTAI, network)
        val entry = adapter.feed(FeedRequest()).videos.single()
        val detail = repository.prepareDownload(entry)
        source.resetImagePage = 1; source.resetImageFailures = 1
        repository.downloadPage(entry, 1, detail.pageLinks.first().url)
        val reload = source.requests.first { it.url.queryParameter("nl") == "reload-1" }
        assertEquals("https://e-hentai.org/s/a1/8888-1?nl=reload-1", reload.url.toString())
        assertTrue(source.requests.any { it.url.host == "sample.hath.network" && it.header("Referer") == reload.url.toString() })
        val imageCalls = source.requests.count { it.url.host == "sample.hath.network" }
        repository.downloadPage(entry, 1, detail.pageLinks.first().url)
        assertEquals("completed pages must not download again", imageCalls, source.requests.count { it.url.host == "sample.hath.network" })

        source.certificateImagePage = 2
        assertThrows(javax.net.ssl.SSLHandshakeException::class.java) {
            kotlinx.coroutines.runBlocking { repository.downloadPage(entry, 2, adapter.galleryPageLink(detail, 2)) }
        }
        assertTrue(source.requests.none { it.url.encodedPath.endsWith("8888-2") && it.url.queryParameter("nl") != null })
        assertEquals(listOf(1), repository.auditDownload(entry).validPages)
        assertFalse(repository.isDownloaded(entry))
    }

    @Test fun unexpectedImageRedirectUsesThePageReloadInsteadOfAcceptingAnotherHost() = fixture { _, network, source, context ->
        val repository = GalleryRepository(context, network)
        val adapter = EhAdapter(SiteRegistry.EHENTAI, network)
        val entry = adapter.feed(FeedRequest()).videos.single()
        val detail = repository.prepareDownload(entry)
        source.redirectImagePage = 1
        repository.downloadPage(entry, 1, detail.pageLinks.first().url)
        assertEquals(1, source.requests.count { it.url.queryParameter("nl") == "reload-1" })
        assertEquals(2, source.requests.count { it.url.host == "sample.hath.network" && it.url.encodedPath.endsWith("page1.png") })
    }

    @Test fun readingProgressIsSavedToTheGalleryOwnerEvenAfterSwitching() = fixture { store, _, _, _ ->
        val entry = Video("8888", "City sketchbook", "https://e-hentai.org/g/8888/abcdef1234/", site = "ehentai", mediaKind = MediaKind.GALLERY)
        store.switchSite("exhentai")
        store.saveReading(ReadingProgress(entry, 3, 5, "https://e-hentai.org/s/a3/8888-3"))
        assertTrue(store.readings().isEmpty()); assertEquals(3, store.reading("8888", "ehentai")!!.page)
        store.toggleFavorite(Favorite("gallery", entry.id, entry.title, entry.url, site = "ehentai"), "ehentai")
        assertFalse(store.isFavorite("gallery", "8888")); assertTrue(store.isFavorite("gallery", "8888", "ehentai"))
    }
    @Test fun explicitExAccessDenialCannotFallBackToCachedPrivateDetails() = fixture { _, network, source, context ->
        network.session.replace(SiteRegistry.EXHENTAI, EhProtocol.sessionCookies(SiteRegistry.EXHENTAI, source.cookie))
        val repository = GalleryRepository(context, network)
        val entry = Video("8888", "City sketchbook", "https://exhentai.org/g/8888/abcdef1234/", site = "exhentai", mediaKind = MediaKind.GALLERY)
        assertEquals(5, repository.detail(entry).pageCount)
        source.rejectEx = true
        try { repository.detail(entry); fail("Access denial must require login") } catch (_: GalleryLoginRequiredException) {}
    }
}
