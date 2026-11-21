package com.r34v.r34v.data

import androidx.core.content.edit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.File
import java.net.Proxy
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Cookies are bucketed per host so that switching sites never leaks or drops a session.
 * Only hosts belonging to a registered site are ever written or read; CDNs and the update
 * server stay cookie-free.
 */
class SessionCookies(private val store: LibraryStore) : CookieJar {
    private val buckets = mutableMapOf<String, MutableList<Cookie>>()

    private fun registered(host: String) = SiteRegistry.isRegistered(host)

    private fun load(site: SiteContext): MutableList<Cookie> = buckets.getOrPut(site.host) {
        val restored = mutableListOf<Cookie>()
        ((if (site.mediaKind == MediaKind.GALLERY) store.galleryCookies(site) else null)
            ?: store.prefs.getStringSet("${site.id}:cookies", null))?.forEach { raw ->
            decodeCookie(site, raw)?.let(restored::add)
        }
        // Legacy: the pre-multi-site jar carried a single unnamed bucket plus anti-bot cookies.
        if (restored.isEmpty() && site.id == SiteRegistry.R34V.id) {
            for ((name, value) in store.legacyCookies(site.id)) {
                runCatching { Cookie.Builder().name(name).value(value).hostOnlyDomain(site.host).secure().httpOnly().build() }
                    .getOrNull()?.let(restored::add)
            }
            runCatching {
                val jar = com.google.gson.JsonParser.parseString(store.prefs.getString("site_cookies_v1", "{}")).asJsonObject
                for ((host, entries) in jar.entrySet()) if (site.isSite(host)) for (entry in entries.asJsonArray) {
                    val raw = entry.asJsonObject; val name = raw["n"]?.asString.orEmpty()
                    if (!name.startsWith("__ddg") || restored.any { it.name == name }) continue
                    val expiry = raw["e"]?.asLong ?: Long.MAX_VALUE
                    if (expiry <= System.currentTimeMillis()) continue
                    restored.add(
                        Cookie.Builder().name(name).value(raw["v"].asString).hostOnlyDomain(host)
                            .path(raw["p"]?.asString ?: "/").expiresAt(expiry).secure().build()
                    )
                }
            }
        }
        restored
    }

    /**
     * Cookie 序列化。
     *
     * `Cookie.toString()` 对 host-only cookie 不输出 Domain，重启后按站点主域解析会把镜像
     * （missav.media 之类）签发的 cookie 绑到主域上，既发给错误主机也收不到原镜像的会话。
     * 这里显式带上来源主机；没有前缀的旧数据仍按原逻辑解析。
     */
    private fun encodeCookie(cookie: Cookie): String =
        if (cookie.hostOnly) "h\u0001${cookie.domain}\u0001$cookie" else "d\u0001$cookie"

    private fun decodeCookie(site: SiteContext, raw: String): Cookie? {
        if (!raw.startsWith("h\u0001") && !raw.startsWith("d\u0001")) return Cookie.parse(site.base.toHttpUrl(), raw)
        val parts = raw.split('\u0001', limit = 3)
        val header = parts.getOrNull(2) ?: return null
        return when (parts[0]) {
            "h" -> {
                val host = parts.getOrNull(1).orEmpty()
                val base = if (host.isNotBlank() && site.isSite(host)) host else site.host
                Cookie.parse("https://$base/".toHttpUrl(), header)
            }
            else -> Cookie.parse(site.base.toHttpUrl(), header)
        }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val site = SiteRegistry.forHost(url.host) ?: return
        if (!registered(url.host)) return
        val bucket = load(site)
        for (cookie in cookies) {
            if (!site.isSite(cookie.domain)) continue
            bucket.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) bucket.add(cookie)
        }
        persist(site, bucket)
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val site = SiteRegistry.forHost(url.host) ?: return emptyList()
        if (!registered(url.host)) return emptyList()
        val now = System.currentTimeMillis()
        val cookies = load(site).filter { it.matches(url) && it.expiresAt > now }
        return if (site.id == SiteRegistry.EHENTAI.id || site.id == SiteRegistry.EXHENTAI.id) cookies.filter { it.name != "nw" } +
            Cookie.Builder().name("nw").value("1").hostOnlyDomain(url.host).secure().build() else cookies
    }

    private fun persist(site: SiteContext, cookies: List<Cookie>) {
        val values = cookies.map(::encodeCookie).toSet()
        if (site.mediaKind == MediaKind.GALLERY) store.saveGalleryCookies(site, values)
        else store.prefs.edit { putStringSet("${site.id}:cookies", values) }
    }
    @Synchronized fun replace(site: SiteContext, cookies: List<Cookie>) {
        require(cookies.all { site.isSite(it.domain) })
        persist(site, cookies)
        buckets[site.host] = cookies.toMutableList()
    }
    @Synchronized fun hasGallerySession(site: SiteContext) = load(site).let { cookies ->
        listOf("ipb_member_id", "ipb_pass_hash").all { name -> cookies.any { it.name == name && it.expiresAt > System.currentTimeMillis() } }
    }

    /** Clears one site only. Signing out of A must never sign the user out of B. */
    @Synchronized fun clear(site: SiteContext) {
        buckets.remove(site.host)
        store.prefs.edit { remove("${site.id}:cookies").remove("${site.id}:encrypted_cookies") }
        store.logout(site.id)
    }
}

