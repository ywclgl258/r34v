package com.r34v.r34v.ui

import android.net.Uri
import androidx.core.net.toUri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import kotlinx.coroutines.*

private fun InputStream.readBackup(): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(32 * 1024)
    while (true) {
        val read = read(chunk); if (read < 0) break
        require(output.size() + read <= BackupCodec.MAX_BYTES + 64) { "备份文件超过 16 MiB" }
        output.write(chunk, 0, read)
    }
    return output.toByteArray()
}

@Composable fun BackupScreen(app: R34Application, restored: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceUri by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LibraryBackup?>(null) }
    // 当前持有持久授权的文档；换文件或离开页面时释放，避免无限累积授权。
    var previousUri by remember { mutableStateOf<Uri?>(null) }
    fun releasePersisted(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pending?.let { File(app.cacheDir, "library-backups/$it") }; pending = null
        if (uri == null || source == null) { source?.delete(); busy = false; status = "已取消导出" }
        else scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { (app.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件")).use { output -> source.inputStream().use { it.copyTo(output) } } }
                status = "备份已保存"; password = ""; confirmation = ""
            } catch (e: Exception) { if (e is CancellationException) throw e; status = "导出失败：${e.message}" }
            finally { source.delete(); busy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        busy = false
        if (uri == null) status = "已取消选择"
        else {
            // 上一次选择的文档授权要释放：反复换文件会让应用持续持有旧文件的访问权，
            // 并占用系统的持久 URI 授权配额。
            previousUri?.takeIf { it != uri }?.let { releasePersisted(it) }
            runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            previousUri = uri
            sourceUri = uri.toString(); preview = null; status = "已选择备份文件，请输入密码"
        }
    }
    // 预览被清空/组件离开时释放授权，恢复完成后不再占着文件。
    DisposableEffect(Unit) { onDispose { previousUri?.let(::releasePersisted); previousUri = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!busy) Choices(listOf("导出备份", "恢复备份"), mode) { mode = it; preview = null; status = null; password = ""; confirmation = "" }
        Copy("备份包含收藏、标签订阅、观看与阅读进度、片单、书签、屏蔽词和设置。下载文件仍由下载中心管理，恢复后请重新登录站点账号。")
        if (preview == null) {
            if (mode == 0 || sourceUri != null) TextField(password, { password = it; status = null }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("备份密码") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = filledFieldColors(),
                shape = RentalShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (mode == 0) TextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("再次输入密码") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = filledFieldColors(),
                shape = RentalShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (mode == 0) {
                Copy("密码至少 8 个字符。恢复备份时需要此密码。")
                Action("选择位置并导出", primary = true, enabled = !busy && password.length >= 8 && confirmation == password) {
                    busy = true; status = null
                    val secret = password.toCharArray()
                    scope.launch {
                        try {
                            pending = withContext(Dispatchers.IO) {
                                val bytes = BackupCodec.encode(app.library.backupSnapshot(), secret)
                                val file = File(app.cacheDir, "library-backups/${java.util.UUID.randomUUID()}.tmp")
                                try { file.parentFile!!.mkdirs(); file.writeBytes(bytes); file.name } finally { bytes.fill(0) }
                            }
                            password = ""; confirmation = ""
                            export.launch("R34V-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())}.r34vbackup")
                        } catch (e: Exception) { if (e is CancellationException) throw e; pending?.let { File(app.cacheDir, "library-backups/$it").delete() }; pending = null; status = "导出失败：${e.message}"; busy = false }
                        finally { secret.fill('\u0000') }
                    }
                }
            } else {
                Action(if (sourceUri == null) "选择备份文件" else "重新选择文件", enabled = !busy) { status = null; import.launch(arrayOf("*/*")) }
                if (sourceUri != null) Action("解密并预览", primary = true, enabled = !busy && password.isNotEmpty()) {
                    busy = true; status = null
                    val secret = password.toCharArray(); val uri = requireNotNull(sourceUri).toUri()
                    scope.launch {
                        try {
                            preview = withContext(Dispatchers.IO) {
                                val bytes = (app.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")).use { it.readBackup() }
                                try { BackupCodec.decode(bytes, secret) } finally { bytes.fill(0) }
                            }
                            password = ""
                        } catch (e: Exception) { if (e is CancellationException) throw e; status = "无法读取备份：${e.message}" }
                        finally { secret.fill('\u0000'); busy = false }
                    }
                }
            }
        } else {
            val backup = preview!!
            Title("恢复预览")
            Text(backup.summary())
            Copy("记录将合并去重，并保留时间较新的观看与阅读进度。设置使用备份中的值。")
            Action("合并恢复", primary = true, enabled = !busy) {
                busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { app.library.restoreBackup(backup) }; preview = null; status = "已合并恢复"; restored() }
                    catch (e: Exception) { if (e is CancellationException) throw e; status = "恢复失败：${e.message}" }
                    finally { busy = false }
                }
            }
            TextAction({ preview = null }, enabled = !busy) { Text("取消恢复") }
        }
        if (busy) Loading(title = if (preview == null) "正在处理备份" else "正在合并数据")
        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
