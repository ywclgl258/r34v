package com.r34v.r34v.data.adapter

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Official forum authentication; no application password form or password storage. */
object EhLogin {
    const val officialUrl = "https://forums.e-hentai.org/index.php?act=Login"
    val cookieOrigins = listOf("https://forums.e-hentai.org/", "https://e-hentai.org/", "https://exhentai.org/")
    fun allowed(raw: String) = raw.toHttpUrlOrNull()?.let { it.isHttps && it.host in setOf("forums.e-hentai.org", "e-hentai.org", "exhentai.org", "www.e-hentai.org", "www.exhentai.org") } == true
    fun credentials(headers: List<String?>): Map<String, String> = headers.flatMap { raw -> raw.orEmpty().split(';') }.mapNotNull { field ->
        val pair = field.trim().split('=', limit = 2)
        if (pair.size == 2 && pair[0] in setOf("ipb_member_id", "ipb_pass_hash", "igneous") && pair[1].isNotBlank()) pair[0] to pair[1] else null
    }.toMap()
    fun hasIdentity(cookies: Map<String, String>) = cookies["ipb_member_id"]?.toLongOrNull()?.let { it > 0 } == true &&
        cookies["ipb_pass_hash"]?.matches(Regex("[A-Za-z0-9_-]{16,128}")) == true
}
