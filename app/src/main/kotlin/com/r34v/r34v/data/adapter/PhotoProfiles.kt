package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.FeedKind
import com.r34v.r34v.data.Sort

/**
 * The per-site selector tables for the WordPress-shaped photo archives.
 *
 * Each entry was written against the live markup, and the differences are only in class names, the
 * article container and whether a post is split over `/post-slug/N/` content pages.
 */
object PhotoProfiles {
    // A post URL is the slug, optionally followed by the content-page number (`/slug/2/`).
    // Listing routes are excluded explicitly: without that guard `/page/2/` would look like a post
    // whose slug is "page".
    private val reserved = setOf(
        "page", "category", "tag", "search", "author", "feed", "comments", "wp-admin", "wp-json",
        "wp-content", "wp-login.php", "about", "contact", "privacy", "dmca", "faq",
    )
    private val datedSlug = Regex("^/(\\d{4}/\\d{2}/\\d{2}/[^/]+?)(?:/\\d+)?/?$")
    private val slug = Regex("^/([^/]+?)(?:/\\d+)?/?$")

    private fun postPath(pattern: Regex) = pattern

    /**
     * everia.club — 模特写真集，图片在站外 CDN 上，按帖子轮换域名（fujibox.top / karubox.top …）。
     *
     * 因此这里不锁主机名：正文里的 `figure` 就是图集本身，图片直取不需要 Referer。
     * 锁定单个 CDN 域名会在站点换域名时整帖读不到图。
     */
    fun everia() = WordPressPhotoProfile(
        itemSelector = "div.rt-holder",
        linkSelector = "h3.entry-title a[href]",
        thumbSelector = "img.rt-img-responsive",
        articleSelectors = listOf("div.entry-content", "article"),
        pageLinkSelector = "div.page-links span.post-page-numbers, div.page-links a.post-page-numbers",
        imageSelector = "figure img",
        imageHosts = emptySet(),
        postPath = postPath(datedSlug),
        reservedSegments = reserved,
    )

    /** 4kup.net — one post, one photo set; images live on Blogger's CDN and never paginate. */
    fun fourKup() = WordPressPhotoProfile(
        itemSelector = "div.generate-columns-container article",
        linkSelector = "h2.entry-title a[href]",
        thumbSelector = "div.post-thumbnail img",
        dateSelector = "time.entry-date",
        articleSelectors = listOf("div#gallery", "div.entry-content", "article"),
        imageSelector = "a.thumb-photo[href], img",
        imageHosts = setOf("googleusercontent.com"),
        postPath = postPath(slug),
        reservedSegments = reserved,
    )

    /** cosplaytele.com — full-resolution gallery inline in the article, one page. */
    fun cosplayTele() = WordPressPhotoProfile(
        itemSelector = "div.col.post-item",
        linkSelector = "h5.post-title a[href]",
        thumbSelector = "img.wp-post-image",
        articleSelectors = listOf("div#gallery-1", "div.entry-content", "article"),
        imageSelector = "figure a[href], img",
        imageHosts = setOf("cosplaytele.com/wp-content/uploads"),
        postPath = postPath(slug),
        reservedSegments = reserved,
    )
}

