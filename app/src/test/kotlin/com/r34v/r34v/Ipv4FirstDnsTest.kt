package com.r34v.r34v

import com.r34v.r34v.data.Ipv4FirstDns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/**
 * 连接超时是整段建连预算，不是每个地址各给一份。站点同时有 A/AAAA 时，若先试不通的 IPv6，
 * 5 秒预算会被吃光，IPv4 明明可达也打不开。这里锁住“IPv4 先试”这个顺序。
 */
class Ipv4FirstDnsTest {
    private fun address(host: String) = InetAddress.getByName(host)

    @Test fun ipv4AddressesAreTriedFirst() {
        val first = address("2001:db8::1")
        val second = address("192.0.2.10")
        val third = address("2001:db8::2")
        val fourth = address("192.0.2.11")
        // 系统原顺序是 IPv6 打头，排序后两组各按原相对顺序排好，IPv4 全部在前。
        assertEquals(
            listOf(second, fourth, first, third),
            Ipv4FirstDns.order(listOf(first, second, third, fourth)),
        )
        assertTrue(Ipv4FirstDns.order(listOf(first, second, third, fourth)).take(2).all { it is java.net.Inet4Address })
    }

    @Test fun singleAndIpv6OnlyAnswersAreLeftAlone() {
        val only = address("192.0.2.10")
        assertEquals(listOf(only), Ipv4FirstDns.order(listOf(only)))
        // 纯 IPv6 网络：没有 IPv4 可优先，顺序保持不变，仍然能用。
        val a = address("2001:db8::1")
        val b = address("2001:db8::2")
        assertEquals(listOf(a, b), Ipv4FirstDns.order(listOf(a, b)))
        assertTrue(Ipv4FirstDns.order(emptyList()).isEmpty())
    }
}
