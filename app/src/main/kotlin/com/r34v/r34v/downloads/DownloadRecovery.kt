package com.r34v.r34v.downloads

import com.r34v.r34v.data.GalleryDownloadStage
import com.r34v.r34v.data.GalleryFailureKind
import com.r34v.r34v.data.GalleryResponseException
import com.r34v.r34v.data.adapter.GalleryAccessException
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class GalleryStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class DownloadFailure(
    val kind: GalleryFailureKind,
    val message: String,
    val retryable: Boolean,
    val retryAt: Long = 0,
)

object DownloadRecovery {
    const val MAX_ATTEMPTS = 3
    const val MAX_AUTO_WAIT_MS = 30_000L

    fun classify(error: Throwable, stage: GalleryDownloadStage, now: Long = System.currentTimeMillis()): DownloadFailure {
        val raw = error.message.orEmpty()
        if (error is GalleryResponseException) {
            val wait = retryAfterMs(error.retryAfter, now)
            return when (error.status) {
                401, 403 -> DownloadFailure(GalleryFailureKind.AUTH, raw.ifBlank { "登录或访问权限不足，请重新登录" }, false)
                404, 410 -> DownloadFailure(GalleryFailureKind.NOT_FOUND, raw.ifBlank { "文件已被原站移除" }, false)
                429 -> DownloadFailure(GalleryFailureKind.RATE_LIMIT, raw.ifBlank { "原站请求过于频繁，请稍后重试" }, true, if (wait != null) now + wait else 0)
                509 -> DownloadFailure(GalleryFailureKind.QUOTA, raw.ifBlank { "原站图片配额已用尽" }, false)
                408, 425, 500, 502, 503, 504 -> DownloadFailure(GalleryFailureKind.NETWORK, raw.ifBlank { "站点暂时不可用，请稍后重试" }, true, if (wait != null) now + wait else 0)
                else -> DownloadFailure(GalleryFailureKind.SOURCE, raw.ifBlank { "原站返回 HTTP ${error.status}" }, false)
            }
        }
        if (error is GalleryLoginRequiredException) return DownloadFailure(GalleryFailureKind.AUTH, raw, false)
        if (error is GalleryAccessException) {
            val kind = when {
                raw.contains("配额", true) || raw.contains("509", true) -> GalleryFailureKind.QUOTA
                raw.contains("频率", true) || raw.contains("稍后重试", true) -> GalleryFailureKind.RATE_LIMIT
                raw.contains("移除", true) || raw.contains("不存在", true) -> GalleryFailureKind.NOT_FOUND
                raw.contains("登录", true) || raw.contains("权限", true) -> GalleryFailureKind.AUTH
                else -> GalleryFailureKind.SOURCE
            }
            return DownloadFailure(kind, raw, kind == GalleryFailureKind.RATE_LIMIT)
        }
        if (error is GalleryStorageException || stage == GalleryDownloadStage.COMMITTING || raw.contains("存储", true) || raw.contains("保存", true) || raw.contains("目录", true))
            return DownloadFailure(GalleryFailureKind.STORAGE, raw.ifBlank { "无法写入下载目录，请检查存储空间和权限" }, false)
        if (error is SSLHandshakeException || error is java.security.GeneralSecurityException ||
            generateSequence(error) { it.cause }.any { it is java.security.GeneralSecurityException || it is javax.net.ssl.SSLPeerUnverifiedException }) {
            if (isTlsConnectionInterruption(error)) return DownloadFailure(GalleryFailureKind.NETWORK, raw.ifBlank { "TLS 连接中断，请检查网络后重试" }, true)
            return DownloadFailure(GalleryFailureKind.TLS, "安全连接校验失败，请检查系统时间、证书和网络；未关闭证书验证", false)
        }
        if (error is SSLException || error is IOException)
            return DownloadFailure(GalleryFailureKind.NETWORK, raw.ifBlank { "连接中断，请检查网络后重试" }, true)
        return DownloadFailure(GalleryFailureKind.UNKNOWN, raw.ifBlank { "下载失败，请重试" }, false)
    }

    fun isTlsConnectionInterruption(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(12).toList()
        if (chain.none { it is SSLException } || chain.any { it is java.security.GeneralSecurityException || it is javax.net.ssl.SSLPeerUnverifiedException }) return false
        if (chain.any { it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.io.EOFException }) return true
        val messages = chain.filterIsInstance<SSLHandshakeException>().mapNotNull { it.message?.lowercase(Locale.US) }
        return messages.any { message ->
            listOf("readhandshakerecord", "connection reset", "software caused connection abort", "connection timed out", "socket closed", "broken pipe").any(message::contains)
        }
    }

    fun backoffMs(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
        1 -> 1_000L
        2 -> 2_000L
        else -> 4_000L
    }

    fun automaticDelayMs(failure: DownloadFailure, now: Long = System.currentTimeMillis()): Long? {
        if (!failure.retryable) return null
        val serverWait = (failure.retryAt - now).coerceAtLeast(0L)
        return if (failure.retryAt > 0 && serverWait > MAX_AUTO_WAIT_MS) null else serverWait
    }

    fun retryAfterMs(raw: String?, now: Long): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return (it * 1000L).coerceIn(0L, 7L * 24 * 60 * 60 * 1000) }
        val formats = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }.parse(value)
            }.getOrNull() ?: continue
            return (parsed.time - now).coerceIn(0L, 7L * 24 * 60 * 60 * 1000)
        }
        return null
    }
}
