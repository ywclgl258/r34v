package com.r34v.r34v.data

import java.io.IOException
import java.net.*

/** Use the OS route for each URI, including PAC exclusions and redirects. */
class SystemProxySelector(private val system: () -> ProxySelector? = { ProxySelector.getDefault() }) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val routes = try { system()?.select(uri).orEmpty() }
        catch (error: RuntimeException) { throw IOException("无法读取系统代理设置", error) }
        // A proxy failure must remain visible. Do not try a later DIRECT fallback.
        return listOf(routes.firstOrNull() ?: Proxy.NO_PROXY)
    }
    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
        runCatching { system()?.connectFailed(uri, address, error) }
    }
    fun describe(uri: URI): String {
        val proxy = select(uri).single()
        if (proxy.type() == Proxy.Type.DIRECT) return "未使用系统代理 · 系统 DNS 直连"
        val address = proxy.address() as? InetSocketAddress
        return "系统代理 · ${proxy.type()} · ${address?.hostString ?: ""}:${address?.port ?: ""}"
    }
}
