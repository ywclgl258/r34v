package com.r34v.r34v.ui

import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.adapter.Capability
import com.r34v.r34v.data.adapter.Section
import com.r34v.r34v.data.adapter.SiteAdapter

internal data class SiteFunctionPage(
    val featured: List<Section>,
    val sections: List<Section>,
    val search: List<SiteSearchOption>,
    val subscriptions: Boolean,
    val account: Boolean,
    val languages: Boolean,
    val originals: Boolean,
)

internal data class SiteSearchOption(val title: String, val kind: FeedKind, val gallery: Boolean = false)

internal fun siteFunctionPage(adapter: SiteAdapter): SiteFunctionPage {
    val site = adapter.site.id
    val capabilities = adapter.capabilities
    val login = Capability.LOGIN in capabilities
    val sections = (adapter.sections + extraDirectories(site)).filter { section ->
        when (section.feed?.kind) {
            null -> !adapter.sectionUrl(section.key).isNullOrBlank()
            FeedKind.SUBSCRIPTIONS -> login
            else -> true
        }
    }.distinctBy(Section::key)
    val byKey = sections.associateBy(Section::key)
    val featured = featuredKeys(site).mapNotNull(byKey::get).ifEmpty {
        listOfNotNull(sections.firstOrNull { it.feed != null })
    }

    return SiteFunctionPage(
        featured = featured,
        sections = sections.filterNot { it in featured },
        search = if (Capability.SEARCH in capabilities) searchOptions(adapter) else emptyList(),
        subscriptions = Capability.TAG in capabilities && site in tagSubscriptionSites,
        account = login || Capability.SESSION_IMPORT in capabilities,
        languages = adapter.supportsLanguage,
        originals = Capability.ORIGINAL_IMAGES in capabilities,
    )
}

private fun featuredKeys(site: String): List<String> = when (site) {
    "r34v" -> listOf("popular", "latest")
    "hanime1" -> listOf("recent", "today")
    "iwara" -> listOf("trending", "images")
    "missav" -> listOf("new", "actresses")
    "jable" -> listOf("new", "hot")
    "rouvideo" -> listOf("home", "category")
    "kbjfree" -> listOf("recent", "trending")
    "pornhub" -> listOf("newest", "mostviewed")
    "ehentai", "exhentai", "nhentai", "hitomi" -> listOf("latest", "popular")
    "wnacg", "jmcomic" -> listOf("latest")
    "4khd" -> listOf("latest", "popular")
    "everia", "4kup", "cosplaytele" -> listOf("latest")
    "galleryepic" -> listOf("latest", "cosers")
    else -> emptyList()
}

private fun extraDirectories(site: String): List<Section> = when (site) {
    "pornhub" -> listOf(Section("categories", "分类"), Section("pornstars", "演员索引"))
    else -> emptyList()
}

private val tagSearchSites = setOf("r34v", "hanime1", "iwara", "missav", "pornhub", "wnacg")
private val creatorSearchSites = setOf("r34v", "iwara", "missav", "pornhub", "ehentai", "exhentai", "nhentai", "hitomi")

// These galleries accept qualified tag queries from subscriptions and detail links.
private val tagSubscriptionSites = tagSearchSites + setOf("ehentai", "exhentai", "nhentai", "hitomi")

private fun searchOptions(adapter: SiteAdapter): List<SiteSearchOption> = buildList {
    val site = adapter.site.id
    val workTitle = if (site == "galleryepic") "Coser" else adapter.itemNoun
    add(SiteSearchOption(workTitle, FeedKind.SEARCH))
    if (Capability.TAG in adapter.capabilities && site in tagSearchSites) {
        add(SiteSearchOption(if (site == "missav") "类型" else "标签", FeedKind.TAG))
    }
    if (Capability.MODEL in adapter.capabilities && site in creatorSearchSites) {
        add(SiteSearchOption(adapter.creatorTitle, FeedKind.MODEL))
    }
    if (site == "iwara") add(SiteSearchOption("图库", FeedKind.SEARCH, gallery = true))
}
