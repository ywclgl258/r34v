package com.r34v.r34v.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import java.net.ProxySelector

class SessionCookies(private val store: LibraryStore) : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    init {
        store.prefs.getStringSet("native_cookies", null)?.forEach { raw -> Cookie.parse(Site.BASE.toHttpUrl(), raw)?.let(cookies::add) }
        if (cookies.isEmpty()) for ((name, value) in store.legacyCookies()) {
            runCatching { Cookie.Builder().name(name).value(value).hostOnlyDomain(Site.HOST).secure().httpOnly().build() }.getOrNull()?.let(cookies::add)
        }
        // The legacy public-page jar carries long-lived anti-bot cookies separately from login.
        runCatching {
            val jar = com.google.gson.JsonParser.parseString(store.prefs.getString("site_cookies_v1", "{}")).asJsonObject
            for ((host, entries) in jar.entrySet()) if (Site.isSite(host)) for (entry in entries.asJsonArray) {
                val raw = entry.asJsonObject; val name = raw["n"]?.asString.orEmpty()
                if (!name.startsWith("__ddg") || cookies.any { it.name == name }) continue
                val expiry = raw["e"]?.asLong ?: Long.MAX_VALUE
                if (expiry <= System.currentTimeMillis()) continue
                val cookie = Cookie.Builder().name(name).value(raw["v"].asString).hostOnlyDomain(host)
                    .path(raw["p"]?.asString ?: "/").expiresAt(expiry).secure().build()
                cookies.add(cookie)
            }
        }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!Site.isSite(url.host)) return
        for (cookie in cookies) {
            if (!Site.isSite(cookie.domain)) continue
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        store.prefs.edit().putStringSet("native_cookies", this.cookies.map { it.toString() }.toSet()).apply()
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = if (Site.isSite(url.host)) cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } else emptyList()
    @Synchronized fun clear() { cookies.clear(); store.logout() }
}

