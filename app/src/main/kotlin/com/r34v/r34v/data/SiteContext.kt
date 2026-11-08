package com.r34v.r34v.data

import java.net.URI

/** Which extraction strategy a site uses. */
enum class SiteMode { NATIVE, WEB }

/**
 * What a site serves, used by the switcher to group sites instead of showing one flat list.
 *
 * The category is a browsing concept and deliberately separate from [MediaKind]: a photo set is
 * read page by page like a gallery, but calling it a comic in the UI would be wrong.
 */
enum class SiteCategory(val title: String, val blurb: String, val itemNoun: String, val verb: String) {
    VIDEO("视频", "影片与动画", "视频", "观看"),
    COMIC("漫画", "同人志与连载", "漫画", "阅读"),
    PHOTO("写真", "写真与 Cosplay 图集", "写真集", "浏览"),
}

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
    val category: SiteCategory = SiteCategory.VIDEO,
    val accessNote: String = "",
    /**
     * Same-site mirrors, tried in order when [base] answers a Cloudflare challenge or is
     * unreachable. Only hosts that serve the identical markup may be listed here; a clone that
     * merely resembles the site belongs in its own [SiteContext].
     */
    val mirrors: List<String> = emptyList(),
) {
    fun absolute(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:")) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault("")
    }

    fun isSite(hostname: String) =
        hostname == host || hostname == "www.$host" ||
            mirrors.any { hostname == it || hostname == "www.$it" }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

/** Every site the app knows about. Order is the order shown in the switcher. */
object SiteRegistry {
    /** Local books have an identity but never participate in network site discovery. */
    val LOCAL = SiteContext(id = "local", name = "本地导入", base = "", host = "", mediaKind = MediaKind.GALLERY, category = SiteCategory.COMIC)
    val librarySources get() = all + LOCAL
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
        category = SiteCategory.COMIC,
    )
    val EXHENTAI = SiteContext(
        id = "exhentai", name = "ExHentai", base = "https://exhentai.org", host = "exhentai.org",
        userAgent = "R34V/Android", mediaDomains = setOf("ehgt.org", "hath.network"), mediaKind = MediaKind.GALLERY,
        category = SiteCategory.COMIC,
        accessNote = "需登录及账号权限",
    )
    val NHENTAI = SiteContext(
        id = "nhentai", name = "nhentai", base = "https://nhentai.net", host = "nhentai.net",
        userAgent = "R34V/2.2.0 (Android)", mediaDomains = setOf("nhentai.net"), mediaKind = MediaKind.GALLERY,
        category = SiteCategory.COMIC,
    )
    val HITOMI = SiteContext(
        id = "hitomi", name = "Hitomi", base = "https://hitomi.la", host = "hitomi.la",
        userAgent = "R34V/2.2.0 (Android)", mediaDomains = setOf("gold-usergeneratedcontent.net"), mediaKind = MediaKind.GALLERY,
        category = SiteCategory.COMIC,
    )

    /**
     * 4KHD — a WordPress photo-set archive: one post is one model book, and the images are spread
     * over numbered content pages (`/content/04/<slug>.html/2`). Read through the same gallery
     * reader as the comics, because both are "a titled, ordered list of images".
     */
    val FOURKHD = SiteContext(
        id = "4khd", name = "4KHD", base = "https://www.4khd.com", host = "4khd.com",
        // Every image is served through WordPress' Jetpack CDN, which keeps the pic.4khd.com path.
        mediaDomains = setOf("i0.wp.com", "pic.4khd.com"),
        mediaReferer = "https://www.4khd.com/",
        mediaKind = MediaKind.GALLERY,
        category = SiteCategory.PHOTO,
        accent = 0xFF4E9BFF.toInt(),
    )

    /**
     * everia.club — 模特写真集；图片在站外 CDN 上，域名按帖子轮换（fujibox.top / karubox.top …），
     * 所以这里不登记固定媒体域：图片请求由代码显式携带站点身份，不依赖主机名匹配。
     */
    val EVERIA = SiteContext(
        id = "everia", name = "Everia", base = "https://everia.club", host = "everia.club",
        mediaKind = MediaKind.GALLERY,
        category = SiteCategory.PHOTO,
        accent = 0xFFB07BFF.toInt(),
    )

    /** 4kup.net — Chinese model photo sets; images are served from Blogger's CDN. */
    val FOURKUP = SiteContext(
        id = "4kup", name = "4KUp", base = "https://4kup.net", host = "4kup.net",
        mediaDomains = setOf("blogger.googleusercontent.com"),
        mediaKind = MediaKind.GALLERY,
        category = SiteCategory.PHOTO,
        accent = 0xFFFF9A62.toInt(),
    )

    /** galleryepic.com — cosplay archive; a Next.js app whose payload carries the whole image list. */
    val GALLERYEPIC = SiteContext(
        id = "galleryepic", name = "GalleryEpic", base = "https://galleryepic.com", host = "galleryepic.com",
        mediaDomains = setOf("static.galleryepic.xyz"),
        mediaKind = MediaKind.GALLERY,
        category = SiteCategory.PHOTO,
        accent = 0xFF4ECDC4.toInt(),
    )

    /** cosplaytele.com — cosplay galleries published inline in the article, one page each. */
    val COSPLAYTELE = SiteContext(
        id = "cosplaytele", name = "Cosplaytele", base = "https://cosplaytele.com", host = "cosplaytele.com",
        mediaDomains = setOf("cossora.stream"),
        mediaKind = MediaKind.GALLERY,
        category = SiteCategory.PHOTO,
        accent = 0xFFFF6B9A.toInt(),
    )

    /**
     * MissAV — 番号式 JAV 站（`/dldss-584`）。站群按地区轮换域名，.ws 目前被 Cloudflare
     * 直接挑战，.media 返回同样结构，因此把 .media 作为镜像写入 [mirrors]。
     */
    val MISSAV = SiteContext(
        id = "missav", name = "MissAV", base = "https://missav.ws", host = "missav.ws",
        mirrors = listOf("missav.media"),
        mediaDomains = setOf("mrstcdn.store"),
        accent = 0xFFE23E57.toInt(),
        accessNote = "主域名被拦截时自动改用镜像",
    )

    /**
     * Jable — 番号式 JAV 站。jable.tv 长期处在 Cloudflare 托管挑战下，浏览器之外取不到页面；
     * jable.vip 是同一内容库的 MacCMS 前端，结构稳定，因此以它为基址，jable.tv 作为可识别镜像。
     */
    val JABLE = SiteContext(
        id = "jable", name = "Jable", base = "https://jable.vip", host = "jable.vip",
        mirrors = listOf("jable.tv"),
        mediaDomains = setOf("senlin2026.com", "sl260204.top"),
        accent = 0xFFFFB020.toInt(),
    )

    /**
     * rou.video — 短视频/短剧站。SSR 由 TanStack Start 输出，播放地址藏在 `ev` 载荷里
     * （base64 + 逐字节偏移），解析后指向 `/api/hls/<id>` 的 302 清单。
     */
    val ROUVIDEO = SiteContext(
        id = "rouvideo", name = "rou.video", base = "https://rou.video", host = "rou.video",
        mediaDomains = setOf("rn221.xyz", "rn252.xyz"),
        accent = 0xFFFF7A45.toInt(),
    )

    /** kbj.im — 韩国 BJ 录播聚合，一个帖子一集，播放器是站外 iframe，只做外链与信息展示。 */
    val KBJ = SiteContext(
        id = "kbj", name = "KBJ", base = "https://kbj.im", host = "kbj.im",
        mediaDomains = setOf("i1.wp.com"),
        accent = 0xFFF59E0B.toInt(),
        accessNote = "播放器由第三方托管，需在浏览器打开",
    )

    /** kbjfree.com — Next.js 版 BJ 录播站，列表数据在 RSC payload，播放地址是 CDN 上的 mp4。 */
    val KBJFREE = SiteContext(
        id = "kbjfree", name = "KBJFree", base = "https://kbjfree.com", host = "kbjfree.com",
        mediaDomains = setOf("marshlecdn.com"),
        accent = 0xFF22D3EE.toInt(),
    )

    /** pornhub.com — 综合视频站，卡片 `.pcVideoListItem`，播放源来自 flashvars 的 mediaDefinitions。 */
    val PORNHUB = SiteContext(
        id = "pornhub", name = "Pornhub", base = "https://www.pornhub.com", host = "www.pornhub.com",
        mirrors = listOf("pornhub.com"),
        mediaDomains = setOf("phncdn.com"),
        accent = 0xFFFF9000.toInt(),
    )

    val all: List<SiteContext> get() = listOf(
        R34V, HANIME1, IWARA, MISSAV, JABLE, ROUVIDEO, KBJ, KBJFREE, PORNHUB,
        EHENTAI, EXHENTAI, NHENTAI, HITOMI,
        FOURKHD, EVERIA, FOURKUP, GALLERYEPIC, COSPLAYTELE,
    )

    /** Sites of one category, in registry order; empty categories are never rendered. */
    fun ofCategory(category: SiteCategory): List<SiteContext> = all.filter { it.category == category }

    @Volatile
    var current: SiteContext = R34V

    fun byId(id: String): SiteContext? = if (id == LOCAL.id) LOCAL else all.firstOrNull { it.id == id }

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
