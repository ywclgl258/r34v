package com.r34v.r34v.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.r34v.r34v.BuildConfig
import java.io.File
import java.security.MessageDigest

object UpgradePackages {
    fun select(names: List<String>, compatibility: Boolean): String? = names.filter { it.endsWith(".apk", true) && !it.contains("debug", true) }.firstOrNull {
        it.contains("-compat-2.0.0", true) == compatibility
    }
    @Suppress("DEPRECATION")
    fun needsCompatibility(context: Context): Boolean = Build.VERSION.SDK_INT < 28 &&
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.any { digest(it.toByteArray()) == BuildConfig.RELEASE_CERTIFICATE } == true

    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("安装包无法解析")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(candidate.packageName == context.packageName) { "此安装包的包名与当前应用不同" }
        val candidateVersion = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        check(candidateVersion >= BuildConfig.VERSION_CODE) { "安装包版本号低于当前版本，已停止安装" }
        val accepted = if (Build.VERSION.SDK_INT >= 28) {
            val history = candidate.signingInfo?.signingCertificateHistory?.map { digest(it.toByteArray()) }.orEmpty()
            val current = installed.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }.orEmpty()
            current.isNotEmpty() && current.all { it in history }
        } else candidate.signatures?.map { digest(it.toByteArray()) } == installed.signatures?.map { digest(it.toByteArray()) }
        check(accepted) { "安装包签名无法继承当前版本，请使用对应的兼容包；无需卸载应用" }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
