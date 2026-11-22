package com.r34v.r34v.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.r34v.r34v.BuildConfig
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.SiteCategory
import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val ArtworkCanvas = Color(0xff0b0b10)

data class ArtworkPalette(val top: Color) {
    val bottom = ArtworkCanvas
    val middle = lerp(top, bottom, .60f)
    val ink = Color(0xfff8f5f8)
    val muted = Color(0xffc1b8c2)
    val surface = lerp(middle, Color(0xff2d2a33), .55f)
    val raised = lerp(surface, Color.White, .07f)
    val edge = lerp(surface, ink, .20f)
    val accent = Color(0xffff8db4)
    val onAccent = Color(0xff30101e)
}

internal val LocalArtworkPalette = compositionLocalOf<ArtworkPalette?> { null }
internal val ArtworkShape = RoundedCornerShape(16.dp)
internal val ArtworkPill = RoundedCornerShape(50)

@Composable internal fun ArtworkSeekThumb(enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(12.dp).background(if (enabled) colors.primary else colors.onSurfaceVariant, CircleShape))
}

@Composable internal fun ArtworkSeekTrack(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors.primary))
    }
}

@Composable internal fun Modifier.artworkPanel(): Modifier {
    val palette = LocalArtworkPalette.current
    val shape = if (palette == null) RentalShape else ArtworkShape
    return clip(shape).background(palette?.surface ?: RentalSurface, shape)
        .border(1.dp, palette?.edge?.copy(alpha = .68f) ?: RentalEdge, shape)
}

internal fun artworkPalette(category: SiteCategory, hue: Float? = null): ArtworkPalette {
    val top = if (hue != null) Color.hsl(hue, .31f, .18f) else when (category) {
        SiteCategory.VIDEO -> Color(0xff2d1b29)
        SiteCategory.COMIC -> Color(0xff192438)
        SiteCategory.PHOTO -> Color(0xff2b211c)
    }
    return ArtworkPalette(top)
}

/** Use the same URL admission rules and site partition as visible covers. */
internal fun coverRequest(app: R34Application, site: SiteContext, url: String): ImageRequest.Builder {
    val allowed = url.startsWith("https://") || BuildConfig.DEBUG && url.startsWith("http://") ||
        site.id == SiteRegistry.LOCAL.id && app.localLibrary.ownsImageUri(url)
    val key = "${site.id}|$url"
    return ImageRequest.Builder(app).data(url.takeIf { allowed }).memoryCacheKey(key).diskCacheKey(key)
}

private val coverHues = LruCache<String, Float>(64)

@Composable internal fun rememberArtworkPalette(
    site: SiteContext,
    cover: String,
    category: SiteCategory = site.category,
    cachedOnly: Boolean = false,
): ArtworkPalette {
    val app = LocalContext.current.applicationContext as R34Application
    val fallback = remember(category) { artworkPalette(category) }
    val key = "${site.id}|$cover"
    var hue by remember(site.id) { mutableStateOf(coverHues.get(key)) }
    LaunchedEffect(key, cachedOnly) {
        val cached = coverHues.get(key)
        hue = when {
            cached != null -> cached
            cover.isBlank() -> null
            else -> {
                val builder = coverRequest(app, site, cover).allowHardware(false).size(32)
                    .memoryCacheKey("$key|artwork-hue")
                if (cachedOnly) builder.networkCachePolicy(CachePolicy.DISABLED)
                val result = app.coverLoader(site).execute(builder.build()) as? SuccessResult
                val bitmap = (result?.image as? BitmapImage)?.bitmap
                bitmap?.let { withContext(Dispatchers.Default) { dominantHue(it) } }
                    ?.also { coverHues.put(key, it) }
            }
        }
    }
    return hue?.let { artworkPalette(category, it) } ?: fallback
}

