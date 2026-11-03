package com.r34v.r34v

import com.r34v.r34v.data.RoutedDns
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class RoutedDnsTest {
    private fun answer(vararg ips: String) = object : Dns { override fun lookup(hostname: String) = ips.map { InetAddress.getByName(it) } }
    private val broken = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("unavailable") }
    @Test fun manualIpSurvivesUnavailableDohAndSystemDns() {
        val dns = RoutedDns({ RoutedDns.Policy(customIps = "190.115.31.243") }, listOf("failed" to broken), broken)
        assertEquals("190.115.31.243", dns.lookup("rule34video.com").first().hostAddress)
    }
    @Test fun historicalPoolAndFallbackSurviveBothProvidersFailing() {
        val dns = RoutedDns({ RoutedDns.Policy() }, listOf("first" to broken, "second" to broken), answer("104.244.46.57"), { listOf("190.115.31.242") })
        assertEquals(listOf("190.115.31.242", "190.115.31.243", "104.244.46.57"), dns.lookup("rule34video.com").map { it.hostAddress })
    }
    @Test fun secondProviderCanRescueFirst() {
        val dns = RoutedDns({ RoutedDns.Policy() }, listOf("first" to broken, "second" to answer("190.115.31.241")), broken)
        assertEquals("190.115.31.241", dns.lookup("rule34video.com").first().hostAddress)
    }
    @Test fun vpnAndProxyBypassAllDirectCandidates() {
        val calls = AtomicInteger()
        val dns = RoutedDns({ RoutedDns.Policy(bypass = true, customIps = "190.115.31.243") }, listOf("unused" to object : Dns { override fun lookup(hostname: String): List<InetAddress> { calls.incrementAndGet(); error("must not resolve") } }), answer("10.0.2.3"))
        assertEquals("10.0.2.3", dns.lookup("rule34video.com").single().hostAddress); assertEquals(0, calls.get())
    }
    @Test fun turningDirectOffUsesSystemDnsForTheSite() {
        val dns = RoutedDns({ RoutedDns.Policy(direct = false) }, listOf("unused" to broken), answer("10.0.2.3"))
        assertEquals("10.0.2.3", dns.lookup("rule34video.com").single().hostAddress)
    }
    @Test fun ipv4ValidationNeverPerformsNameLookup() {
        for (value in listOf("999.1.2.3", "host.example", "1.2.3", "1.2.3.4.5", "")) assertNull(value, RoutedDns.ipv4(value))
        for (value in listOf("127.0.0.1", "10.0.0.1", "0.0.0.0", "192.168.1.1", "100.64.0.1")) assertFalse(value, RoutedDns.publicIpv4(value))
    }
    @Test fun tlsValidatedPeerIsRememberedAndFailedPeerDemoted() {
        var saved = emptyList<String>()
        val dns = RoutedDns({ RoutedDns.Policy(doh = false) }, emptyList(), broken, saveSitePool = { saved = it })
        dns.succeeded("rule34video.com", "190.115.31.242")
        assertEquals(listOf("190.115.31.242"), saved)
        dns.failed("rule34video.com", "190.115.31.242")
        assertEquals("190.115.31.243", dns.lookup("rule34video.com").first().hostAddress)
        dns.succeeded("cdn.example", "190.115.31.241"); assertEquals(listOf("190.115.31.242"), saved)
    }
    @Test fun providerCacheAvoidsRepeatedLookups() {
        val calls = AtomicInteger()
        val provider = object : Dns { override fun lookup(hostname: String): List<InetAddress> { calls.incrementAndGet(); return answer("190.115.31.241").lookup(hostname) } }
        val dns = RoutedDns({ RoutedDns.Policy() }, listOf("test" to provider), broken)
        dns.lookup("rule34video.com"); dns.lookup("rule34video.com")
        assertEquals(1, calls.get()); dns.clear(); dns.lookup("rule34video.com"); assertEquals(2, calls.get())
    }
}
