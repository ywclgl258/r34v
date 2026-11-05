package com.r34v.r34v.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A continuously progressing APK may take longer than a page request. */
internal suspend fun downloadUpgrade(client: OkHttpClient, url: String, file: File, expectedSize: Long,
                                     progress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
    val owner = currentCoroutineContext()
    val transport = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).cache(null).build()
    transport.readResponse(Request.Builder().url(url).build()) { response ->
        check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
        val body = response.body ?: error("下载内容为空")
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建更新目录" }
        val temporary = File(file.path + ".part")
        try {
            var received = 0L
            body.byteStream().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    owner.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count); received += count
                    progress(received)
                }
            } }
            owner.ensureActive()
            check(expectedSize <= 0 || received == expectedSize) { "安装包不完整，请重新下载" }
            check(temporary.renameTo(file)) { "无法保存安装包" }
        } finally { temporary.delete() }
    }
}