/**
 * 同一站点主机上的请求起点节流。
 *
 * rule34video 这类站对短时间连续的页面请求很快返回 429，而开屏时的订阅合并（3 并发）、
 * 保存的搜索、诊断会在同一主机上叠出一段突发。这里把每个主机的请求**起点**按固定间隔
 * 错开——只推迟起点，不占住连接，响应体仍然并发传输。间隔按目的地分两档：页面慢、
 * 图片快（浏览器滚动列表时本来也是图片高频、导航低频）。媒体 CDN（boomio-cdn 等不在
 * 站点清单里的主机）与更新服务器不受影响。
 */
internal class HostGate {
    private val lock = Any()
    private var nextSlot = 0L

    /** 返回本次请求需要等待的毫秒数，并把该主机的下一个起点往前推 [gapMs] 一格。 */
    fun reserve(now: Long, gapMs: Long): Long = synchronized(lock) {
        val start = maxOf(now, nextSlot)
        nextSlot = start + gapMs
        start - now
    }
}

/** 页面导航的起点间隔：400ms ≈ 每主机至多 2.5 请求/秒。 */
private const val PAGE_REQUEST_GAP_MS = 400L

/** 列表封面这类图片的起点间隔：浏览一屏 30 张封面时摊开到 4.5 秒，接近真实滚动手感。 */
private const val IMAGE_REQUEST_GAP_MS = 150L

/** 带 these 后缀的路径按图片对待：封面、预览图、动图封面。 */
internal val BROWSER_IMAGE_PATH =
    Regex("\\.(jpe?g|png|gif|webp|avif|webm)(\\?.*|#.*|$)", RegexOption.IGNORE_CASE)

/**
 * 站点把媒体地址 302 到 CDN 的解析结果缓存。
 *
 * KVS 的 /get-file 每次都要先敲站点主域再跳 CDN：播放里的每一次 seek、每一次引擎重建、
 * 下载管理器的每次重开都会重复这笔账，而站点的限流器把它们全算在同一 IP 上。浏览器每次
 * seek 也会重新跟一遍 302，这里更进一步：解析一次，令牌窗口期内直接敲 CDN，把主域的
 * 请求数压到浏览器的几分之一。CDN 侧 403/404/410（令牌过期、防盗链变化）立即作废并回
 * 站点重解析，不会拿旧地址硬撑。
 */
