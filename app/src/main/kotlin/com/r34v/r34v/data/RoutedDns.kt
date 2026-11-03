package com.r34v.r34v.data

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** DNS routing only: URLs, TLS SNI and certificate verification keep the original hostname. */
class RoutedDns(
    private val policy: () -> Policy,
    private val providers: List<Pair<String, Dns>>,
    private val system: Dns = Dns.SYSTEM,
    private val loadSitePool: () -> List<String> = { emptyList() },
    private val saveSitePool: (List<String>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val lookupBudgetMs: Long = 4500,
) : Dns {
    data class Policy(val direct: Boolean = true, val doh: Boolean = true, val bypass: Boolean = false, val customIps: String = "")
    private data class Cached(val ips: List<InetAddress>, val until: Long, val source: String)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val refreshes = ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>>()
    private val generation = AtomicLong()
    private val badUntil = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "r34v-dns").apply { isDaemon = true } }
    @Volatile var lastResolution: String = "尚未解析"
        private set
    @Volatile private var goodSiteIps = loadSitePool().filter(::publicIpv4).take(4)

    override fun lookup(hostname: String): List<InetAddress> {
        val settings = policy()
        if (settings.bypass || Site.isSite(hostname) && !settings.direct) {
            lastResolution = "$hostname · 系统 DNS（VPN / 代理或直连关闭）"
            return system.lookup(hostname)
        }
        val site = Site.isSite(hostname)
        val key = if (site) Site.HOST else hostname
        val manual = if (site) settings.customIps.split(Regex("[\\s,;]+")).filter(::publicIpv4).mapNotNull(::ipv4) else emptyList()
        val stored = if (site) goodSiteIps.mapNotNull(::ipv4) else emptyList()
        val cached = cache[key]?.takeIf { it.until > now() }
        var resolved = if (settings.doh) cached?.ips.orEmpty() else emptyList()
        val ready = (manual + stored + resolved).any { !bad(key, it) }
        if (settings.doh && cached == null) {
            val first = refresh(hostname, key)
            // A warm request must never wait for DNS. Cold requests use the first successful
            // provider; slower providers finish in the background and expand the next route pool.
            if (!ready) resolved = runCatching { first.get(lookupBudgetMs, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
        }
        val fallback = if (site) listOfNotNull(ipv4("190.115.31.243")) else emptyList()
        var candidates = (manual + stored + resolved + fallback).distinct()
        // System DNS is a last resort, not a mandatory extra lookup after a usable DoH answer.
        if (candidates.isEmpty() || candidates.all { bad(key, it) }) {
            val job = executor.submit<List<InetAddress>> { system.lookup(hostname) }
            val normal = try { job.get(lookupBudgetMs.coerceAtMost(1500), TimeUnit.MILLISECONDS) } catch (_: Exception) { emptyList() } finally { job.cancel(true) }
            candidates = (candidates + normal).distinct()
        }
        candidates = candidates.sortedBy { bad(key, it) }
        lastResolution = "$hostname · " + when {
            manual.any { !bad(key, it) } -> "手填 IP（DNS 后台刷新）"
            stored.any { !bad(key, it) } -> "已验证 IP（DNS 后台刷新）"
            resolved.isNotEmpty() -> cached?.source ?: "DoH 首个可用结果"
            site -> "已知 IP 回退"
            else -> "系统 DNS 回退"
        }
        if (candidates.isEmpty()) throw UnknownHostException("$hostname：DoH 和系统 DNS 均未返回地址")
        return candidates
    }
    private fun bad(key: String, ip: InetAddress) = (badUntil["$key:${ip.hostAddress}"] ?: 0) > now()
    @Synchronized private fun refresh(hostname: String, key: String): CompletableFuture<List<InetAddress>> {
        val first = CompletableFuture<List<InetAddress>>()
        val stamp = generation.get()
        refreshes.putIfAbsent(key, first)?.let { return it }
        if (providers.isEmpty()) { cache[key] = Cached(emptyList(), now() + 30000, "DoH 不可用"); refreshes.remove(key, first); first.complete(emptyList()); return first }
        val left = AtomicInteger(providers.size)
        for ((name, dns) in providers) executor.execute {
            try {
                val ips = dns.lookup(hostname).filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
                if (ips.isNotEmpty()) {
                    cache.compute(key) { _, old -> if (stamp != generation.get()) old else Cached((old?.takeIf { it.until > now() }?.ips.orEmpty() + ips).distinct(), now() + 300000, "DoH $name") }
                    first.complete(ips)
                }
            } catch (_: Exception) { /* Other providers and the validated pool remain available. */ }
            finally {
                if (left.decrementAndGet() == 0) {
                    cache.compute(key) { _, old -> if (stamp != generation.get()) old else old?.takeIf { it.until > now() } ?: Cached(emptyList(), now() + 30000, "DoH 不可用") }
                    first.complete(emptyList()); refreshes.remove(key, first)
                }
            }
        }
        return first
    }
    @Synchronized fun succeeded(hostname: String, ip: String) {
        if (!Site.isSite(hostname) || !publicIpv4(ip)) return
        goodSiteIps = (listOf(ip) + goodSiteIps).distinct().take(4)
        badUntil.remove("${Site.HOST}:$ip")
        saveSitePool(goodSiteIps)
    }
    fun failed(hostname: String, ip: String) {
        val key = if (Site.isSite(hostname)) Site.HOST else hostname
        badUntil["$key:$ip"] = now() + 60000
    }
    @Synchronized fun clear() { generation.incrementAndGet(); cache.clear(); refreshes.clear(); badUntil.clear() }
    companion object {
        fun ipv4(raw: String): InetAddress? {
            val parts = raw.trim().split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.any { ch -> !ch.isDigit() } || (it.toIntOrNull() ?: -1) !in 0..255 }) return null
            return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
        }
        fun publicIpv4(raw: String): Boolean {
            val ip = ipv4(raw) ?: return false
            val bytes = ip.address.map { it.toInt() and 255 }
            return !ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isSiteLocalAddress && !ip.isLinkLocalAddress && !ip.isMulticastAddress &&
                bytes[0] < 224 && bytes[0] != 0 && !(bytes[0] == 100 && bytes[1] in 64..127)
        }
    }
}
