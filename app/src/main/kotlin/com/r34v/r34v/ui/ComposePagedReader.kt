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
    position: (Int, GalleryImage?, Int) -> Unit, attachSave: ((() -> Unit)?) -> Unit,
    fit: ReaderFit = ReaderFit.WIDTH, cropPercent: Int = 0, combineCover: Boolean = true, currentPage: Int = jump.page) {
    val config = LocalConfiguration.current
    val readingPage by rememberUpdatedState(currentPage)
    val spread = if (mode == ReaderMode.SPREAD && config.screenWidthDp > config.screenHeightDp) 2 else 1
    key(spread, mode, combineCover) {
        val scope = rememberCoroutineScope()
        val buffer = remember { ReaderBuffer(count, scope, load) }
        val sheets = remember(count, spread, combineCover) { readerSheets(count, spread, combineCover) }
        val initial = sheets.indexOfFirst { readingPage in it }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = initial) { sheets.size }
        var pending by remember { mutableStateOf<ReaderJump?>(jump) }
        val currentPosition by rememberUpdatedState(position)
        fun logicalPage(): Int {
            val sheet = sheets[pager.settledPage.coerceIn(0, sheets.lastIndex)]
            return if (readingPage in sheet) readingPage else sheet.first()
        }
        fun save() { if (pending == null) { val page = logicalPage(); currentPosition(page, buffer.pages[page]?.image, 0) } }
        DisposableEffect(buffer) { attachSave(::save); onDispose { save(); attachSave(null); buffer.close() } }
        LaunchedEffect(jump) {
            // Layout changes recreate this effect; resume the latest logical page, not the old jump.
            pending = jump; pager.scrollToPage(sheets.indexOfFirst { readingPage in it }.coerceAtLeast(0))
            withFrameNanos { }; pending = null
        }
        LaunchedEffect(pager, jump) {
            snapshotFlow { Triple(pager.settledPage, buffer.pages[logicalPage()]?.image, pending) }.distinctUntilChanged().collect {
                val sheet = sheets[pager.settledPage.coerceIn(0, sheets.lastIndex)]
                buffer.window(sheet.first(), sheet.toSet())
                save()
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("paged-reader"), reverseLayout = mode == ReaderMode.RIGHT_TO_LEFT,
                beyondViewportPageCount = 0) { sheetIndex ->
                val sheet = sheets[sheetIndex]
                Row(Modifier.fillMaxSize()) {
                    sheet.forEach { number ->
                        ReaderLeaf(number, buffer.pages[number], buffer.errors[number], Modifier.weight(1f).fillMaxHeight(), toggle, fit, cropPercent) { buffer.retry(number) }
                    }
                    if (sheet.size == 1 && spread == 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun ReaderLeaf(number: Int, rendered: GalleryRepository.RenderedPage?, error: String?, modifier: Modifier,
    toggle: () -> Unit, fit: ReaderFit, cropPercent: Int, retry: () -> Unit) {
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
                .graphicsLayer {
                    val crop = 1f + cropPercent / 100f; scaleX = zoom * crop; scaleY = zoom * crop; translationX = pan.x; translationY = pan.y
                }, contentScale = when (fit) { ReaderFit.WIDTH -> ContentScale.FillWidth; ReaderFit.HEIGHT -> ContentScale.FillHeight; ReaderFit.ORIGINAL -> ContentScale.None })
            error != null -> Column(Modifier.padding(16.dp)) { Copy("第 $number 页：$error"); Action("重试此页", click = retry) }
            else -> CircularProgressIndicator(Modifier.size(32.dp), color = BrandColor)
        }
    }
}
