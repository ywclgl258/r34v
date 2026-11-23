package com.r34v.r34v

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.r34v.r34v.data.UpgradeAsset
import com.r34v.r34v.data.UpgradePackages
import com.r34v.r34v.data.cachedUpgrade
import com.r34v.r34v.data.downloadUpgrade
import com.r34v.r34v.data.readResponse
import com.r34v.r34v.ui.ComposeDialogs
import com.r34v.r34v.ui.showError
import com.r34v.r34v.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.Request
import java.io.File

class UpdateController(private val activity: ComponentActivity, private val app: R34Application, private val dialogs: ComposeDialogs) {
    private val updates get() = File(activity.cacheDir, "updates")

    suspend fun check(silent: Boolean) {
        if (BuildConfig.DEBUG) {
            if (!silent) activity.toast("当前是开发版，请使用 R34V 正式版接收更新")
            return
        }
        runUpdate(silent) {
            val request = Request.Builder().url("$RELEASES_URL/latest")
                .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache").build()
            val release = app.network.client.readResponse(request) { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 403 || response.code == 429) {
                    error(if (body.contains("rate limit", true)) "GitHub 请求过于频繁，请稍后重试" else "GitHub 拒绝访问，请检查网络或仓库权限")
                }
                check(response.isSuccessful) { "更新检查失败：HTTP ${response.code}" }
                JsonParser.parseString(body).asJsonObject
            }
            app.library.prefs.edit { putLong("update_checked_at", System.currentTimeMillis()) }
            val version = release["tag_name"].asString
            if (!newer(version, BuildConfig.VERSION_NAME)) {
                if (!silent) activity.toast("当前已是最新版本")
                return@runUpdate
            }
            val assets = release.getAsJsonArray("assets").map { it.asJsonObject }
            val filename = UpgradePackages.select(assets.map { it["name"].asString }, UpgradePackages.needsCompatibility(activity))
                ?: error("此版本缺少适配当前安装签名的 APK，请稍后重试")
            val asset = UpgradeAsset.fromGitHub(release["id"].asLong, assets.first { it["name"].asString == filename })
            val pending = cachedUpgrade(updates, asset, ::validate)
            if (activity.isFinishing || activity.isDestroyed) return@runUpdate
            if (pending != null) {
                dialogs.confirm("新版本 $version", "安装", "安装包已下载并通过校验，可直接安装。") { install(pending) }
                return@runUpdate
            }
            val notes = release["body"]?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(1800)
            dialogs.confirm("新版本 $version", "下载", notes) {
                activity.lifecycleScope.launch { download(asset) }
            }
        }
    }

    private suspend fun download(asset: UpgradeAsset) = runUpdate(silent = false) {
        var progress by mutableStateOf<Float?>(null)
        dialogs.content = { AlertDialog({}, title = { Text("正在下载更新") }, text = {
            val amount = progress
            if (amount == null) LinearProgressIndicator() else LinearProgressIndicator(progress = { amount })
        }, confirmButton = {}) }
        val file = try {
            var lastUpdate = 0L
            downloadUpgrade(app.network.client, RELEASES_URL, updates, asset, ::validate) { received ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 100 || received == asset.size) {
                    lastUpdate = now
                    progress = (received.toFloat() / asset.size).coerceIn(0f, 1f)
                }
            }
        } finally {
            dialogs.dismiss()
        }
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialogs.confirm("更新已下载", "安装") { install(file) }
        }
    }

    private fun validate(file: File) = UpgradePackages.validate(activity, file)

    private suspend fun runUpdate(silent: Boolean, action: suspend () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!updateOperation.tryLock()) {
            if (!silent) activity.toast("正在处理更新，请稍候")
            return
        }
        try {
            action()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!silent) activity.showError(error)
        } finally {
            updateOperation.unlock()
        }
    }

    private fun install(file: File) {
        try {
            check(file.isFile) { "安装包已从缓存清除，请重新检查更新" }
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
                activity.toast("允许安装后返回，再点“检查更新”即可继续安装")
                return
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (error: Exception) {
            activity.showError(error)
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/ywclgl258/r34v/releases"
        private val updateOperation = Mutex()

        fun newer(remote: String, current: String): Boolean {
            fun numbers(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val r = numbers(remote); val c = numbers(current)
            for (i in 0 until maxOf(r.size, c.size)) { val comparison = (r.getOrNull(i) ?: 0).compareTo(c.getOrNull(i) ?: 0); if (comparison != 0) return comparison > 0 }
            return false
        }
    }
}