class Network(private val context: Context, private val store: LibraryStore) {
    val session = SessionCookies(store)
    @Volatile private var current: OkHttpClient? = null
    private val diskCache by lazy { Cache(File(context.cacheDir, "http"), 32L * 1024 * 1024) }
    val feedCacheDirectory get() = File(context.cacheDir, "feeds")
    @Volatile var lastPeer: String = "尚未连接"
        private set
    private val resolver by lazy {
        val bootstrap = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
        fun provider(url: String, vararg ips: String): Dns = DnsOverHttps.Builder().client(bootstrap).url(url.toHttpUrl())
            .bootstrapDnsHosts(*ips.mapNotNull(RoutedDns::ipv4).toTypedArray()).includeIPv6(false).build()
        RoutedDns(
            policy = { RoutedDns.Policy(store.bool("direct_ip", true), store.bool("doh", true), vpn() || store.string("player_proxy").isNotBlank() || systemProxy(), store.string("custom_site_ips")) },
            providers = listOf(
                "Cloudflare" to provider("https://cloudflare-dns.com/dns-query", "104.16.248.249", "104.16.249.249", "1.1.1.1"),
                "Google" to provider("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")),
            loadSitePool = { runCatching { com.google.gson.JsonParser.parseString(store.prefs.getString("site_ip_pool_v1", "[]")).asJsonArray.map { it.asString } }.getOrDefault(emptyList()) },
            saveSitePool = { ips -> store.prefs.edit().putString("site_ip_pool_v1", store.gson.toJson(ips)).putLong("site_ip_pool_v1_at", System.currentTimeMillis()).apply() })
    }
    val client: OkHttpClient get() = current ?: synchronized(this) { current ?: build().also { current = it } }
    val mediaClient: OkHttpClient get() = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).cache(null)
        .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> =
            if (!Site.isSite(hostname) && !store.bool("cdn_direct", true)) Dns.SYSTEM.lookup(hostname) else resolver.lookup(hostname) }).build()
    fun invalidate() { current?.connectionPool?.evictAll(); current = null; resolver.clear() }
    private fun systemProxy() = runCatching { ProxySelector.getDefault()?.select(URI(Site.BASE))?.any { it.type() != Proxy.Type.DIRECT } == true }.getOrDefault(false)
    private fun vpn(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
    private fun build(): OkHttpClient {
        val proxyText = store.string("player_proxy").trim()
        return OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).cookieJar(session)
            .cache(diskCache)
            .dns(resolver)
            .eventListenerFactory { object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    val route = connection.route(); val host = route.address.url.host; val peer = route.socketAddress.address?.hostAddress.orEmpty()
                    lastPeer = "$peer · ${if (route.proxy.type() == Proxy.Type.DIRECT) "直连" else "代理"}"
                    if (route.proxy.type() == Proxy.Type.DIRECT && connection.handshake() != null) resolver.succeeded(host, peer)
                }
                override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
                    if (proxy.type() == Proxy.Type.DIRECT) resolver.failed(call.request().url.host, inetSocketAddress.address?.hostAddress.orEmpty())
                }
            } }
            .apply {
                if (proxyText.isNotEmpty()) {
                    val uri = URI(proxyText)
                    require(uri.scheme in listOf("http", "socks5") && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null) { "代理格式：http://主机:端口 或 socks5://主机:端口" }
                    proxy(Proxy(if (uri.scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port)))
                }
            }
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", Site.UA).header("Referer", Site.BASE + "/").build())
            }.build()
    }
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            client.await(Request.Builder().url("${Site.BASE}/latest-updates/").cacheControl(CacheControl.FORCE_NETWORK).build()).use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                HtmlParser.feed(response.body?.string().orEmpty(), FeedRequest())
                "实时请求成功 · HTTP ${response.code} · ${System.currentTimeMillis() - start} ms\n$lastPeer\n${resolver.lastResolution}"
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            "实时请求失败：${error.message}\n$lastPeer\n${resolver.lastResolution}"
        }
    }
    suspend fun html(url: String): String = withContext(Dispatchers.IO) {
        client.await(Request.Builder().url(url).build()).use { response ->
            if (!response.isSuccessful) error("请求失败：HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.contains("Checking your browser", true) || body.contains("DDoS-Guard", true) && !body.contains("class=\"thumbs")) error("站点要求浏览器验证，请稍后重试或调整网络")
            if (body.contains("id=\"login_username\"")) error("此页面需要登录，请在设置中登录")
            body
        }
    }
    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty()) { "请填写用户名和密码" }
        val url = "${Site.BASE}/login/"
        val loginPage = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "登录页 HTTP ${response.code}" }; response.body?.string().orEmpty()
        }
        val token = Jsoup.parse(loginPage).selectFirst("input[name=remember_me_csrf_token]")?.attr("value") ?: error("无法读取登录令牌，站点可能要求浏览器验证")
        val form = FormBody.Builder().add("username", username.trim()).add("pass", password).add("action", "login").add("remember_me", "1").add("remember_me_csrf_token", token).build()
        client.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
            check(response.isSuccessful) { "登录失败：HTTP ${response.code}" }
            val body = response.body?.string().orEmpty(); val doc = Jsoup.parse(body)
            val error = doc.selectFirst(".generic-error, .alert-error")?.text().orEmpty()
            check(error.isBlank()) { error }
            check(!body.contains("id=\"login_username\"") || body.contains("action=logout") || body.contains("/logout/")) { "登录未成功，请检查用户名、密码和邮箱验证" }
            check(session.loadForRequest(Site.BASE.toHttpUrl()).any { it.name == "PHPSESSID" }) { "服务器没有返回会话" }
            store.username = username.trim()
        }
    }
}

class Repository(private val network: Network) {
    suspend fun feed(request: FeedRequest): FeedPage = withContext(Dispatchers.IO) {
        val folder = network.feedCacheDirectory.apply { mkdirs() }
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(request.url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(folder, name)
        try {
            val page = HtmlParser.feed(network.html(request.url), request)
            if (request.kind != FeedKind.FOR_YOU) runCatching {
                val temp = File(folder, "$name.part"); temp.writeText(com.google.gson.Gson().toJson(page)); temp.renameTo(file)
                folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
            }
            page
        } catch (error: IOException) {
            val stale = if (request.kind != FeedKind.FOR_YOU) runCatching { com.google.gson.Gson().fromJson(file.readText(), FeedPage::class.java) }.getOrNull() else null
            stale?.copy(cached = true) ?: throw error
        }
    }
    suspend fun detail(url: String) = withContext(Dispatchers.IO) { HtmlParser.detail(network.html(Site.absolute(url)), Site.absolute(url)) }
    suspend fun index(segment: String) = HtmlParser.index(network.html("${Site.BASE}/$segment/"), segment)
    suspend fun works() = HtmlParser.works(network.html("${Site.BASE}/top-characters/"))
    suspend fun ranking(url: String) = HtmlParser.ranking(network.html(Site.absolute(url)))
}

private suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
