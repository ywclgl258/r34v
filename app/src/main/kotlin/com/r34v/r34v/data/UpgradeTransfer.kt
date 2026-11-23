package com.r34v.r34v.data

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private val sha256Hex = Regex("[0-9a-f]{64}")

internal data class UpgradeAsset(val releaseId: Long, val id: Long, val size: Long, val sha256: String?) {
    init {
        check(releaseId > 0 && id > 0 && size > 0) { "更新资产信息无效" }
        check(sha256 == null || sha256Hex.matches(sha256)) { "更新资产摘要无效" }
    }

    fun cacheDirectory(updates: File) = File(updates, "apk/$releaseId-$id")

    companion object {
        fun fromGitHub(releaseId: Long, asset: JsonObject): UpgradeAsset {
            val digest = asset["digest"]?.takeUnless { it.isJsonNull }?.asString?.lowercase()
            check(digest == null || digest.startsWith("sha256:")) { "更新资产摘要无效" }
            return UpgradeAsset(releaseId, asset["id"].asLong, asset["size"].asLong, digest?.removePrefix("sha256:"))
        }
    }
}

internal suspend fun cachedUpgrade(updates: File, asset: UpgradeAsset, validate: (File) -> Unit): File? =
    withContext(Dispatchers.IO) {
        val directory = asset.cacheDirectory(updates)
        val candidates = asset.sha256?.let { listOf(File(directory, "$it.apk")) }
            ?: directory.listFiles().orEmpty().asList()
        val owner = currentCoroutineContext()
        candidates.firstOrNull { file ->
            val hash = file.nameWithoutExtension
            if (file.extension != "apk" || !sha256Hex.matches(hash)) return@firstOrNull false
            try {
                if (!file.matchesUpgrade(asset.size, hash, owner)) return@firstOrNull false
                validate(file)
                true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
        }
    }

/** A continuously progressing APK may take longer than a page request. */
internal suspend fun downloadUpgrade(
    client: OkHttpClient,
    releasesUrl: String,
    updates: File,
    asset: UpgradeAsset,
    validate: (File) -> Unit,
    progress: (Long) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    val owner = currentCoroutineContext()
    val transport = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).cache(null).build()
    // GitHub assigns a new asset ID on re-upload, including uploads with the same name.
    val request = Request.Builder().url("$releasesUrl/assets/${asset.id}")
        .header("Accept", "application/octet-stream").header("Cache-Control", "no-cache").build()
    transport.readResponse(request) { response ->
        check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
        val body = response.body ?: error("下载内容为空")
        val directory = asset.cacheDirectory(updates)
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "无法创建更新目录" }
        // PackageManager needs an APK path. The temporary name cannot match a published hash.
        val temporary = File.createTempFile("download-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        owner.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= asset.size) { "安装包大小与发布信息不符，请重新检查更新" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress(received)
                    }
                }
            }
            owner.ensureActive()
            check(received == asset.size) { "安装包不完整，请重新下载" }
            val hash = digest.digest().toByteString().hex()
            check(asset.sha256 == null || hash == asset.sha256) { "安装包摘要与发布信息不符，请重新检查更新" }
            validate(temporary)
            owner.ensureActive()
            // A single same-directory rename publishes the validated APK and its local hash together.
            val file = File(directory, "$hash.apk")
            check(temporary.renameTo(file) || file.matchesUpgrade(asset.size, hash, owner)) { "无法保存安装包" }
            file
        } finally {
            temporary.delete()
        }
    }
}

private fun File.matchesUpgrade(size: Long, hash: String, owner: CoroutineContext): Boolean {
    if (!isFile || length() != size) return false
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            owner.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toByteString().hex() == hash
}
