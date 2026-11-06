package com.r34v.r34v.ui

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.r34v.r34v.R34Application

/** Only the content layer is recorded. Floating controls must be siblings of that layer. */
val LocalLiquidBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalReduceTransparency = staticCompositionLocalOf { false }
val LocalSourceTopInset = staticCompositionLocalOf { 132.dp }

@Composable fun Modifier.liquidContent(backdrop: LayerBackdrop): Modifier =
    if (Build.VERSION.SDK_INT >= 31 && !LocalReduceTransparency.current) layerBackdrop(backdrop) else this

@Composable internal fun rememberReducedTransparency(): Boolean {
    val store = (LocalContext.current.applicationContext as? R34Application)?.library
    var reduced by remember(store) { mutableStateOf(store?.bool("reduce_transparency") ?: false) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "settings_v1_reduce_transparency") reduced = store?.bool("reduce_transparency") ?: false
        }
        store?.prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store?.prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return reduced
}

/** Regular dark glass: tint guarantees label contrast even over a white page. */
@Composable fun Modifier.liquidSurface(radius: Int = 28): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val backdrop = LocalLiquidBackdrop.current
    val reduced = LocalReduceTransparency.current
    val base = shadow(10.dp, shape, clip = false)
    return if (!reduced && Build.VERSION.SDK_INT >= 31 && backdrop != null) base.drawBackdrop(
        backdrop = backdrop, shape = { shape }, effects = {
            blur(18.dp.toPx())
            if (Build.VERSION.SDK_INT >= 33) lens(5.dp.toPx(), 9.dp.toPx())
        }, highlight = null, shadow = null,
        onDrawBehind = { drawRect(CanvasColor) },
        onDrawSurface = { drawRect(Color(0xff111215).copy(alpha = .76f)) }
    ).border(1.dp, Color.White.copy(alpha = .15f), shape).clip(shape)
    else base.clip(shape).background(if (reduced || Build.VERSION.SDK_INT < 31) Color(0xff202124) else Color(0xf0242528))
        .border(1.dp, Color.White.copy(alpha = .12f), shape)
}

/** Stable content surfaces never refract covers, descriptions, or task information. */
fun Modifier.contentPanel(radius: Int = 20): Modifier = clip(RoundedCornerShape(radius.dp)).background(SurfaceColor)
