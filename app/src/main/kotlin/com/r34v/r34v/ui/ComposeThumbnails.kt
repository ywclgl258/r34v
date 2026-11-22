package com.r34v.r34v.ui

import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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

@Composable internal fun rememberSmallGalleryPage(
    page: Int,
    size: Int,
    permits: Semaphore,
    load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage,
): Pair<Bitmap?, Boolean> {
    val loader by rememberUpdatedState(load)
    var bitmap by remember(page, size) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page, size) { mutableStateOf(false) }
    LaunchedEffect(page, size) {
        try {
            bitmap = permits.withPermit {
                val full = loader(page, false).bitmap
                withContext(Dispatchers.Default) {
                    val scale = minOf(1f, size.toFloat() / maxOf(full.width, full.height))
                    full.scale((full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
                        .also { if (it !== full) full.recycle() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    return bitmap to failed
}

@Composable fun ReaderThumbnails(count: Int, current: Int, load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage, dismiss: () -> Unit, choose: (Int) -> Unit) {
    val permits = remember { Semaphore(2) }
    val grid = rememberLazyGridState((current - 1).coerceAtLeast(0))
    val colors = MaterialTheme.colorScheme
    AlertDialog(dismiss, title = { Text("页面总览 · $current / $count") }, text = {
        LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxWidth().heightIn(max = 440.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(count, key = { it }) { index ->
                val (bitmap, failed) = rememberSmallGalleryPage(index + 1, 240, permits, load)
                val selected = index + 1 == current
                Column(Modifier.fillMaxWidth().clip(ArtworkShape).background(if (selected) colors.primary else colors.surfaceVariant)
                    .border(1.dp, if (selected) colors.primary else colors.outline, ArtworkShape)
                    .selectable(selected, role = Role.RadioButton) { choose(index + 1) }.padding(5.dp)
                    .semantics { contentDescription = "跳到第 ${index + 1} 页" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(.7f).clip(ArtworkShape).background(colors.background),
                        contentAlignment = Alignment.Center) {
                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Copy(if (failed) "暂不可用" else "加载中")
                    }
                    Text("第 ${index + 1} 页", Modifier.padding(top = 4.dp), color = if (selected) colors.onPrimary else colors.onSurface,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }, shape = ArtworkShape, containerColor = colors.surfaceContainer,
        confirmButton = { TextAction(dismiss) { Text("关闭") } })
}
