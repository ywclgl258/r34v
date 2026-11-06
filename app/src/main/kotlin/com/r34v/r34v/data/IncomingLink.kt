package com.r34v.r34v.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class IncomingLink(val site: SiteContext, val url: String)
fun incomingLink(text: String): IncomingLink {
    require(text.length <= 16384) { "分享内容过长，请只粘贴作品链接" }
    val links = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', '。', ')', '）') }
    for (raw in links) {
        val parsed = raw.toHttpUrlOrNull() ?: continue
        val site = SiteRegistry.forHost(parsed.host) ?: continue
        require(parsed.username.isBlank() && parsed.password.isBlank() && raw.length <= 4096) { "作品链接格式不受支持" }
        return IncomingLink(site, parsed.newBuilder().scheme("https").fragment(null).build().toString())
    }
    error("未找到支持站点的作品链接，请使用完整的网页地址")
}
