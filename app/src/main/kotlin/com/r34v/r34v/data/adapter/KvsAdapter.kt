package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * rule34video.com — a Kernel Video Sharing (KVS) tube.
 *
 * Behaviour is unchanged from the pre-multi-site implementation; the class only exposes it
 * through [SiteAdapter] and resolves URLs against its own [SiteContext] instead of a global.
 */
class KvsAdapter(
    override val site: SiteContext,
    private val network: Network,
) : SiteAdapter {

    override val capabilities = setOf(
        Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL,
        Capability.CATEGORY, Capability.RANKING, Capability.LOGIN, Capability.DOWNLOAD,
    )

    override val sorts = listOf(Sort.RELEVANCE, Sort.NEWEST, Sort.VIEWS, Sort.RATING, Sort.DURATION, Sort.RANDOM)

    override val sections = listOf(
        Section("popular", "热门榜", "看看大家最近在看什么", FeedRequest(FeedKind.POPULAR)),
        Section("latest", "最新更新", "", FeedRequest(FeedKind.LATEST)),
        Section("rated", "高分榜", "", FeedRequest(FeedKind.RATED)),
        Section("foryou", "为你推荐", "", FeedRequest(FeedKind.FOR_YOU)),
        Section("categories", "分类", "按内容类型浏览"),
        Section("works", "角色与作品", ""),
        Section("artists", "作者排行", ""),
        Section("models", "作者索引", ""),
        Section("tags", "标签索引", ""),
    )

    override fun resolve(request: FeedRequest): String {
        val sortPart = if (request.sort.param.isNotEmpty() && (request.sort != Sort.NEWEST || request.kind == FeedKind.SEARCH))
            "?sort_by=${request.sort.param}" else ""
        return site.base + request.path + sortPart
    }

    override fun homeUrl() = "${site.base}/latest-updates/"

    override suspend fun feed(request: FeedRequest): FeedPage {
        val page = HtmlParser.feed(network.html(resolve(request)), request, site)
        return page
    }

    override suspend fun detail(url: String) = HtmlParser.detail(network.html(site.absolute(url)), site.absolute(url), site)

    override suspend fun index(section: String): List<Link> {
        val url = sectionUrl(section) ?: return emptyList()
        val html = network.html(url)
        return when {
            section == "works" -> HtmlParser.works(html, site)
            section == "artists" || section.startsWith("work:") -> HtmlParser.ranking(html, site)
            else -> HtmlParser.index(html, section, site)
        }
    }

    override fun sectionUrl(key: String): String? = when {
        key in setOf("categories", "tags", "models") -> "${site.base}/$key/"
        key == "works" -> "${site.base}/top-characters/"
        key == "artists" -> "${site.base}/top-model/?sort=az"
        key.startsWith("work:") -> "${site.base}/top-characters/${key.removePrefix("work:")}/"
        else -> null
    }

    override suspend fun login(network: Network, username: String, password: String) {
        val store = network.session
        val url = "${site.base}/login/"
        val loginPage = network.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "登录页 HTTP ${response.code}" }; response.body?.string().orEmpty()
        }
        val token = org.jsoup.Jsoup.parse(loginPage).selectFirst("input[name=remember_me_csrf_token]")?.attr("value")
            ?: error("无法读取登录令牌，站点可能要求浏览器验证")
        val form = okhttp3.FormBody.Builder()
            .add("username", username.trim()).add("pass", password)
            .add("action", "login").add("remember_me", "1")
            .add("remember_me_csrf_token", token).build()
        network.client.newCall(okhttp3.Request.Builder().url(url).post(form).build()).execute().use { response ->
            check(response.isSuccessful) { "登录失败：HTTP ${response.code}" }
            val body = response.body?.string().orEmpty(); val doc = org.jsoup.Jsoup.parse(body)
            val error = doc.selectFirst(".generic-error, .alert-error")?.text().orEmpty()
            check(error.isBlank()) { error }
            check(!body.contains("id=\"login_username\"") || body.contains("action=logout") || body.contains("/logout/")) {
                "登录未成功，请检查用户名、密码和邮箱验证"
            }
            check(store.loadForRequest(site.base.toHttpUrl()).any { it.name == "PHPSESSID" }) { "服务器没有返回会话" }
        }
    }
}
