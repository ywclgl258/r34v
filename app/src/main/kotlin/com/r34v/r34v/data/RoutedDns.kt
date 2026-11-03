package com.r34v.r34v.data

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private data class Cached(val ips: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Any>()
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
        val manual = if (site) settings.customIps.split(Regex("[\\s,;]+")) .mapNotNull(::ipv4) else emptyList()
        val stored = if (site) goodSiteIps.mapNotNull(::ipv4) else emptyList()
        // This is a fallback, not a claim that this address works in every region.
        val fallback = if (site) listOfNotNull(ipv4("190.115.31.243")) else emptyList()
        val resolved = if (settings.doh) synchronized(locks.getOrPut(key) { Any() }) {
            val cached = cache[key]?.takeIf { it.until > now() }
            if (cached != null) cached.ips else {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lookupBudgetMs)
                val jobs = providers.map { (name, dns) -> name to executor.submit<List<InetAddress>> { dns.lookup(hostname) } }
                val answers = mutableListOf<InetAddress>()
                val successes = mutableListOf<String>()
                for ((name, job) in jobs) {
                    try {
                        val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
                        val ips = job.get(remaining, TimeUnit.NANOSECONDS).filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress }
                        if (ips.isNotEmpty()) { answers.addAll(ips); successes.add(name) }
                    } catch (_: Exception) { /* Another resolver or the preserved IP pool can still work. */ }
                    finally { job.cancel(true) }
                }
                lastResolution = "$hostname · " + if (answers.isNotEmpty()) "DoH ${successes.joinToString(" / ")}" else "DoH 不可达，使用已知 IP / 系统 DNS 回退"
                answers.distinct().also { cache[key] = Cached(it, now() + if (it.isEmpty()) 30000 else 300000) }
            }
        } else { lastResolution = "$hostname · DoH 已关闭"; emptyList() }
        // Manual and previously TLS-validated addresses must survive an unavailable DoH provider.
        val normal = runCatching { system.lookup(hostname) }.getOrDefault(emptyList())
        val candidates = (manual + stored + resolved + fallback + normal).distinct()
            .sortedBy { badUntil["$key:${it.hostAddress}"]?.let { until -> until > now() } == true }
        if (candidates.isEmpty()) throw UnknownHostException("$hostname：DoH 和系统 DNS 均未返回地址")
        return candidates
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
    fun clear() { cache.clear(); badUntil.clear() }
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
