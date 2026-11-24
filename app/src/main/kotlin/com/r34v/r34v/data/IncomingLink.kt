package com.r34v.r34v.data

import com.r34v.r34v.data.adapter.IwaraProtocol
import com.r34v.r34v.data.adapter.JmcomicProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class IncomingLink(val site: SiteContext, val url: String)
fun incomingLink(text: String): IncomingLink {
    require(text.length <= 16384) { "分享内容过长，请只粘贴作品链接" }
    val links = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', '。', ')', '）') }
    for (raw in links) {
        val parsed = raw.toHttpUrlOrNull() ?: continue
        val site = SiteRegistry.forHost(parsed.host) ?: SiteRegistry.IWARA.takeIf {
            parsed.host in it.apiHosts && IwaraProtocol.linkRequest(it, parsed.toString()) != null
        } ?: continue
        require(parsed.username.isBlank() && parsed.password.isBlank() && raw.length <= 4096) { "作品链接格式不受支持" }
        val secure = parsed.newBuilder().scheme("https")
        val chapters = site.id == SiteRegistry.JMCOMIC.id && parsed.encodedFragment == "chapters" &&
            JmcomicProtocol.linkRequest(secure.build().toString())?.kind == FeedKind.CATEGORY
        if (!chapters) secure.fragment(null)
        return IncomingLink(site, secure.build().toString())
    }
    error("未找到支持站点的作品链接，请使用完整的网页地址")
}
