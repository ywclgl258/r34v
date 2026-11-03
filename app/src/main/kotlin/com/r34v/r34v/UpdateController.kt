package com.r34v.r34v

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.r34v.r34v.ui.showError
import com.r34v.r34v.ui.toast
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File

class UpdateController(private val activity: ComponentActivity, private val app: R34Application) {
    suspend fun check(silent: Boolean) {
        try {
            val release = withContext(Dispatchers.IO) {
                app.network.client.newCall(Request.Builder().url("https://api.github.com/repos/ywclgl258/r34v/releases/latest").header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code == 403 || response.code == 429) error(if (body.contains("rate limit", true)) "GitHub 请求过于频繁，请稍后重试" else "GitHub 拒绝访问，请检查网络或仓库权限")
                    check(response.isSuccessful) { "更新检查失败：HTTP ${response.code}" }
                    JsonParser.parseString(body).asJsonObject
                }
            }
            app.library.prefs.edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
            val version = release["tag_name"].asString
            if (!newer(version, BuildConfig.VERSION_NAME)) { if (!silent) activity.toast("当前已是最新版本"); return }
            val asset = release.getAsJsonArray("assets").firstOrNull { it.asJsonObject["name"].asString.endsWith(".apk", true) }?.asJsonObject ?: error("此版本没有 APK 安装包")
            if (activity.isFinishing || activity.isDestroyed) return
            AlertDialog.Builder(activity).setTitle("新版本 $version").setMessage(release["body"]?.asString.orEmpty().take(1800)).setPositiveButton("下载") { _, _ ->
                activity.lifecycleScope.launch { download(asset["browser_download_url"].asString, asset["size"].asLong) }
            }.setNegativeButton("稍后", null).show()
        } catch (error: Exception) { if (error is CancellationException) throw error; if (!silent) activity.showError(error) }
    }
    private suspend fun download(url: String, expectedSize: Long) {
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val dialog = AlertDialog.Builder(activity).setTitle("正在下载更新").setView(progress).setCancelable(false).create(); dialog.show()
        val file = File(activity.cacheDir, "updates/r34v-update.apk")
        try {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".part")
                app.network.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                    val body = response.body ?: error("下载内容为空"); var received = 0L
                    body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive(); val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count); received += count
                            if (expectedSize > 0) withContext(Dispatchers.Main) { progress.isIndeterminate = false; progress.progress = (received * 100 / expectedSize).toInt() }
                        }
                    } }
                    check(expectedSize <= 0 || received == expectedSize) { "安装包不完整，请重新下载" }
                    check(temp.renameTo(file)) { "无法保存安装包" }
                }
            }
            dialog.dismiss()
            AlertDialog.Builder(activity).setTitle("更新已下载").setPositiveButton("安装") { _, _ -> install(file) }.setNegativeButton("稍后", null).show()
        } catch (error: Exception) { dialog.dismiss(); if (error is CancellationException) throw error; activity.showError(error) }
    }
    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            activity.toast("允许安装后，再点检查更新即可重新安装")
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    companion object {
        fun newer(remote: String, current: String): Boolean {
            fun numbers(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val r = numbers(remote); val c = numbers(current)
            for (i in 0 until maxOf(r.size, c.size)) { val comparison = (r.getOrNull(i) ?: 0).compareTo(c.getOrNull(i) ?: 0); if (comparison != 0) return comparison > 0 }
            return false
        }
    }
}
