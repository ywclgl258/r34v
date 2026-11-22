package com.r34v.r34v.ui

import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import kotlin.random.Random

internal val RentalInk = Color(0xff0e1419)
internal val RentalSurface = Color(0xff1c2429)
internal val RentalPaper = Color(0xfff1dfbd)
internal val RentalMuted = Color(0xffc1b8a6)
internal val RentalRed = Color(0xffee6953)
internal val RentalBlue = Color(0xff78949d)
internal val RentalEdge = Color(0xff827766)
internal val RentalShape = RoundedCornerShape(4.dp)
internal val RentalBorder = BorderStroke(1.dp, RentalEdge)
internal val LocalMotionEnabled = staticCompositionLocalOf { true }

@Composable internal fun rememberSystemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val key = Settings.Global.ANIMATOR_DURATION_SCALE
    fun enabled() = Settings.Global.getFloat(resolver, key, 1f) > 0f
    var motion by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        motion = enabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return motion
}

/** Draw only the face of the key below its resting position. Its touch target stays put. */
@Composable internal fun Modifier.rentalKeyPress(interaction: InteractionSource): Modifier {
    if (!LocalMotionEnabled.current) return this
    val pressed by interaction.collectIsPressedAsState()
    val depth = animateFloatAsState(if (pressed) 1f else 0f,
        tween(if (pressed) 75 else 135), label = "keyDepth")
    return drawWithContent {
        val fraction = depth.value
        translate(top = 1.5.dp.toPx() * fraction) {
            scale(1f - .025f * fraction) { this@drawWithContent.drawContent() }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun RentalTag(label: String, longClickLabel: String, onLongClick: () -> Unit, onClick: () -> Unit) {
    val palette = LocalArtworkPalette.current
    val shape = if (palette != null) ArtworkPill else RentalShape
    Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
        .clip(shape).combinedClickable(role = Role.Button, onLongClickLabel = longClickLabel, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, Modifier.background(palette?.surface ?: RentalSurface, shape).border(1.dp, palette?.edge ?: RentalEdge, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp), color = palette?.ink ?: RentalPaper, style = MaterialTheme.typography.labelLarge)
    }
}

// Share a seamless translucent grain tile across jackets, independent of cover images.
private val stockGrain by lazy {
    val size = 96
    val random = Random(34)
    val pixels = IntArray(size * size) {
        val alpha = random.nextInt(0, 16)
        (alpha shl 24) or if (random.nextBoolean()) 0xffffff else 0
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

internal fun Modifier.rentalStock(): Modifier = drawBehind { drawRect(stockGrain) }

private val printInks = listOf(RentalRed, RentalPaper, RentalBlue)

@Composable internal fun RentalStripes(modifier: Modifier) {
    Canvas(modifier) {
        val slant = minOf(size.height * .45f, size.width * .22f)
        val band = (size.width - slant) / printInks.size
        printInks.forEachIndexed { index, ink ->
            val left = index * band
            val stripe = Path().apply {
                moveTo(left + slant, 0f)
                lineTo(left + band + slant, 0f)
                lineTo(left + band, size.height)
                lineTo(left, size.height)
                close()
            }
            drawPath(stripe, ink)
        }
    }
}

@Composable internal fun RentalSiteSwitch(name: String, compact: Boolean, click: () -> Unit) {
    val palette = LocalArtworkPalette.current
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .clickable(role = Role.Button, onClick = click)
        .semantics { contentDescription = "切换站点，当前 $name" }, contentAlignment = Alignment.Center) {
        val shape = if (palette != null) ArtworkPill else RentalShape
        val surface = palette?.let { Modifier.background(it.surface, shape) } ?: Modifier.border(1.dp, RentalEdge, shape)
        Row(surface.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (!compact) Text(name, Modifier.widthIn(max = 110.dp), color = palette?.ink ?: RentalPaper,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Glyph(R.drawable.ic_chevron_down, null, Modifier.size(14.dp))
        }
    }
}
