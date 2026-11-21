package com.r34v.r34v

import androidx.core.net.toUri

import androidx.core.content.edit

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
            app.library.prefs.edit { putLong("update_checked_at", System.currentTimeMillis()) }
            val version = release["tag_name"].asString
            if (!newer(version, BuildConfig.VERSION_NAME)) { if (!silent) activity.toast("当前已是最新版本"); return }
            val assets = release.getAsJsonArray("assets").map { it.asJsonObject }
            val filename = UpgradePackages.select(assets.map { it["name"].asString }, UpgradePackages.needsCompatibility(activity)) ?: error("此版本缺少适配当前安装签名的 APK，请稍后重试")
            val asset = assets.first { it["name"].asString == filename }
            if (activity.isFinishing || activity.isDestroyed) return
            // 上一次已经下好并校验过的同一个安装包直接复用：授予安装权限返回后按提示再点一次
            // 不该重新下载整个 APK（弱网下第一次成功、授权回来反而装不上）。
            val pending = pendingFile()
            if (pending.exists() && pending.length() == asset["size"].asLong && runCatching { withContext(Dispatchers.IO) { UpgradePackages.validate(activity, pending) } }.isSuccess) {
                dialogs.confirm("新版本 $version", "安装", "安装包已下载并通过校验，可直接安装。") { install(pending) }
                return
            }
            dialogs.confirm("新版本 $version", "下载", release["body"]?.asString.orEmpty().take(1800)) {
                activity.lifecycleScope.launch { download(asset["browser_download_url"].asString, asset["size"].asLong) }
            }
        } catch (error: Exception) { if (error is CancellationException) throw error; if (!silent) activity.showError(error) }
    }
    private fun pendingFile() = File(activity.cacheDir, "updates/r34v-update.apk")

    private suspend fun download(url: String, expectedSize: Long) {
        var progress by mutableStateOf<Float?>(null)
        dialogs.content = { AlertDialog({}, title = { Text("正在下载更新") }, text = {
            val amount = progress
            if (amount == null) LinearProgressIndicator() else LinearProgressIndicator(progress = { amount })
        }, confirmButton = {}) }
        val file = pendingFile()
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
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
            activity.toast("允许安装后返回，再点“检查更新”即可直接安装，无需重新下载")
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
