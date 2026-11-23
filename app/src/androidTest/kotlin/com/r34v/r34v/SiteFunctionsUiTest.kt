package com.r34v.r34v

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.AdapterRegistry
import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.GalleryDownloadState
import com.r34v.r34v.data.GalleryLanguage
import com.r34v.r34v.data.MediaKind
import com.r34v.r34v.data.Network
import com.r34v.r34v.data.SessionCookies
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Subscription
import com.r34v.r34v.data.Video
import com.r34v.r34v.data.adapter.SiteAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SiteFunctionsUiTest : ComposeUiCase() {
    private val eh = SiteRegistry.EHENTAI
    private val ex = SiteRegistry.EXHENTAI
    private val nh = SiteRegistry.NHENTAI
    private val iwara = SiteRegistry.IWARA

    @Test fun everySiteCanSwitchInPlaceFromTheFunctionsPage() = withSiteFixture(SiteRegistry.all) { f ->
        f.offline = true
        f.launch()
        ui.onNodeWithContentDescription("本站功能").assertIsDisplayed().performClick()
        requireFunctions(SiteRegistry.R34V)
        assertEquals(SiteRegistry.R34V.id, f.app.library.siteId)
        for (site in SiteRegistry.all.filterNot { it == SiteRegistry.R34V }) {
            selectSite(site)
            requireFunctions(site)
            assertEquals(site.id, f.app.library.siteId)
            if (site == SiteRegistry.COSPLAYTELE) {
                functionRow(site, "site-reader-mode").assert(hasText("写真浏览方式"))
            }
        }
    }

    @Test fun favoritesAreScopedToTheSelectedSite() = withSiteFixture { f ->
        val books = listOf(eh, nh).map { site ->
            val entry = f.video(site.id)
            f.launchGallery(site = site.id)
            waitText("收藏漫画")
            ui.onNodeWithText("收藏漫画").performScrollTo().performClick()
            ui.onNodeWithText("已收藏").assertIsSelected()
            assertTrue(f.app.library.isFavorite("gallery", entry.id, site.id))
            entry
        }

        f.launch()
        waitHome()
        for ((index, book) in books.withIndex()) {
            val site = SiteRegistry.byIdOrDefault(book.site)
            openFunctions(site)
            functionRow(site, "site-favorites").performClick()
            requireTitle(site, "收藏")
            val key = "${book.site}:gallery:${book.id}"
            val other = books[1 - index]
            assertScopedRows("favorites-list", key, "${other.site}:gallery:${other.id}")
            ui.onNode(hasTestTag("favorite-$key") and hasText(book.title)).assertIsDisplayed()
            if (site == eh) f.shot("site-functions-favorites-ehentai")
            returnToFunctions(f, site)
        }
    }

    @Test fun downloadsAreScopedAndExHentaiOpensOfflineWithoutAnAccount() = withSiteFixture { f ->
        val context = f.ins.targetContext
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            f.ins.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        f.app.downloadPolicy.setUnmetered(false)
        val publicBook = f.video(eh.id)
        val privateBook = f.video(ex.id)
        f.launchGallery(site = eh.id)
        waitText("下载整本")
        ui.onNodeWithText("下载整本").performScrollTo().performClick()
        awaitDownload(f, publicBook)
        // Model a book downloaded before sign-out using the real queue and neutral fixture pages.
        runBlocking { f.app.galleryDownloads.add(privateBook) }
        awaitDownload(f, privateBook)
        val publicKey = "gallery:${f.app.galleryDownloads.key(publicBook)}"
        val privateKey = "gallery:${f.app.galleryDownloads.key(privateBook)}"

        f.launch()
        waitHome()
        openFunctions(eh)
        functionRow(eh, "site-downloads").performClick()
        requireTitle(eh, "下载")
        assertScopedRows("download-center", publicKey, privateKey)
        ui.onNodeWithTag("download-$publicKey").assertIsDisplayed()
        returnToFunctions(f, eh)

        openFunctions(ex)
        assertFalse(f.app.network.session.hasGallerySession(ex))
        functionRow(ex, "site-account-required")
        f.shot("site-functions-exhentai-signed-out")
        functionRow(ex, "site-downloads").performClick()
        requireTitle(ex, "下载")
        assertScopedRows("download-center", privateKey, publicKey)
        val offlineRow = hasAnyAncestor(hasTestTag("download-$privateKey")) and
            hasClickAction() and hasText(privateBook.title) and hasText("离线打开")
        ui.onNode(offlineRow).assertIsDisplayed()
        f.shot("site-functions-downloads-exhentai")
        returnToFunctions(f, ex)

        f.offline = true
        val galleryCalls = f.galleryCalls.size
        val pageCalls = f.imageRequests.size
        functionRow(ex, "site-downloads").performClick()
        assertScopedRows("download-center", privateKey, publicKey)
        ui.onNode(offlineRow).performClick()
        val page = hasContentDescription("第 1 页，双击或双指缩放") and
            hasAnyAncestor(hasTestTag("reader-page-1"))
        ui.waitUntil(15_000) { ui.onAllNodes(page, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(page, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(galleryCalls, f.galleryCalls.size)
        assertEquals(pageCalls, f.imageRequests.size)
    }

    @Test fun tagSubscriptionsStayLocalAndScopedToTheSelectedSite() = withSiteFixture { f ->
        val sharedSlug = "site-functions-${f.nonce}"
        val publicTag = Subscription(sharedSlug, "公用画廊示例标签", site = eh.id)
        val privateTag = Subscription(sharedSlug, "离线画廊示例标签", site = ex.id)
        for (tag in listOf(publicTag, privateTag)) {
            assertTrue(f.app.library.toggleSubscription(tag, tag.site))
        }
        f.offline = true
        f.launch()
        openFunctions(eh)
        functionRow(eh, "site-subscriptions").performClick()
        requireTitle(eh, "标签订阅")
        assertScopedRows("subscriptions", publicTag.key, privateTag.key)
        ui.onNodeWithText(publicTag.title).assertIsDisplayed()
        returnToFunctions(f, eh)

        openFunctions(ex)
        functionRow(ex, "site-account-required")
        functionRow(ex, "site-subscriptions").performClick()
        requireTitle(ex, "标签订阅")
        assertScopedRows("subscriptions", privateTag.key, publicTag.key)
        ui.onNodeWithText(privateTag.title).assertIsDisplayed()
        f.shot("site-functions-subscriptions-exhentai")

        ui.onNodeWithContentDescription("管理 ${privateTag.title} · ${ex.name}").performClick()
        clickText("取消订阅")
        ui.onNode(hasText("取消订阅") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        assertFalse(f.app.library.isSubscribed(sharedSlug, ex.id))
        assertTrue(f.app.library.isSubscribed(sharedSlug, eh.id))
        assertTrue(f.requests.none { it.second.kind == FeedKind.TAG && it.second.target == sharedSlug })
        returnToFunctions(f, ex)
    }

    @Test fun comicOptionsAndIwaraGalleryUseSiteMetadata() = withSiteFixture { f ->
        val store = f.app.library
        store.setGalleryLanguage(GalleryLanguage.ALL, eh.id)
        store.personal.setReadingBehavior(eh.id, store.personal.readingBehavior(eh.id).copy(originalImages = false))
        f.launch()
        waitHome()
        openFunctions(eh)
        f.shot("site-functions-ehentai")
        functionRow(eh, "site-language").performClick()
        ui.onNodeWithText("本站漫画语言").assertIsDisplayed()
        ui.onNodeWithText(GalleryLanguage.CHINESE.title).assertIsDisplayed().performClick()
        assertEquals(GalleryLanguage.CHINESE, store.galleryLanguage(eh.id))
        functionRow(eh, "site-reader-mode").assert(hasText("漫画阅读方式"))
        functionRow(eh, "site-originals").performClick()
        ui.onNodeWithText("本站图页画质").assertIsDisplayed()
        ui.onNodeWithText("优先原图（消耗更多流量）").assertIsDisplayed().performClick()
        assertTrue(store.personal.readingBehavior(eh.id).originalImages)
        ui.onNode(hasTestTag("site-originals") and hasText("已开启")).assertIsDisplayed()

        openFunctions(iwara)
        val gallery = AdapterRegistry.adapter(iwara, f.app.network).sections.single { it.key == "images" }
        val galleryFeed = checkNotNull(gallery.feed).copy(language = store.galleryLanguage(iwara.id))
        assertEquals(MediaKind.GALLERY, galleryFeed.contentKind)
        val tile = functionRow(iwara, "site-section-images")
        tile.assert(hasText(gallery.title))
        f.shot("site-functions-iwara")
        val galleryRequests = f.requests.count { (site, request) -> site == iwara.id && request == galleryFeed }
        tile.performClick()
        ui.waitUntil(15_000) {
            f.requests.count { (site, request) -> site == iwara.id && request == galleryFeed } > galleryRequests
        }
        returnToFunctions(f, iwara)
    }

    private fun openFunctions(site: SiteContext) {
        selectSite(site)
        ui.onNode(hasText("发现") and hasAnyAncestor(hasTestTag("primary-navigation"))).performClick()
        ui.onNode(hasText("本站功能") and hasClickAction()).assertIsDisplayed().performClick()
        requireFunctions(site)
    }

    private fun selectSite(site: SiteContext) {
        ui.onNode(hasContentDescription("切换站点，当前", substring = true)).performClick()
        ui.onNodeWithContentDescription("站点列表").performScrollToKey(site.id)
        ui.onNodeWithTag("site-entry-${site.id}").assertIsDisplayed().performClick()
    }

    private fun requireFunctions(site: SiteContext) {
        ui.waitUntil(15_000) { ui.onNodeWithTag("site-functions-${site.id}").isDisplayed() }
    }

    private fun returnToFunctions(f: ComposeFixture, site: SiteContext) {
        back(f)
        requireFunctions(site)
    }

    private fun requireTitle(site: SiteContext, title: String) {
        val fullTitle = "${site.name} · $title"
        try {
            waitText(fullTitle)
            ui.onNodeWithText(fullTitle).assertIsDisplayed()
        } catch (error: AssertionError) {
            ui.onRoot().printToLog("SiteFunctionsRoute")
            throw error
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            ui.onRoot().printToLog("SiteFunctionsRoute")
            throw error
        }
    }

    private fun functionRow(site: SiteContext, tag: String): SemanticsNodeInteraction {
        val root = hasTestTag("site-functions-${site.id}")
        val row = hasTestTag(tag)
        ui.onNode(root).performScrollToNode(row)
        return ui.onNode(row and hasAnyAncestor(root)).assertIsDisplayed().assertHasClickAction()
    }

    private fun assertScopedRows(listTag: String, currentKey: String, otherKey: String) {
        val list = ui.onNodeWithTag(listTag)
        ui.waitUntil(15_000) {
            list.isDisplayed() &&
                list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.IndexForKey)
                    ?.invoke(currentKey)?.let { it >= 0 } == true
        }
        val indexForKey = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        // IndexForKey sees the whole lazy list, including rows outside the viewport.
        assertEquals("Foreign row $otherKey in $listTag", -1, indexForKey(otherKey))
        list.performScrollToKey(currentKey)
    }

    private fun awaitDownload(f: ComposeFixture, entry: Video) {
        ui.waitUntil(25_000) { f.app.galleryDownloads.task(entry)?.state == GalleryDownloadState.COMPLETED }
        assertTrue(f.app.galleries.isDownloaded(entry))
    }

    private fun withSiteFixture(
        sites: List<SiteContext> = listOf(eh, ex, nh, iwara),
        test: (ComposeFixture) -> Unit,
    ) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as R34Application
        val metadata = sites.associateWith { AdapterRegistry.adapter(it, app.network) }
        val cacheField = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val adapters = cacheField.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val session = app.network.session
        val bucketsField = SessionCookies::class.java.getDeclaredField("buckets").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val buckets = bucketsField.get(session) as MutableMap<String, MutableList<Cookie>>
        val previous = synchronized(session) { buckets.put(ex.host, mutableListOf()) }
        try {
            ComposeFixture(awaitUi = { ui.waitForIdle() }).use { f ->
                synchronized(AdapterRegistry) {
                    for ((site, live) in metadata) {
                        val neutral = adapters.getValue(site.id)
                        adapters[site.id] = object : SiteAdapter by neutral {
                            override val capabilities = live.capabilities
                            override val sections = live.sections
                            override val sorts = live.sorts
                            override val supportsLanguage = live.supportsLanguage
                            override val catalogueTitle = live.catalogueTitle
                            override val creatorTitle = live.creatorTitle
                            override val itemNoun = live.itemNoun
                            override fun sectionUrl(key: String) = live.sectionUrl(key)
                        }
                    }
                }
                val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
                // Fixture interceptors serve synthetic covers and pages before this fallback.
                clientField.set(app.network, app.network.client.newBuilder().addInterceptor {
                    throw IOException("Unexpected network request in site functions test")
                }.build())
                test(f)
            }
        } finally {
            synchronized(session) {
                if (previous == null) buckets.remove(ex.host) else buckets[ex.host] = previous
            }
        }
    }
}
