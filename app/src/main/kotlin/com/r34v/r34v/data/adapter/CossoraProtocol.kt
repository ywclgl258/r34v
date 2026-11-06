package com.r34v.r34v.data.adapter

import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.Stream
import com.r34v.r34v.data.Video
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CossoraProtocol {
    data class Embed(val id: String, val url: String)
    data class Media(val stream: Stream, val poster: String)

    private val embedPath = Regex("^/embed/([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})/?$")
    private val encryptedVideo = Regex("const\\s+videoURL\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val encryptedPoster = Regex("const\\s+portadaURL\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val key = Regex("decryptLink\\(\\s*videoURL\\s*,\\s*['\"]([a-fA-F0-9]{32})['\"]\\s*\\)")
    private val quality = Regex("defaultQuality\\s*:\\s*['\"](\\d{3,4}p)['\"]")

    fun embeds(html: String, base: String): List<Embed> = Jsoup.parse(html, base).select("iframe[src]").mapNotNull { frame ->
        val url = frame.absUrl("src").toHttpUrlOrNull() ?: return@mapNotNull null
        if (!url.isHttps || (url.host != "cossora.stream" && url.host != "www.cossora.stream")) return@mapNotNull null
        val id = embedPath.matchEntire(url.encodedPath)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
        Embed("video-$id", "https://cossora.stream/embed/$id")
    }.distinctBy(Embed::id)

    fun videos(html: String, articleUrl: String): List<Video> = embeds(html, articleUrl).mapIndexed { index, embed ->
        Video(embed.id, "文章内视频 ${index + 1}", "${articleUrl.substringBefore('#')}#${embed.id}",
            site = SiteRegistry.COSPLAYTELE.id, type = "文章内视频")
    }

    fun select(html: String, articleUrl: String, raw: String): Pair<Video, Embed> {
        val entries = videos(html, articleUrl)
        if (entries.isEmpty()) error("此写真集仅含图片，请使用阅读器")
        val video = entries.firstOrNull { it.url == raw } ?: entries.singleOrNull()
            ?: error("此文章包含多个视频，请从写真集详情选择要观看的视频")
        return video to Embed(video.id, "https://cossora.stream/embed/${video.id.removePrefix("video-")}")
    }

    fun media(html: String, embed: Embed): Media {
        val payload = encryptedVideo.find(html)?.groupValues?.get(1)?.takeIf { it.length <= 4096 }
            ?: error("视频嵌入页没有返回播放参数，请刷新重试")
        val passphrase = key.find(html)?.groupValues?.get(1) ?: error("视频嵌入页的播放参数不完整")
        val url = mediaUrl(decrypt(payload, passphrase))
        check(url.encodedPath.endsWith(".m3u8")) { "视频嵌入页未返回受支持的 HLS 地址" }
        val poster = encryptedPoster.find(html)?.groupValues?.get(1)?.takeIf { it.length <= 4096 }
            ?.let { mediaUrl(decrypt(it, passphrase)).toString() }.orEmpty()
        return Media(Stream(quality.find(html)?.groupValues?.get(1) ?: "HLS", url.toString()), poster)
    }

    fun decrypt(payload: String, passphrase: String): String {
        val bytes = payload.decodeBase64()?.toByteArray() ?: error("视频播放参数不是有效的 Base64")
        require(bytes.size >= 32 && bytes.size % 16 == 0) { "视频播放参数长度无效" }
        val secret = passphrase.toByteArray(Charsets.UTF_8)
        require(secret.size in setOf(16, 24, 32)) { "视频播放密钥长度无效" }
        val decrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(bytes.copyOfRange(0, 16)))
        }.doFinal(bytes.copyOfRange(16, bytes.size))
        return String(decrypted, Charsets.UTF_8)
    }

    private fun mediaUrl(raw: String) = raw.toHttpUrlOrNull()?.takeIf {
        it.isHttps && (it.host == "cossora.stream" || it.host.endsWith(".cossora.stream")) &&
            it.username.isEmpty() && it.password.isEmpty()
    } ?: error("视频嵌入页返回了不受信任的媒体地址")
}
