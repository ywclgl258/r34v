package com.r34v.r34v.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owns requests and strong bitmap references, independent of lazy item composition. */
internal class ReaderBuffer(private val count: Int, private val scope: CoroutineScope, private val load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage) {
    val pages = mutableStateMapOf<Int, GalleryRepository.RenderedPage>()
    val errors = mutableStateMapOf<Int, String>()
    val ratios = mutableStateMapOf<Int, Float>()
    private val jobs = mutableMapOf<Int, Job>()
    private val cached = mutableSetOf<Int>()
    private val permits = Semaphore(2)
    private var anchor = 1
    private var visible = setOf<Int>()
    private var disposed = false
    fun window(page: Int, visiblePages: Set<Int>) {
        if (disposed) return
        anchor = page.coerceIn(1, count); visible = visiblePages
        val keep = visible + ((anchor - 1).coerceAtLeast(1)..(anchor + 3).coerceAtMost(count))
        jobs.keys.toList().filter { it !in keep }.forEach { jobs.remove(it)?.cancel() }
        pages.keys.toList().filter { it !in keep }.forEach { pages.remove(it) }
        errors.keys.toList().filter { it !in keep }.forEach { errors.remove(it) }
        ensure(anchor); visible.forEach { ensure(it) }
        if (anchor in cached) (anchor + 1..minOf(count, anchor + 3)).forEach { ensure(it, prefetch = true) }
        var bytes = 0L
        pages.keys.filter { it !in visible && it != anchor }.sortedBy { kotlin.math.abs(it - anchor) }.forEach { number ->
            val size = pages[number]?.bitmap?.byteCount ?: 0
            if (bytes + size > 24L * 1024 * 1024) pages.remove(number) else bytes += size
        }
    }
    private fun ensure(number: Int, force: Boolean = false, prefetch: Boolean = false) {
        if (disposed || number !in 1..count || jobs[number]?.isActive == true || !force && (number in pages || number in errors || prefetch && number in cached)) return
        errors.remove(number)
        jobs[number] = scope.launch {
            try {
                val rendered = permits.withPermit { load(number, force) }; ensureActive()
                pages[number] = rendered; cached.add(number); ratios[number] = rendered.bitmap.width.toFloat() / rendered.bitmap.height
            } catch (e: Exception) { if (e is CancellationException) throw e; errors[number] = e.message ?: "图页加载失败" }
            finally { if (jobs[number] === coroutineContext[Job]) jobs.remove(number) }
            window(anchor, visible)
        }
    }
    fun retry(number: Int) { pages.remove(number); errors.remove(number); cached.remove(number); ensure(number, force = true) }
    fun close() { disposed = true; jobs.values.toList().forEach { it.cancel() }; jobs.clear(); pages.clear(); errors.clear() }
}
data class ReaderJump(val serial: Int, val page: Int, val offset: Int = 0)

@Composable fun ComicReader(count: Int, jump: ReaderJump, load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage,
    toggle: () -> Unit, position: (Int, GalleryImage?, Int) -> Unit, attachSave: ((() -> Unit)?) -> Unit) {
    val scope = rememberCoroutineScope()
    val buffer = remember { ReaderBuffer(count, scope, load) }
    val list = rememberLazyListState(jump.page - 1, (-jump.offset).coerceAtLeast(0))
    var pending by remember { mutableStateOf<ReaderJump?>(jump) }
    val currentPosition by rememberUpdatedState(position)
    val currentToggle by rememberUpdatedState(toggle)
    fun save() {
        if (pending != null) return
        val page = if (!list.canScrollForward && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == count - 1) count else (list.firstVisibleItemIndex + 1).coerceIn(1, count)
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page - 1 }
        currentPosition(page, buffer.pages[page]?.image, info?.offset ?: -list.firstVisibleItemScrollOffset)
    }
    DisposableEffect(buffer) { attachSave(::save); onDispose { save(); attachSave(null); buffer.close() } }
    LaunchedEffect(jump) {
        pending = jump; buffer.window(jump.page, setOf(jump.page))
        list.scrollToItem(jump.page - 1, (-jump.offset).coerceAtLeast(0))
    }
    val pendingBitmap = pending?.let { buffer.pages[it.page]?.bitmap }
    LaunchedEffect(pendingBitmap, pending) {
        val target = pending
        if (target != null && pendingBitmap != null) { withFrameNanos { }; list.scrollToItem(target.page - 1, (-target.offset).coerceAtLeast(0)); pending = null; save() }
    }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) pending = null } }
    LaunchedEffect(list, buffer) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.visibleItemsInfo.map { it.index + 1 }) }.distinctUntilChanged().collect { (index, _, visible) ->
            buffer.window(pending?.page ?: index + 1, visible.toSet()); save()
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("comic-strip").semantics { contentDescription = "条漫阅读，上下滑动连续阅读" }, state = list, userScrollEnabled = true) {
        items(count, key = { it + 1 }, contentType = { "comic-page" }) { index ->
            val number = index + 1; val rendered = buffer.pages[number]; val error = buffer.errors[number]
            val ratio = buffer.ratios[number] ?: (2f / 3f)
            var zoom by remember(number) { mutableFloatStateOf(1f) }; var pan by remember(number) { mutableStateOf(Offset.Zero) }
            LaunchedEffect(list.firstVisibleItemIndex) { if (number != list.firstVisibleItemIndex + 1) { zoom = 1f; pan = Offset.Zero } }
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds().background(CanvasColor).testTag("reader-page-$number").semantics { stateDescription = "缩放 $zoom" }
                .pointerInput(number) { detectTapGestures(onTap = { currentToggle() }, onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }) }
                .pointerInput(number) { awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var movementTotal = Offset.Zero
                    do { val event = awaitPointerEvent(); val change = event.calculateZoom(); val movement = event.calculatePan()
                        movementTotal += movement
                        val pinch = event.changes.count { it.pressed } > 1 && (kotlin.math.abs(change - 1f) > .001f || movementTotal.getDistance() > viewConfiguration.touchSlop)
                        if (pinch || zoom > 1f && (dragging || movementTotal.getDistance() > viewConfiguration.touchSlop)) {
                            dragging = true
                            zoom = (zoom * change).coerceIn(1f, 5f)
                            val limitX = size.width * (zoom - 1f) / 2f; val limitY = size.height * (zoom - 1f) / 2f
                            pan = Offset((pan.x + movement.x).coerceIn(-limitX, limitX), (pan.y + movement.y).coerceIn(-limitY, limitY))
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } }, contentAlignment = Alignment.Center) {
                when {
                    rendered != null -> Image(rendered.bitmap.asImageBitmap(), "漫画第 $number 页，双击或双指缩放", Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit)
                    error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Copy("第 $number 页：$error"); Action("重试此页") { buffer.retry(number) } }
                    else -> CircularProgressIndicator(Modifier.size(32.dp), color = BrandColor)
                }
            }
        }
    }
}
