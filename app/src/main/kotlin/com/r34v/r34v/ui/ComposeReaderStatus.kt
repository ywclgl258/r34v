package com.r34v.r34v.ui

import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.r34v.r34v.data.ReadingBehavior
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Date

@Composable fun ReaderStatusBar(value: ReadingBehavior, page: Int, total: Int, modifier: Modifier = Modifier) {
    if (!value.showClock && !value.showBattery && !value.showPage && !value.showProgress) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var clock by remember { mutableStateOf("") }
    var battery by remember { mutableIntStateOf(-1) }
    LaunchedEffect(value.showClock, value.showBattery, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (value.showClock) clock = DateFormat.getTimeFormat(context).format(Date())
                if (value.showBattery) battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                delay(30_000)
            }
        }
    }
    val parts = listOfNotNull(clock.takeIf { value.showClock && it.isNotBlank() },
        "电量 $battery%".takeIf { value.showBattery && battery in 0..100 },
        "$page / $total".takeIf { value.showPage }, "${page * 100 / total.coerceAtLeast(1)}%".takeIf { value.showProgress })
    Text(parts.joinToString("  ·  "), modifier.testTag("reader-status").background(Color.Black.copy(alpha = .72f)).padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall)
}

fun readerStatusOptions(dialogs: ComposeDialogs, current: ReadingBehavior, save: (ReadingBehavior) -> Unit) {
    dialogs.content = {
        var value by remember { mutableStateOf(current) }
        fun update(next: ReadingBehavior) { value = next; save(next) }
        AlertDialog(dialogs::dismiss, title = { Text("阅读状态栏与屏幕") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("时间" to value.showClock, "电量" to value.showBattery, "页码" to value.showPage, "阅读进度" to value.showProgress, "保持亮屏" to value.keepAwake)
                    .forEachIndexed { index, (label, checked) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f))
                            Switch(checked, { enabled -> update(when (index) {
                                0 -> value.copy(showClock = enabled); 1 -> value.copy(showBattery = enabled)
                                2 -> value.copy(showPage = enabled); 3 -> value.copy(showProgress = enabled)
                                else -> value.copy(keepAwake = enabled)
                            }) }, Modifier.testTag("reader-status-$index"))
                        }
                    }
                Copy("状态栏在收起阅读工具后显示。设置适用于当前来源。")
                Text(if (value.brightnessPercent < 0) "亮度：跟随系统" else "阅读亮度：${value.brightnessPercent}%")
                Slider(value.brightnessPercent.coerceAtLeast(0).toFloat(), { update(value.copy(brightnessPercent = it.toInt())) }, Modifier.testTag("reader-brightness"), valueRange = 0f..100f)
                TextAction({ update(value.copy(brightnessPercent = -1)) }) { Text("亮度跟随系统") }
            }
        }, confirmButton = { TextAction(dialogs::dismiss) { Text("完成") } })
    }
}
