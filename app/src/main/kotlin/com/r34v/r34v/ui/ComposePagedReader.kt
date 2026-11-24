@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import com.r34v.r34v.data.*

@Composable internal fun PagedComicReader(navigation: ReaderNavigation, jump: ReaderJump, mode: ReaderMode,
    load: suspend (Int, Boolean) -> GalleryRepository.RenderedPage, toggle: () -> Unit, pause: () -> Unit,
    position: (Int, GalleryImage?, Int) -> Unit, readyPages: (List<Int>) -> Unit, attachSave: ((() -> Unit)?) -> Unit,
    fit: ReaderFit, cropPercent: Int, currentPage: Int) {
    val readingPage by rememberUpdatedState(currentPage)
    val currentJump by rememberUpdatedState(jump)
    val loader by rememberUpdatedState(load)
    val currentPosition by rememberUpdatedState(position)
    val currentReadyPages by rememberUpdatedState(readyPages)
    val currentPause by rememberUpdatedState(pause)
    key(navigation, mode) {
        val scope = rememberCoroutineScope()
        val buffer = remember { ReaderBuffer(navigation.pages, scope) { number, force -> loader(number, force) } }
        val sheets = navigation.sheets
        val pager = rememberPagerState(initialPage = navigation.sheetIndex(readingPage)) { sheets.size }
        var settledJump by remember { mutableStateOf<ReaderJump?>(null) }
        var lastJumpSerial by remember { mutableIntStateOf(jump.serial) }
        fun settledSheet() = sheets[pager.settledPage.coerceIn(0, sheets.lastIndex)]
        fun logicalPage(sheet: List<Int>) = readingPage.takeIf { it in sheet } ?: sheet.first()
        fun save() {
            if (settledJump != currentJump) return
            val page = logicalPage(settledSheet())
            currentPosition(page, buffer.pages[page]?.image, 0)
        }
        DisposableEffect(buffer) { attachSave(::save); onDispose { save(); attachSave(null); buffer.close() } }
        LaunchedEffect(jump) {
            // The window can change its sheet size without changing the latest logical page;
            // a new jump serial is an explicit programmatic jump to jump.page.
            val targetPage = if (jump.serial != lastJumpSerial) jump.page else readingPage
            lastJumpSerial = jump.serial
            pager.scrollToPage(navigation.sheetIndex(targetPage))
            settledJump = jump
        }
        LaunchedEffect(pager) {
            pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) currentPause() }
        }
        LaunchedEffect(pager, buffer) {
            snapshotFlow {
                val sheet = settledSheet()
                val page = logicalPage(sheet)
                val ready = settledJump == currentJump && !pager.isScrollInProgress && sheet.all { it in buffer.pages }
                Triple(sheet, buffer.pages[page]?.image, ready)
            }.collect { (sheet, _, ready) ->
                buffer.window(sheet.first(), sheet.toSet())
                save()
                currentReadyPages(if (ready) sheet else emptyList())
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("paged-reader"), reverseLayout = mode == ReaderMode.RIGHT_TO_LEFT,
                beyondViewportPageCount = 0) { sheetIndex ->
                val sheet = sheets[sheetIndex]
                Row(Modifier.fillMaxSize()) {
                    sheet.forEach { number ->
                        ReaderLeaf(number, buffer.pages[number], buffer.errors[number], Modifier.weight(1f).fillMaxHeight(), toggle, pause, fit, cropPercent) {
                            buffer.retry(number)
                        }
                    }
                    if (sheet.size == 1 && navigation.spread == 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