internal class MediaRedirectCache(
    private val ttlMs: Long = 30L * 60 * 1000,
    private val eligible: (okhttp3.HttpUrl) -> Boolean = { SiteRegistry.isRegistered(it.host) },
) {
    private data class Resolved(val url: okhttp3.HttpUrl, val expiresAt: Long)
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, Resolved>()

    private fun expired(entry: Resolved) = entry.expiresAt <= System.currentTimeMillis()

    /**
     * 回填解析结果。过期项必须真正删除：只跳过不删会让旧键一直占位，
     * `putIfAbsent` 永远写不进去，同一媒体之后的每次打开都会重新请求站点主域。
     */
    private fun cacheIfAbsent(key: String, url: okhttp3.HttpUrl) {
        if (resolved.size > 256) resolved.values.removeAll { expired(it) }
        val fresh = Resolved(url, System.currentTimeMillis() + ttlMs)
        resolved.compute(key) { _, existing -> if (existing == null || expired(existing)) fresh else existing }
    }

    /**
     * 应用层：命中缓存就把请求改写到已解析的 CDN 地址；未命中时给请求挂上解析键，
     * 由 [networkInterceptor] 在最终一跳上回填结果。
     */
    fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        if (!eligible(original.url)) return chain.proceed(original)
        val key = original.url.toString()
        val hit = resolved[key]?.takeIf { !expired(it) }
        if (hit != null) {
            val direct = chain.proceed(original.newBuilder().tag(String::class.java, key).url(hit.url).build())
            if (direct.code !in INVALID) return direct
            // CDN 拒绝：多半是签好令牌过期了。作废缓存，回到站点重新走一遍 302。
            direct.close()
            resolved.remove(key, hit)
            RateLimitLog.record(original.url.host, "CDN HTTP ${direct.code}，重新解析媒体地址")
        }
        return chain.proceed(original.newBuilder().tag(String::class.java, key).build())
    }

    /** 网络层：每一跳都能看到真实地址（应用层看不到 302 之后的跳转），成功响应即回填。 */
    fun interceptNetwork(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val key = request.tag(String::class.java)
        if (key != null && response.isSuccessful) {
            val origin = key.toHttpUrlOrNull()
            if (origin != null && request.url.host != origin.host) cacheIfAbsent(key, request.url)
        }
        return response
    }

    val interceptor = okhttp3.Interceptor { intercept(it) }
    val networkInterceptor = okhttp3.Interceptor { interceptNetwork(it) }

    companion object { private val INVALID = setOf(403, 404, 410) }
}

/**
 * 按请求目的地补齐“浏览器一定会带、裸 OkHttp 不带”的头。站点的机器人评分和限流都看
 * 请求形态：Chrome 对页面导航、封面图片、CORS API、<video> 媒体各自发不同的 Accept 与
 * Sec-Fetch 组合。已显式设置的头部一律不覆盖——adapter 或调用方显式给的头说明它有更具体的意图。
 * 未登记的主机（更新服务器、未知 CDN）保持最小形态不动。
 */
internal fun applyBrowserShape(builder: okhttp3.Request.Builder, request: okhttp3.Request, site: SiteContext?) {
    if (site == null) return
    val sameOrigin = SiteRegistry.forRequestHost(request.url.host) != null
    when {
        request.url.host in site.apiHosts -> {
            if (request.header("Accept") == null) builder.header("Accept", "application/json, text/plain, */*")
            fetch(request, builder, dest = "empty", mode = "cors", site = "same-site")
        }
        BROWSER_IMAGE_PATH.containsMatchIn(request.url.encodedPath) -> {
            if (request.header("Accept") == null)
                builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            fetch(request, builder, dest = "image", mode = "no-cors",
                site = if (sameOrigin) "same-origin" else "cross-site")
        }
        else -> {
            if (request.header("Accept") == null)
                builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            fetch(request, builder, dest = "document", mode = "navigate", site = "same-origin")
            if (request.header("Upgrade-Insecure-Requests") == null) builder.header("Upgrade-Insecure-Requests", "1")
        }
    }
    if (request.header("Accept-Language") == null) builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
}

private fun fetch(request: okhttp3.Request, builder: okhttp3.Request.Builder, dest: String, mode: String, site: String) {
    if (request.header("Sec-Fetch-Dest") == null) builder.header("Sec-Fetch-Dest", dest)
    if (request.header("Sec-Fetch-Mode") == null) builder.header("Sec-Fetch-Mode", mode)
    if (request.header("Sec-Fetch-Site") == null) builder.header("Sec-Fetch-Site", site)
}

/**
 * API JSON 路径的 429 退避，与 [SiteHtml] 的页面口径一致：同地址退避重试一次，
 * 尊重 Retry-After（封顶 15 秒）。页面 HTML 的重试在 SiteHtml 内部，这里只管
 * `text()/json()`（GET 语义）；POST 登录这类有副作用的请求不进这条路径。
 */
