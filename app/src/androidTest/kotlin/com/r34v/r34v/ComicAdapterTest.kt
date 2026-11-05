package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ComicAdapterTest {
    @Test fun combinedNhLanguagesMergeUnevenPagesWithoutDuplicatesOrLostItems() = fixture { _, network, source, _ ->
        source.nhLanguages = mapOf("chinese" to (8800..8828 step 2).reversed().toList(), "japanese" to (8801..8827 step 2).reversed().toList())
        val adapter = NhentaiAdapter(network)
        val request = FeedRequest(FeedKind.CATEGORY, "manga", language = GalleryLanguage.CHINESE_JAPANESE)
        val first = adapter.feed(request)
        assertEquals((8828 downTo 8805).map(Int::toString), first.videos.map { it.id }); assertTrue(first.hasNext)
        val next = adapter.feed(request.copy(page = 2, cursor = first.nextCursor!!))
        assertEquals((8804 downTo 8800).map(Int::toString), next.videos.map { it.id }); assertFalse(next.hasNext)
        val searches = source.requests.filter { it.url.encodedPath == "/api/v2/search" }
        assertEquals(4, searches.size)
        assertTrue(searches.all { it.url.queryParameter("query")!!.startsWith("category:manga language:") })
        assertTrue(searches.any { it.url.queryParameter("query")!!.endsWith("language:japanese -language:chinese") })
    }

    @Test fun hitomiLanguageUnionIntersectsSearchAndKeepsRankingOrder() = fixture { _, network, source, _ ->
        source.indexes = mapOf(
            "/index-chinese.nozomi" to listOf(8804, 8802, 8800),
            "/index-japanese.nozomi" to listOf(8803, 8802, 8801),
            "/type/manga-all.nozomi" to listOf(8804, 8801, 8800),
            "/popular/year-all.nozomi" to listOf(8801, 8810, 8804, 8800, 8803, 8802),
        )
        val adapter = HitomiAdapter(network)
        assertEquals(listOf("8804", "8802", "8800"), adapter.feed(FeedRequest(language = GalleryLanguage.CHINESE)).videos.map { it.id })
        assertEquals((8804 downTo 8800).map(Int::toString), adapter.feed(FeedRequest(language = GalleryLanguage.CHINESE_JAPANESE)).videos.map { it.id })
        val category = FeedRequest(FeedKind.CATEGORY, "manga", language = GalleryLanguage.CHINESE_JAPANESE)
        assertEquals(listOf("8804", "8801", "8800"), adapter.feed(category).videos.map { it.id })
        assertEquals(listOf("8801", "8804", "8800"), adapter.feed(category.copy(sort = Sort.POPULARITY)).videos.map { it.id })
    }

    @Test fun languagePreferenceAndOfflineFeedsRemainSeparate() = fixture { store, network, source, context ->
        source.nhLanguages = mapOf("chinese" to listOf(8802), "japanese" to listOf(8801))
        store.switchSite("nhentai"); store.setGalleryLanguage(GalleryLanguage.CHINESE)
        store.switchSite("hitomi"); assertEquals(GalleryLanguage.ALL, store.galleryLanguage())
        store.setGalleryLanguage(GalleryLanguage.JAPANESE)
        store.switchSite("nhentai"); assertEquals(GalleryLanguage.CHINESE, store.galleryLanguage())
        val repository = Repository(network, store)
        for (language in GalleryLanguage.entries) repository.feed(FeedRequest(language = language))
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        adapters["nhentai"] = NhentaiAdapter(network)
        source.offline = true
        for ((language, ids) in listOf(GalleryLanguage.ALL to listOf("8800"), GalleryLanguage.CHINESE to listOf("8802"), GalleryLanguage.JAPANESE to listOf("8801"), GalleryLanguage.CHINESE_JAPANESE to listOf("8802", "8801"))) {
            val feed = repository.feed(FeedRequest(language = language))
            assertEquals(ids, feed.videos.map { it.id }); assertTrue(feed.cached)
        }
    }
    private fun fixture(action: suspend (LibraryStore, Network, ComicFixtures, Context) -> Unit) = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "comic-${System.nanoTime()}"; val dir = File(target.filesDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = dir }
        val site = SiteRegistry.current; val store = LibraryStore(context, name, "$name-legacy"); val network = Network(context, store)
        val source = ComicFixtures(); val client = network.client.newBuilder().addInterceptor { source.respond(it.request()) }.build()
        Network::class.java.getDeclaredField("current").apply { isAccessible = true }.set(network, client)
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val original = adapters.toMap()
        adapters["nhentai"] = NhentaiAdapter(network); adapters["hitomi"] = HitomiAdapter(network)
        try { action(store, network, source, context) } finally {
            adapters.clear(); adapters.putAll(original); SiteRegistry.current = site
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-legacy"); dir.deleteRecursively()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun nhV2ListDetailAndReadReuseMetadataAndRespectRateLimits() = fixture { _, network, source, _ ->
        val adapter = NhentaiAdapter(network)
        val first = adapter.feed(FeedRequest()); assertTrue(first.hasNext)
        val detail = adapter.gallery(first.videos.single().url)
        assertEquals(2, detail.pageCount)
        assertTrue(adapter.galleryImage(detail.pageLinks[1].url).imageUrl.endsWith("2.png"))
        adapter.galleryImage(detail.pageLinks[0].url)
        assertEquals(1, source.requests.count { it.url.encodedPath == "/api/v2/galleries/8800" })
        assertTrue(source.requests.none { it.url.encodedPath.startsWith("/api/gallery/") })
        source.nhStatus = 429
        try { adapter.feed(FeedRequest()); fail("Rate limit swallowed") } catch (error: GalleryAccessException) { assertTrue(error.message!!.contains("60")) }
    }
    @Test fun hitomiRangePaginationAndBtreeSearchFollowRealProtocol() = fixture { _, network, source, _ ->
        val adapter = HitomiAdapter(network)
        val first = adapter.feed(FeedRequest()); assertEquals(12, first.videos.size); assertTrue(first.hasNext)
        val second = adapter.feed(FeedRequest(page = 2)); assertEquals(1, second.videos.size); assertFalse(second.hasNext)
        assertTrue(source.requests.any { it.header("Range") == "bytes=48-95" })
        adapter.feed(FeedRequest(FeedKind.CATEGORY, "manga"))
        adapter.feed(FeedRequest(FeedKind.CATEGORY, "manga", Sort.POPULARITY))
        assertTrue(source.requests.any { it.url.encodedPath == "/type/manga-all.nozomi" && it.header("Range") == "bytes=0-47" })
        assertTrue(source.requests.any { it.url.encodedPath == "/type/popular/year/manga-all.nozomi" && it.header("Range") == "bytes=0-47" })
        val result = adapter.feed(FeedRequest(FeedKind.SEARCH, "city")); assertEquals("8800", result.videos.single().id)
        assertEquals("8800", adapter.feed(FeedRequest(FeedKind.SEARCH, "city", Sort.POPULARITY)).videos.single().id)
        assertTrue(source.requests.any { it.url.encodedPath.endsWith(".index") && it.header("Range") == "bytes=464-927" })
        val image = adapter.galleryImage("https://hitomi.la/reader/8800.html#2")
        assertTrue(image.imageUrl.startsWith("https://w1.")); assertTrue(image.imageUrl.endsWith(".webp"))
    }
    @Test fun newComicSitesKeepSameIdsAndOfflinePagesIsolated() = fixture { store, network, source, context ->
        val repository = GalleryRepository(context, network)
        val nh = repository.detail(Video("8800", "City sketchbook", "https://nhentai.net/g/8800/", site = "nhentai", mediaKind = MediaKind.GALLERY))
        val hitomi = repository.detail(Video("8800", "City sketchbook", "https://hitomi.la/galleries/8800.html", site = "hitomi", mediaKind = MediaKind.GALLERY))
        for (detail in listOf(nh, hitomi)) {
            val owner = SiteRegistry.byId(detail.entry.site)!!
            val image = repository.page(owner, detail.pageLinks[1].url, galleryUrl = detail.entry.url); image.bitmap.recycle()
            store.saveReading(ReadingProgress(detail.entry, 2, 2, detail.pageLinks[1].url))
            store.toggleFavorite(Favorite("gallery", detail.entry.id, detail.entry.title, detail.entry.url, site = owner.id), owner.id)
            assertEquals(owner.id, detail.entry.site); assertEquals(1, repository.cacheInfo(owner).first)
        }
        assertEquals(2, store.reading("8800", "nhentai")!!.page); assertEquals(2, store.reading("8800", "hitomi")!!.page)
        val adaptersField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = adaptersField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        // A fresh adapter models a new process with no in-memory metadata.
        adapters["nhentai"] = NhentaiAdapter(network); adapters["hitomi"] = HitomiAdapter(network)
        val before = source.requests.size; source.offline = true
        for (detail in listOf(nh, hitomi)) {
            assertTrue(repository.detail(detail.entry).cached)
            val page = repository.page(SiteRegistry.byId(detail.entry.site)!!, repository.cachedPageUrl(detail.entry, 2)!!)
            assertTrue(page.cached); page.bitmap.recycle()
        }
        assertTrue(source.requests.drop(before).none { it.url.encodedPath.endsWith(".webp") || it.url.encodedPath.endsWith(".png") })
        repository.clear(SiteRegistry.NHENTAI); assertEquals(1, repository.cacheInfo(SiteRegistry.HITOMI).first)
        val images = source.requests.filter { it.url.host.startsWith("i1.") || it.url.host.startsWith("w1.") }
        assertEquals(listOf("https://nhentai.net/g/8800/2/", "https://hitomi.la/reader/8800.html"), images.map { it.header("Referer") })
        assertTrue(images.all { it.header("Cookie") == null && it.header("Authorization") == null })
    }
}
