package com.r34v.r34v

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.FeedRequest
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.MediaKind
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.adapter.Capability
import com.r34v.r34v.data.adapter.Section
import com.r34v.r34v.data.adapter.SiteAdapter
import com.r34v.r34v.ui.siteFunctionPage
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SiteFunctionPageTest {
    @Test fun everyRegisteredSiteHasItsOwnFeaturedSectionsAndKeepsOtherRoutes() {
        val featuredBySite = mapOf(
            "r34v" to listOf("popular", "latest"),
            "hanime1" to listOf("recent", "today"),
            "iwara" to listOf("trending", "images"),
            "missav" to listOf("new", "actresses"),
            "jable" to listOf("new", "hot"),
            "rouvideo" to listOf("home", "category"),
            "kbjfree" to listOf("recent", "trending"),
            "pornhub" to listOf("newest", "mostviewed"),
            "ehentai" to listOf("latest", "popular"),
            "exhentai" to listOf("latest", "popular"),
            "nhentai" to listOf("latest", "popular"),
            "hitomi" to listOf("latest", "popular"),
            "wnacg" to listOf("latest"),
            "jmcomic" to listOf("latest"),
            "4khd" to listOf("latest", "popular"),
            "everia" to listOf("latest"),
            "4kup" to listOf("latest"),
            "galleryepic" to listOf("latest", "cosers"),
            "cosplaytele" to listOf("latest"),
        )
        assertEquals(19, SiteRegistry.all.size)
        assertEquals(featuredBySite.keys, SiteRegistry.all.map { it.id }.toSet())
        for (site in SiteRegistry.all) {
            val adapter = adapter(site)
            val page = siteFunctionPage(adapter)
            val featured = featuredBySite.getValue(site.id)
            assertEquals(site.id, featured, page.featured.map(Section::key))
            val extra = if (site == SiteRegistry.PORNHUB) listOf("categories", "pornstars") else emptyList()
            assertEquals(site.id, adapter.sections.map(Section::key).filterNot { it in featured } + extra,
                page.sections.map(Section::key))
            val sections = page.featured + page.sections
            assertEquals(site.id, sections.size, sections.map(Section::key).toSet().size)
            for (section in sections) {
                val url = section.feed?.let(adapter::resolve) ?: requireNotNull(adapter.sectionUrl(section.key))
                val host = url.toHttpUrl().host
                assertTrue("${site.id}/${section.key}", site.isSite(host) || host in site.apiHosts)
            }
        }
    }

    @Test fun searchModesAndCapabilitiesMatchEachSite() {
        val tagSearch = setOf("r34v", "hanime1", "iwara", "missav", "pornhub", "wnacg")
        val creators = mapOf(
            "r34v" to "作者", "iwara" to "作者", "missav" to "女优", "pornhub" to "演员",
            "ehentai" to "上传者", "exhentai" to "上传者", "nhentai" to "画师", "hitomi" to "画师",
        )
        val titlesBySite = mapOf(
            "ehentai" to "漫画", "exhentai" to "漫画", "nhentai" to "漫画", "hitomi" to "漫画",
            "wnacg" to "漫画", "jmcomic" to "漫画", "4khd" to "写真集", "everia" to "写真集",
            "4kup" to "写真集", "galleryepic" to "Coser", "cosplaytele" to "写真集",
        )
        val localSubscriptions = tagSearch + setOf("ehentai", "exhentai", "nhentai", "hitomi")
        val accounts = setOf("r34v", "hanime1", "iwara", "ehentai", "exhentai")
        val languages = setOf("ehentai", "exhentai", "nhentai", "hitomi")
        val originals = setOf("ehentai", "exhentai")

        for (site in SiteRegistry.all) {
            val id = site.id
            val adapter = adapter(site)
            val page = siteFunctionPage(adapter)
            val expectedSearch = buildList {
                add(Triple(titlesBySite[id] ?: "视频", FeedKind.SEARCH, false))
                if (id in tagSearch) add(Triple(if (id == "missav") "类型" else "标签", FeedKind.TAG, false))
                creators[id]?.let { add(Triple(it, FeedKind.MODEL, false)) }
                if (id == "iwara") add(Triple("图库", FeedKind.SEARCH, true))
            }
            assertEquals(id, expectedSearch, page.search.map { Triple(it.title, it.kind, it.gallery) })
            val keywordUrl = adapter.resolve(adapter.searchRequest("sample", FeedKind.SEARCH))
            page.search.forEach { option ->
                val request = adapter.searchRequest("sample", option.kind)
                assertEquals(id, option.kind, request.kind)
                if (option.kind != FeedKind.SEARCH) {
                    assertFalse("$id ${option.kind} uses the keyword route", adapter.resolve(request) == keywordUrl)
                }
            }
            assertEquals("$id subscriptions", id in localSubscriptions, page.subscriptions)
            assertEquals("$id account", id in accounts, page.account)
            assertEquals("$id languages", id in languages, page.languages)
            assertEquals("$id originals", id in originals, page.originals)
        }
    }

    @Test fun directoryEntriesRouteToTheirNativeFeedsOrIndex() {
        val pornhub = adapter(SiteRegistry.PORNHUB)
        val directories = siteFunctionPage(pornhub).sections.filter { it.feed == null }.associateBy(Section::key)
        val categories = pornhub.sectionUrl(directories.getValue("categories").key)
        val performers = pornhub.sectionUrl(directories.getValue("pornstars").key)
        assertEquals("${pornhub.site.base}/categories", categories)
        assertEquals("${pornhub.site.base}/pornstars", performers)
        assertEquals(FeedKind.CATEGORY, pornhub.linkRequest("$categories/sample")?.kind)
        assertEquals(FeedKind.MODEL, pornhub.linkRequest("${pornhub.site.base}/pornstar/sample")?.kind)

        val galleryEpic = adapter(SiteRegistry.GALLERYEPIC)
        val page = siteFunctionPage(galleryEpic)
        val directory = requireNotNull(page.featured.single { it.key == "cosers" }.feed)
        val search = galleryEpic.searchRequest("sample", page.search.single().kind)
        assertEquals("cosers:1:", galleryEpic.indexSection(directory))
        assertEquals("cosers:1:sample", galleryEpic.indexSection(search))
        assertEquals("sample", galleryEpic.resolve(search).toHttpUrl().queryParameter("coserName"))
    }

    @Test fun iwaraSeparatesVideoAndGallerySearchAndKeepsItsAuthenticatedFeed() {
        val iwara = adapter(SiteRegistry.IWARA)
        val page = siteFunctionPage(iwara)
        val images = page.featured.single { it.key == "images" }
        assertEquals("/images", iwara.resolve(requireNotNull(images.feed)).toHttpUrl().encodedPath)
        val searchTypes = page.search.filter { it.kind == FeedKind.SEARCH }.map { option ->
            val content = if (option.gallery) MediaKind.GALLERY else MediaKind.VIDEO
            val search = iwara.searchRequest("sample", option.kind).copy(contentKind = content)
            iwara.resolve(search).toHttpUrl().queryParameter("type")
        }
        assertEquals(listOf("videos", "images"), searchTypes)
        val following = requireNotNull(page.sections.single { it.key == "subscriptions" }.feed)
        assertEquals(FeedKind.SUBSCRIPTIONS, following.kind)
        assertEquals("/subscriptions/videos", iwara.resolve(following).toHttpUrl().encodedPath)
        val withoutLogin = object : SiteAdapter by iwara {
            override val capabilities = iwara.capabilities - Capability.LOGIN
        }
        val anonymous = siteFunctionPage(withoutLogin)
        assertFalse(anonymous.account)
        assertTrue(anonymous.subscriptions)
        assertFalse(anonymous.sections.any { it.feed?.kind == FeedKind.SUBSCRIPTIONS })
    }

    @Test fun missingPreferredRoutesFallBackToTheFirstReportedFeed() {
        val pornhub = adapter(SiteRegistry.PORNHUB)
        val first = Section("fixture-first", "示例列表", feed = FeedRequest())
        val second = Section("fixture-second", "另一列表", feed = FeedRequest(FeedKind.POPULAR))
        val directory = Section("catalog", "测试目录")
        val fixture = object : SiteAdapter by pornhub {
            override val capabilities = setOf(Capability.FEED)
            override val sections = listOf(Section("newest", "无路由"), directory, first, second, second)
            override val supportsLanguage = true
            override fun sectionUrl(key: String) = if (key == "catalog") "${site.base}/catalog/" else null
        }
        val page = siteFunctionPage(fixture)
        assertEquals(listOf(first), page.featured)
        assertEquals(listOf(directory, second), page.sections)
        assertTrue(page.search.isEmpty())
        assertFalse(page.subscriptions)
        assertFalse(page.account)
        assertTrue(page.languages)
        assertFalse(page.originals)
        assertNull(fixture.sectionUrl("pornstars"))
    }

    private fun adapter(site: SiteContext): SiteAdapter = AdapterRegistry.adapter(site, offlineNetwork)

    private companion object {
        // This fixture only supplies the metadata dependencies needed to construct the real adapters.
        val offlineNetwork by lazy {
            val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "contains" -> args?.get(0) == LibraryStore.SITE_SWITCH_TUTORIAL_SEEN
                    "getBoolean" -> args?.get(0) in setOf("kotlin_migration_v1", "site_namespace_v1")
                    "getString" -> args?.get(1)
                    "registerOnSharedPreferenceChangeListener" -> null
                    else -> error("Unexpected preferences call: ${method.name}")
                }
            } as SharedPreferences
            val context = object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String, mode: Int) = prefs
                override fun getApplicationInfo() = ApplicationInfo().apply {
                    dataDir = System.getProperty("java.io.tmpdir")
                }
            }
            Network(context, LibraryStore(context))
        }
    }
}