/** Sample a small decode and give saturated, non-shadow pixels more influence. */
private fun dominantHue(bitmap: Bitmap): Float? {
    val weights = FloatArray(12)
    val hues = FloatArray(weights.size)
    val hsv = FloatArray(3)
    val step = (minOf(bitmap.width, bitmap.height) / 18).coerceAtLeast(1)
    for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
        val pixel = bitmap.getPixel(x, y)
        if (android.graphics.Color.alpha(pixel) < 128) continue
        android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[1] < .19f || hsv[2] < .16f) continue
        val bucket = (hsv[0] / 30f).toInt().coerceIn(weights.indices)
        val weight = hsv[1] * hsv[1] * (.35f + hsv[2])
        weights[bucket] += weight
        hues[bucket] += hsv[0] * weight
    }
    val bucket = weights.indices.maxBy { weights[it] }
    return if (weights[bucket] > 0f) hues[bucket] / weights[bucket] else null
}

@Composable internal fun Modifier.artworkBackground(palette: ArtworkPalette): Modifier {
    val animation = if (LocalMotionEnabled.current) tween<Color>(330, easing = FastOutSlowInEasing) else snap()
    val top = animateColorAsState(palette.top, animation, label = "artworkTop")
    val middle = animateColorAsState(palette.middle, animation, label = "artworkMiddle")
    return drawWithCache {
        val gradient = Brush.verticalGradient(0f to top.value, .32f to middle.value, .76f to palette.bottom, 1f to palette.bottom)
        onDrawBehind { drawRect(gradient) }
    }
}

@Composable internal fun Modifier.artworkReveal(key: Any): Modifier {
    if (!LocalMotionEnabled.current) return this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        alpha = .78f + .22f * progress.value
        translationY = 5.dp.toPx() * (1f - progress.value)
    }
}

@Composable internal fun ArtworkColors(palette: ArtworkPalette, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme.copy(
        primary = palette.accent, onPrimary = palette.onAccent,
        secondary = palette.accent, onSecondary = palette.onAccent,
        background = palette.bottom, onBackground = palette.ink,
        surface = palette.surface, onSurface = palette.ink, onSurfaceVariant = palette.muted,
        surfaceVariant = palette.raised, surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.raised, surfaceContainerHighest = palette.raised,
        outline = palette.edge, outlineVariant = palette.edge,
        error = Color(0xffffb1be),
    )
    val shapes = MaterialTheme.shapes.copy(
        small = RoundedCornerShape(9.dp), medium = ArtworkShape,
        large = ArtworkShape, extraLarge = RoundedCornerShape(22.dp),
    )
    CompositionLocalProvider(LocalArtworkPalette provides palette, LocalContentColor provides palette.ink) {
        MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
    }
}

@Composable internal fun ArtworkAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    primary: Boolean = true,
    click: () -> Unit,
) {
    val palette = LocalArtworkPalette.current ?: artworkPalette(SiteCategory.VIDEO)
    val interaction = remember { MutableInteractionSource() }
    Button(click, modifier.heightIn(min = 48.dp).rentalKeyPress(interaction), shape = ArtworkPill,
        interactionSource = interaction, elevation = null,
        border = if (primary) null else BorderStroke(1.dp, palette.edge),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) palette.accent else palette.surface,
            contentColor = if (primary) palette.onAccent else palette.ink),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        if (icon != null) {
            Glyph(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable internal fun ArtworkShortcut(label: String, click: (() -> Unit)? = null) {
    val palette = LocalArtworkPalette.current ?: artworkPalette(SiteCategory.VIDEO)
    val selected = click == null
    val surface = if (selected) lerp(palette.surface, palette.accent, .26f) else palette.surface
    Box(Modifier.heightIn(min = 48.dp).clip(ArtworkPill)
        .then(if (click != null) Modifier.clickable(role = Role.Button, onClick = click) else Modifier),
        contentAlignment = Alignment.Center) {
        Text(label, Modifier.widthIn(max = 146.dp).background(surface, ArtworkPill)
            .then(if (selected) Modifier.border(1.dp, palette.edge, ArtworkPill) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
            color = palette.ink, style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