internal suspend fun <T> withRateLimitRetry(block: suspend () -> T): T =
    try { block() }
    catch (error: GalleryResponseException) {
        if (error.status != 429) throw error
        val wait = SiteHtml.retryWaitMs(error.retryAfter)
        RateLimitLog.record("api", "HTTP 429，退避 ${wait}ms 后重试")
        kotlinx.coroutines.delay(wait)
        try { block() } catch (retry: GalleryResponseException) {
            if (retry.status == 429) RateLimitLog.record("api", "HTTP 429，重试后仍被拒")
            throw retry
        }
    }

/** 播放、下载、测速走 <video> 的取法：覆盖基线拦截器给的 document 形态，Range 等原样保留。 */
private fun applyMediaShape(builder: okhttp3.Request.Builder, host: String) {
    val sameOrigin = SiteRegistry.forRequestHost(host) != null
    builder.header("Accept", "*/*")
        .header("Sec-Fetch-Dest", "video")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", if (sameOrigin) "same-origin" else "cross-site")
        .removeHeader("Upgrade-Insecure-Requests")
        .removeHeader("Sec-Fetch-User")
}

class Network(private val context: Context, private val store: LibraryStore) {
    val session = SessionCookies(store)
    @Volatile private var current: OkHttpClient? = null
    private val diskCache by lazy { Cache(File(context.cacheDir, "http"), 32L * 1024 * 1024) }
    val feedCacheDirectory get() = File(context.cacheDir, "feeds")
    @Volatile var lastPeer: String = "尚未连接"
        private set
    private val systemRoutes = SystemProxySelector()
    private val hostGates = java.util.concurrent.ConcurrentHashMap<String, HostGate>()
    private val authTokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val mediaRedirects = MediaRedirectCache()
    val client: OkHttpClient get() = current ?: synchronized(this) { current ?: build().also { current = it } }

