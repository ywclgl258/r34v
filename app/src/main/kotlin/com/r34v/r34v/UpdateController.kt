package com.r34v.r34v

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.r34v.r34v.ui.showError
import com.r34v.r34v.ui.toast
import com.r34v.r34v.ui.ComposeDialogs
import kotlinx.coroutines.*
import okhttp3.Request
import com.r34v.r34v.data.UpgradePackages
import com.r34v.r34v.data.downloadUpgrade
import java.io.File

class UpdateController(private val activity: ComponentActivity, private val app: R34Application, private val dialogs: ComposeDialogs) {
    suspend fun check(silent: Boolean) {
        if (BuildConfig.DEBUG) { if (!silent) activity.toast("当前是开发版，请使用 R34V 正式版接收更新"); return }
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
            val assets = release.getAsJsonArray("assets").map { it.asJsonObject }
            val filename = UpgradePackages.select(assets.map { it["name"].asString }, UpgradePackages.needsCompatibility(activity)) ?: error("此版本缺少适配当前安装签名的 APK，请稍后重试")
            val asset = assets.first { it["name"].asString == filename }
            if (activity.isFinishing || activity.isDestroyed) return
            dialogs.confirm("新版本 $version", "下载", release["body"]?.asString.orEmpty().take(1800)) {
                activity.lifecycleScope.launch { download(asset["browser_download_url"].asString, asset["size"].asLong) }
            }
        } catch (error: Exception) { if (error is CancellationException) throw error; if (!silent) activity.showError(error) }
    }
    private suspend fun download(url: String, expectedSize: Long) {
        var progress by mutableStateOf<Float?>(null)
        dialogs.content = { AlertDialog({}, title = { Text("正在下载更新") }, text = {
            val amount = progress
            if (amount == null) LinearProgressIndicator() else LinearProgressIndicator(progress = { amount })
        }, confirmButton = {}) }
        val file = File(activity.cacheDir, "updates/r34v-update.apk")
        try {
            var lastUpdate = 0L
            downloadUpgrade(app.network.client, url, file, expectedSize) { received ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (expectedSize > 0 && (now - lastUpdate >= 100 || received == expectedSize)) {
                    lastUpdate = now
                    progress = (received.toFloat() / expectedSize).coerceIn(0f, 1f)
                }
            }
            withContext(Dispatchers.IO) { UpgradePackages.validate(activity, file) }
            dialogs.dismiss()
            dialogs.confirm("更新已下载", "安装") { install(file) }
        } catch (error: Exception) { dialogs.dismiss(); if (error is CancellationException) throw error; activity.showError(error) }
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
