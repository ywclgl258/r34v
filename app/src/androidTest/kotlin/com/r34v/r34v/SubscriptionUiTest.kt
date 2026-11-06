package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The subscription screen must show only followed tags, keep sites apart, and survive one tag
 * failing. Those three claims are what make it different from a normal feed, so each is checked
 * against the real Compose screen rather than the store alone.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionUiTest : ComposeUiCase() {

    private fun subscribeMany(f: ComposeFixture, site: String, slugs: List<String>) {
        slugs.forEach { slug ->
            f.app.library.toggleSubscription(Subscription(slug, slug.uppercase(), "${SiteRegistry.byIdOrDefault(site).base}/tags/$slug/", site), site)
        }
    }

    private fun openSubscriptions() {
        clickText("片库")
        ui.onNode(hasContentDescription("订阅")).performClick()
    }

    @Test fun indexFollowButtonReflectsBothClicksImmediately() {
        ComposeFixture().use { f ->
            f.app.library.clearSubscriptions("r34v")
            f.launch(); waitHome(); openSubscriptions()
            clickText("浏览标签"); waitText("示例索引")
            ui.onNodeWithText("订阅").performClick()
            ui.onNodeWithText("已订阅").assertExists()
            assertTrue(f.app.library.isSubscribed("sample", "r34v"))
            ui.onNodeWithText("已订阅").performClick()
            ui.onNodeWithText("订阅").assertExists()
            assertFalse(f.app.library.isSubscribed("sample", "r34v"))
        }
    }

    @Test fun authorIndexesDoNotOfferTagSubscriptionButtons() {
        ComposeFixture().use { f ->
            val activity = f.launch(); waitHome()
            val browse = MainActivity::class.java.getDeclaredMethod("browse", String::class.java).apply { isAccessible = true }
            for (segment in listOf("models", "artists")) {
                f.ins.runOnMainSync { browse.invoke(activity, segment) }
                ui.waitUntil(15000) { ui.onAllNodes(hasText("示例索引", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                ui.onNode(hasText("示例索引", substring = true)).assertExists()
                ui.onAllNodesWithText("订阅").assertCountEquals(0)
                ui.onAllNodesWithText("已订阅").assertCountEquals(0)
            }
        }
    }

    @Test fun gallerySubscriptionMenuRecognizesAndRemovesSavedGallery() {
        ComposeFixture().use { f ->
            f.app.library.clearSubscriptions("ehentai")
            subscribeMany(f, "ehentai", listOf("alpha"))
            val video = f.video("ehentai").let { it.copy(id = "alpha-${it.id}", title = "alpha 作品 0") }
            f.app.library.toggleFavorite(Favorite("gallery", video.id, video.title, video.url, site = "ehentai"), "ehentai")
            f.app.library.switchSite("ehentai")
            f.launch(); openSourceHome(); waitText("最新漫画"); openSubscriptions(); waitText(video.title)
            ui.onNodeWithContentDescription("${video.title} 的更多操作").performClick()
            ui.onNodeWithText("已收藏 / 取消收藏").performClick()
            assertFalse(f.app.library.isFavorite("gallery", video.id, "ehentai"))
            ui.onNodeWithContentDescription("${video.title} 的更多操作").performClick()
            ui.onNodeWithText("收藏").assertExists()
        }
    }

    @Test fun explicitSubscriptionSiteUsesItsOwnAdapterBlocklistAndOfflineCache() {
        ComposeFixture().use { f ->
            f.app.library.prefs.edit().putString("r34v:blocked_tags", "[\"alpha\"]")
                .putString("ehentai:blocked_tags", "[\"beta\"]").commit()
            f.app.library.switchSite("r34v")
            val tags = listOf("alpha", "beta").map { Subscription(slug = it, site = "ehentai") }
            val page = runBlocking { f.app.repository.subscriptions(SiteRegistry.EHENTAI, SubscriptionOrder.SUBSCRIBED, tags) }
            assertTrue(page.failed.isEmpty())
            assertTrue(page.videos.isNotEmpty())
            assertTrue(page.videos.all { it.site == "ehentai" && it.id.startsWith("alpha-") })
            assertTrue(f.requests.all { it.first == "ehentai" && it.second.kind == FeedKind.TAG })
            f.offline = true
            val cached = runBlocking { f.app.repository.subscriptions(SiteRegistry.EHENTAI, SubscriptionOrder.SUBSCRIBED, tags) }
            assertTrue(cached.tags.all { it.cached })
            assertEquals(page.videos.map { it.id }, cached.videos.map { it.id })
            assertEquals("r34v", f.app.library.siteId)
        }
    }

    @Test fun screenShowsOnlyFollowedTagsAndTheirWorks() {
        ComposeFixture().use { f ->
            f.launch(); waitHome()
            subscribeMany(f, "r34v", listOf("alpha", "beta"))
            openSubscriptions()
            // The center proves it fetched exactly the followed tags, not the site's whole feed.
            ui.waitUntil(15000) { f.requests.any { it.second.kind == FeedKind.TAG && it.second.target == "alpha" } }
            ui.waitUntil(15000) { f.requests.any { it.second.kind == FeedKind.TAG && it.second.target == "beta" } }
            ui.onNodeWithTag("subscriptions").assertExists()
            assertEquals("只请求已订阅的两个标签", setOf("alpha", "beta"),
                f.requests.filter { it.second.kind == FeedKind.TAG }.map { it.second.target }.toSet())
            ui.onNodeWithText("ALPHA").assertExists()
            // BETA lives below the first screen; scroll to it instead of waiting for it to appear.
            ui.onNodeWithTag("subscriptions").performScrollToNode(hasText("BETA"))
            ui.onNodeWithText("BETA").assertExists()
            f.shot("30-subscriptions")
        }
    }

    @Test fun sitesKeepTheirOwnSubscriptions() {
        ComposeFixture().use { f ->
            subscribeMany(f, "r34v", listOf("alpha"))
            subscribeMany(f, "ehentai", listOf("omega"))
            f.app.library.switchSite("r34v")
            assertTrue(f.app.library.isSubscribed("alpha", "r34v"))
            assertFalse("别的站的订阅不能串过来", f.app.library.isSubscribed("omega", "r34v"))
            assertEquals(1, f.app.library.subscriptions("r34v").size)
            assertEquals(1, f.app.library.subscriptions("ehentai").size)
            assertEquals("omega", f.app.library.subscriptions("ehentai").single().slug)
        }
    }

    @Test fun reorderingChangesTheGroupedOrder() {
        ComposeFixture().use { f ->
            subscribeMany(f, "r34v", listOf("alpha", "beta", "gamma"))
            assertEquals(listOf("alpha", "beta", "gamma"), f.app.library.subscriptions("r34v").map { it.slug })
            assertTrue(f.app.library.moveSubscription(2, 0, "r34v"))
            assertEquals(listOf("gamma", "alpha", "beta"), f.app.library.subscriptions("r34v").map { it.slug })
            assertFalse("越界移动必须被拒绝", f.app.library.moveSubscription(0, 9, "r34v"))
            assertEquals(listOf("gamma", "alpha", "beta"), f.app.library.subscriptions("r34v").map { it.slug })
        }
    }

    @Test fun orderChoiceIsRememberedPerSite() {
        ComposeFixture().use { f ->
            f.app.library.setSubscriptionOrder(SubscriptionOrder.INTERLEAVED, "r34v")
            assertEquals(SubscriptionOrder.INTERLEAVED, f.app.library.subscriptionOrder("r34v"))
            assertEquals("别的站保持默认", SubscriptionOrder.SUBSCRIBED, f.app.library.subscriptionOrder("ehentai"))
            f.app.library.setSubscriptionOrder(SubscriptionOrder.TITLE, "r34v")
            assertEquals(SubscriptionOrder.TITLE, f.app.library.subscriptionOrder("r34v"))
        }
    }

    @Test fun aFailingTagLeavesTheRestVisible() {
        ComposeFixture().use { f ->
            subscribeMany(f, "r34v", listOf("alpha", "beta"))
            f.failTag = "beta"
            f.launch(); waitHome()
            openSubscriptions()
            ui.waitUntil(15000) { ui.onAllNodesWithText("alpha 作品", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15000) { ui.onAllNodesWithText("1 个标签暂时取不到内容", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("alpha 作品 0").assertExists()
        }
    }

    @Test fun unfollowingRemovesOnlyThatTag() {
        ComposeFixture().use { f ->
            subscribeMany(f, "r34v", listOf("alpha", "beta"))
            f.app.library.removeSubscription("alpha", "r34v")
            assertEquals(listOf("beta"), f.app.library.subscriptions("r34v").map { it.slug })
            f.app.library.clearSubscriptions("r34v")
            assertTrue(f.app.library.subscriptions("r34v").isEmpty())
            assertEquals("清空一个站不影响另一个", 0, f.app.library.subscriptions("ehentai").size)
        }
    }
}
