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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owns requests and strong bitmap references, independent of lazy item composition. */
internal class ReaderBuffer(private val numbers: List<Int>, private val scope: CoroutineScope, private val load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage) {
    val pages = mutableStateMapOf<Int, GalleryRepository.RenderedPage>()
    val errors = mutableStateMapOf<Int, String>()
    val ratios = mutableStateMapOf<Int, Float>()
    private val jobs = mutableMapOf<Int, Job>()
    private val cached = mutableSetOf<Int>()
    private val permits = Semaphore(2)
    private var anchor = numbers.first()
    private var visible = setOf<Int>()
    private var disposed = false
    fun window(page: Int, visiblePages: Set<Int>) {
        if (disposed) return
        val index = numbers.binarySearch(page)
        if (index < 0) return
        anchor = page; visible = visiblePages
        val end = minOf(numbers.size, index + 4)
        val keep = visible + numbers.subList((index - 1).coerceAtLeast(0), end)
        jobs.keys.toList().filter { it !in keep }.forEach { jobs.remove(it)?.cancel() }
        pages.keys.toList().filter { it !in keep }.forEach { pages.remove(it) }
        errors.keys.toList().filter { it !in keep }.forEach { errors.remove(it) }
        ensure(anchor); visible.forEach { ensure(it) }
        if (anchor in cached) numbers.subList(index + 1, end).forEach { ensure(it, prefetch = true) }
        var bytes = 0L
        pages.keys.filter { it !in visible && it != anchor }.sortedBy { kotlin.math.abs(numbers.binarySearch(it) - index) }.forEach { number ->
            val size = pages[number]?.bitmap?.byteCount ?: 0
            if (bytes + size > 24L * 1024 * 1024) pages.remove(number) else bytes += size
        }
    }
    private fun ensure(number: Int, force: Boolean = false, prefetch: Boolean = false) {
        if (disposed || jobs[number]?.isActive == true || !force && (number in pages || number in errors || prefetch && number in cached)) return
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

@Composable fun ComicReader(numbers: List<Int>, jump: ReaderJump, load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage,
    toggle: () -> Unit, position: (Int, GalleryImage?, Int) -> Unit, attachSave: ((() -> Unit)?) -> Unit,
    fit: ReaderFit = ReaderFit.WIDTH, cropPercent: Int = 0, sparse: Boolean = false) {
    val scope = rememberCoroutineScope()
    val loader by rememberUpdatedState(load)
    val buffer = remember(numbers) { ReaderBuffer(numbers, scope) { number, force -> loader(number, force) } }
    val list = rememberLazyListState(numbers.binarySearch(jump.page).coerceAtLeast(0))
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val lastRatio = buffer.ratios[numbers.last()] ?: (2f / 3f)
    // Let the last saved page reach the top even if a short photo cannot fill the viewport.
    val bottomPadding = if (sparse) with(LocalDensity.current) {
        (viewport.height - (viewport.width / lastRatio).roundToInt()).coerceAtLeast(0).toDp()
    } else 0.dp
    val currentJump by rememberUpdatedState(jump)
    var settledJump by remember { mutableStateOf<ReaderJump?>(null) }
    val targetHeight = remember(jump) { mutableStateOf<Int?>(null) }
    val currentPosition by rememberUpdatedState(position)
    val currentToggle by rememberUpdatedState(toggle)
    fun visiblePosition(): Triple<Int, GalleryImage?, Int>? {
        if (settledJump != currentJump) return null
        val visible = list.layoutInfo.visibleItemsInfo
        val last = visible.lastOrNull()
        val item = if (!sparse && !list.canScrollForward && last?.index == numbers.lastIndex) last
            else visible.firstOrNull { it.index == list.firstVisibleItemIndex }
        if (item == null) return null
        val page = numbers.getOrNull(item.index) ?: return null
        return Triple(page, buffer.pages[page]?.image, item.offset)
    }
    fun save() { visiblePosition()?.let { (page, image, offset) -> currentPosition(page, image, offset) } }
    DisposableEffect(buffer) { attachSave(::save); onDispose { save(); attachSave(null); buffer.close() } }
    LaunchedEffect(jump, buffer) {
        val index = numbers.binarySearch(jump.page)
        if (index < 0) return@LaunchedEffect
        buffer.window(jump.page, setOf(jump.page))
        // The placeholder starts at the top so a failed page can be retried there.
        list.scrollToItem(index)
        snapshotFlow { targetHeight.value != null || settledJump == jump }.first { it }
        val height = targetHeight.value ?: return@LaunchedEffect
        if (settledJump == jump || currentJump != jump) return@LaunchedEffect
        // Older history can store a positive offset for the last page at the bottom of the list.
        val offset = jump.offset.coerceIn(1 - height.coerceAtLeast(1), (list.layoutInfo.viewportEndOffset - 1).coerceAtLeast(0))
        list.scrollToItem(index, -offset)
        settledJump = jump
        save()
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) settledJump = currentJump }
    }
    LaunchedEffect(list, buffer) {
        snapshotFlow {
            val anchor = if (settledJump == currentJump) numbers.getOrNull(list.firstVisibleItemIndex) else currentJump.page
            anchor to list.layoutInfo.visibleItemsInfo.mapNotNull { numbers.getOrNull(it.index) }
        }.collect { (anchor, visible) -> if (anchor != null) buffer.window(anchor, visible.toSet()) }
    }
    LaunchedEffect(list, buffer) {
        snapshotFlow { visiblePosition() }.filterNotNull().collect { (page, image, offset) -> currentPosition(page, image, offset) }
    }
    val measureViewport = if (sparse) Modifier.onSizeChanged { viewport = it } else Modifier
    LazyColumn(Modifier.fillMaxSize().then(measureViewport).testTag("comic-strip").semantics { contentDescription = "条漫阅读，上下滑动连续阅读" },
        state = list, contentPadding = PaddingValues(bottom = bottomPadding)) {
        itemsIndexed(numbers, key = { _, number -> number }, contentType = { _, _ -> "comic-page" }) { index, number ->
            val rendered = buffer.pages[number]; val error = buffer.errors[number]
            val ratio = buffer.ratios[number] ?: (2f / 3f)
            var zoom by remember(number) { mutableFloatStateOf(1f) }; var pan by remember(number) { mutableStateOf(Offset.Zero) }
            LaunchedEffect(list, number) {
                snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { first ->
                    if (index != first) { zoom = 1f; pan = Offset.Zero }
                }
            }
            val measuredTarget = if (number == jump.page && rendered != null && settledJump != jump) {
                Modifier.onGloballyPositioned { targetHeight.value = it.size.height }
            } else Modifier
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).then(measuredTarget).clipToBounds().background(androidx.compose.ui.graphics.Color.Black).testTag("reader-page-$number").semantics { stateDescription = "缩放 $zoom" }
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
                    rendered != null -> Image(rendered.bitmap.asImageBitmap(), "第 $number 页，双击或双指缩放", Modifier.fillMaxSize().graphicsLayer {
                        val crop = 1f + cropPercent / 100f; scaleX = zoom * crop; scaleY = zoom * crop; translationX = pan.x; translationY = pan.y
                    }, contentScale = when (fit) { ReaderFit.WIDTH -> ContentScale.FillWidth; ReaderFit.HEIGHT -> ContentScale.FillHeight; ReaderFit.ORIGINAL -> ContentScale.None })
                    error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Copy("第 $number 页：$error"); Action("重试此页") { buffer.retry(number) } }
                    else -> CircularProgressIndicator(Modifier.size(32.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