    /**
     * 媒体专用客户端：不缓存、不超时，带 CDN 重解析与 <video> 形态。播放器、下载管理器、
     * 测速都从这里派生（newBuilder 会继承这两个拦截器）。
     */
    val mediaClient: OkHttpClient get() = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).cache(null)
        .addInterceptor(mediaRedirects.interceptor)
        .addNetworkInterceptor(mediaRedirects.networkInterceptor)
        .addInterceptor { chain ->
            val shaped = chain.request().newBuilder()
            applyMediaShape(shaped, chain.request().url.host)
            chain.proceed(shaped.build())
        }.build()

    /**
     * 图页专用客户端：沿用页面客户端的 Cookie、站点形态头与节流，但去掉整次调用时限。
     *
     * 单页最大 24 MiB，页面客户端的 25 秒 callTimeout 会把“慢但一直在传”的图页在传输中途掐断，
     * 整本下载于是从头重试同一页，慢链路上既费流量又永远下不完。这里保留读超时（卡住仍会失败），
     * 只去掉总时限。
     */
    val imageClient: OkHttpClient get() = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).cache(null)
        .build()
    @Volatile private var routeGeneration = 0L
    @Synchronized fun invalidate() {
        current?.connectionPool?.evictAll()
        current = null
        routeGeneration++
    }
    fun routeDescription(url: String = SiteRegistry.current.base) = systemRoutes.describe(url.toHttpUrl().toUri())

    internal fun systemRouteKey(urls: List<String>): String {
        val network = context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork
        return listOf(network?.networkHandle, routeGeneration, urls.map(::routeDescription)).toString()
    }
    private fun build(): OkHttpClient {
        return OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).cookieJar(session)
            .cache(diskCache).dns(Ipv4FirstDns).proxySelector(systemRoutes)
            .eventListenerFactory { object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    val route = connection.route(); val peer = route.socketAddress.address?.hostAddress.orEmpty()
                    lastPeer = "$peer · ${if (route.proxy.type() == Proxy.Type.DIRECT) "系统 DNS 直连" else "系统代理"}"
                }
            } }
            .addInterceptor { chain ->
                val host = chain.request().url.host
                // 只有站点页面与 API 受节流；图片走快车道。等待被中断即视为调用被取消。
                if (SiteRegistry.forRequestHost(host) != null) {
                    val gap = if (BROWSER_IMAGE_PATH.containsMatchIn(chain.request().url.encodedPath))
                        IMAGE_REQUEST_GAP_MS else PAGE_REQUEST_GAP_MS
                    val wait = hostGates.computeIfAbsent(host) { HostGate() }
                        .reserve(System.currentTimeMillis(), gap)
                    if (wait > 0) Thread.sleep(wait)
                }
                val site = chain.request().tag(SiteContext::class.java) ?: SiteRegistry.forMediaHost(host)
                // A blank User-Agent is rejected outright by Cloudflare, so every request carries one.
                val agent = site?.userAgent?.takeIf { it.isNotBlank() } ?: SiteContext.DEFAULT_UA
                val referer = chain.request().header("Referer") ?: site?.referer ?: "${chain.request().url.scheme}://$host/"
                val request = chain.request().newBuilder().header("User-Agent", agent).header("Referer", referer)
                if (site?.id == SiteRegistry.IWARA.id) {
                    request.header("Origin", site.base).header("x-site", "www.iwara.tv")
                    // Bearer 只发给 API 主机；媒体 CDN 不带账号凭据。
                    if (host in site.apiHosts) authToken(site.id)?.let { request.header("Authorization", "Bearer $it") }
                }
                applyBrowserShape(request, chain.request(), site)
                chain.proceed(request.build())
            }.build()
    }
    suspend fun diagnose(site: SiteContext = SiteRegistry.current): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val limits = RateLimitLog.report()
        try {
            val result = AdapterRegistry.adapter(site, this@Network).feed(AdapterRegistry.adapter(site, this@Network).homeRequest())
            "实时请求成功 · ${System.currentTimeMillis() - start} ms\n${site.name} · ${result.videos.size} 个条目\n${routeDescription(site.base)}\n$lastPeer" +
                if (limits.isEmpty()) "" else "\n$limits"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            "实时请求失败：${error.message}\n${site.name}\n${runCatching { routeDescription(site.base) }.getOrDefault("系统代理设置不可用")}\n$lastPeer" +
                if (limits.isEmpty()) "" else "\n$limits"
        }
    }
    private suspend fun getText(request: Request): String {
        return client.readResponse(request) { response ->
            if (!response.isSuccessful) throw GalleryResponseException(response.code, response.header("Retry-After"), "请求失败：HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
    suspend fun html(url: String, site: SiteContext? = null): String = withContext(Dispatchers.IO) {
        val body = SiteHtml.load(url, site) { target ->
            getText(Request.Builder().url(target).tag(SiteContext::class.java, site).apply {
                if (site?.mediaKind == MediaKind.GALLERY) header("Cache-Control", "no-store")
            }.build())
        }
        if (body.contains("id=\"login_username\"")) error("此页面需要登录，请在设置中登录")
        body
    }

    suspend fun body(url: String): String = html(url)
    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        withRateLimitRetry { getText(request) }
    }
    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): com.google.gson.JsonElement {
        val body = text(url, headers)
        return try {
            com.google.gson.JsonParser.parseString(body).also {
                check(it.isJsonObject || it.isJsonArray) { "站点返回了非 JSON 数据，请稍后重试或调整网络" }
            }
        }
        catch (_: com.google.gson.JsonParseException) { error("站点返回了非 JSON 数据，请稍后重试或调整网络") }
    }
    /** JWT 类站点会话（Iwara）：明文只在内存缓存，落盘走 SessionVault 密文。 */
    fun saveAuthToken(siteId: String, token: String) {
        store.saveAuthToken(siteId, SessionVault.seal(token)); authTokenCache[siteId] = token
    }
    fun clearAuthToken(siteId: String) { store.clearAuthToken(siteId); authTokenCache.remove(siteId) }
    fun authToken(siteId: String): String? = authTokenCache.getOrPut(siteId) {
        store.authToken(siteId)?.let { sealed -> runCatching { SessionVault.open(sealed) }.getOrNull() }.orEmpty()
    }.takeIf { it.isNotEmpty() }

    /** 带 JSON 请求体的 POST；非 2xx 抛 GalleryResponseException，保留状态码与 Retry-After。 */
    suspend fun postJson(url: String, body: okhttp3.RequestBody): String = withContext(Dispatchers.IO) {
        withRateLimitRetry {
            client.readResponse(Request.Builder().url(url).post(body).build()) { response ->
                if (!response.isSuccessful) throw GalleryResponseException(response.code, response.header("Retry-After"), "请求失败：HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }
    suspend fun post(url: String, form: okhttp3.RequestBody): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
            check(response.isSuccessful) { "请求失败：HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
    }
    suspend fun statusOrNull(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.code } }.getOrNull()
    }
    /**
     * 密码登录。
     *
     * [site] 必须由调用方（登录对话框持有的站点）显式传入：进程级 `SiteRegistry.current`
     * 可能已被另一个 Activity 实例切走，用它决定收件方会把账号密码提交给错误的站点。
     */
    suspend fun login(site: SiteContext, username: String, password: String) = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty()) { "请填写用户名和密码" }
        AdapterRegistry.adapter(site, this@Network).login(this@Network, username, password)
        // Cookie 类登录（KVS / Hanime 等）不产生 JWT，只有写下用户名后设置页才认得出已登录并允许退出。
        store.setUsername(username.trim(), site.id)
    }
}

