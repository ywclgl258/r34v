package com.r34v.r34v.ui

import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.r34v.r34v.data.GalleryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable fun ReaderThumbnails(count: Int, current: Int, load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage, dismiss: () -> Unit, choose: (Int) -> Unit) {
    val permits = remember { Semaphore(2) }
    val grid = rememberLazyGridState((current - 1).coerceAtLeast(0))
    AlertDialog(dismiss, title = { Text("页面总览 · $current / $count") }, text = {
        LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxWidth().heightIn(max = 440.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(count, key = { it }) { index ->
                var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
                var failed by remember(index) { mutableStateOf(false) }
                LaunchedEffect(index) { try {
                    bitmap = permits.withPermit {
                        val full = load(index + 1, false).bitmap
                        withContext(Dispatchers.Default) {
                            val scale = minOf(1f, 240f / maxOf(full.width, full.height))
                            full.scale((full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
                        }
                    }
                } catch (e: Exception) { if (e is CancellationException) throw e; failed = true } }
                Column(Modifier.fillMaxWidth().background(if (index + 1 == current) ControlColor else SurfaceColor).clickable { choose(index + 1) }.padding(5.dp)
                    .semantics { contentDescription = "跳到第 ${index + 1} 页" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(.7f), contentAlignment = Alignment.Center) {
                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Copy(if (failed) "暂不可用" else "加载中")
                    }
                    Copy("第 ${index + 1} 页")
                }
            }
        }
    }, confirmButton = { TextAction(dismiss) { Text("关闭") } })
}
