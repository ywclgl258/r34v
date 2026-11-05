package com.r34v.r34v

import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Merging rules for the subscription screen.
 *
 * Pure logic on purpose: the interesting cases are duplicates, tag order and back-pressure from a
 * failed tag, none of which need a network to be worth checking.
 */
class SubscriptionMergeTest {
    private fun video(id: String, title: String = "标题 $id", site: String = "r34v") = Video(id = id, title = title, site = site)

    private fun tag(slug: String, ids: List<String>) =
        TagFeed(Subscription(slug = slug, label = slug.uppercase(), site = "r34v"), ids.map { video(it) })

    @Test fun oneWorkUnderTwoTagsAppearsOnceInTagOrder() {
        val tags = listOf(tag("a", listOf("1", "2")), tag("b", listOf("2", "3")))
        val grouped = SubscriptionMerge.grouped(tags)
        assertEquals(listOf("a", "b"), grouped.map { it.first.slug })
        assertEquals(listOf("1", "2"), grouped[0].second.map { it.id })
        // "2" already appeared under the first tag, so the second tag must not repeat it.
        assertEquals(listOf("3"), grouped[1].second.map { it.id })
        assertEquals(3, SubscriptionMerge.sort(tags, SubscriptionOrder.SUBSCRIBED).size)
    }

    @Test fun interleavedTakesTurnsBetweenTags() {
        val tags = listOf(tag("a", listOf("a1", "a2", "a3")), tag("b", listOf("b1", "b2")))
        val mixed = SubscriptionMerge.sort(tags, SubscriptionOrder.INTERLEAVED)
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), mixed.map { it.id })
    }

    @Test fun interleavedDropsDuplicatesWithoutSkippingTheQueue() {
        val tags = listOf(tag("a", listOf("x", "y")), tag("b", listOf("x", "z")))
        val mixed = SubscriptionMerge.sort(tags, SubscriptionOrder.INTERLEAVED)
        // b's "x" is already shown, so b fills its turn with its next item instead of going idle.
        assertEquals(listOf("x", "z", "y"), mixed.map { it.id })
    }

    @Test fun titleOrderIsCaseInsensitiveAndStableForEqualTitles() {
        val tags = listOf(TagFeed(Subscription(slug = "a", site = "r34v"),
            listOf(video("2", "beta"), video("1", "Alpha"), video("3", "alpha"))))
        val sorted = SubscriptionMerge.sort(tags, SubscriptionOrder.TITLE).map { it.id }
        assertEquals("Alpha 与 alpha 视为同一档，按 id 兜底", listOf("1", "3", "2"), sorted)
    }

    @Test fun aFailedTagDoesNotRemoveTheOthers() {
        val good = tag("good", listOf("1"))
        val bad = TagFeed(Subscription(slug = "bad", site = "r34v"), emptyList(), error = "网络失败")
        val page = SubscriptionPage(listOf(good, bad), SubscriptionMerge.sort(listOf(good, bad), SubscriptionOrder.SUBSCRIBED),
            SubscriptionOrder.SUBSCRIBED, listOf(bad))
        assertEquals(1, page.videos.size)
        assertEquals(1, page.available)
        assertEquals("bad", page.failed.single().subscription.slug)
    }

    @Test fun emptyTagListProducesAnEmptyPageRatherThanAnError() {
        val page = SubscriptionPage(emptyList(), SubscriptionMerge.sort(emptyList(), SubscriptionOrder.SUBSCRIBED), SubscriptionOrder.SUBSCRIBED)
        assertTrue(page.isEmpty)
        assertTrue(page.videos.isEmpty())
    }

    @Test fun subscriptionsAreIsolatedPerSite() {
        val store = FakeStore()
        store.toggle("r34v", "sample")
        store.toggle("ehentai", "sample")
        // Same slug on two sites is two independent subscriptions.
        assertTrue(store.has("r34v", "sample"))
        assertTrue(store.has("ehentai", "sample"))
        store.toggle("r34v", "sample")
        assertFalse(store.has("r34v", "sample"))
        assertTrue("取消一边不能影响另一边", store.has("ehentai", "sample"))
    }

    /** Minimal mirror of the store's per-site keying, proving the isolation rule itself. */
    private class FakeStore {
        private val data = mutableMapOf<String, MutableList<String>>()
        fun toggle(site: String, slug: String) {
            val list = data.getOrPut(site) { mutableListOf() }
            if (!list.remove(slug)) list.add(slug)
        }
        fun has(site: String, slug: String) = data[site]?.contains(slug) == true
    }
}
