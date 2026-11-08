package com.r34v.r34v.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Current app window, including multi-window resizing; child layouts still use their constraints. */
@Composable fun windowSizeDp(): IntSize {
    val pixels = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return IntSize((pixels.width / density).roundToInt(), (pixels.height / density).roundToInt())
}