/** Maps a site onto its adapter. Adapters are cheap and stateless apart from the network. */
object AdapterRegistry {
    private val cache = mutableMapOf<String, com.r34v.r34v.data.adapter.SiteAdapter>()

    @Synchronized
    fun adapter(site: SiteContext, network: Network? = null): com.r34v.r34v.data.adapter.SiteAdapter {
        cache[site.id]?.let { return it }
        val net = network ?: error("adapter not initialised for ${site.id}")
        val created = when (site.id) {
            SiteRegistry.HANIME1.id -> com.r34v.r34v.data.adapter.HanimeAdapter(site, net)
            SiteRegistry.IWARA.id -> com.r34v.r34v.data.adapter.IwaraAdapter(site, net)
            SiteRegistry.EHENTAI.id, SiteRegistry.EXHENTAI.id -> com.r34v.r34v.data.adapter.EhAdapter(site, net)
            SiteRegistry.NHENTAI.id -> com.r34v.r34v.data.adapter.NhentaiAdapter(net)
            SiteRegistry.HITOMI.id -> com.r34v.r34v.data.adapter.HitomiAdapter(net)
            SiteRegistry.MISSAV.id -> com.r34v.r34v.data.adapter.MissavAdapter(site, net)
            SiteRegistry.JABLE.id -> com.r34v.r34v.data.adapter.JableAdapter(site, net)
            SiteRegistry.ROUVIDEO.id -> com.r34v.r34v.data.adapter.RouVideoAdapter(site, net)
            SiteRegistry.KBJ.id -> com.r34v.r34v.data.adapter.KbjAdapter(site, net)
            SiteRegistry.KBJFREE.id -> com.r34v.r34v.data.adapter.KbjFreeAdapter(site, net)
            SiteRegistry.PORNHUB.id -> com.r34v.r34v.data.adapter.PornhubAdapter(site, net)
            SiteRegistry.NETFLAV.id -> com.r34v.r34v.data.adapter.NetflavAdapter(site, net)
            SiteRegistry.WNACG.id -> com.r34v.r34v.data.adapter.WnacgAdapter(site, net)
            SiteRegistry.JMCOMIC.id -> com.r34v.r34v.data.adapter.JmcomicAdapter(site, net)
            SiteRegistry.FOURKHD.id -> com.r34v.r34v.data.adapter.FourKhdAdapter(site, net)
            SiteRegistry.EVERIA.id -> com.r34v.r34v.data.adapter.PhotoArchiveAdapter(
                site, net, com.r34v.r34v.data.adapter.PhotoProfiles.everia())
            SiteRegistry.FOURKUP.id -> com.r34v.r34v.data.adapter.PhotoArchiveAdapter(
                site, net, com.r34v.r34v.data.adapter.PhotoProfiles.fourKup())
            SiteRegistry.COSPLAYTELE.id -> com.r34v.r34v.data.adapter.PhotoArchiveAdapter(
                site, net, com.r34v.r34v.data.adapter.PhotoProfiles.cosplayTele())
            SiteRegistry.GALLERYEPIC.id -> com.r34v.r34v.data.adapter.PhotoArchiveAdapter(
                site, net, com.r34v.r34v.data.adapter.GalleryEpicProfile())
            else -> com.r34v.r34v.data.adapter.KvsAdapter(site, net)
        }
        cache[site.id] = created
        return created
    }

    @Synchronized
    fun warm(network: Network) { SiteRegistry.all.forEach { adapter(it, network) } }
}

class Repository(private val network: Network, private val library: LibraryStore) {
    private fun adapter() = AdapterRegistry.adapter(SiteRegistry.current, network)

