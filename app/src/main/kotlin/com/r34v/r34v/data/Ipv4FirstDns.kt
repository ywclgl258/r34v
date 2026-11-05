package com.r34v.r34v.data

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 优先使用 IPv4 地址。
 *
 * 有些站点同时有 A 和 AAAA 记录，而部分网络（移动热点、隧道、模拟器 NAT）只有 IPv4 出口。
 * OkHttp 的连接超时覆盖整个建连过程，先试一个不可达的 IPv6 地址就会耗光预算，于是站点在
 * “IPv4 明明可达”的网络下打不开，报错还会写成连接超时，很容易被误判成站点挂了。
 *
 * 这里只调整顺序：仍然使用系统 DNS 的结果，不写死任何地址，不改超时，也不影响 TLS 校验；
 * 纯 IPv6 网络下 IPv4 地址并不存在，仍然回退到原有顺序。
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = order(Dns.SYSTEM.lookup(hostname))

    /** 供测试使用的排序本身；稳定排序，同类地址保持系统返回的原顺序。 */
    internal fun order(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size < 2) return addresses
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}
