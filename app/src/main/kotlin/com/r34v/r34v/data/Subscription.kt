package com.r34v.r34v.data

import com.google.gson.annotations.SerializedName

/**
 * One tag the user follows.
 *
 * Stored per site: a tag slug only means something inside one catalogue, so the same slug on two
 * sites is two different subscriptions and neither can shadow the other.
 */
data class Subscription(
    val slug: String = "",
    @SerializedName("label") val label: String = "",
    val url: String = "",
    @SerializedName("site") val site: String = SiteRegistry.current.id,
    @SerializedName("at") val at: Long = System.currentTimeMillis(),
) {
    val key get() = "$site:$slug"
    val title get() = label.ifBlank { slug }
}

/** How the merged subscription feed is ordered. */
enum class SubscriptionOrder(val title: String, val hint: String) {
    SUBSCRIBED("订阅顺序", "按你排好的标签顺序分组显示"),
    INTERLEAVED("混合交替", "各标签轮流插入，避免一个标签占满整屏"),
    TITLE("名称排序", "按作品标题排列"),
}

/** One tag's result inside a subscription refresh; failures stay local to that tag. */
data class TagFeed(val subscription: Subscription, val videos: List<Video>, val error: String? = null, val cached: Boolean = false)

data class SubscriptionPage(
    val tags: List<TagFeed>,
    /** Deduplicated works, already ordered by the requested [SubscriptionOrder]. */
    val videos: List<Video>,
    val order: SubscriptionOrder,
    val failed: List<TagFeed> = emptyList(),
) {
    val isEmpty get() = tags.isEmpty()
    val available get() = tags.count { it.error == null }
}

/**
 * Merging rules for several tag feeds, kept pure so the ordering can be tested without network.
 *
 * A work can carry several subscribed tags. It is shown once, under the first tag that produced
 * it, which means the user's own tag order decides where it lands in [SubscriptionOrder.SUBSCRIBED].
 */
object SubscriptionMerge {
    fun key(video: Video) = "${video.site}:${video.id}"

    /** Works in the user's tag order, each work appearing once under the first tag that had it. */
    fun grouped(tags: List<TagFeed>): List<Pair<Subscription, List<Video>>> {
        val seen = HashSet<String>()
        return tags.map { feed ->
            val fresh = feed.videos.filter { seen.add(key(it)) }
            feed.subscription to fresh
        }.filter { it.second.isNotEmpty() }
    }

    /** Round-robin across tags so no single tag monopolises the top of the screen. */
    fun interleaved(tags: List<TagFeed>): List<Video> {
        val seen = HashSet<String>()
        val queues = tags.map { feed -> feed.videos.filter { key(it) !in seen }.toMutableList() }
        val result = mutableListOf<Video>()
        while (queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                while (queue.isNotEmpty()) {
                    val next = queue.removeAt(0)
                    if (seen.add(key(next))) { result += next; break }
                }
            }
        }
        return result
    }

    fun sort(tags: List<TagFeed>, order: SubscriptionOrder): List<Video> = when (order) {
        SubscriptionOrder.SUBSCRIBED -> grouped(tags).flatMap { it.second }
        SubscriptionOrder.INTERLEAVED -> interleaved(tags)
        SubscriptionOrder.TITLE -> interleaved(tags).sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    }
}
