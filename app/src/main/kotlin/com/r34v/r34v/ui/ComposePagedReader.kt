@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.r34v.r34v.data.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable fun PagedComicReader(count: Int, jump: ReaderJump, mode: ReaderMode,
    load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage, toggle: () -> Unit,
    position: (Int, GalleryImage?, Int) -> Unit, attachSave: ((() -> Unit)?) -> Unit) {
    val config = LocalConfiguration.current
    val spread = if (mode == ReaderMode.SPREAD && config.screenWidthDp > config.screenHeightDp) 2 else 1
    key(spread, mode) {
        val scope = rememberCoroutineScope()
        val buffer = remember { ReaderBuffer(count, scope, load) }
        val pager = rememberPagerState(initialPage = (jump.page - 1) / spread) { (count + spread - 1) / spread }
        var pending by remember { mutableStateOf<ReaderJump?>(jump) }
        val currentPosition by rememberUpdatedState(position)
        fun logicalPage(): Int {
            val first = pager.settledPage * spread + 1
            return if (jump.page in first until minOf(first + spread, count + 1)) jump.page else first
        }
        fun save() { if (pending == null) { val page = logicalPage(); currentPosition(page, buffer.pages[page]?.image, 0) } }
        DisposableEffect(buffer) { attachSave(::save); onDispose { save(); attachSave(null); buffer.close() } }
        LaunchedEffect(jump) {
            pending = jump; pager.scrollToPage((jump.page.coerceIn(1, count) - 1) / spread)
            withFrameNanos { }; pending = null
        }
        LaunchedEffect(pager, jump) {
            snapshotFlow { Triple(pager.settledPage, buffer.pages[logicalPage()]?.image, pending) }.distinctUntilChanged().collect {
                val first = pager.settledPage * spread + 1
                buffer.window(first, (first until minOf(first + spread, count + 1)).toSet())
                save()
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("paged-reader"), reverseLayout = mode == ReaderMode.RIGHT_TO_LEFT,
                beyondViewportPageCount = 0) { sheet ->
                val first = sheet * spread + 1
                Row(Modifier.fillMaxSize()) {
                    (first until minOf(first + spread, count + 1)).forEach { number ->
                        ReaderLeaf(number, buffer.pages[number], buffer.errors[number], Modifier.weight(1f).fillMaxHeight(), toggle) { buffer.retry(number) }
                    }
                    if (spread == 2 && first == count) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun ReaderLeaf(number: Int, rendered: GalleryRepository.RenderedPage?, error: String?, modifier: Modifier,
    toggle: () -> Unit, retry: () -> Unit) {
    var zoom by remember(number) { mutableFloatStateOf(1f) }
    var pan by remember(number) { mutableStateOf(Offset.Zero) }
    val currentToggle by rememberUpdatedState(toggle)
    Box(modifier.clipToBounds().background(CanvasColor).testTag("reader-page-$number").semantics { stateDescription = "缩放 $zoom" }
        .pointerInput(number) { detectTapGestures(onTap = { currentToggle() }, onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }) }
        .pointerInput(number) { awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            var total = Offset.Zero
            do {
                val event = awaitPointerEvent(); val change = event.calculateZoom(); val movement = event.calculatePan(); total += movement
                val pinch = event.changes.count { it.pressed } > 1 && (kotlin.math.abs(change - 1f) > .001f || total.getDistance() > viewConfiguration.touchSlop)
                if (pinch || zoom > 1f && (dragging || total.getDistance() > viewConfiguration.touchSlop)) {
                    dragging = true; zoom = (zoom * change).coerceIn(1f, 5f)
                    pan = Offset((pan.x + movement.x).coerceIn(-size.width * (zoom - 1f) / 2f, size.width * (zoom - 1f) / 2f),
                        (pan.y + movement.y).coerceIn(-size.height * (zoom - 1f) / 2f, size.height * (zoom - 1f) / 2f))
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        } }, contentAlignment = Alignment.Center) {
        when {
            rendered != null -> Image(rendered.bitmap.asImageBitmap(), "第 $number 页，双击或双指缩放", Modifier.fillMaxSize()
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit)
            error != null -> Column(Modifier.padding(16.dp)) { Copy("第 $number 页：$error"); Action("重试此页", click = retry) }
            else -> CircularProgressIndicator(Modifier.size(32.dp), color = BrandColor)
        }
    }
}