    suspend fun feed(request: FeedRequest, site: SiteContext = SiteRegistry.current): FeedPage {
        val adapter = AdapterRegistry.adapter(site, network)
        return withContext(Dispatchers.IO) {
            val site = adapter.site
            val url = adapter.resolve(request)
            val folder = network.feedCacheDirectory.apply { mkdirs() }
            // The cache key carries the site so two sites can never overwrite each other's snapshots.
            val name = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("${site.id}|$url" + if (request.language == GalleryLanguage.ALL) "" else "|${request.language.name}|${request.cursor}").toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(folder, name)
            try {
                val page = adapter.feed(request)
                if (request.kind != FeedKind.FOR_YOU) runCatching {
                    val temp = File(folder, "$name.part")
                    // 关注动态/个人列表带账号身份：快照里只有页码与条数，切号后会回放上一账号的内容。
                    // 这类页面不写快照，避免不同账号之间串数据。
                    if (request.kind == FeedKind.SUBSCRIPTIONS) return@runCatching
                    temp.writeText(com.google.gson.Gson().toJson(page)); temp.renameTo(file)
                    folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
                }
                page
            } catch (error: IOException) {
                staleFallback(request, file) ?: throw error
            } catch (error: GalleryResponseException) {
                // 退避与镜像都失败后仍然 429：回退旧快照，别让限流把整页打死。
                staleFallback(request, file) ?: throw error
            }
        }
    }

    /** FOR_YOU 是每次现算的合成页，没有可回退的快照；其余失败都允许展示旧缓存。 */
    private fun staleFallback(request: FeedRequest, file: File): FeedPage? =
        if (request.kind == FeedKind.FOR_YOU) null else runCatching {
            com.google.gson.Gson().fromJson(file.readText(), FeedPage::class.java)
        }.getOrNull()?.copy(cached = true)

    suspend fun detail(url: String): Detail {
        val site = SiteRegistry.forHost(url.toHttpUrlOrNull()?.host.orEmpty()) ?: SiteRegistry.current
        val adapter = AdapterRegistry.adapter(site, network)
        return withContext(Dispatchers.IO) { adapter.detail(url) }
    }
    suspend fun index(segment: String) = withContext(Dispatchers.IO) { adapter().index(segment) }
    suspend fun works() = withContext(Dispatchers.IO) { adapter().index("works") }
    suspend fun ranking(url: String) = withContext(Dispatchers.IO) { adapter().index("artists") }

    /**
     * Fetches every followed tag for [site] at once.
     *
     * One tag failing must not blank the screen: each failure is recorded on its own [TagFeed] and
     * the rest still render. Results are merged by [SubscriptionMerge], so a work carrying several
     * followed tags appears once, in the position the user's tag order gives it.
     */
    suspend fun subscriptions(site: SiteContext, order: SubscriptionOrder, tags: List<Subscription>): SubscriptionPage =
        withContext(Dispatchers.IO) {
            val permits = kotlinx.coroutines.sync.Semaphore(3)
            val feeds = coroutineScope {
                tags.map { entry ->
                    async {
                        runCatching {
                            permits.acquire()
                            try {
                            val page = feed(FeedRequest(FeedKind.TAG, entry.slug, Sort.NEWEST), site)
                            TagFeed(entry, page.videos.filter { library.allowed(it, site.id) }, null, page.cached)
                            } finally { permits.release() }
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            TagFeed(entry, emptyList(), error.message ?: "这个标签暂时取不到内容")
                        }
                    }
                }.awaitAll()
            }
            SubscriptionPage(tags = feeds, videos = SubscriptionMerge.sort(feeds, order), order = order,
                failed = feeds.filter { it.error != null })
        }

    /** Whether a tag has anything to show right now, used to keep the follow list honest. */
    suspend fun probeTag(site: SiteContext, slug: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { AdapterRegistry.adapter(site, network).feed(FeedRequest(FeedKind.TAG, slug, Sort.NEWEST)).videos.size }
    }
}

/** Cancellation remains connected to the call until the full body has been consumed. */
internal suspend fun <T> OkHttpClient.readResponse(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(consume)
                if (!continuation.isCancelled) continuation.resume(value)
            } catch (error: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(error) }
        }
    })
}
