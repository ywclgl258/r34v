package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

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

    private fun openSubscriptions(showContent: Boolean = true) {
        clickText("片库")
        ui.onNode(hasContentDescription("订阅")).performClick()
        if (showContent) clickText("订阅内容")
    }

    @Test fun managementSearchAndActionsUseTheTagsOwnSiteAndFullOrder() = ComposeFixture().use { f ->
        val store = f.app.library
        (SiteRegistry.librarySources.map { it.id } + SiteRegistry.retiredVideoHosts.keys).forEach { store.clearSubscriptions(it) }
        val nearby = Subscription("shared-sample", "本站样本", site = "r34v")
        val first = Subscription("amber", "琥珀样本", site = "ehentai")
        val remote = Subscription("shared-sample", "蓝本示例", site = "ehentai")
        listOf(nearby, first, remote).forEach { store.toggleSubscription(it, it.site) }

        f.launch(); waitHome(); openSubscriptions(showContent = false)
        val list = ui.onNodeWithTag("subscriptions")
        ui.onNodeWithText("全部来源").assertExists()
        ui.onNodeWithText("3 个订阅").assertExists()
        ui.onNodeWithText("Rule34Video · 顺序 1").assertExists()
        f.shot("library-management-subscriptions")
        list.performScrollToNode(hasText("E-Hentai · 顺序 2"))
        val search = ui.onNodeWithTag("subscription-search")
        search.performTextInput("蓝本")
        search.performImeAction()
        waitText("找到 1 个标签")
        ui.onNodeWithText(remote.title).assertExists()
        ui.onNodeWithText(nearby.title).assertDoesNotExist()

        search.performTextReplacement("SHARED-SAMPLE")
        search.performImeAction()
        waitText("找到 2 个标签")
        clickText("全部来源")
        clickText("E-Hentai · 2")
        waitText("找到 1 个标签")
        ui.onNodeWithText(first.title).assertDoesNotExist()
        ui.onNodeWithText("E-Hentai · 顺序 2").assertExists()
        ui.onNodeWithContentDescription("管理 ${remote.title} · E-Hentai").performClick()
        clickText("上移")
        waitText("E-Hentai · 顺序 1")
        assertEquals(listOf(remote.slug, first.slug), store.subscriptions(remote.site).map { it.slug })

        ui.onNodeWithContentDescription("管理 ${remote.title} · E-Hentai").performClick()
        clickText("取消订阅")
        ui.onNode(hasText("取消订阅") and hasAnyAncestor(isDialog())).performClick()
        waitText("没有匹配的标签，试试其他名称或标识。")
        ui.onNodeWithContentDescription("清空标签搜索").performClick()
        waitText(first.title)
        assertEquals(listOf(first.slug), store.subscriptions(remote.site).map { it.slug })
        assertTrue(store.isSubscribed(nearby.slug, nearby.site))
        assertEquals("r34v", store.siteId)
        assertTrue(f.requests.none { it.second.kind == FeedKind.TAG })
    }

    @Test fun browsingAManagedTagUsesItsSourceAndReturnsToTheFilter() = ComposeFixture().use { f ->
        val store = f.app.library
        store.clearSubscriptions("r34v")
        store.clearSubscriptions("ehentai")
        subscribeMany(f, "r34v", listOf("local-sample"))
        subscribeMany(f, "ehentai", listOf("remote-sample"))
        f.launch(); waitHome(); openSubscriptions(showContent = false)
        clickText("全部来源")
        clickText("E-Hentai · 1")
        waitText("REMOTE-SAMPLE")
        clickText("浏览")
        ui.waitUntil(15000) { f.requests.any { (source, request) ->
            source == "ehentai" && request.kind == FeedKind.TAG && request.target == "remote-sample"
        } }
        waitText("remote-sample 作品 0")
        assertEquals("r34v", store.siteId)
        ui.onNodeWithContentDescription("返回").performClick()
        waitText("REMOTE-SAMPLE")
        ui.onNode(hasText("E-Hentai") and hasClickAction() and !hasTestTag("site-entry-ehentai")).assertIsDisplayed()
        ui.onNodeWithText("LOCAL-SAMPLE").assertDoesNotExist()
        assertTrue(f.requests.none { (source, request) -> source == "r34v" && request.kind == FeedKind.TAG })
    }

    @Test fun readingTheSelectedSitesUpdatesReusesItsContentRefresh() = ComposeFixture().use { f ->
        val store = f.app.library
        store.clearSubscriptions("r34v")
        store.clearSubscriptions("ehentai")
        subscribeMany(f, "r34v", listOf("local-sample"))
        subscribeMany(f, "ehentai", listOf("remote-sample"))
        f.launch(); waitHome(); openSubscriptions(showContent = false)
        clickText("全部来源")
        clickText("E-Hentai · 1")
        clickText("订阅内容")
        waitText("remote-sample 作品 0")
        clickText("更新收件箱")
        waitText("检查更新")
        fun tagSources() = f.requests.filter { it.second.kind == FeedKind.TAG }.map { it.first }
        assertEquals(listOf("ehentai"), tagSources())
        clickText("检查更新")
        ui.waitUntil(15000) { tagSources().size == 2 }
        waitText("检查更新")
        assertEquals(listOf("ehentai", "ehentai"), tagSources())
        assertEquals("r34v", store.siteId)
    }

    private fun openTagMenu(tag: Subscription) {
        val source = SiteRegistry.byId(tag.site)?.name ?: SiteRegistry.retiredVideoHosts.getValue(tag.site)
        val menu = hasContentDescription("管理 ${tag.title} · $source")
        ui.onNodeWithTag("subscriptions").performScrollToNode(menu)
        ui.onNode(menu).performClick()
    }

    private fun checkTag(tag: Subscription, result: String) {
        openTagMenu(tag)
        clickText("检查此标签")
        waitText(result)
    }

    private fun ComposeFixture.tagRequests() = requests.filter { it.second.kind == FeedKind.TAG }
        .map { (site, request) -> site to request.target }

    @Test fun checkingOneManagedTagEstablishesItsBaselineAndCountsOnlyNewUnreadWorks() = ComposeFixture().use { f ->
        val store = f.app.library
        val source = SiteRegistry.EHENTAI.id
        val tag = Subscription("shared-sample", "同名标签", site = source)
        val peer = Subscription("another-sample", "同站未检查标签", site = source)
        val other = tag.copy(site = SiteRegistry.R34V.id)
        val retired = Subscription("retired-sample", "已下线来源标签", site = SiteRegistry.retiredVideoHosts.keys.first())
        store.prefs.edit().remove(PersonalStore.KEY).remove("$source:blocked_tags").commit()
        listOf(tag.site, other.site, retired.site).forEach(store::clearSubscriptions)
        listOf(tag, peer, other, retired).forEach { store.toggleSubscription(it, it.site) }
        val baseline = f.video(source)
        val fresh = f.video(source, index = 1)
        val wrongSite = f.video(other.site, index = 2)
        val response = AtomicReference(listOf(baseline, wrongSite))
        f.feedOverride = { site, request ->
            if (site == source && request.kind == FeedKind.TAG && request.target == tag.slug) {
                FeedPage(response.get(), 1, 1, "标签检查样本")
            } else null
        }

        f.launch(); waitHome(); openSubscriptions(showContent = false)
        checkTag(tag, "联网成功 · 已建立基线 · 新增未读 0 项")
        assertEquals(mapOf(tag.slug to listOf(baseline.id)), store.personal.inbox(source).known)
        assertTrue(store.personal.inbox(source).entries.single().read)
        assertEquals(listOf(source to tag.slug), f.tagRequests())
        assertEquals(SubscriptionInbox(), store.personal.inbox(other.site))
        assertEquals(other.site, store.siteId)

        response.set(listOf(fresh, baseline, wrongSite))
        checkTag(tag, "联网成功 · 新增未读 1 项")
        assertEquals(listOf(fresh.entryKey), store.personal.inbox(source).entries.filterNot { it.read }.map { it.video.entryKey })
        assertEquals(List(2) { source to tag.slug }, f.tagRequests())
        clickText("更新收件箱")
        waitText("未读 1")
        assertEquals(List(2) { source to tag.slug }, f.tagRequests())
        clickText("全部标记已读")
        waitText("未读 0")
        clickText("管理标签")
        checkTag(tag, "联网成功 · 新增未读 0 项")
        assertTrue(store.personal.inbox(source).entries.all { it.read })
        assertEquals(List(3) { source to tag.slug }, f.tagRequests())
        assertEquals(SubscriptionInbox(), store.personal.inbox(other.site))

        clickText("订阅内容")
        ui.waitUntil(15_000) { peer.slug in store.personal.inbox(source).known }
        ui.onNodeWithTag("subscriptions").performScrollToNode(hasText(peer.title))
        val expectedRequests = List(3) { source to tag.slug } + (source to peer.slug)
        assertEquals(expectedRequests, f.tagRequests())
        clickText("更新收件箱")
        waitText("未读 0")
        clickText("管理标签")
        openTagMenu(retired)
        ui.onNodeWithText("检查此标签").assertDoesNotExist()
        ui.onNodeWithText("取消订阅").assertIsDisplayed()
        assertEquals(expectedRequests, f.tagRequests())
        assertEquals(other.site, store.siteId)
    }

    @Test fun failedAndCachedTagChecksPreserveBaselineAndReadState() = ComposeFixture().use { f ->
        val store = f.app.library
        val source = SiteRegistry.R34V
        val tag = Subscription("tag-check-${f.nonce}", "快照测试标签", site = source.id)
        store.prefs.edit().remove(PersonalStore.KEY).remove("${source.id}:blocked_tags").commit()
        store.clearSubscriptions(source.id)
        store.toggleSubscription(tag, source.id)
        val baseline = f.video(source.id)
        val unread = f.video(source.id, index = 1)
        val fresh = f.video(source.id, index = 2)
        store.personal.refreshInbox(source.id, listOf(TagFeed(tag, listOf(baseline))))
        store.personal.refreshInbox(source.id, listOf(TagFeed(tag, listOf(unread, baseline))))
        val before = store.personal.snapshot().inboxes
        f.failTag = tag.slug

        f.launch(); waitHome(); openSubscriptions(showContent = false)
        checkTag(tag, "检查失败 · 未更新收件箱")
        assertEquals(before, store.personal.snapshot().inboxes)

        val response = listOf(fresh, unread, baseline.copy(tagSlugs = listOf("changed-in-snapshot")))
        f.failTag = ""
        f.feedOverride = { site, request ->
            if (site == source.id && request.kind == FeedKind.TAG && request.target == tag.slug) {
                FeedPage(response, 1, 1, "标签检查样本")
            } else null
        }
        runBlocking { f.app.repository.feed(FeedRequest(FeedKind.TAG, tag.slug, Sort.NEWEST), source) }
        f.offline = true
        checkTag(tag, "使用旧快照 · 未更新收件箱")
        assertEquals(before, store.personal.snapshot().inboxes)

        f.offline = false
        checkTag(tag, "联网成功 · 新增未读 1 项")
        val after = store.personal.inbox(source.id)
        assertEquals(setOf(baseline.id, unread.id, fresh.id), after.known.getValue(tag.slug).toSet())
        assertEquals(setOf(unread.id, fresh.id), after.entries.filterNot { it.read }.map { it.video.id }.toSet())
        assertEquals(List(4) { source.id to tag.slug }, f.tagRequests())
    }

    @Test fun lockedSiteChecksStayLocalAndCancellingAnInflightTagLeavesNoBaseline() = ComposeFixture().use { f ->
        val store = f.app.library
        val source = SiteRegistry.R34V.id
        val tag = Subscription("held-sample", "稍后取消的标签", site = source)
        val peer = Subscription("peer-sample", "同站还在订阅的标签", site = source)
        val other = tag.copy(site = SiteRegistry.EHENTAI.id, label = "其他来源的同名标识")
        store.prefs.edit().remove(PersonalStore.KEY).remove("$source:blocked_tags").commit()
        listOf(source, other.site).forEach(store::clearSubscriptions)
        listOf(tag, peer, other).forEach { store.toggleSubscription(it, it.site) }
        val hold = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.feedOverride = { site, request ->
            if (site == source && request.kind == FeedKind.TAG && request.target == tag.slug) {
                try {
                    hold.await()
                    FeedPage(listOf(f.video(source)), 1, 1, "取消测试样本")
                } finally {
                    stopped.complete(Unit)
                }
            } else null
        }

        f.launch(); openSourceHome()
        ui.onNodeWithContentDescription("本站功能").performClick()
        ui.onNodeWithTag("site-functions-$source").performScrollToNode(hasTestTag("site-subscriptions"))
        ui.onNodeWithTag("site-subscriptions").performClick()
        waitText(tag.title)
        ui.onNodeWithText(other.title).assertDoesNotExist()
        ui.onNodeWithText("全部来源").assertDoesNotExist()
        openTagMenu(tag)
        clickText("检查此标签")
        waitText("正在检查此标签…")
        ui.waitUntil(15_000) { f.tagRequests().size == 1 }
        openTagMenu(tag)
        ui.onNodeWithText("检查此标签").assertIsNotEnabled()
        back(f)
        clickText("更新收件箱")
        ui.onNodeWithText("检查中").assertIsNotEnabled()
        assertEquals(listOf(source to tag.slug), f.tagRequests())
        clickText("管理标签")
        openTagMenu(tag)
        clickText("取消订阅")
        ui.onNode(hasText("取消订阅") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        ui.waitUntil(15_000) { stopped.isCompleted }
        assertFalse(store.isSubscribed(tag.slug, source))
        assertTrue(store.isSubscribed(other.slug, other.site))
        assertEquals(emptyMap<String, SubscriptionInbox>(), store.personal.snapshot().inboxes)
        assertEquals(listOf(source to tag.slug), f.tagRequests())

        checkTag(peer, "联网成功 · 已建立基线 · 新增未读 0 项")
        assertEquals(setOf(peer.slug), store.personal.inbox(source).known.keys)
        assertEquals(listOf(source to tag.slug, source to peer.slug), f.tagRequests())
    }

    @Test fun indexFollowButtonReflectsBothClicksImmediately() {
        ComposeFixture().use { f ->
            f.app.library.clearSubscriptions("r34v")
            f.launch(); waitHome(); openSubscriptions(showContent = false)
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

    @Test fun existingGlobalUpdatesFollowTheWorksSiteAndUpdateTheBadgeWithoutRefreshing() = ComposeFixture().use { f ->
        val store = f.app.library
        store.prefs.edit().remove(PersonalStore.KEY).remove("r34v:blocked_tags").remove("ehentai:blocked_tags").commit()
        val local = f.video("r34v").copy(id = "same-id", title = "Shared marker 本站作品")
        val remote = f.video("ehentai").copy(id = "same-id", title = "Shared marker 其他来源作品")
        for (video in listOf(local, remote)) {
            val tag = Subscription("sample", site = video.site)
            store.personal.refreshInbox(video.site, listOf(TagFeed(tag, emptyList())))
            store.personal.refreshInbox(video.site, listOf(TagFeed(tag, listOf(video))))
        }
        f.launch(); waitHome(); openPersonalHome()
        val home = ui.onNodeWithTag("personal-home")
        home.performScrollToNode(hasText("订阅更新 · 2"))
        val tagRequests = f.requests.count { it.second.kind == FeedKind.TAG }
        store.toggleBlocked("shared-marker", remote.site)
        clickText("订阅更新 · 1")
        waitText(local.title)
        ui.onNodeWithText(remote.title).assertDoesNotExist()
        assertEquals("r34v", store.siteId)
        assertFalse(store.allowed(remote))
        assertTrue(store.allowed(local))

        clickText("全部")
        ui.onNodeWithText(remote.title).assertDoesNotExist()
        store.toggleBlocked("shared-marker", remote.site)
        waitText(remote.title)
        clickText("未读")
        waitText(remote.title)
        assertFalse(store.personal.inbox(remote.site).entries.single().read)
        assertEquals(tagRequests, f.requests.count { it.second.kind == FeedKind.TAG })
    }

    @Test fun siteInboxBulkReadPreservesHiddenEntriesAndRefreshBaseline() = ComposeFixture().use { f ->
        val store = f.app.library
        store.prefs.edit().remove(PersonalStore.KEY).remove("r34v:blocked_tags").commit()
        store.clearSubscriptions("r34v")
        subscribeMany(f, "r34v", listOf("sample"))
        val subscription = store.subscriptions("r34v").single()
        val base = f.video().copy(id = "baseline", title = "原有屏蔽作品", tagSlugs = listOf("sample_hidden"))
        val hidden = f.video().copy(id = "hidden", title = "后来屏蔽作品", tagSlugs = listOf("sample_hidden"))
        val visible = f.video().copy(id = "visible", title = "相似标签作品", tagSlugs = listOf("sample_hidden_extra"))
        store.toggleBlocked("sample_hidden")
        store.personal.refreshInbox("r34v", listOf(TagFeed(subscription, listOf(base))))
        f.feedOverride = { owner, request ->
            if (owner == "r34v" && request.kind == FeedKind.TAG) FeedPage(listOf(hidden, visible, base), 1, 1, "样例") else null
        }
        f.launch(); waitHome(); openSubscriptions(); waitText(visible.title)
        ui.onNodeWithText(hidden.title).assertDoesNotExist()
        clickText("更新收件箱"); waitText("未读 1")
        val inbox = ui.onNodeWithTag("subscriptions")
        inbox.performScrollToNode(hasText("全部标记已读"))
        clickText("全部标记已读"); waitText("未读 0")
        val stored = store.personal.inbox("r34v").entries.associateBy { it.video.id }
        assertTrue(stored.getValue(base.id).read)
        assertTrue(stored.getValue(visible.id).read)
        assertFalse(stored.getValue(hidden.id).read)
        val tagRequests = f.requests.count { it.second.kind == FeedKind.TAG }
        store.toggleBlocked("sample_hidden")
        waitText("未读 1")
        inbox.performScrollToNode(hasText(hidden.title))
        ui.onNodeWithText(hidden.title).assertExists()
        assertEquals(tagRequests, f.requests.count { it.second.kind == FeedKind.TAG })
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
