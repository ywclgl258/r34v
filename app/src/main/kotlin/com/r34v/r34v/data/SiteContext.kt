package com.r34v.r34v.data

import java.net.URI

/** Which extraction strategy a site uses. */
enum class SiteMode { NATIVE, WEB }

/**
 * One browsable site. Everything the network, parser and UI layers need in order to
 * talk to a site without hard-coding a host anywhere.
 *
 * Stored records keep the stable [id]; the host may change when a mirror is swapped in.
 */
data class SiteContext(
    val id: String,
    val name: String,
    val base: String,
    val host: String,
    val userAgent: String = DEFAULT_UA,
    val referer: String = "$base/",
    val mode: SiteMode = SiteMode.NATIVE,
    /** Referer the media CDN expects; blank means the site referer. */
    val mediaReferer: String = "$base/",
    val accent: Int = 0xFFE50914.toInt(),
    val darkOnly: Boolean = true,
    val apiBase: String = base,
    /** API aliases only affect request headers; they must not share the website DNS/IP pool. */
    val apiHosts: Set<String> = emptySet(),
    /** Media receives the owning site's public headers, never its session cookies. */
    val mediaDomains: Set<String> = emptySet(),
    val mediaKind: MediaKind = MediaKind.VIDEO,
    val accessNote: String = "",
) {
    fun absolute(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:")) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault("")
    }

    fun isSite(hostname: String) = hostname == host || hostname == "www.$host"

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

/** Every site the app knows about. Order is the order shown in the switcher. */
object SiteRegistry {
    val R34V = SiteContext(
        id = "r34v",
        name = "Rule34Video",
        base = "https://rule34video.com",
        host = "rule34video.com",
    )

    val HANIME1 = SiteContext(
        id = "hanime1",
        name = "Hanime1",
        base = "https://hanime1.me",
        host = "hanime1.me",
        // Cloudflare rejects a blank User-Agent; any browser string is accepted.
        mediaReferer = "https://hanime1.me/",
        accent = 0xFFE50914.toInt(),
    )

    val IWARA = SiteContext(
        id = "iwara",
        name = "Iwara",
        base = "https://www.iwara.tv",
        host = "iwara.tv",
        userAgent = "R34V/Android",
        apiBase = "https://apiq.iwara.tv",
        apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv"),
        mediaDomains = setOf("iwara.tv"),
    )

    val EHENTAI = SiteContext(
        id = "ehentai", name = "E-Hentai", base = "https://e-hentai.org", host = "e-hentai.org",
        userAgent = "R34V/Android", mediaDomains = setOf("ehgt.org", "hath.network"), mediaKind = MediaKind.GALLERY,
    )
    val EXHENTAI = SiteContext(
        id = "exhentai", name = "ExHentai", base = "https://exhentai.org", host = "exhentai.org",
        userAgent = "R34V/Android", mediaDomains = setOf("ehgt.org", "hath.network"), mediaKind = MediaKind.GALLERY,
        accessNote = "需登录及账号权限",
    )
    val NHENTAI = SiteContext(
        id = "nhentai", name = "nhentai", base = "https://nhentai.net", host = "nhentai.net",
        userAgent = "R34V/2.2.0 (Android)", mediaDomains = setOf("nhentai.net"), mediaKind = MediaKind.GALLERY,
    )
    val HITOMI = SiteContext(
        id = "hitomi", name = "Hitomi", base = "https://hitomi.la", host = "hitomi.la",
        userAgent = "R34V/2.2.0 (Android)", mediaDomains = setOf("gold-usergeneratedcontent.net"), mediaKind = MediaKind.GALLERY,
    )

    val all: List<SiteContext> get() = listOf(R34V, HANIME1, IWARA, EHENTAI, EXHENTAI, NHENTAI, HITOMI)

    @Volatile
    var current: SiteContext = R34V

    fun byId(id: String): SiteContext? = all.firstOrNull { it.id == id }

    fun byIdOrDefault(id: String): SiteContext = byId(id) ?: R34V

    fun forHost(hostname: String): SiteContext? = all.firstOrNull { it.isSite(hostname) }

    fun forRequestHost(hostname: String): SiteContext? = forHost(hostname)
        ?: all.firstOrNull { hostname in it.apiHosts }

    fun forMediaHost(hostname: String): SiteContext? = forRequestHost(hostname)
        ?: all.firstOrNull { site -> site.mediaDomains.any { hostname == it || hostname.endsWith(".$it") } }

    /** True when the host belongs to any registered site. CDs and update servers stay false. */
    fun isRegistered(hostname: String): Boolean = forHost(hostname) != null

    fun mediaRefererFor(hostname: String): String? = forHost(hostname)?.mediaReferer

    fun userAgentFor(hostname: String): String? = forHost(hostname)?.userAgent
}
