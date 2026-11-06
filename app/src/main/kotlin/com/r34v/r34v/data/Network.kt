package com.r34v.r34v.data

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
            Cookie.parse(site.base.toHttpUrl(), raw)?.let(restored::add)
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
        val values = cookies.map { it.toString() }.toSet()
        if (site.mediaKind == MediaKind.GALLERY) store.saveGalleryCookies(site, values)
        else store.prefs.edit().putStringSet("${site.id}:cookies", values).apply()
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
        store.prefs.edit().remove("${site.id}:cookies").remove("${site.id}:encrypted_cookies").apply()
        store.logout(site.id)
    }
}

class Network(private val context: Context, private val store: LibraryStore) {
    val session = SessionCookies(store)
    @Volatile private var current: OkHttpClient? = null
    private val diskCache by lazy { Cache(File(context.cacheDir, "http"), 32L * 1024 * 1024) }
    val feedCacheDirectory get() = File(context.cacheDir, "feeds")
    @Volatile var lastPeer: String = "尚未连接"
        private set
    private val systemRoutes = SystemProxySelector()
    val client: OkHttpClient get() = current ?: synchronized(this) { current ?: build().also { current = it } }
    val mediaClient: OkHttpClient get() = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).cache(null).build()
    @Synchronized fun invalidate() { current?.connectionPool?.evictAll(); current = null }
    fun routeDescription(url: String = SiteRegistry.current.base) = systemRoutes.describe(url.toHttpUrl().toUri())
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
                val site = chain.request().tag(SiteContext::class.java) ?: SiteRegistry.forMediaHost(host)
                // A blank User-Agent is rejected outright by Cloudflare, so every request carries one.
                val agent = site?.userAgent?.takeIf { it.isNotBlank() } ?: SiteContext.DEFAULT_UA
                val referer = chain.request().header("Referer") ?: site?.referer ?: "${chain.request().url.scheme}://$host/"
                val request = chain.request().newBuilder().header("User-Agent", agent).header("Referer", referer)
                if (site?.id == SiteRegistry.IWARA.id) request.header("Origin", site.base).header("x-site", "www.iwara.tv")
                chain.proceed(request.build())
            }.build()
    }
    suspend fun diagnose(site: SiteContext = SiteRegistry.current): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val result = AdapterRegistry.adapter(site, this@Network).feed(AdapterRegistry.adapter(site, this@Network).homeRequest())
            "实时请求成功 · ${System.currentTimeMillis() - start} ms\n${site.name} · ${result.videos.size} 个条目\n${routeDescription(site.base)}\n$lastPeer"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            "实时请求失败：${error.message}\n${site.name}\n${runCatching { routeDescription(site.base) }.getOrDefault("系统代理设置不可用")}\n$lastPeer"
        }
    }
    private suspend fun getText(request: Request): String {
        return client.readResponse(request) { response ->
            if (!response.isSuccessful) throw GalleryResponseException(response.code, response.header("Retry-After"), "请求失败：HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
    suspend fun html(url: String, site: SiteContext? = null): String = withContext(Dispatchers.IO) {
        val body = getText(Request.Builder().url(url).tag(SiteContext::class.java, site).apply {
            if (site?.mediaKind == MediaKind.GALLERY) header("Cache-Control", "no-store")
        }.build())
        if (body.contains("Checking your browser", true) || body.contains("DDoS-Guard", true) && !body.contains("class=\"thumbs")) error("站点要求浏览器验证，请稍后重试或调整网络")
        if (body.contains("id=\"login_username\"")) error("此页面需要登录，请在设置中登录")
        body
    }
    suspend fun body(url: String): String = html(url)
    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        getText(request)
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
    suspend fun post(url: String, form: okhttp3.RequestBody): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
            check(response.isSuccessful) { "请求失败：HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
    }
    suspend fun statusOrNull(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.code } }.getOrNull()
    }
    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty()) { "请填写用户名和密码" }
        val site = SiteRegistry.current
        AdapterRegistry.adapter(site).login(this@Network, username, password)
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
                    val temp = File(folder, "$name.part"); temp.writeText(com.google.gson.Gson().toJson(page)); temp.renameTo(file)
                    folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
                }
                page
            } catch (error: IOException) {
                val stale = if (request.kind != FeedKind.FOR_YOU) runCatching {
                    com.google.gson.Gson().fromJson(file.readText(), FeedPage::class.java)
                }.getOrNull() else null
                stale?.copy(cached = true) ?: throw error
            }
        }
    }
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
